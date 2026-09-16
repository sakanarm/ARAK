/**
 * Where the session token lives, and the only module allowed to touch it.
 *
 * It is separate from the store on purpose. The axios interceptor needs the
 * token on every request and the store needs axios to log in, so putting both
 * in one module makes a cycle. A tiny module in the middle that neither imports
 * breaks it.
 *
 * localStorage rather than a cookie because the backend is a stateless bearer
 * API: a cookie the browser attaches on its own would need CSRF defences that a
 * header the code sets deliberately does not. The trade is that a script
 * running on our own origin could read the token, which is what the content
 * security policy and a short token lifetime are for.
 */

const TOKEN_KEY = 'dac.session.token';
const USER_KEY = 'dac.session.user';

export interface SessionUser {
  id: string;
  username: string;
  email: string | null;
  displayName: string | null;
  source: string;
  roles: string[];
  scopes: string[];
}

type Listener = () => void;

const expiryListeners = new Set<Listener>();

/** Called by the axios interceptor when the backend rejects our token. */
export function onSessionExpired(listener: Listener): () => void {
  expiryListeners.add(listener);
  return () => expiryListeners.delete(listener);
}

export function notifySessionExpired(): void {
  expiryListeners.forEach((listener) => listener());
}

export function readToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    // Private mode, or storage disabled by policy. The session then lasts only
    // as long as the tab, which is inconvenient but not broken.
    return null;
  }
}

export function readUser(): SessionUser | null {
  try {
    const raw = window.localStorage.getItem(USER_KEY);
    return raw ? (JSON.parse(raw) as SessionUser) : null;
  } catch {
    return null;
  }
}

export function writeSession(token: string, user: SessionUser): void {
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
    window.localStorage.setItem(USER_KEY, JSON.stringify(user));
  } catch {
    /* see readToken */
  }
}

export function clearSession(): void {
  try {
    window.localStorage.removeItem(TOKEN_KEY);
    window.localStorage.removeItem(USER_KEY);
  } catch {
    /* see readToken */
  }
}
