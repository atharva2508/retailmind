// Small fetch wrapper for the RetailMind API.
// The access token lives in memory only. The refresh token is kept in sessionStorage so a page reload
// keeps you logged in for this tab; it is gone when the tab closes.
// ponytail: sessionStorage is readable by any script on the page. Move the refresh token to an
// HttpOnly cookie set by the server when this UI is deployed anywhere but a developer's machine.

export type Role = "ROLE_ADMIN" | "ROLE_STORE_MANAGER" | "ROLE_REVIEWER" | "ROLE_USER";

export interface Session {
  username: string;
  role: Role;
  storeId: number | null;
}

interface AuthResponse extends Session {
  accessToken: string;
  refreshToken: string;
}

const REFRESH_KEY = "retailmind.refreshToken";

let accessToken: string | null = null;
let onSessionLost: () => void = () => {};

export class ApiError extends Error {
  constructor(public status: number, message: string, public fields?: Record<string, string>) {
    super(message);
  }
}

export function setSessionLostHandler(handler: () => void) {
  onSessionLost = handler;
}

function readRefresh(): string | null {
  try {
    return sessionStorage.getItem(REFRESH_KEY);
  } catch {
    return null;
  }
}

function writeRefresh(token: string | null) {
  try {
    if (token) sessionStorage.setItem(REFRESH_KEY, token);
    else sessionStorage.removeItem(REFRESH_KEY);
  } catch {
    // storage blocked: the session simply ends on reload
  }
}

function keep(auth: AuthResponse): Session {
  accessToken = auth.accessToken;
  writeRefresh(auth.refreshToken);
  return { username: auth.username, role: auth.role, storeId: auth.storeId };
}

async function toError(response: Response): Promise<ApiError> {
  let body: { error?: string; fields?: Record<string, string> } = {};
  try {
    body = await response.json();
  } catch {
    // empty or non-JSON body
  }
  const fallback: Record<number, string> = {
    401: "Please log in again.",
    403: "You are not allowed to do this.",
    404: "Not found.",
    429: "Too many requests. Wait a moment and try again.",
    503: "The service is unavailable. Try again shortly.",
  };
  return new ApiError(response.status, body.error ?? fallback[response.status] ?? `Request failed (${response.status})`, body.fields);
}

export async function login(username: string, password: string): Promise<Session> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) {
    if (response.status === 401) throw new ApiError(401, "Wrong username or password.");
    throw await toError(response);
  }
  return keep(await response.json());
}

// One refresh at a time. A refresh token works once: the server treats a second use as theft and ends the
// session. Startup (run twice by React in dev), parallel 401s and retries must all share one request.
let refreshing: Promise<Session | null> | null = null;

/** Uses the stored refresh token to start a new session. Returns null when there is none or it is no longer valid. */
export function resume(): Promise<Session | null> {
  refreshing ??= refreshOnce().finally(() => (refreshing = null));
  return refreshing;
}

async function refreshOnce(): Promise<Session | null> {
  const refreshToken = readRefresh();
  if (!refreshToken) return null;
  const response = await fetch("/api/auth/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });
  if (!response.ok) {
    writeRefresh(null);
    accessToken = null;
    return null;
  }
  return keep(await response.json());
}

export async function logout() {
  const refreshToken = readRefresh();
  accessToken = null;
  writeRefresh(null);
  if (refreshToken) {
    await fetch("/api/auth/logout", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    }).catch(() => undefined);
  }
}

/** Authenticated call. On a 401 it refreshes the session once and retries. */
export async function api<T>(path: string, options: { method?: string; body?: unknown; query?: Record<string, string | number | undefined | null> } = {}): Promise<T> {
  const params = new URLSearchParams();
  Object.entries(options.query ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  });
  const url = params.size ? `${path}?${params}` : path;

  const send = () =>
    fetch(url, {
      method: options.method ?? "GET",
      headers: {
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
        ...(options.body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });

  let response = await send();
  if (response.status === 401) {
    if (await resume()) {
      response = await send();
    } else {
      onSessionLost();
      throw new ApiError(401, "Your session has ended. Please log in again.");
    }
  }
  if (!response.ok) throw await toError(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
