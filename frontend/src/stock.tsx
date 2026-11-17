import { FormEvent, useMemo, useState } from "react";
import { api } from "./api";
import { ReconciliationTrend, SegmentedBar } from "./charts";
import { Banner, Card, count, day, daysAgo, ErrorBox, Loading, money, PageHead, Status, STORES, Tile, today, useLoad, when } from "./ui";

interface LowStockItem {
  productId: number;
  sku: string;
  name: string;
  available: number;
  status: string;
  absent: boolean;
  asOf: string;
  dataStatus: string;
}

interface Issue {
  issueType: string;
  count: number;
  from: string;
  to: string;
}

interface ReconciliationResult {
  storeCode: string;
  businessDate: string;
  expectedEodBal: number | null;
  balVariance: number | null;
  racVariance: number | null;
  overnightGap: number | null;
  status: string;
  asOf: string | null;
}

interface Summary {
  businessDate: string;
  status: string;
  dataStatus: string;
  lowStockCount: number;
  lowStockItems: LowStockItem[];
  fulfillmentIssuesCount: number;
  fulfillmentIssues: Issue[];
  pendingProposalCount: number;
  reconciliation: ReconciliationResult;
  recommendedActions: string[];
}

interface Product {
  id: number;
  sku: string;
  name: string;
  price: number;
  isEssential: boolean;
}

interface Details {
  sku: string;
  name: string;
  available: number | null;
  inCheckout: number | null;
  hold: number;
  goodReturn: number;
  unavailable: number;
  absent: boolean;
  asOf: string | null;
  dataStatus: string;
}

const ISSUE_NAMES: Record<string, string> = {
  NOT_FOUND: "Order lines the store could not find",
  GOOD_RETURN_STALE: "Good returns waiting more than 2 days to go back to stock",
};

/** Explains freshness in words, so a number from an old snapshot is never read as current. */
export function Freshness({ status, asOf }: { status: string; asOf?: string | null }) {
  if (status === "STALE") {
    return (
      <Banner tone="warning">
        <strong>Stock data is out of date.</strong> Latest snapshot {when(asOf)}. Refresh the inventory feed before acting on these numbers.
      </Banner>
    );
  }
  if (status === "NO_DATA") {
    return (
      <Banner tone="critical">
        <strong>No stock snapshot for this store yet.</strong> Zero here means unknown, not empty shelves.
      </Banner>
    );
  }
  return null;
}

