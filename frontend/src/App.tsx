import { useEffect, useState } from "react";
import Auth from "./Auth";

type CampaignStatus = "DRAFT" | "QUEUED" | "SENDING" | "COMPLETED";

interface CampaignView {
  id: number;
  subject: string;
  status: CampaignStatus;
  total: number;
  pending: number;
  sent: number;
  failed: number;
  bounced: number;
  suppressed: number;
  opened: number;
  clicked: number;
  createdAt: string;
}

export default function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem("mail.token"));
  const [email, setEmail] = useState<string | null>(() => localStorage.getItem("mail.email"));
  const [online, setOnline] = useState(false);
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [subject, setSubject] = useState("월간 뉴스레터");
  const [body, setBody] = useState("안녕하세요, 이번 달 소식입니다.");
  const [recipients, setRecipients] = useState(
    "alice@example.com\nbob@example.com\ncarol@example.com\nbad-address",
  );
  const [submitting, setSubmitting] = useState(false);

  function authHeader(): Record<string, string> {
    return token ? { Authorization: "Bearer " + token } : {};
  }

  function logout() {
    localStorage.removeItem("mail.token");
    localStorage.removeItem("mail.email");
    setToken(null);
    setEmail(null);
    setCampaigns([]);
  }

  function onAuthed(newToken: string, newEmail: string) {
    localStorage.setItem("mail.token", newToken);
    localStorage.setItem("mail.email", newEmail);
    setToken(newToken);
    setEmail(newEmail);
  }

  async function refresh() {
    try {
      const [h, c] = await Promise.all([
        fetch("/api/health"),
        fetch("/api/campaigns", { headers: authHeader() }),
      ]);
      setOnline(h.ok);
      if (c.status === 401) {
        logout();
        return;
      }
      if (c.ok) setCampaigns(await c.json());
    } catch {
      setOnline(false);
    }
  }

  // Poll so send progress updates live as the worker drains the queue.
  useEffect(() => {
    if (!token) return;
    refresh();
    const id = setInterval(refresh, 1500);
    return () => clearInterval(id);
  }, [token]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    try {
      const list = recipients
        .split(/[\n,]/)
        .map((r) => r.trim())
        .filter(Boolean);
      const res = await fetch("/api/campaigns", {
        method: "POST",
        headers: { "Content-Type": "application/json", ...authHeader() },
        body: JSON.stringify({ subject, body, recipients: list }),
      });
      if (res.status === 401) {
        logout();
        return;
      }
      await refresh();
    } finally {
      setSubmitting(false);
    }
  }

  if (!token) {
    return <Auth onAuthed={onAuthed} />;
  }

  return (
    <main style={{ maxWidth: 720, margin: "2rem auto", fontFamily: "system-ui", padding: "0 1rem" }}>
      <h1>
        Mail Platform{" "}
        <span style={{ fontSize: 14, color: online ? "green" : "crimson" }}>
          ● api {online ? "online" : "offline"}
        </span>
      </h1>

      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <span style={{ fontSize: 14, color: "#555" }}>{email}</span>
        <button type="button" onClick={logout}>
          로그아웃
        </button>
      </div>

      <form onSubmit={submit} style={{ display: "grid", gap: 8, marginBottom: 24 }}>
        <label>
          제목
          <input
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            style={{ width: "100%" }}
          />
        </label>
        <label>
          본문
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            rows={3}
            style={{ width: "100%" }}
          />
        </label>
        <label>
          수신자 (줄바꿈 또는 쉼표로 구분)
          <textarea
            value={recipients}
            onChange={(e) => setRecipients(e.target.value)}
            rows={5}
            style={{ width: "100%" }}
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? "발송 큐 등록 중…" : "캠페인 생성 & 발송"}
        </button>
      </form>

      <h2>캠페인</h2>
      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 14 }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "1px solid #ccc" }}>
            <th>#</th>
            <th>제목</th>
            <th>상태</th>
            <th>진행</th>
            <th>오픈</th>
            <th>클릭</th>
          </tr>
        </thead>
        <tbody>
          {campaigns.length === 0 && (
            <tr>
              <td colSpan={6} style={{ padding: 12, color: "#888" }}>
                아직 캠페인이 없습니다.
              </td>
            </tr>
          )}
          {campaigns.map((c) => (
            <tr key={c.id} style={{ borderBottom: "1px solid #eee" }}>
              <td>{c.id}</td>
              <td>{c.subject}</td>
              <td>{c.status}</td>
              <td>
                {c.sent}/{c.total} 발송
                {c.failed > 0 && (
                  <span style={{ color: "crimson" }}> · {c.failed} 실패</span>
                )}
                {c.pending > 0 && (
                  <span style={{ color: "#888" }}> · {c.pending} 대기</span>
                )}
              </td>
              <td>{c.opened}</td>
              <td>{c.clicked}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}
