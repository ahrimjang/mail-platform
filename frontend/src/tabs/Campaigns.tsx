import { useEffect, useState } from "react";
import { api } from "../api";
import type { CampaignView, ContactListView, TemplateView } from "../types";

type Mode = "direct" | "template";

export default function Campaigns() {
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [templates, setTemplates] = useState<TemplateView[]>([]);
  const [lists, setLists] = useState<ContactListView[]>([]);
  const [mode, setMode] = useState<Mode>("direct");
  const [subject, setSubject] = useState("월간 뉴스레터");
  const [body, setBody] = useState("<p>안녕하세요, 이번 달 소식입니다.</p>");
  const [recipients, setRecipients] = useState("alice@example.com\nbob@example.com");
  const [templateId, setTemplateId] = useState("");
  const [listId, setListId] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    try {
      const res = await api("/api/campaigns");
      if (res.ok) setCampaigns(await res.json());
    } catch {
      /* polling failure is transient */
    }
  }

  async function loadOptions() {
    try {
      const [t, l] = await Promise.all([api("/api/templates"), api("/api/lists")]);
      if (t.ok) setTemplates(await t.json());
      if (l.ok) setLists(await l.json());
    } catch {
      /* ignore */
    }
  }

  // Poll so send progress updates live as the worker drains the queue.
  useEffect(() => {
    refresh();
    loadOptions();
    const id = setInterval(refresh, 2000);
    return () => clearInterval(id);
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const payload =
        mode === "direct"
          ? {
              subject,
              body,
              recipients: recipients
                .split(/[\n,]/)
                .map((r) => r.trim())
                .filter(Boolean),
            }
          : {
              templateId: templateId ? Number(templateId) : null,
              listId: listId ? Number(listId) : null,
            };
      const res = await api("/api/campaigns", {
        method: "POST",
        body: JSON.stringify(payload),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "캠페인 생성에 실패했습니다.");
        return;
      }
      await refresh();
    } catch {
      setError("캠페인 생성에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      <section className="card">
        <h2>캠페인 생성</h2>
        <div className="radio-row">
          <label>
            <input
              type="radio"
              name="campaign-mode"
              checked={mode === "direct"}
              onChange={() => setMode("direct")}
            />{" "}
            직접 입력
          </label>
          <label>
            <input
              type="radio"
              name="campaign-mode"
              checked={mode === "template"}
              onChange={() => setMode("template")}
            />{" "}
            템플릿 × 리스트
          </label>
        </div>

        <form onSubmit={submit} className="form-grid">
          {mode === "direct" ? (
            <>
              <label>
                제목
                <input value={subject} onChange={(e) => setSubject(e.target.value)} />
              </label>
              <label>
                본문 (HTML)
                <textarea value={body} onChange={(e) => setBody(e.target.value)} rows={4} />
              </label>
              <label>
                수신자 (줄바꿈 또는 쉼표로 구분)
                <textarea
                  value={recipients}
                  onChange={(e) => setRecipients(e.target.value)}
                  rows={4}
                />
              </label>
            </>
          ) : (
            <>
              <label>
                템플릿
                <select value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
                  <option value="">-- 템플릿 선택 --</option>
                  {templates.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                리스트
                <select value={listId} onChange={(e) => setListId(e.target.value)}>
                  <option value="">-- 리스트 선택 --</option>
                  {lists.map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.name} ({l.memberCount}명)
                    </option>
                  ))}
                </select>
              </label>
            </>
          )}
          <button type="submit" disabled={submitting}>
            {submitting ? "발송 큐 등록 중…" : "캠페인 생성 & 발송"}
          </button>
        </form>
        {error && <p className="error">{error}</p>}
      </section>

      <section className="card">
        <h2>캠페인</h2>
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>제목</th>
              <th>상태</th>
              <th>발송</th>
              <th>바운스+억제</th>
              <th>오픈</th>
              <th>클릭</th>
            </tr>
          </thead>
          <tbody>
            {campaigns.length === 0 && (
              <tr>
                <td colSpan={7} className="empty">
                  아직 캠페인이 없습니다.
                </td>
              </tr>
            )}
            {campaigns.map((c) => (
              <tr key={c.id}>
                <td>{c.id}</td>
                <td>{c.subject}</td>
                <td>{c.status}</td>
                <td>
                  {c.sent}/{c.total}
                  {c.failed > 0 && <span className="error"> · {c.failed} 실패</span>}
                  {c.pending > 0 && <span className="muted"> · {c.pending} 대기</span>}
                </td>
                <td>{c.bounced + c.suppressed}</td>
                <td>{c.opened}</td>
                <td>{c.clicked}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