export function Dashboard({ storeId, showReconciliation, onOpenReconciliation }: { storeId: number; showReconciliation: boolean; onOpenReconciliation: () => void }) {
  const { data, error, loading, reload } = useLoad(() => api<Summary>("/api/dashboard/summary", { query: { storeId } }), [storeId]);

  return (
    <>
      <PageHead
        eyebrow={STORES[storeId] ?? `Store ${storeId}`}
        title="Overview"
        lede={data ? `Business date ${day(data.businessDate)}.` : undefined}
        actions={
          <button className="btn btn-ghost" type="button" onClick={reload}>
            Refresh
          </button>
        }
      />
      <Loading show={loading && !data} />
      <ErrorBox text={error} />
      {data && (
        <div style={{ display: "grid", gap: 20, opacity: loading ? 0.6 : 1 }}>
          <Freshness status={data.dataStatus} asOf={data.lowStockItems[0]?.asOf} />
          <div className="kpis">
            <Tile label="Store status" value={<Status value={data.status} />} />
            <Tile label="Stock data" value={<Status value={data.dataStatus} />} />
            <Tile label="Low-stock products" value={count(data.lowStockCount)} note="at or below reorder level" />
            <Tile label="Fulfillment issues" value={count(data.fulfillmentIssuesCount)} note="last 7 days" />
            <Tile label="Proposals to review" value={count(data.pendingProposalCount)} />
            <Tile label="Today's reconciliation" value={<Status value={data.reconciliation.status} />} note={data.reconciliation.status === "NO_DATA" ? "end-of-day snapshot not in yet" : undefined} />
          </div>

          {showReconciliation && (
            <Card
              title="Stock balance, last 14 days"
              sub="End-of-day stock against what orders and returns say it should be. Below zero means units went missing."
              actions={
                <button className="btn btn-ghost btn-sm" type="button" onClick={onOpenReconciliation}>
                  Open reconciliation
                </button>
              }
            >
              <ReconciliationTrend storeId={storeId} days={14} field="balVariance" title="Stock balance variance per day, last 14 days" />
            </Card>
          )}

          <div className="grid-2">
            <Card title="Low stock" sub="Lowest first">
              <LowStockTable items={[...data.lowStockItems].sort((a, b) => a.available - b.available)} />
            </Card>
            <div style={{ display: "grid", gap: 20, alignContent: "start" }}>
              <Card title="What to do next">
                <ul className="plain">
                  {data.recommendedActions.map((action) => (
                    <li key={action}>{action}</li>
                  ))}
                </ul>
              </Card>
              <Card title="Fulfillment issues" sub="Last 7 days">
                {data.fulfillmentIssues.length === 0 ? (
                  <p className="empty">None.</p>
                ) : (
                  <table>
                    <tbody>
                      {data.fulfillmentIssues.map((issue) => (
                        <tr key={issue.issueType}>
                          <td>{ISSUE_NAMES[issue.issueType] ?? issue.issueType}</td>
                          <td className="num">{count(issue.count)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </Card>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function LowStockTable({ items, onPick }: { items: LowStockItem[]; onPick?: (productId: number) => void }) {
  if (items.length === 0) return <p className="empty">Nothing is at or below its reorder level.</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Product</th>
            <th className="num">Available</th>
            <th>Status</th>
            <th>Data</th>
            <th>As of</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.productId}>
              <td>
                {onPick ? (
                  <button className="btn-link" type="button" onClick={() => onPick(item.productId)}>
                    {item.name}
                  </button>
                ) : (
                  item.name
                )}
                <div className="code">
                  {item.sku}
                  {item.absent && " · not in last file"}
                </div>
              </td>
              <td className="num">{count(item.available)}</td>
              <td>
                <Status value={item.status} />
              </td>
              <td>
                <Status value={item.dataStatus} />
              </td>
              <td className="sub">{when(item.asOf)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Products({ storeId }: { storeId: number }) {
  const products = useLoad(() => api<Product[]>("/api/products"), []);
  const lowStock = useLoad(() => api<LowStockItem[]>("/api/products/low-stock", { query: { storeId } }), [storeId]);
  const [picked, setPicked] = useState<number | null>(null);
  const [search, setSearch] = useState("");

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    const list = products.data ?? [];
    return term ? list.filter((p) => p.name.toLowerCase().includes(term) || p.sku.toLowerCase().includes(term)) : list;
  }, [products.data, search]);

  return (
    <>
      <PageHead eyebrow={STORES[storeId] ?? `Store ${storeId}`} title="Products & stock" lede="Pick a product to see where its units are in this store." />
      {picked != null && <ProductDetails productId={picked} storeId={storeId} onClose={() => setPicked(null)} />}
      <Card title="Low stock" sub="Lowest first">
        <Loading show={lowStock.loading} />
        <ErrorBox text={lowStock.error} />
        {lowStock.data && (
          <>
            <LowStockTable items={[...lowStock.data].sort((a, b) => a.available - b.available)} onPick={setPicked} />
          </>
        )}
      </Card>
      <Card
        title="All products"
        sub={products.data ? `${filtered.length} of ${products.data.length}` : undefined}
        actions={
          <label>
            <span className="sr-only">Search products</span>
            <input className="search" type="search" placeholder="Search name or SKU" value={search} onChange={(e) => setSearch(e.target.value)} />
          </label>
        }
      >
        <Loading show={products.loading} />
        <ErrorBox text={products.error} />
        {products.data && filtered.length === 0 && <p className="empty">No product matches “{search}”.</p>}
        {filtered.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Product</th>
                  <th className="num">Price</th>
                  <th>Essential</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((product) => (
                  <tr key={product.id}>
                    <td>
                      {product.name}
                      <div className="code">{product.sku}</div>
                    </td>
                    <td className="num">{money(product.price)}</td>
                    <td>{product.isEssential ? "Yes" : "No"}</td>
                    <td className="num">
                      <button className="btn btn-ghost btn-sm" type="button" onClick={() => setPicked(product.id)}>
                        View stock
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

function ProductDetails({ productId, storeId, onClose }: { productId: number; storeId: number; onClose: () => void }) {
  const { data, error, loading } = useLoad(() => api<Details>(`/api/products/${productId}/details`, { query: { storeId } }), [productId, storeId]);
  return (
    <Card
      title={data ? data.name : "Product stock"}
      sub={data ? `${data.sku} · ${STORES[storeId] ?? `store ${storeId}`} · as of ${when(data.asOf)}` : undefined}
      actions={
        <button className="btn btn-ghost btn-sm" type="button" onClick={onClose}>
          Close
        </button>
      }
    >
      <Loading show={loading} />
      <ErrorBox text={error} />
      {data && (
        <>
          <Freshness status={data.dataStatus} asOf={data.asOf} />
          {data.absent && <Banner tone="warning">This product was missing from the last inventory file, so its stock is counted as zero.</Banner>}
          <div className="kpis">
            <Tile label="Available" value={count(data.available)} />
            <Tile label="In checkout" value={count(data.inCheckout)} />
            <Tile label="On hold" value={count(data.hold)} note="confirmed, not shipped" />
            <Tile label="Good returns" value={count(data.goodReturn)} note="code 03" />
            <Tile label="Unavailable" value={count(data.unavailable)} note="bad returns, code 09" />
          </div>
          {data.available != null && (
            <SegmentedBar
              label={`Where the units of ${data.name} are`}
              parts={[
                { label: "Available", value: data.available ?? 0, color: "var(--series-1)" },
                { label: "In checkout", value: data.inCheckout ?? 0, color: "var(--series-2)" },
                { label: "On hold", value: data.hold, color: "var(--series-3)" },
                { label: "Good returns", value: data.goodReturn, color: "var(--series-4)", lightFill: true },
                { label: "Unavailable", value: data.unavailable, color: "var(--series-5)" },
              ]}
            />
          )}
        </>
      )}
    </Card>
  );
}

export function Reconciliation({ storeId }: { storeId: number }) {
  const yesterday = daysAgo(1);
  const [date, setDate] = useState(yesterday);
  const [asked, setAsked] = useState(yesterday);
  const { data, error, loading } = useLoad(() => api<ReconciliationResult>(`/api/stores/${storeId}/reconciliation`, { query: { date: asked } }), [storeId, asked]);
  const pick = (value: string) => {
    setDate(value);
    setAsked(value);
  };

  return (
    <>
      <PageHead
        eyebrow={STORES[storeId] ?? `Store ${storeId}`}
        title="Reconciliation"
        lede="Expected end-of-day stock = start-of-day stock − confirmed orders + customer cancels after confirmation + good returns moved back."
      />
      <div className="grid-2">
        <Card title="Stock balance (BAL), last 28 days" sub="Click a day to open it below.">
          <ReconciliationTrend storeId={storeId} days={28} field="balVariance" title="Stock balance variance per day, last 28 days" onPick={pick} selected={asked} />
        </Card>
        <Card title="Returns awaiting check (RAC), last 28 days" sub="Returns received but not yet moved to stock or sent to the vendor.">
          <ReconciliationTrend storeId={storeId} days={28} field="racVariance" title="Returns variance per day, last 28 days" onPick={pick} selected={asked} />
        </Card>
      </div>
      <Card
        title={data ? `Day detail: ${day(data.businessDate)}` : "Day detail"}
        actions={
          <form
            className="row"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              setAsked(date);
            }}
          >
            <label>
              <span className="sr-only">Business date</span>
              <input type="date" value={date} max={today()} onChange={(e) => setDate(e.target.value)} required />
            </label>
            <button className="btn btn-primary" type="submit">
              Open
            </button>
          </form>
        }
      >
        <Loading show={loading} />
        <ErrorBox text={error} />
        {data &&
          (data.status === "NO_DATA" ? (
            <Banner tone="critical">No start-of-day and end-of-day snapshots for this date, so there is nothing to reconcile.</Banner>
          ) : (
            <div className="kpis">
              <Tile label="Result" value={<Status value={data.status} />} />
              <Tile label="Expected end-of-day stock" value={count(data.expectedEodBal)} />
              <Tile label="Stock variance" value={signedCount(data.balVariance)} note={data.balVariance ? (data.balVariance < 0 ? "units missing" : "extra units") : "balanced"} />
              <Tile label="Returns variance" value={signedCount(data.racVariance)} />
              <Tile label="Overnight change" value={signedCount(data.overnightGap)} note="stock added between days" />
            </div>
          ))}
      </Card>
    </>
  );
}

function signedCount(value: number | null): string {
  if (value == null) return "–";
  return value > 0 ? `+${count(value)}` : count(value);
}

export function Fulfillment({ storeId }: { storeId: number }) {
  const [from, setFrom] = useState(daysAgo(7));
  const [to, setTo] = useState(today());
  const [range, setRange] = useState({ from: daysAgo(7), to: today() });
  const { data, error, loading } = useLoad(() => api<Issue[]>("/api/fulfillment-issues", { query: { storeId, from: range.from, to: range.to } }), [storeId, range]);

  return (
    <>
      <PageHead eyebrow={STORES[storeId] ?? `Store ${storeId}`} title="Fulfillment issues" lede="Orders the store could not find, and good returns still waiting to go back to stock." />
      <form
        className="row"
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          setRange({ from, to });
        }}
      >
        <label>
          From
          <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} required />
        </label>
        <label>
          To
          <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} required />
        </label>
        <button className="btn btn-primary" type="submit">
          Show
        </button>
      </form>
      <Card title={`${day(range.from)} to ${day(range.to)}`}>
        <Loading show={loading} />
        <ErrorBox text={error} />
        {data &&
          (data.length === 0 ? (
            <p className="empty">No issues in this range.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Issue</th>
                  <th className="num">Count</th>
                </tr>
              </thead>
              <tbody>
                {data.map((issue) => (
                  <tr key={issue.issueType}>
                    <td>{ISSUE_NAMES[issue.issueType] ?? issue.issueType}</td>
                    <td className="num">{count(issue.count)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
      </Card>
    </>
  );
}
