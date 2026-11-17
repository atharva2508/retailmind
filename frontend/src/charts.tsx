import { RefObject, useEffect, useMemo, useRef, useState } from "react";
import { api } from "./api";
import { count, day, daysAgo, ErrorBox, Loading, Status, useLoad } from "./ui";

export interface DayResult {
  businessDate: string;
  status: string;
  balVariance: number | null;
  racVariance: number | null;
}

/** Reconciliation for the last `days` days in one request (one request per day used to trip the rate limit). */
export function useReconciliationRange(storeId: number, days: number, endOffset = 1) {
  return useLoad(
    () => api<DayResult[]>(`/api/stores/${storeId}/reconciliation/range`, { query: { from: daysAgo(endOffset + days - 1), to: daysAgo(endOffset) } }),
    [storeId, days, endOffset],
  );
}

const H = 200;

/** Width of an element in CSS pixels, so the chart draws at real size and its text never scales down. */
function useWidth<T extends HTMLElement>(fallback: number): [RefObject<T>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(fallback);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(280, Math.round(entry.contentRect.width))));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}
const PAD = { top: 12, right: 8, bottom: 26, left: 40 };

/**
 * Diverging column chart: one bar per day, below zero = units missing (red pole), above = extra units
 * (blue pole), zero draws nothing. A day without snapshots gets an open ring on the baseline, not a bar,
 * so "no data" never reads as "balanced". Every value is also in the table view.
 */
