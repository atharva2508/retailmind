"""
Turns the UCI "Online Retail II" dataset into RetailMind dev data, as one SQL file.

Real data used as is: invoice lines (orders), cancelled invoices (returns), products, prices, dates.
Generated, because the dataset has none: daily start/end-of-day stock snapshots, return condition
codes (03 good / 09 bad), and when returns move back to stock or go to the vendor.

Dataset: https://archive.ics.uci.edu/dataset/502/online+retail+ii  (CC BY 4.0, Daqing Chen, 2012)

Usage (standard library only, Python 3.9+):
    python -I tools/import_uci_online_retail.py <online_retail_II.xlsx> <out.sql> [--end-date YYYY-MM-DD]
Then load it in one transaction:
    docker exec -i springboot-postgres-api-db psql -U postgres -d products_db -v ON_ERROR_STOP=1 -1 < out.sql

Mapping:
- Countries become stores: United Kingdom -> store 1 (MAIN), Germany -> store 2 (NORTH), France -> store 3 (SOUTH).
- The last DAYS days of the data are kept and moved forward so the last day lands on --end-date
  (default: yesterday), so the app shows the data as fresh.
- The TOP_PRODUCTS best-selling real products in those three countries become products with SKU "UCI-<StockCode>".
- Every invoice line becomes a confirmed order line, shipped the next morning if that is inside the window.
- Every line of a cancelled invoice ("C...") becomes a returned line. 1 in 5 is marked 09 (damaged, sent to
  the vendor 3 days later), the rest 03 (good, moved back to stock the next morning).
- Snapshots: per store, product and day. End-of-day stock follows the app's reconciliation formula exactly,
  except on a few chosen days where 1-3 units go missing, so reconciliation shows real VARIANCE days.
  Stock is topped up overnight when it runs low, which shows up as the overnight gap.

The SQL refuses to run twice: it stops if any "UCI-" product already exists.
"""

import argparse
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter, defaultdict
from datetime import date, datetime, time, timedelta
from statistics import median

DAYS = 28
TOP_PRODUCTS = 150
STORES = {"United Kingdom": 1, "Germany": 2, "France": 3}
NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
PRODUCT_CODE = re.compile(r"^\d{5}[A-Z]{0,2}$")  # real products; drops POST, DOT, M, BANK CHARGES and similar
SHEET = "xl/worksheets/sheet2.xml"  # "Year 2010-2011", which holds the last months of the data


def stable(*parts) -> int:
    """Deterministic number from the inputs, so every run produces the same data."""
    return int(hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()[:8], 16)


def read_rows(xlsx_path):
    """Reads the second sheet with the standard library only. Yields one dict per data row."""
    with zipfile.ZipFile(xlsx_path) as book:
        strings = []
        for _, si in ET.iterparse(book.open("xl/sharedStrings.xml")):
            if si.tag == NS + "si":
                strings.append("".join(t.text or "" for t in si.iter(NS + "t")))
                si.clear()
        header = None
        for _, row in ET.iterparse(book.open(SHEET)):
            if row.tag != NS + "row":
                continue
            cells = {}
            for c in row.findall(NS + "c"):
                column = re.match(r"[A-Z]+", c.get("r")).group()
                kind = c.get("t")
                if kind == "inlineStr":
                    value = "".join(t.text or "" for t in c.iter(NS + "t"))
                else:
                    v = c.find(NS + "v")
                    value = None if v is None else (strings[int(v.text)] if kind == "s" else v.text)
                cells[column] = value
            row.clear()
            if header is None:
                header = cells
                expected = ["Invoice", "StockCode", "Description", "Quantity", "InvoiceDate", "Price", "Customer ID", "Country"]
                if [header.get(col) for col in "ABCDEFGH"] != expected:
                    sys.exit(f"Unexpected header in {SHEET}: {header}")
                continue
            yield cells


def excel_time(value: str) -> datetime:
    return datetime(1899, 12, 30) + timedelta(days=float(value))


def number_text(value) -> str:
    """Excel stores codes like 85123 as numbers: '85123.0' -> '85123'."""
    text = str(value).strip()
    return text[:-2] if text.endswith(".0") else text


