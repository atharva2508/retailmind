import { Component, ReactNode, useCallback, useEffect, useState } from "react";

/** Keeps one broken screen from blanking the whole app. */
export class ScreenBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? (
      <p className="error" role="alert">
        This screen hit an error. Pick another screen or reload the page.
      </p>
    ) : (
      this.props.children
    );
  }
}
import { ApiError } from "./api";

/** Runs a load function and keeps its data, error and loading state. `reload` runs it again. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const run = useCallback(() => {
    setLoading(true);
    setError(null);
    load()
      .then(setData)
      .catch((e: unknown) => {
        setData(null);
        setError(message(e));
      })
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(run, [run]);
  return { data, error, loading, reload: run };
}

export function message(e: unknown): string {
  if (e instanceof ApiError) {
    const fields = e.fields ? Object.entries(e.fields).map(([field, text]) => `${field}: ${text}`).join("; ") : "";
    return fields ? `${e.message} (${fields})` : e.message;
  }
  return e instanceof Error ? e.message : "Something went wrong.";
}

export function ErrorBox({ text }: { text: string | null }) {
  if (!text) return null;
  return (
    <p className="error" role="alert">
      {text}
    </p>
  );
}

export function Loading({ show }: { show: boolean }) {
  return show ? (
    <p className="muted" aria-live="polite">
      Loading…
    </p>
  ) : null;
}

const STATUS_TONE: Record<string, string> = {
  FRESH: "good",
  OK: "good",
  STABLE: "good",
  APPROVED: "good",
  APPLIED: "good",
  STALE: "warn",
  AT_RISK: "warn",
  PENDING_REVIEW: "warn",
  ACTION_REQUIRED: "warn",
  VARIANCE: "bad",
  OUT_OF_STOCK: "bad",
  NO_DATA: "bad",
  REJECTED: "bad",
};

/** Status word with a colour. The word itself always carries the meaning, so colour is never the only signal. */
export function Badge({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">–</span>;
  return <span className={`badge ${STATUS_TONE[value] ?? ""}`}>{value.replace(/_/g, " ")}</span>;
}

export function Card({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card" aria-label={title}>
      <header className="card-head">
        <h2>{title}</h2>
        {actions}
      </header>
      {children}
    </section>
  );
}

export function Stat({ label, value, tone }: { label: string; value: ReactNode; tone?: string }) {
  return (
    <div className={`stat ${tone ?? ""}`}>
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value ?? "–"}</span>
    </div>
  );
}

export function when(value: string | null | undefined): string {
  if (!value) return "–";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

// Local calendar date as yyyy-MM-dd. toISOString() would give the UTC date, which is a day off
// for part of the day outside UTC (for example before 05:30 in India).
function localDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function today(): string {
  return localDate(new Date());
}

export function daysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return localDate(date);
}
