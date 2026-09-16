import { create } from 'zustand';
import * as api from '../api/client';
import {
  clearSession,
  onSessionExpired,
  readToken,
  readUser,
  writeSession,
  type SessionUser,
} from './session';

/**
 * Who is signed in.
 *
 * Zustand rather than a context provider, matching OpenMetadata, and for the
 * same reason: the axios interceptor and the route guards read this outside the
 * React tree, which a context cannot serve.
 *
 * The store starts from localStorage so a refresh does not bounce the user back
 * to the login screen while a /me request is in flight. That initial user is
 * only a hint — the backend verifies the token on every call, and a token that
 * no longer holds is cleared by the 401 interceptor.
 */
export interface AuthState {
  token: string | null;
  user: SessionUser | null;
  /** True until the stored session has been checked against the backend. */
  initialising: boolean;
  mustChangePassword: boolean;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => void;
  refresh: () => Promise<void>;
  hasRole: (...roles: string[]) => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  token: readToken(),
  user: readUser(),
  initialising: readToken() !== null,
  mustChangePassword: false,

  signIn: async (username, password) => {
    const result = await api.login(username, password);
    writeSession(result.accessToken, result.user);
    set({
      token: result.accessToken,
      user: result.user,
      mustChangePassword: result.mustChangePassword,
      initialising: false,
    });
  },

  signOut: () => {
    // No server call: the token is stateless, so signing out is forgetting it.
    // Revocation before expiry needs a deny list, which is a Phase 2 decision.
    clearSession();
    set({ token: null, user: null, mustChangePassword: false, initialising: false });
  },

  refresh: async () => {
    if (!get().token) {
      set({ initialising: false });
      return;
    }
    try {
      const user = await api.fetchMe();
      const token = get().token;
      if (token) {
        writeSession(token, user);
      }
      set({ user, initialising: false });
    } catch {
      // A 401 has already cleared the session through the interceptor; anything
      // else (backend down) leaves the cached user in place so the console is
      // still readable, and the next call will say so plainly.
      set({ initialising: false });
    }
  },

  hasRole: (...roles) => {
    const held = get().user?.roles ?? [];
    return held.includes('PLATFORM_ADMIN') || roles.some((role) => held.includes(role));
  },
}));

// The interceptor cannot import the store without a cycle, so it raises a
// signal and the store listens for it.
onSessionExpired(() => {
  useAuthStore.setState({
    token: null,
    user: null,
    mustChangePassword: false,
    initialising: false,
  });
});
