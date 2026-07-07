/**
 * Thin fetch wrapper: attaches the stored JWT and JSON content type,
 * and forces a re-login (storage clear + reload) when the token is
 * rejected — 401 from the API, plus 403 as a safety net (this app has no
 * role tiers, so a 403 can only mean a stale/invalid session).
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
  if (res.status === 401 || res.status === 403) {
    localStorage.removeItem("mail.token");
    localStorage.removeItem("mail.email");
    location.reload();
    throw new Error("unauthorized");
  }
  return res;
}
