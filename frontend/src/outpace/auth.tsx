import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

/* Minimal auth store backed by localStorage (same keys the api() wrapper reads).
   Carries the workspace name and role so the shell can show where the user is
   operating and gate the admin console. */

interface AuthState {
  token: string | null;
  email: string | null;
  role: string | null;          // ADMIN | OPERATOR
  workspaceName: string | null;
  login: (token: string, email: string, role?: string | null, workspaceName?: string | null) => void;
  logout: () => void;
}

const TOKEN_KEY = "mail.token";
const EMAIL_KEY = "mail.email";
const ROLE_KEY = "mail.role";
const WORKSPACE_KEY = "mail.workspace";

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_KEY));
  const [email, setEmail] = useState<string | null>(() => localStorage.getItem(EMAIL_KEY));
  const [role, setRole] = useState<string | null>(() => localStorage.getItem(ROLE_KEY));
  const [workspaceName, setWorkspaceName] = useState<string | null>(() => localStorage.getItem(WORKSPACE_KEY));

  const value = useMemo<AuthState>(
    () => ({
      token,
      email,
      role,
      workspaceName,
      login(t, e, r, w) {
        localStorage.setItem(TOKEN_KEY, t);
        localStorage.setItem(EMAIL_KEY, e);
        if (r) localStorage.setItem(ROLE_KEY, r); else localStorage.removeItem(ROLE_KEY);
        if (w) localStorage.setItem(WORKSPACE_KEY, w); else localStorage.removeItem(WORKSPACE_KEY);
        setToken(t);
        setEmail(e);
        setRole(r ?? null);
        setWorkspaceName(w ?? null);
      },
      logout() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(EMAIL_KEY);
        localStorage.removeItem(ROLE_KEY);
        localStorage.removeItem(WORKSPACE_KEY);
        setToken(null);
        setEmail(null);
        setRole(null);
        setWorkspaceName(null);
      },
    }),
    [token, email, role, workspaceName],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
