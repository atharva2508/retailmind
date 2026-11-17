import { FormEvent, useEffect, useRef, useState } from "react";
import { api, Session } from "./api";
import { Badge, Card, ErrorBox, Loading, message, today, useLoad, when } from "./ui";

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
      <div className="page-head">
        <h1>Pricing proposals</h1>
        <button className="secondary" type="button" onClick={list.reload}>
          Refresh
        </button>
      </div>
      <p className="muted">
        A price changes only when a store manager proposes it, a reviewer approves it, and an admin applies it. Nobody can approve their own proposal.
      </p>
      {notice && (
        <p className="success" role="status">
          {notice}
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

      <Card title={`Suggested discounts for store ${storeId}`}>
        <Loading show={suggestions.loading} />
        <ErrorBox text={suggestions.error} />
        {suggestions.data && suggestions.data.length === 0 && <p className="muted">No low-stock products to suggest a discount for.</p>}
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
                      <span className="skip">Actions</span>
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
                          className="secondary"
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
        {list.data && visible.length === 0 && <p className="muted">No proposals yet.</p>}
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
                    <span className="skip">Actions</span>
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
                      <div className="muted">{p.rationale}</div>
                    </td>
                    <td>
                      <Badge value={p.status} />
                    </td>
                    <td>{p.createdBy ?? p.creatorType}</td>
                    <td>
                      {p.reviewerUsername ?? "–"}
                      {p.reviewComment && <div className="muted">{p.reviewComment}</div>}
                    </td>
                    <td>{when(p.expiresAt)}</td>
                    <td>
                      <div className="actions">
                        {canReview && p.status === "PENDING_REVIEW" && p.createdBy !== session.username && (
                          <>
                            <button className="primary" type="button" onClick={() => act(`Proposal ${p.id} approved.`, () => review(p.id!, "APPROVE"))}>
                              Approve
                            </button>
                            <button className="danger" type="button" onClick={() => act(`Proposal ${p.id} rejected.`, () => review(p.id!, "REJECT"))}>
                              Reject
                            </button>
                          </>
                        )}
                        {canApply && p.status === "APPROVED" && (
                          <button
                            className="primary"
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
          <button className="primary" type="submit" disabled={busy}>
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
      <div className="page-head">
        <h1>Ask RetailMind</h1>
      </div>
      <p className="muted">Read-only questions about store {storeId}: low stock, overall status, reconciliation, fulfillment issues, a product by id.</p>
      <Card title="Conversation">
        <div className="chat-log" aria-live="polite">
          {lines.length === 0 && <p className="muted">Try: "Which products are running low?"</p>}
          {lines.map((line, index) => (
            <div key={index} className={`bubble ${line.from}`}>
              <span className="skip">{line.from === "me" ? "You:" : "RetailMind:"}</span>
              {line.text}
              {line.meta && <span className="meta">{line.meta}</span>}
            </div>
          ))}
          {busy && <p className="muted">Thinking…</p>}
          <div ref={end} />
        </div>
        <ErrorBox text={error} />
        <form className="row" onSubmit={send}>
          <label style={{ flex: 1 }}>
            Your question
            <input value={text} onChange={(e) => setText(e.target.value)} maxLength={2000} disabled={busy} required />
          </label>
          <button className="primary" type="submit" disabled={busy}>
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
  const [csv, setCsv] = useState("sku,bal,aud,rac,held\nSKU-1001,20,21,1,3\nSKU-2002,8,8,0,1\nSKU-3003,12,12,0,0");
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
      <div className="page-head">
        <h1>Inventory imports</h1>
      </div>
      <Card title={`Import a snapshot for store ${storeId}`}>
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
          <p id="file-name-help" className="muted">
            The file name is built from the store, date and type, as the API requires. Products missing from the file are recorded as zero stock.
          </p>
          <label>
            Rows (CSV: sku,bal,aud,rac,held)
            <textarea value={csv} onChange={(e) => setCsv(e.target.value)} required />
          </label>
          <ErrorBox text={error} />
          {result && (
            <p className="success" role="status">
              Imported {result.rowCount} rows. Unknown SKUs: {result.unknownSkuCount}. Products not in the file, set to zero: {result.missingSkuCount}.
            </p>
          )}
          <div className="actions">
            <button className="primary" type="submit" disabled={busy}>
              {busy ? "Importing…" : "Import"}
            </button>
          </div>
        </form>
      </Card>
      <Card title="Recent imports">
        <Loading show={history.loading} />
        <ErrorBox text={history.error} />
        {history.data && history.data.length === 0 && <p className="muted">No imports yet.</p>}
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
                      <Badge value={record.status} />
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
      <div className="page-head">
        <h1>Audit trail</h1>
        <button className="secondary" type="button" onClick={reload}>
          Refresh
        </button>
      </div>
      <p className="muted">Newest 200 events you are allowed to see. Entries cannot be changed or deleted.</p>
      <Loading show={loading} />
      <ErrorBox text={error} />
      {data && (
        <Card title={`${data.length} events`}>
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
