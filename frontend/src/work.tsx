import { FormEvent, useEffect, useRef, useState } from "react";
import { api, Session } from "./api";
import { Card, count, ErrorBox, Icon, Loading, message, PageHead, Status, STORES, today, useLoad, when } from "./ui";

interface Proposal {
  id: number | null;
  storeId: number | null;
  proposalType: string;
  targetSku: string;
  status: string;
  creatorType: string;
  createdBy: string | null;
  rationale: string;
  proposedValues: string;
  reviewerUsername: string | null;
  reviewComment: string | null;
  basePrice: number | null;
  createdAt: string | null;
  decidedAt: string | null;
  appliedAt: string | null;
  expiresAt: string | null;
}

function describeChange(values: string, basePrice: number | null): string {
  try {
    const parsed = JSON.parse(values) as { newPrice?: number; discountPercent?: number };
    if (parsed.newPrice != null) return `New price ${parsed.newPrice}${basePrice != null ? ` (was ${basePrice})` : ""}`;
    if (parsed.discountPercent != null) return `${parsed.discountPercent}% off${basePrice != null ? ` ${basePrice}` : ""}`;
  } catch {
    // shown raw below
  }
  return values;
}

export function Proposals({ storeId, session }: { storeId: number; session: Session }) {
  const role = session.role;
  const canCreate = role === "ROLE_ADMIN" || role === "ROLE_STORE_MANAGER" || role === "ROLE_REVIEWER";
  const canReview = role === "ROLE_ADMIN" || role === "ROLE_REVIEWER";
  const canApply = role === "ROLE_ADMIN";

  const list = useLoad(() => api<Proposal[]>("/api/proposals"), []);
  const suggestions = useLoad(() => api<Proposal[]>("/api/proposals/suggestions", { query: { storeId } }), [storeId]);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [draft, setDraft] = useState<{ sku: string; discount: string } | null>(null);

  async function act(label: string, run: () => Promise<unknown>) {
    setNotice(null);
    setActionError(null);
    try {
      await run();
      setNotice(label);
      list.reload();
    } catch (e) {
      setActionError(message(e));
    }
  }

  const visible = (list.data ?? []).filter((p) => role !== "ROLE_ADMIN" || p.storeId === storeId);

  return (
    <>
      <PageHead
        eyebrow={STORES[storeId] ?? `Store ${storeId}`}
        title="Pricing proposals"
        lede="A price changes only when a store manager proposes it, a reviewer approves it, and an admin applies it. Nobody can approve their own proposal."
        actions={
          <button className="btn btn-ghost" type="button" onClick={list.reload}>
            Refresh
          </button>
        }
      />
      {notice && (
        <p className="success" role="status">
          <Icon name="check" />
          <span>{notice}</span>
        </p>
      )}
      <ErrorBox text={actionError} />

      {canCreate && (
        <NewProposal
          storeId={storeId}
          draft={draft}
          onCreated={() => {
            setDraft(null);
            setNotice("Proposal created. It now waits for a reviewer.");
            setActionError(null);
            list.reload();
          }}
        />
      )}

      <Card title="Suggested discounts" sub="Low-stock products in this store">
        <Loading show={suggestions.loading} />
        <ErrorBox text={suggestions.error} />
        {suggestions.data && suggestions.data.length === 0 && <p className="sub">No low-stock products to suggest a discount for.</p>}
        {suggestions.data && suggestions.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>SKU</th>
                  <th>Why</th>
                  <th>Suggestion</th>
                  {canCreate && (
                    <th>
                      <span className="sr-only">Actions</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {suggestions.data.map((s) => (
                  <tr key={s.targetSku}>
                    <td>{s.targetSku}</td>
                    <td>{s.rationale}</td>
                    <td>{describeChange(s.proposedValues, null)}</td>
                    {canCreate && (
                      <td>
                        <button
                          className="btn btn-ghost btn-sm"
                          type="button"
                          onClick={() => {
                            const parsed = JSON.parse(s.proposedValues) as { discountPercent?: number };
                            setDraft({ sku: s.targetSku, discount: String(parsed.discountPercent ?? 10) });
                          }}
                        >
                          Use as draft
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="All proposals">
        <Loading show={list.loading} />
        <ErrorBox text={list.error} />
        {list.data && visible.length === 0 && <p className="sub">No proposals yet.</p>}
        {visible.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>SKU</th>
                  <th>Change</th>
                  <th>Status</th>
                  <th>By</th>
                  <th>Reviewed by</th>
                  <th>Expires</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {visible.map((p) => (
                  <tr key={p.id}>
                    <td>{p.id}</td>
                    <td>{p.targetSku}</td>
                    <td>
                      {describeChange(p.proposedValues, p.basePrice)}
                      <div className="sub">{p.rationale}</div>
                    </td>
                    <td>
                      <Status value={p.status} />
                    </td>
                    <td>{p.createdBy ?? p.creatorType}</td>
                    <td>
                      {p.reviewerUsername ?? "–"}
                      {p.reviewComment && <div className="sub">{p.reviewComment}</div>}
                    </td>
                    <td>{when(p.expiresAt)}</td>
                    <td>
                      <div className="actions">
                        {canReview && p.status === "PENDING_REVIEW" && p.createdBy !== session.username && (
                          <>
                            <button className="btn btn-primary" type="button" onClick={() => act(`Proposal ${p.id} approved.`, () => review(p.id!, "APPROVE"))}>
                              Approve
                            </button>
                            <button className="btn btn-danger btn-sm" type="button" onClick={() => act(`Proposal ${p.id} rejected.`, () => review(p.id!, "REJECT"))}>
                              Reject
                            </button>
                          </>
                        )}
                        {canApply && p.status === "APPROVED" && (
                          <button
                            className="btn btn-primary"
                            type="button"
                            onClick={() => {
                              if (window.confirm(`Apply proposal ${p.id}? This changes the live price of ${p.targetSku} in every store.`)) {
                                act(`Proposal ${p.id} applied. The price of ${p.targetSku} is changed.`, () =>
                                  api(`/api/proposals/${p.id}/apply`, { method: "POST" }),
                                );
                              }
                            }}
                          >
                            Apply
                          </button>
                        )}
                      </div>
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

function review(id: number, decision: "APPROVE" | "REJECT") {
  const comment = window.prompt(decision === "APPROVE" ? "Comment (optional)" : "Why is it rejected? (optional)") ?? "";
  return api(`/api/proposals/${id}/review`, { method: "POST", body: { decision, reviewComment: comment } });
}

function NewProposal({ storeId, draft, onCreated }: { storeId: number; draft: { sku: string; discount: string } | null; onCreated: () => void }) {
  const [sku, setSku] = useState("");
  const [kind, setKind] = useState<"discount" | "price">("discount");
  const [amount, setAmount] = useState("10");
  const [type, setType] = useState("DISCOUNT");
  const [rationale, setRationale] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (draft) {
      setSku(draft.sku);
      setKind("discount");
      setAmount(draft.discount);
      setType("DISCOUNT");
    }
  }, [draft]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const value = Number(amount);
      await api("/api/proposals", {
        method: "POST",
        body: {
          proposalType: type,
          targetSku: sku.trim(),
          storeId,
          rationale: rationale.trim() || undefined,
          proposedValues: JSON.stringify(kind === "discount" ? { discountPercent: value } : { newPrice: value }),
        },
      });
      setSku("");
      setRationale("");
      onCreated();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="New proposal">
      <form onSubmit={submit}>
        <div className="row">
          <label>
            SKU
            <input value={sku} onChange={(e) => setSku(e.target.value)} placeholder="SKU-1001" required maxLength={255} />
          </label>
          <label>
            Type
            <select value={type} onChange={(e) => setType(e.target.value)}>
              <option value="DISCOUNT">Discount</option>
              <option value="PROMOTION">Promotion</option>
              <option value="CLEARANCE">Clearance</option>
            </select>
          </label>
          <label>
            Change
            <select value={kind} onChange={(e) => setKind(e.target.value as "discount" | "price")}>
              <option value="discount">Percent off</option>
              <option value="price">New price</option>
            </select>
          </label>
          <label>
            {kind === "discount" ? "Percent" : "Price"}
            <input
              type="number"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              min={kind === "discount" ? 0.01 : 0.01}
              max={kind === "discount" ? 99.99 : 99999999.99}
              step="0.01"
              required
            />
          </label>
        </div>
        <label>
          Why
          <textarea value={rationale} onChange={(e) => setRationale(e.target.value)} maxLength={4000} placeholder="Reason for the change" />
        </label>
        <ErrorBox text={error} />
        <div className="actions">
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Creating…" : "Create proposal"}
          </button>
        </div>
      </form>
    </Card>
  );
}

interface ChatAnswer {
  engine: string;
  answer: string;
  toolCalls: { toolName: string; args: Record<string, string> }[];
}

interface ChatLine {
  from: "me" | "bot";
  text: string;
  meta?: string;
}

export function Chat({ storeId }: { storeId: number }) {
  const [lines, setLines] = useState<ChatLine[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => {
    end.current?.scrollIntoView({ block: "end" });
  }, [lines]);

  async function send(event: FormEvent) {
    event.preventDefault();
    const question = text.trim();
    if (!question) return;
    setText("");
    setError(null);
    setLines((current) => [...current, { from: "me", text: question }]);
    setBusy(true);
    try {
      const reply = await api<ChatAnswer>("/api/chat", { method: "POST", body: { message: question, storeId } });
      const tools = reply.toolCalls.map((call) => call.toolName).join(", ");
      setLines((current) => [
        ...current,
        { from: "bot", text: reply.answer, meta: `${reply.engine === "claude" ? "Claude" : "Keyword routing"}${tools ? ` · used ${tools}` : ""}` },
      ]);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHead
        eyebrow={STORES[storeId] ?? `Store ${storeId}`}
        title="Ask RetailMind"
        lede="Read-only questions about this store. It can look things up but never changes stock, prices or orders."
      />
      <Card title="Conversation">
        <div className="chat-log" aria-live="polite">
          {lines.length === 0 && (
            <div className="suggestions">
              {["Which products are running low?", "Give me the dashboard summary", "Show reconciliation", "Any fulfillment issues?"].map((q) => (
                <button key={q} type="button" className="chip" onClick={() => setText(q)}>
                  {q}
                </button>
              ))}
            </div>
          )}
          {lines.map((line, index) => (
            <div key={index} className={`bubble ${line.from}`}>
              <span className="sr-only">{line.from === "me" ? "You:" : "RetailMind:"}</span>
              {line.text}
              {line.meta && <span className="meta">{line.meta}</span>}
            </div>
          ))}
          {busy && <p className="sub">Thinking…</p>}
          <div ref={end} />
        </div>
        <ErrorBox text={error} />
        <form className="row" onSubmit={send}>
          <label style={{ flex: 1 }}>
            Your question
            <input value={text} onChange={(e) => setText(e.target.value)} maxLength={2000} disabled={busy} required />
          </label>
          <button className="btn btn-primary" type="submit" disabled={busy}>
            Send
          </button>
        </form>
      </Card>
    </>
  );
}

interface ImportRecord {
  id: number;
  storeId: number;
  storeCode: string;
  fileName: string;
  snapshotType: string;
  status: string;
  rowCount: number;
  asOf: string;
  receivedAt: string;
}

interface ImportResult {
  rowCount: number;
  unknownSkuCount: number;
  missingSkuCount: number;
  checksum: string;
}

const STORE_CODES: Record<number, string> = { 1: "MAIN", 2: "NORTH", 3: "SOUTH" };

export function Imports({ storeId }: { storeId: number }) {
  const history = useLoad(() => api<ImportRecord[]>("/api/admin/inventory-imports"), []);
  const [type, setType] = useState("SOD");
  const [asOf, setAsOf] = useState(today());
  const [csv, setCsv] = useState("");
  const catalogue = useLoad(() => api<unknown[]>("/api/products"), []);
  const needed = catalogue.data ? Math.max(1, Math.ceil(catalogue.data.length * 0.5)) : null;
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [busy, setBusy] = useState(false);
  const fileName = `${STORE_CODES[storeId] ?? "STORE"}_${asOf.replace(/-/g, "")}_${type}.csv`;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setResult(null);
    const lines = csv.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    const body = lines[0]?.toLowerCase().startsWith("sku") ? lines.slice(1) : lines;
    const rows = [];
    for (const [index, line] of body.entries()) {
      const [sku, ...numbers] = line.split(",").map((cell) => cell.trim());
      const values = numbers.map(Number);
      if (!sku || values.length !== 4 || values.some((v) => !Number.isInteger(v))) {
        setError(`Line ${index + 1} must be: sku,bal,aud,rac,held with whole numbers.`);
        return;
      }
      rows.push({ sku, bal: values[0], aud: values[1], rac: values[2], held: values[3] });
    }
    setBusy(true);
    try {
      setResult(await api<ImportResult>("/api/admin/inventory-imports/synthetic", { method: "POST", body: { storeId, snapshotType: type, asOf, fileName, rows } }));
      history.reload();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHead eyebrow={STORES[storeId] ?? `Store ${storeId}`} title="Inventory imports" lede="Load a start-of-day or end-of-day stock file for this store." />
      <Card title="Import a snapshot">
        <form onSubmit={submit}>
          <div className="row">
            <label>
              Snapshot
              <select value={type} onChange={(e) => setType(e.target.value)}>
                <option value="SOD">Start of day (SOD)</option>
                <option value="EOD">End of day (EOD)</option>
              </select>
            </label>
            <label>
              Business date
              <input type="date" value={asOf} max={today()} onChange={(e) => setAsOf(e.target.value)} required />
            </label>
            <label>
              File name
              <input value={fileName} readOnly aria-describedby="file-name-help" />
            </label>
          </div>
          <p id="file-name-help" className="sub">
            The file name is built from the store, date and type, as the API requires. Products missing from the file are recorded as zero stock.
          </p>
          <label>
            Rows (CSV: sku,bal,aud,rac,held)
            <textarea
              value={csv}
              onChange={(e) => setCsv(e.target.value)}
              required
              aria-describedby="coverage-help"
              placeholder={"sku,bal,aud,rac,held\nUCI-22197,120,124,4,2\n…"}
            />
          </label>
          <p id="coverage-help" className="sub">
            {needed != null
              ? `The file must cover at least ${count(needed)} of the ${count(catalogue.data!.length)} products (half). Products left out are recorded as zero stock.`
              : "The file must cover at least half of all products."}
          </p>
          <ErrorBox text={error} />
          {result && (
            <p className="success" role="status">
              <Icon name="check" />
              Imported {result.rowCount} rows. Unknown SKUs: {result.unknownSkuCount}. Products not in the file, set to zero: {result.missingSkuCount}.
            </p>
          )}
          <div className="actions">
            <button className="btn btn-primary" type="submit" disabled={busy}>
              {busy ? "Importing…" : "Import"}
            </button>
          </div>
        </form>
      </Card>
      <Card title="Recent imports">
        <Loading show={history.loading} />
        <ErrorBox text={history.error} />
        {history.data && history.data.length === 0 && <p className="sub">No imports yet.</p>}
        {history.data && history.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>File</th>
                  <th>Store</th>
                  <th>Type</th>
                  <th>Date</th>
                  <th className="num">Rows</th>
                  <th>Status</th>
                  <th>Received</th>
                </tr>
              </thead>
              <tbody>
                {history.data.map((record) => (
                  <tr key={record.id}>
                    <td>{record.fileName}</td>
                    <td>{record.storeCode}</td>
                    <td>{record.snapshotType}</td>
                    <td>{record.asOf}</td>
                    <td className="num">{record.rowCount}</td>
                    <td>
                      <Status value={record.status} />
                    </td>
                    <td>{when(record.receivedAt)}</td>
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

interface AuditEvent {
  id: number;
  actionType: string;
  entityType: string;
  entityId: number | null;
  storeId: number | null;
  actorUsername: string;
  details: string;
  occurredAt: string;
}

export function Audit() {
  const { data, error, loading, reload } = useLoad(() => api<AuditEvent[]>("/api/audit/events"), []);
  return (
    <>
      <PageHead
        title="Audit trail"
        lede="Newest 200 events you are allowed to see. Entries cannot be changed or deleted."
        actions={
          <button className="btn btn-ghost" type="button" onClick={reload}>
            Refresh
          </button>
        }
      />
      <Loading show={loading} />
      <ErrorBox text={error} />
      {data && (
        <Card title={`${count(data.length)} events`}>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Action</th>
                  <th>What</th>
                  <th>Store</th>
                  <th>Who</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {data.map((event) => (
                  <tr key={event.id}>
                    <td>{when(event.occurredAt)}</td>
                    <td>{event.actionType}</td>
                    <td>
                      {event.entityType}
                      {event.entityId != null && ` #${event.entityId}`}
                    </td>
                    <td>{event.storeId ?? "–"}</td>
                    <td>{event.actorUsername}</td>
                    <td>{event.details}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  );
}