def sql_text(value) -> str:
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def sql_time(value) -> str:
    return "NULL" if value is None else f"'{value:%Y-%m-%d %H:%M:%S}'"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("xlsx")
    parser.add_argument("out")
    parser.add_argument("--end-date", default=str(date.today() - timedelta(days=1)))
    args = parser.parse_args()
    target_end = date.fromisoformat(args.end_date)

    raw = []
    last_day = date.min
    for cells in read_rows(args.xlsx):
        country = (cells.get("H") or "").strip()
        if country not in STORES or not cells.get("E"):
            continue
        stamp = excel_time(cells["E"])
        last_day = max(last_day, stamp.date())
        raw.append((cells, stamp, country))
    first_day = last_day - timedelta(days=DAYS - 1)
    shift = target_end - last_day
    print(f"data window {first_day}..{last_day}, moved forward {shift.days} days to end on {target_end}", file=sys.stderr)

    orders, returns = [], []
    descriptions = defaultdict(Counter)
    prices = defaultdict(list)
    volume = Counter()
    for cells, stamp, country in raw:
        if stamp.date() < first_day:
            continue
        code = number_text(cells.get("B") or "").upper()
        if not PRODUCT_CODE.match(code):
            continue
        invoice = number_text(cells.get("A") or "")
        quantity = int(float(cells.get("D") or 0))
        price = float(cells.get("F") or 0)
        line = dict(invoice=invoice, code=code, quantity=quantity, at=stamp + shift, store=STORES[country])
        if invoice.upper().startswith("C"):
            if quantity < 0:
                returns.append(dict(line, quantity=-quantity))
        elif quantity > 0 and price > 0:
            orders.append(line)
            volume[code] += quantity
            prices[code].append(price)
            if cells.get("C"):
                descriptions[code][cells["C"].strip()] += 1

    products = [code for code, _ in volume.most_common(TOP_PRODUCTS)]
    keep = set(products)
    orders = [o for o in orders if o["code"] in keep]
    returns = [r for r in returns if r["code"] in keep]
    start = first_day + shift
    days = [start + timedelta(days=i) for i in range(DAYS)]
    end = days[-1]

    # Per store, product and day: what the reconciliation formula counts.
    confirmed = defaultdict(int)
    received = defaultdict(int)
    good_moved = defaultdict(int)
    bad_sent = defaultdict(int)
    order_rows, return_rows = [], []
    line_numbers = Counter()
    for o in sorted(orders, key=lambda o: (o["at"], o["invoice"])):
        line_numbers[o["invoice"]] += 1
        confirmed_at = o["at"] + timedelta(minutes=10)
        ship_day = confirmed_at.date() + timedelta(days=1)
        shipped_at = datetime.combine(ship_day, time(10, 0)) if ship_day <= end else None
        confirmed[(o["store"], o["code"], confirmed_at.date())] += o["quantity"]
        order_rows.append((f"UCI-{o['invoice']}", line_numbers[o["invoice"]], o["store"], o["code"], o["quantity"],
                           o["quantity"] if shipped_at else 0, "SHIPPED" if shipped_at else "CONFIRMED",
                           o["at"], confirmed_at, shipped_at))
    return_numbers = Counter()
    for r in sorted(returns, key=lambda r: (r["at"], r["invoice"])):
        return_numbers[r["invoice"]] += 1
        bad = stable(r["invoice"], r["code"], return_numbers[r["invoice"]]) % 5 == 0
        day = r["at"].date()
        moved = sent = None
        if bad and day + timedelta(days=3) <= end:
            sent = datetime.combine(day + timedelta(days=3), time(15, 0))
            bad_sent[(r["store"], r["code"], sent.date())] += r["quantity"]
        if not bad and day + timedelta(days=1) <= end:
            moved = datetime.combine(day + timedelta(days=1), time(9, 0))
            good_moved[(r["store"], r["code"], moved.date())] += r["quantity"]
        received[(r["store"], r["code"], day)] += r["quantity"]
        return_rows.append((f"UCI-{r['invoice']}", return_numbers[r["invoice"]], r["store"], r["code"], r["quantity"],
                            "09" if bad else "03", r["at"], moved, sent))

    # A few days per store where 1-3 units of one product go missing (shrink): the variances to find.
    shrink = {}
    for store in STORES.values():
        for pick in range(4):
            day = days[3 + stable("day", store, pick) % (DAYS - 4)]
            code = products[stable("product", store, pick) % len(products)]
            shrink[(store, code, day)] = 1 + stable("units", store, pick) % 3

    snapshot_rows = []
    for store in STORES.values():
        for code in products:
            daily = [confirmed[(store, code, d)] for d in days]
            target = max(12, max(daily) * 2)       # what a buyer would aim to hold
            reorder_point = max(5, target * 2 // 5)
            bal, rac = target, 0
            for d in days:
                # Overnight top-up: when stock is low, or when today's confirmed orders would need more than is there.
                need = confirmed[(store, code, d)]
                if bal < reorder_point or bal < need:
                    bal = max(target, need + reorder_point)
                sod_bal, sod_rac = bal, rac
                held = min(sod_bal, stable("held", store, code, d, "SOD") % 3)
                snapshot_rows.append((store, code, d, "SOD", datetime.combine(d, time(0, 0)), sod_bal, sod_rac, held))
                eod_bal = sod_bal - need + good_moved[(store, code, d)] - shrink.get((store, code, d), 0)
                eod_bal = max(eod_bal, 0)
                eod_rac = sod_rac + received[(store, code, d)] - good_moved[(store, code, d)] - bad_sent[(store, code, d)]
                held = min(eod_bal, stable("held", store, code, d, "EOD") % 3)
                snapshot_rows.append((store, code, d, "EOD", datetime.combine(d, time(23, 59, 59)), eod_bal, eod_rac, held))
                bal, rac = eod_bal, eod_rac

    with open(args.out, "w", encoding="utf-8", newline="\n") as out:
        out.write("-- Generated by tools/import_uci_online_retail.py. Source: UCI Online Retail II, CC BY 4.0.\n")
        out.write("DO $$ BEGIN IF EXISTS (SELECT 1 FROM products WHERE sku LIKE 'UCI-%') THEN "
                  "RAISE EXCEPTION 'UCI data is already loaded'; END IF; END $$;\n")
        out.write("CREATE TEMP TABLE uci_product (code text PRIMARY KEY, id bigint) ON COMMIT DROP;\n")
        for code in products:
            name = descriptions[code].most_common(1)[0][0] if descriptions[code] else code
            name = name.title()[:255]
            price = round(median(prices[code]), 2)
            out.write(
                "WITH p AS (INSERT INTO products (sku, name, description, price, stock, is_essential, default_reorder_threshold, "
                "version, created_at, updated_at) VALUES "
                f"({sql_text('UCI-' + code)}, {sql_text(name)}, 'UCI Online Retail II, StockCode {code}', {price:.2f}, 0, false, 0, 0, now(), now()) "
                f"RETURNING id) INSERT INTO uci_product SELECT {sql_text(code)}, id FROM p;\n")

        def batches(rows, size=1000):
            for i in range(0, len(rows), size):
                yield rows[i:i + size]

        for chunk in batches(order_rows):
            values = ",\n".join(
                f"({sql_text(oid)}, {line}, {store}, (SELECT id FROM uci_product WHERE code = {sql_text(code)}), {qty}, {qty}, 0, "
                f"{shipped}, {sql_text(status)}, NULL, 'STORE', {sql_time(created)}, {sql_time(confirmed_at)}, NULL, {sql_time(shipped_at)})"
                for oid, line, store, code, qty, shipped, status, created, confirmed_at, shipped_at in chunk)
            out.write("INSERT INTO order_lines (order_id, line_no, store_id, product_id, qty_ordered, qty_confirmed, qty_cancelled, "
                      "qty_shipped, status, cancel_reason, fulfilled_from, created_at, confirmed_at, cancelled_at, shipped_at) VALUES\n"
                      + values + ";\n")
        for chunk in batches(return_rows):
            values = ",\n".join(
                f"({sql_text(rid)}, {line}, {store}, (SELECT id FROM uci_product WHERE code = {sql_text(code)}), {qty}, "
                f"{sql_text(rc)}, {sql_time(at)}, {sql_time(moved)}, {sql_time(sent)}, NULL)"
                for rid, line, store, code, qty, rc, at, moved, sent in chunk)
            out.write("INSERT INTO return_lines (return_id, line_no, store_id, product_id, qty, return_code, received_at, "
                      "moved_to_bal_at, sent_to_vendor_at, source_order_id) VALUES\n" + values + ";\n")
        for chunk in batches(snapshot_rows):
            values = ",\n".join(
                f"({store}, (SELECT id FROM uci_product WHERE code = {sql_text(code)}), '{d}', '{kind}', {sql_time(as_of)}, "
                f"{bal}, {bal + rac}, {rac}, {held}, false, now())"
                for store, code, d, kind, as_of, bal, rac, held in chunk)
            out.write("INSERT INTO stock_snapshots (store_id, product_id, business_date, snapshot_type, as_of, bal, aud, rac, held, "
                      "absent, created_at) VALUES\n" + values + ";\n")
        summary = (f"Loaded UCI Online Retail II {first_day}..{last_day} as {start}..{end}: {len(products)} products, "
                   f"{len(order_rows)} order lines, {len(return_rows)} return lines, {len(snapshot_rows)} snapshots")
        out.write("INSERT INTO audit_events (action_type, entity_type, entity_id, store_id, actor_username, details, occurred_at) "
                  f"VALUES ('IMPORT', 'UCI_DATASET', NULL, NULL, 'uci-import-script', {sql_text(summary)}, now());\n")

    per_store = Counter(row[2] for row in order_rows)
    print(summary, file=sys.stderr)
    print("order lines per store: " + ", ".join(f"{s}: {per_store[s]}" for s in sorted(per_store)), file=sys.stderr)
    print("shrink days: " + ", ".join(f"store {s} {d} {c} -{n}" for (s, c, d), n in sorted(shrink.items())), file=sys.stderr)


if __name__ == "__main__":
    main()
