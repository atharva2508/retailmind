import { FormEvent, useState } from "react";
import { api } from "./api";
import { Badge, Card, daysAgo, ErrorBox, Loading, Stat, today, useLoad, when } from "./ui";

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
  description: string | null;
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
      <p className="notice" role="status">
        Stock data is out of date. Latest snapshot: {when(asOf)}. Refresh the inventory feed before acting on these numbers.
      </p>
    );
  }
  if (status === "NO_DATA") {
    return (
      <p className="notice bad" role="status">
        No stock snapshot for this store yet. Zero here means unknown, not empty shelves.
      </p>
    );
  }
  return null;
}

export function Dashboard({ storeId }: { storeId: number }) {
  const { data, error, loading, reload } = useLoad(() => api<Summary>("/api/dashboard/summary", { query: { storeId } }), [storeId]);

  return (
    <>
      <div className="page-head">
        <h1>Store {storeId} dashboard</h1>
        <button className="secondary" type="button" onClick={reload}>
          Refresh
        </button>
      </div>
      <Loading show={loading} />
      <ErrorBox text={error} />
      {data && (
        <>
          <Freshness status={data.dataStatus} asOf={data.lowStockItems[0]?.asOf} />
          <div className="stats">
            <Stat label="Store status" value={<Badge value={data.status} />} />
            <Stat label="Data" value={<Badge value={data.dataStatus} />} />
            <Stat label="Low-stock items" value={data.lowStockCount} tone={data.lowStockCount ? "warn" : undefined} />
            <Stat label="Fulfillment issues" value={data.fulfillmentIssuesCount} tone={data.fulfillmentIssuesCount ? "warn" : undefined} />
            <Stat label="Proposals to review" value={data.pendingProposalCount} />
            <Stat label="Today's reconciliation" value={<Badge value={data.reconciliation.status} />} />
          </div>
          <div className="grid">
            <Card title="What to do next">
              <ul className="plain">
                {data.recommendedActions.map((action) => (
                  <li key={action}>{action}</li>
                ))}
              </ul>
            </Card>
            <Card title="Fulfillment issues (last 7 days)">
              {data.fulfillmentIssues.length === 0 ? (
                <p className="muted">None.</p>
              ) : (
                <ul className="plain">
                  {data.fulfillmentIssues.map((issue) => (
                    <li key={issue.issueType}>
                      {ISSUE_NAMES[issue.issueType] ?? issue.issueType}: <strong>{issue.count}</strong>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>
          <Card title="Low stock">
            <LowStockTable items={data.lowStockItems} />
          </Card>
        </>
      )}
    </>
  );
}

function LowStockTable({ items, onPick }: { items: LowStockItem[]; onPick?: (productId: number) => void }) {
  if (items.length === 0) return <p className="muted">Nothing is at or below its reorder threshold.</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>SKU</th>
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
              <td>{item.sku}</td>
              <td>
                {onPick ? (
                  <button className="secondary" type="button" onClick={() => onPick(item.productId)}>
                    {item.name}
                  </button>
                ) : (
                  item.name
                )}
                {item.absent && <span className="muted"> (not in last file)</span>}
              </td>
              <td className="num">{item.available}</td>
              <td>
                <Badge value={item.status} />
              </td>
              <td>
                <Badge value={item.dataStatus} />
              </td>
              <td>{when(item.asOf)}</td>
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

  return (
    <>
      <div className="page-head">
        <h1>Products & stock</h1>
      </div>
      {picked != null && <ProductDetails productId={picked} storeId={storeId} onClose={() => setPicked(null)} />}
      <Card title={`Low stock in store ${storeId}`}>
        <Loading show={lowStock.loading} />
        <ErrorBox text={lowStock.error} />
        {lowStock.data && (
          <>
            <Freshness status={lowStock.data[0]?.dataStatus ?? "FRESH"} asOf={lowStock.data[0]?.asOf} />
            <LowStockTable items={lowStock.data} onPick={setPicked} />
          </>
        )}
      </Card>
      <Card title="All products">
        <Loading show={products.loading} />
        <ErrorBox text={products.error} />
        {products.data && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>SKU</th>
                  <th>Name</th>
                  <th className="num">Price</th>
                  <th>Essential</th>
                  <th>
                    <span className="skip">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {products.data.map((product) => (
                  <tr key={product.id}>
                    <td>{product.sku}</td>
                    <td>{product.name}</td>
                    <td className="num">{Number(product.price).toFixed(2)}</td>
                    <td>{product.isEssential ? "Yes" : "No"}</td>
                    <td>
                      <button className="secondary" type="button" onClick={() => setPicked(product.id)}>
                        Stock in store {storeId}
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
  const { data, error, loading } = useLoad(
    () => api<Details>(`/api/products/${productId}/details`, { query: { storeId } }),
    [productId, storeId],
  );
  return (
    <Card
      title={data ? `${data.name} (${data.sku}) in store ${storeId}` : "Product stock"}
      actions={
        <button className="secondary" type="button" onClick={onClose}>
          Close
        </button>
      }
    >
      <Loading show={loading} />
      <ErrorBox text={error} />
      {data && (
        <>
          <Freshness status={data.dataStatus} asOf={data.asOf} />
          <div className="stats">
            <Stat label="Available" value={data.available} />
            <Stat label="In checkout" value={data.inCheckout} />
            <Stat label="On hold (confirmed, not shipped)" value={data.hold} />
            <Stat label="Good returns (code 03)" value={data.goodReturn} />
            <Stat label="Unavailable (bad returns, code 09)" value={data.unavailable} />
          </div>
          <p className="muted">
            Data <Badge value={data.dataStatus} /> · as of {when(data.asOf)}
            {data.absent && " · this product was missing from the last inventory file, so it is counted as zero"}
          </p>
        </>
      )}
    </Card>
  );
}

export function Reconciliation({ storeId }: { storeId: number }) {
  const [date, setDate] = useState(today());
  const [asked, setAsked] = useState(today());
  const { data, error, loading } = useLoad(
    () => api<ReconciliationResult>(`/api/stores/${storeId}/reconciliation`, { query: { date: asked } }),
    [storeId, asked],
  );

  return (
    <>
      <div className="page-head">
        <h1>Daily reconciliation</h1>
      </div>
      <Card title="Business date">
        <form
          className="row"
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            setAsked(date);
          }}
        >
          <label>
            Date
            <input type="date" value={date} max={today()} onChange={(e) => setDate(e.target.value)} required />
          </label>
          <button className="primary" type="submit">
            Check
          </button>
        </form>
      </Card>
      <Loading show={loading} />
      <ErrorBox text={error} />
      {data && (
        <Card title={`Store ${data.storeCode}, ${data.businessDate}`}>
          {data.status === "NO_DATA" ? (
            <p className="notice bad" role="status">
              No start-of-day and end-of-day snapshots for this date, so there is nothing to reconcile.
            </p>
          ) : (
            <>
              <div className="stats">
                <Stat label="Result" value={<Badge value={data.status} />} />
                <Stat label="Expected end-of-day BAL" value={data.expectedEodBal} />
                <Stat label="BAL variance" value={data.balVariance} tone={data.balVariance ? "bad" : undefined} />
                <Stat label="RAC variance" value={data.racVariance} tone={data.racVariance ? "bad" : undefined} />
                <Stat label="Overnight gap" value={data.overnightGap} />
              </div>
              <p className="muted">
                Expected BAL = start-of-day BAL − confirmed + customer cancels after confirmation + good returns moved back. Snapshot as of{" "}
                {when(data.asOf)}.
              </p>
            </>
          )}
        </Card>
      )}
    </>
  );
}

export function Fulfillment({ storeId }: { storeId: number }) {
  const [from, setFrom] = useState(daysAgo(7));
  const [to, setTo] = useState(today());
  const [range, setRange] = useState({ from: daysAgo(7), to: today() });
  const { data, error, loading } = useLoad(
    () => api<Issue[]>("/api/fulfillment-issues", { query: { storeId, from: range.from, to: range.to } }),
    [storeId, range],
  );

  return (
    <>
      <div className="page-head">
        <h1>Fulfillment issues</h1>
      </div>
      <Card title="Date range">
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
          <button className="primary" type="submit">
            Show
          </button>
        </form>
      </Card>
      <Loading show={loading} />
      <ErrorBox text={error} />
      {data && (
        <Card title={`Store ${storeId}`}>
          {data.length === 0 ? (
            <p className="muted">No issues in this range.</p>
          ) : (
            <div className="table-wrap">
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
                      <td className="num">{issue.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </>
  );
}
