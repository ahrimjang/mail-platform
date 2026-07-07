import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

/* Minimal auth store backed by localStorage (same keys the api() wrapper reads).
   Kept tiny on purpose — the app has a single session token, no role tiers. */

interface AuthState {
  token: string | null;
  email: string | null;
  login: (token: string, email: string) => void;
  logout: () => void;
}

const TOKEN_KEY = "mail.token";
const EMAIL_KEY = "mail.email";

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_KEY));
  const [email, setEmail] = useState<string | null>(() => localStorage.getItem(EMAIL_KEY));

  const value = useMemo<AuthState>(
    () => ({
      token,
      email,
      login(t, e) {
        localStorage.setItem(TOKEN_KEY, t);
        localStorage.setItem(EMAIL_KEY, e);
        setToken(t);
        setEmail(e);
      },
      logout() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(EMAIL_KEY);
        setToken(null);
        setEmail(null);
      },
    }),
    [token, email],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