export function VarianceChart({
  data,
  field,
  title,
  onPick,
  selected,
}: {
  data: DayResult[];
  field: "balVariance" | "racVariance";
  title: string;
  onPick?: (date: string) => void;
  selected?: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const [asTable, setAsTable] = useState(false);
  const [box, W] = useWidth<HTMLDivElement>(640);

  const values = data.map((d) => (d.status === "NO_DATA" ? null : d[field] ?? 0));
  const extent = Math.max(1, ...values.map((v) => Math.abs(v ?? 0)));
  const step = niceStep(extent);
  const top = Math.ceil(Math.max(0, ...values.map((v) => v ?? 0)) / step) * step || step;
  const bottom = Math.floor(Math.min(0, ...values.map((v) => v ?? 0)) / step) * step;
  const plotH = H - PAD.top - PAD.bottom;
  const plotW = W - PAD.left - PAD.right;
  const y = (v: number) => PAD.top + ((top - v) / (top - bottom)) * plotH;
  const band = plotW / data.length;
  const barW = Math.min(24, band * 0.62);
  const ticks: number[] = [];
  for (let t = bottom; t <= top + 1e-9; t += step) ticks.push(Math.round(t));
  const labelEvery = Math.max(1, Math.ceil(data.length / Math.max(2, Math.floor(plotW / 64))));

  const missing = values.reduce<number>((sum, v) => sum + Math.min(0, v ?? 0), 0);
  const varianceDays = data.filter((d) => d.status === "VARIANCE").length;
  const noDataDays = data.filter((d) => d.status === "NO_DATA").length;

  const tip = hover != null ? data[hover] : null;

  return (
    <div className="chart" style={{ display: "grid", gap: 10 }}>
      <div className="card-head">
        <div className="legend" aria-hidden={asTable}>
          <span>
            <i className="swatch" style={{ background: "var(--neg)" }} />
            Units missing
          </span>
          <span>
            <i className="swatch" style={{ background: "var(--pos)" }} />
            Extra units
          </span>
          <span>
            <svg width="10" height="10" aria-hidden="true">
              <circle cx="5" cy="5" r="3.5" fill="none" stroke="var(--axis)" strokeWidth="1.5" />
            </svg>
            No snapshot
          </span>
        </div>
        <div className="segmented" role="group" aria-label={`${title} view`}>
          <button type="button" aria-pressed={!asTable} onClick={() => setAsTable(false)}>
            Chart
          </button>
          <button type="button" aria-pressed={asTable} onClick={() => setAsTable(true)}>
            Table
          </button>
        </div>
      </div>

      {asTable ? (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">{title}</caption>
            <thead>
              <tr>
                <th>Date</th>
                <th>Result</th>
                <th className="num">Units</th>
              </tr>
            </thead>
            <tbody>
              {data.map((d, i) => (
                <tr key={d.businessDate}>
                  <td>{day(d.businessDate)}</td>
                  <td>
                    <Status value={d.status} />
                  </td>
                  <td className="num">{values[i] == null ? "–" : signed(values[i]!)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div ref={box} style={{ position: "relative" }}>
          {/* One tab stop for the whole chart; arrow keys move between days, Enter opens one. */}
          <svg
            width={W}
            height={H}
            viewBox={`0 0 ${W} ${H}`}
            tabIndex={0}
            role="group"
            aria-label={`${title}. ${varianceDays} days with a variance, ${noDataDays} without data. Use left and right arrow keys to read each day${onPick ? ", Enter to open it" : ""}.`}
            onFocus={() => setHover((current) => current ?? data.length - 1)}
            onBlur={() => setHover(null)}
            onKeyDown={(e) => {
              const last = data.length - 1;
              const move: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1 };
              if (e.key in move) setHover((current) => Math.min(last, Math.max(0, (current ?? last) + move[e.key])));
              else if (e.key === "Home") setHover(0);
              else if (e.key === "End") setHover(last);
              else if ((e.key === "Enter" || e.key === " ") && onPick && hover != null) onPick(data[hover].businessDate);
              else return;
              e.preventDefault();
            }}
          >
            {ticks.map((t) => (
              <g key={t}>
                <line className={t === 0 ? "zero-line" : "grid-line"} x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} />
                <text className="tick" x={PAD.left - 8} y={y(t) + 4} textAnchor="end">
                  {t > 0 ? `+${count(t)}` : count(t)}
                </text>
              </g>
            ))}
            {data.map((d, i) => {
              const v = values[i];
              const cx = PAD.left + band * i + band / 2;
              const picked = selected === d.businessDate;
              return (
                <g
                  key={d.businessDate}
                  className={`col ${hover === i || picked ? "active" : ""}`}
                  aria-hidden="true"
                  onPointerEnter={() => setHover(i)}
                  onPointerLeave={() => setHover(null)}
                  onClick={() => onPick?.(d.businessDate)}
                  style={{ cursor: onPick ? "pointer" : "default" }}
                >
                  <rect className="bar-hit" x={PAD.left + band * i} y={PAD.top} width={band} height={plotH} />
                  {v == null ? (
                    <circle className="no-data" cx={cx} cy={y(0)} r={3.5} />
                  ) : v !== 0 ? (
                    <path d={columnPath(cx - barW / 2, barW, y(0), y(v))} fill={v < 0 ? "var(--neg)" : "var(--pos)"} />
                  ) : null}
                  {i % labelEvery === 0 && (
                    <text className="tick" x={cx} y={H - 6} textAnchor="middle">
                      {day(d.businessDate)}
                    </text>
                  )}
                </g>
              );
            })}
          </svg>
          <p className="sr-only" aria-live="polite">
            {tip && hover != null ? `${day(tip.businessDate)}: ${values[hover] == null ? "no snapshot" : values[hover] === 0 ? "balanced" : `${signed(values[hover]!)} units`}` : ""}
          </p>
          {tip && hover != null && (
            <div className="chart-tip" style={{ left: `${((PAD.left + band * hover + band / 2) / W) * 100}%`, top: 0 }}>
              <strong>{values[hover] == null ? "No snapshot" : values[hover] === 0 ? "Balanced" : `${signed(values[hover]!)} units`}</strong>
              {day(tip.businessDate)}
              {onPick && <span className="muted"> · click to open</span>}
            </div>
          )}
        </div>
      )}
      <p className="sub">
        {varianceDays === 0 ? "Every day with data balanced." : `${varianceDays} of ${data.length} days off balance, ${count(-missing)} units missing in total.`}
        {noDataDays > 0 && ` ${noDataDays} days have no snapshot.`}
      </p>
    </div>
  );
}

/** Column with a 4px rounded data end and a square end at the zero baseline. */
function columnPath(x: number, w: number, base: number, end: number): string {
  const r = Math.min(4, Math.abs(end - base), w / 2);
  if (end < base) {
    return `M${x},${base} V${end + r} Q${x},${end} ${x + r},${end} H${x + w - r} Q${x + w},${end} ${x + w},${end + r} V${base} Z`;
  }
  return `M${x},${base} V${end - r} Q${x},${end} ${x + r},${end} H${x + w - r} Q${x + w},${end} ${x + w},${end - r} V${base} Z`;
}

function niceStep(extent: number): number {
  const raw = extent / 3;
  const power = 10 ** Math.floor(Math.log10(raw));
  const unit = [1, 2, 5, 10].find((m) => m * power >= raw) ?? 10;
  return Math.max(1, unit * power);
}

function signed(v: number): string {
  return v > 0 ? `+${count(v)}` : count(v);
}

/** Wrapper used by screens: loads the range, then draws it. */
export function ReconciliationTrend({ storeId, days, field, title, onPick, selected }: { storeId: number; days: number; field: "balVariance" | "racVariance"; title: string; onPick?: (date: string) => void; selected?: string }) {
  const { data, error, loading } = useReconciliationRange(storeId, days);
  return (
    <>
      <Loading show={loading && !data} />
      <ErrorBox text={error} />
      {data && (
        <div style={{ opacity: loading ? 0.5 : 1 }}>
          <VarianceChart data={data} field={field} title={title} onPick={onPick} selected={selected} />
        </div>
      )}
    </>
  );
}

export interface Part {
  label: string;
  value: number;
  color: string;
  lightFill?: boolean;
}

/**
 * Tall segmented bar: one whole split into parts. A part at zero draws nothing; a part too narrow
 * for its number prints none (the legend and the tiles above keep every value).
 */
export function SegmentedBar({ parts, label }: { parts: Part[]; label: string }) {
  const total = parts.reduce((sum, p) => sum + Math.max(0, p.value), 0);
  const shown = useMemo(() => parts.filter((p) => p.value > 0), [parts]);
  if (total === 0) return <p className="empty">No units in any bucket.</p>;
  return (
    <div style={{ display: "grid", gap: 10 }}>
      <div className="segbar" role="group" aria-label={label}>
        {shown.map((p) => {
          const share = p.value / total;
          return (
            <div
              key={p.label}
              className={p.lightFill ? "on-light" : ""}
              style={{ flexGrow: p.value, flexBasis: 0, background: p.color }}
              tabIndex={0}
              role="img"
              aria-label={`${p.label}: ${count(p.value)} units, ${Math.round(share * 100)}%`}
              title={`${p.label}: ${count(p.value)} units (${Math.round(share * 100)}%)`}
            >
              {share >= 0.12 ? count(p.value) : ""}
            </div>
          );
        })}
      </div>
      <div className="legend">
        {parts.map((p) => (
          <span key={p.label}>
            <i className="swatch" style={{ background: p.color }} />
            {p.label}
          </span>
        ))}
      </div>
    </div>
  );
}
