import { FormEvent, useEffect, useState } from "react";
import { login, logout, resume, Role, Session, setSessionLostHandler } from "./api";
import { ErrorBox, message, ScreenBoundary } from "./ui";
import { Dashboard, Fulfillment, Products, Reconciliation } from "./stock";
import { Audit, Chat, Imports, Proposals } from "./work";

type ScreenId = "dashboard" | "products" | "reconciliation" | "fulfillment" | "proposals" | "chat" | "imports" | "audit";

const ALL: Role[] = ["ROLE_ADMIN", "ROLE_STORE_MANAGER", "ROLE_REVIEWER", "ROLE_USER"];

// Mirrors the @PreAuthorize rules of the API, so nobody is shown a screen that would only answer 403.
// The API still enforces every rule; this list only decides what the menu shows.
const SCREENS: { id: ScreenId; label: string; roles: Role[] }[] = [
  { id: "dashboard", label: "Dashboard", roles: ALL },
  { id: "products", label: "Products & stock", roles: ALL },
  { id: "reconciliation", label: "Reconciliation", roles: ["ROLE_ADMIN", "ROLE_STORE_MANAGER", "ROLE_USER"] },
  { id: "fulfillment", label: "Fulfillment issues", roles: ALL },
  { id: "proposals", label: "Pricing proposals", roles: ALL },
  { id: "chat", label: "Ask RetailMind", roles: ALL },
  { id: "imports", label: "Inventory imports", roles: ["ROLE_ADMIN"] },
  { id: "audit", label: "Audit trail", roles: ["ROLE_ADMIN", "ROLE_STORE_MANAGER", "ROLE_REVIEWER"] },
];

const ROLE_NAMES: Record<Role, string> = {
  ROLE_ADMIN: "Admin",
  ROLE_STORE_MANAGER: "Store manager",
  ROLE_REVIEWER: "Reviewer",
  ROLE_USER: "Staff",
};

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [starting, setStarting] = useState(true);

  useEffect(() => {
    setSessionLostHandler(() => setSession(null));
    resume()
      .then(setSession)
      .finally(() => setStarting(false));
  }, []);

  if (starting) return <p className="muted login-wrap">Loading…</p>;
  if (!session) return <Login onLogin={setSession} />;
  return (
    <Shell
      session={session}
      onLogout={async () => {
        await logout();
        setSession(null);
      }}
    />
  );
}

function Login({ onLogin }: { onLogin: (session: Session) => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onLogin(await login(username, password));
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <main className="card login">
        <h1>RetailMind</h1>
        <p className="muted">Sign in with your store account.</p>
        <form onSubmit={submit}>
          <label>
            Username
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required autoFocus />
          </label>
          <label>
            Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
          </label>
          <ErrorBox text={error} />
          <button className="primary" type="submit" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </main>
    </div>
  );
}

function Shell({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const screens = SCREENS.filter((screen) => screen.roles.includes(session.role));
  const [screen, setScreen] = useState<ScreenId>("dashboard");
  const isAdmin = session.role === "ROLE_ADMIN";
  // Admin may look at any store. Everyone else is fixed to their own store by the API anyway.
  const [adminStore, setAdminStore] = useState<number>(session.storeId ?? 1);
  const storeId = isAdmin ? adminStore : session.storeId;

  return (
    <div className="layout">
      <a className="skip" href="#content">
        Skip to content
      </a>
      <aside className="sidebar">
        <div className="brand">RetailMind</div>
        <nav aria-label="Main">
          <ul>
            {screens.map((item) => (
              <li key={item.id}>
                <button type="button" aria-current={screen === item.id ? "page" : undefined} onClick={() => setScreen(item.id)}>
                  {item.label}
                </button>
              </li>
            ))}
          </ul>
        </nav>
        <div className="who">
          <span>
            {session.username} · {ROLE_NAMES[session.role]}
          </span>
          {isAdmin ? (
            <label>
              Store
              <select value={adminStore} onChange={(e) => setAdminStore(Number(e.target.value))}>
                {/* ponytail: the API has no store list yet; seeded stores are 1-3. Add GET /api/stores when stores are managed. */}
                <option value={1}>1 · Main</option>
                <option value={2}>2 · North</option>
                <option value={3}>3 · South</option>
              </select>
            </label>
          ) : (
            <span>Store {session.storeId ?? "not assigned"}</span>
          )}
          <button className="secondary" type="button" onClick={onLogout}>
            Sign out
          </button>
        </div>
      </aside>
      <main id="content" tabIndex={-1}>
        {storeId == null ? (
          <p className="notice bad" role="alert">
            Your account has no store yet. Ask an admin to assign one; until then there is nothing to show.
          </p>
        ) : (
          <ScreenBoundary key={`${screen}-${storeId}`}>
            <Screen id={screen} session={session} storeId={storeId} />
          </ScreenBoundary>
        )}
      </main>
    </div>
  );
}

function Screen({ id, session, storeId }: { id: ScreenId; session: Session; storeId: number }) {
  switch (id) {
    case "dashboard":
      return <Dashboard storeId={storeId} />;
    case "products":
      return <Products storeId={storeId} />;
    case "reconciliation":
      return <Reconciliation storeId={storeId} />;
    case "fulfillment":
      return <Fulfillment storeId={storeId} />;
    case "proposals":
      return <Proposals storeId={storeId} session={session} />;
    case "chat":
      return <Chat storeId={storeId} />;
    case "imports":
      return <Imports storeId={storeId} />;
    case "audit":
      return <Audit />;
  }
}
