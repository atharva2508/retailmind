import { FormEvent, useEffect, useState } from "react";
import { login, logout, resume, Role, Session, setSessionLostHandler } from "./api";
import { ErrorBox, Icon, message, ScreenBoundary, STORES, useTheme } from "./ui";
import { Dashboard, Fulfillment, Products, Reconciliation } from "./stock";
import { Audit, Chat, Imports, Proposals } from "./work";

type ScreenId = "dashboard" | "products" | "reconciliation" | "fulfillment" | "proposals" | "chat" | "imports" | "audit";

const ALL: Role[] = ["ROLE_ADMIN", "ROLE_STORE_MANAGER", "ROLE_REVIEWER", "ROLE_USER"];

// Mirrors the @PreAuthorize rules of the API, so nobody is shown a screen that would only answer 403.
// The API still enforces every rule; this list only decides what the menu shows.
const SCREENS: { id: ScreenId; label: string; icon: string; group: string; roles: Role[] }[] = [
  { id: "dashboard", label: "Overview", icon: "dashboard", group: "Store", roles: ALL },
  { id: "products", label: "Products & stock", icon: "box", group: "Store", roles: ALL },
  { id: "reconciliation", label: "Reconciliation", icon: "scale", group: "Store", roles: ["ROLE_ADMIN", "ROLE_STORE_MANAGER", "ROLE_USER"] },
  { id: "fulfillment", label: "Fulfillment", icon: "truck", group: "Store", roles: ALL },
  { id: "proposals", label: "Pricing proposals", icon: "tag", group: "Work", roles: ALL },
  { id: "chat", label: "Ask RetailMind", icon: "chat", group: "Work", roles: ALL },
  { id: "imports", label: "Inventory imports", icon: "upload", group: "Admin", roles: ["ROLE_ADMIN"] },
  { id: "audit", label: "Audit trail", icon: "list", group: "Admin", roles: ["ROLE_ADMIN", "ROLE_STORE_MANAGER", "ROLE_REVIEWER"] },
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
  const [theme, toggleTheme] = useTheme();

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
      theme={theme}
      onToggleTheme={toggleTheme}
      onLogout={async () => {
        await logout();
        setSession(null);
      }}
    />
  );
}

function Brand() {
  return (
    <div className="brand">
      <span className="brand-mark" aria-hidden="true">
        <Icon name="box" size={16} />
      </span>
      RetailMind
    </div>
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
        <Brand />
        <div>
          <h1>Sign in</h1>
          <p className="muted">Store operations: stock, reconciliation, pricing.</p>
        </div>
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
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </main>
    </div>
  );
}

function Shell({ session, theme, onToggleTheme, onLogout }: { session: Session; theme: "light" | "dark"; onToggleTheme: () => void; onLogout: () => void }) {
  const screens = SCREENS.filter((screen) => screen.roles.includes(session.role));
  const groups = [...new Set(screens.map((s) => s.group))];
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
        <Brand />
        <nav aria-label="Main">
          <ul>
            {groups.map((group) => (
              <li key={group} className="nav-group">
                <span className="nav-label">{group}</span>
                <ul>
                  {screens
                    .filter((s) => s.group === group)
                    .map((item) => (
                      <li key={item.id}>
                        <button type="button" className="nav-item" aria-current={screen === item.id ? "page" : undefined} onClick={() => setScreen(item.id)}>
                          <Icon name={item.icon} />
                          {item.label}
                        </button>
                      </li>
                    ))}
                </ul>
              </li>
            ))}
          </ul>
        </nav>
        <div className="account">
          <div>
            <div className="account-name">{session.username}</div>
            <div className="account-role">{ROLE_NAMES[session.role]}</div>
          </div>
          {isAdmin ? (
            <label>
              Store
              <select value={adminStore} onChange={(e) => setAdminStore(Number(e.target.value))}>
                {Object.entries(STORES).map(([id, name]) => (
                  <option key={id} value={id}>
                    {name}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <div className="account-role">Store: {session.storeId != null ? STORES[session.storeId] ?? session.storeId : "not assigned"}</div>
          )}
          <div className="account-row">
            <button className="btn btn-ghost btn-sm" type="button" onClick={onToggleTheme} aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}>
              <Icon name={theme === "dark" ? "sun" : "moon"} size={14} />
              {theme === "dark" ? "Light" : "Dark"}
            </button>
            <button className="btn btn-ghost btn-sm" type="button" onClick={onLogout}>
              <Icon name="logout" size={14} />
              Sign out
            </button>
          </div>
        </div>
      </aside>
      <main id="content" tabIndex={-1}>
        {storeId == null ? (
          <div className="banner critical" role="alert">
            <Icon name="cross" />
            <div>Your account has no store yet. Ask an admin to assign one; until then there is nothing to show.</div>
          </div>
        ) : (
          <ScreenBoundary key={`${screen}-${storeId}`}>
            <Screen id={screen} session={session} storeId={storeId} onNavigate={setScreen} />
          </ScreenBoundary>
        )}
      </main>
    </div>
  );
}

function Screen({ id, session, storeId, onNavigate }: { id: ScreenId; session: Session; storeId: number; onNavigate: (id: ScreenId) => void }) {
  const canReconcile = SCREENS.find((s) => s.id === "reconciliation")!.roles.includes(session.role);
  switch (id) {
    case "dashboard":
      return <Dashboard storeId={storeId} showReconciliation={canReconcile} onOpenReconciliation={() => onNavigate("reconciliation")} />;
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
