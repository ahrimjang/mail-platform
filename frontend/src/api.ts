/**
 * Thin fetch wrapper: attaches the stored JWT and JSON content type,
 * and forces a re-login (storage clear + reload) on 401 responses.
 */
export async function api(path: string, init?: RequestInit): Promise<Response> {
  const token = localStorage.getItem("mail.token");
  const headers: Record<string, string> = {
    ...(token ? { Authorization: "Bearer " + token } : {}),
    "Content-Type": "application/json",
    ...((init?.headers as Record<string, string>) ?? {}),
  };
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401) {
    localStorage.removeItem("mail.token");
    localStorage.removeItem("mail.email");
    location.reload();
    throw new Error("unauthorized");
  }
  return res;
}
