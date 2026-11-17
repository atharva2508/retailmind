import { Component, ReactNode, useCallback, useEffect, useState } from "react";
import { ApiError } from "./api";

/** Keeps one broken screen from blanking the whole app. */
export class ScreenBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? <Banner tone="critical">This screen hit an error. Pick another screen or reload the page.</Banner> : this.props.children;
  }
}

/** Runs a load function and keeps its data, error and loading state. `reload` runs it again. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const run = useCallback(() => {
    let current = true;
    setLoading(true);
    setError(null);
    load()
      .then((value) => current && setData(value))
      .catch((e: unknown) => {
        if (!current) return;
        setData(null);
        setError(message(e));
      })
      .finally(() => current && setLoading(false));
    return () => {
      current = false; // a slower, older request must not overwrite a newer answer
    };
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

// ---------- icons (inline, 16px, stroke follows currentColor) ----------

const PATHS: Record<string, string> = {
  dashboard: "M3 3h7v9H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 16h7v5H3z",
  box: "M21 8l-9-5-9 5v8l9 5 9-5zM3 8l9 5 9-5M12 13v8",
  scale: "M12 3v18M5 7h14M5 7l-3 7a4 4 0 0 0 6 0zM19 7l-3 7a4 4 0 0 0 6 0zM8 21h8",
  truck: "M3 6h11v10H3zM14 9h4l3 3v4h-7M7 19a2 2 0 1 0 0-.01M17 19a2 2 0 1 0 0-.01",
  tag: "M3 12V3h9l9 9-9 9zM7.5 7.5h.01",
  chat: "M21 12a8 8 0 0 1-11.6 7.1L3 21l1.9-6.4A8 8 0 1 1 21 12z",
  upload: "M12 16V4M7 9l5-5 5 5M4 16v4h16v-4",
  list: "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01",
  sun: "M12 4V2M12 22v-2M4 12H2M22 12h-2M5.6 5.6 4.2 4.2M19.8 19.8l-1.4-1.4M5.6 18.4l-1.4 1.4M19.8 4.2l-1.4 1.4M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z",
  moon: "M21 13A9 9 0 1 1 11 3a7 7 0 0 0 10 10z",
  logout: "M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11",
  check: "M5 12l5 5 9-10",
  alert: "M12 8v5M12 16.5h.01M10.3 3.9 2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
  cross: "M6 6l12 12M18 6 6 18",
  clock: "M12 7v5l3 2M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z",
  dash: "M6 12h12",
  refresh: "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7",
};

export function Icon({ name, size = 16 }: { name: keyof typeof PATHS | string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={PATHS[name] ?? PATHS.dash} />
    </svg>
  );
}

// ---------- status: reserved status colours, always with an icon and a label ----------

type Tone = "good" | "warning" | "serious" | "critical" | "neutral";

const STATUS: Record<string, { tone: Tone; label: string }> = {
  FRESH: { tone: "good", label: "Fresh" },
  OK: { tone: "good", label: "Balanced" },
  STABLE: { tone: "good", label: "Stable" },
  APPROVED: { tone: "good", label: "Approved" },
  APPLIED: { tone: "good", label: "Applied" },
  STALE: { tone: "warning", label: "Stale" },
  AT_RISK: { tone: "warning", label: "At risk" },
  PENDING_REVIEW: { tone: "warning", label: "Pending review" },
  ACTION_REQUIRED: { tone: "warning", label: "Action required" },
  VARIANCE: { tone: "serious", label: "Variance" },
  OUT_OF_STOCK: { tone: "critical", label: "Out of stock" },
  NO_DATA: { tone: "critical", label: "No data" },
  REJECTED: { tone: "critical", label: "Rejected" },
  SUGGESTED: { tone: "neutral", label: "Suggested" },
};

const TONE_ICON: Record<Tone, string> = { good: "check", warning: "alert", serious: "alert", critical: "cross", neutral: "dash" };

export function Status({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">–</span>;
  const known = STATUS[value] ?? { tone: "neutral" as Tone, label: value.replace(/_/g, " ").toLowerCase() };
  return (
    <span className={`status ${known.tone}`}>
      <Icon name={TONE_ICON[known.tone]} size={14} />
      {known.label}
    </span>
  );
}

export function Banner({ tone, children }: { tone: "warning" | "critical" | "good"; children: ReactNode }) {
  return (
    <div className={`banner ${tone}`} role={tone === "critical" ? "alert" : "status"}>
      <Icon name={TONE_ICON[tone]} />
      <div>{children}</div>
    </div>
  );
}

export function ErrorBox({ text }: { text: string | null }) {
  if (!text) return null;
  return (
    <p className="error" role="alert">
      <Icon name="cross" />
      <span>{text}</span>
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

export function Card({ title, sub, children, actions, className }: { title: string; sub?: ReactNode; children: ReactNode; actions?: ReactNode; className?: string }) {
  return (
    <section className={`card ${className ?? ""}`} aria-label={title}>
      <header className="card-head">
        <div>
          <h2>{title}</h2>
          {sub && <p className="card-sub">{sub}</p>}
        </div>
        {actions}
      </header>
      {children}
    </section>
  );
}

/** Stat tile: label, value, optional note. Big standalone numbers keep proportional figures. */
export function Tile({ label, value, note }: { label: string; value: ReactNode; note?: ReactNode }) {
  return (
    <div className="tile">
      <span className="tile-label">{label}</span>
      <span className="tile-value">{value ?? "–"}</span>
      {note && <span className="tile-note">{note}</span>}
    </div>
  );
}

export function PageHead({ eyebrow, title, lede, actions }: { eyebrow?: string; title: string; lede?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {lede && <p className="lede">{lede}</p>}
      </div>
      {actions}
    </div>
  );
}

// ponytail: the API has no store list yet; these are the seeded stores. Add GET /api/stores when stores are managed.
export const STORES: Record<number, string> = { 1: "Main (UK)", 2: "North (Germany)", 3: "South (France)" };

// ---------- formatting ----------

const integer = new Intl.NumberFormat();
const compact = new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 });

/** 1,284 under ten thousand, 12.9K above. */
export function count(value: number | null | undefined): string {
  if (value == null) return "–";
  return Math.abs(value) >= 10_000 ? compact.format(value) : integer.format(value);
}

export function money(value: number | null | undefined): string {
  return value == null ? "–" : Number(value).toFixed(2);
}

export function when(value: string | null | undefined): string {
  if (!value) return "–";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export function day(value: string): string {
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString(undefined, { day: "numeric", month: "short" });
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

// ---------- theme (per-viewer convenience, so browser storage is fine) ----------

const THEME_KEY = "retailmind.theme";

export function useTheme(): ["light" | "dark", () => void] {
  const system = () => (window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  const [theme, setTheme] = useState<"light" | "dark">(() => {
    try {
      const saved = localStorage.getItem(THEME_KEY);
      if (saved === "light" || saved === "dark") return saved;
    } catch {
      // storage blocked
    }
    return system();
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  const toggle = () => {
    const next = theme === "dark" ? "light" : "dark";
    try {
      localStorage.setItem(THEME_KEY, next);
    } catch {
      // storage blocked: the choice lasts for this page only
    }
    setTheme(next);
  };
  return [theme, toggle];
}
