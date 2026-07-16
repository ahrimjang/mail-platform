/**
 * Thin fetch wrapper: attaches the stored JWT and JSON content type,
 * and forces a re-login (storage clear + reload) when the token is
 * rejected (401). A 403 is a real answer now that workspaces have role
 * tiers (OPERATOR vs ADMIN) — it flows back to the caller.
 */
export async function api(path: string, init?: RequestInit): Promise<Response> {
  const token = localStorage.getItem("mail.token");
  const headers: Record<string, string> = {
    ...(token ? { Authorization: "Bearer " + token } : {}),
    // FormData must set its own multipart boundary — don't force JSON on it.
    ...(init?.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
    ...((init?.headers as Record<string, string>) ?? {}),
  };
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401) {
    localStorage.removeItem("mail.token");
    localStorage.removeItem("mail.email");
    localStorage.removeItem("mail.role");
    localStorage.removeItem("mail.workspace");
    location.reload();
    throw new Error("unauthorized");
  }
  return res;
}
