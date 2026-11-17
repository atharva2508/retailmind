"""Checks the cancellation rule of the UCI importer. Run: python -I tools/test_import_uci_online_retail.py"""
import os
import sys
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from import_uci_online_retail import match_cancellations  # noqa: E402

T = datetime(2026, 11, 10, 9, 0)


def order(key, quantity, customer="17841", shipped_after=timedelta(days=1)):
    return dict(key=key, store=1, code="22197", customer=customer, quantity=quantity,
                confirmed_at=T, ship_at=None if shipped_after is None else T + shipped_after)


def cancel(quantity, after, customer="17841"):
    return dict(store=1, code="22197", customer=customer, quantity=quantity, at=T + after)


# Cancelled the same day, before shipping: a customer cancel, not a return.
cancelled, returns = match_cancellations([order("A", 80995, shipped_after=None)], [cancel(80995, timedelta(minutes=5))])
assert cancelled == {"A": [(80995, T + timedelta(minutes=5))]} and returns == [], (cancelled, returns)

# Cancelled after the order shipped: a return.
cancelled, returns = match_cancellations([order("A", 5)], [cancel(5, timedelta(days=2))])
assert cancelled == {} and [r["quantity"] for r in returns] == [5], (cancelled, returns)

# Cancel larger than the open order: the rest is a return.
cancelled, returns = match_cancellations([order("A", 3)], [cancel(5, timedelta(hours=2))])
assert cancelled["A"] == [(3, T + timedelta(hours=2))] and [r["quantity"] for r in returns] == [2], (cancelled, returns)

# A different customer's cancel never touches this order; a cancel without a customer is a return.
cancelled, returns = match_cancellations([order("A", 3)], [cancel(3, timedelta(hours=1), customer="99999"), cancel(1, timedelta(hours=1), customer="")])
assert cancelled == {} and len(returns) == 2, (cancelled, returns)

print("ok: 4 cancellation cases")
