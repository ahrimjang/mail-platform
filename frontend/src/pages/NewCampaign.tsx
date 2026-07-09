import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import Portal from "../components/Portal";
import { fmt } from "../outpace/format";
import { renderPreview } from "../outpace/starters";
import type { ContactListView, TemplateView } from "../types";

type Timing = "now" | "scheduled";
type ContentSource = "direct" | "template";
type AudienceSource = "direct" | "list";

/** now+1min as a datetime-local value (local time, not UTC — the input is local). */
function minScheduleLocal(): string {
  const d = new Date(Date.now() + 60_000);
  d.setSeconds(0, 0);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function NewCampaign() {
  const nav = useNavigate();
  const recipientsRef = useRef<HTMLTextAreaElement>(null);

  const [name, setName] = useState("월간 뉴스레터 7월호");
  const [senderName, setSenderName] = useState("Acme 팀");
  const [senderEmail, setSenderEmail] = useState("hello@acme.io");
  const [subject, setSubject] = useState("이번 달 새 소식과 단독 혜택을 확인하세요");
  const [body, setBody] = useState("안녕하세요, 이번 달 소식입니다. {{name}}님을 위한 단독 혜택을 준비했어요.");
  const [recipients, setRecipients] = useState("alice@example.com\nbob@example.com");
  const [timing, setTiming] = useState<Timing>("now");
  const [scheduledLocal, setScheduledLocal] = useState(""); // datetime-local value
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);

  // Content can come from a saved template (snapshotted server-side at create),
  // and the audience from a contact list (fanned out server-side).
  const [contentSource, setContentSource] = useState<ContentSource>("direct");
  const [templateId, setTemplateId] = useState<string>("");
  const [templates, setTemplates] = useState<TemplateView[]>([]);
  const [audienceSource, setAudienceSource] = useState<AudienceSource>("direct");
  const [listId, setListId] = useState<string>("");
  const [lists, setLists] = useState<ContactListView[]>([]);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api("/api/templates"), api("/api/lists")])
      .then(async ([tRes, lRes]) => {
        if (cancelled) return;
        if (tRes.ok) setTemplates(await tRes.json());
        if (lRes.ok) setLists(await lRes.json());
      })
      .catch(() => { /* pickers just stay empty */ });
    return () => { cancelled = true; };
  }, []);

  const emails = useMemo(
    () => recipients.split(/[\n,]/).map((r) => r.trim()).filter(Boolean),
    [recipients],
  );
  const selectedTemplate = templates.find((t) => String(t.id) === templateId) ?? null;
  const selectedList = lists.find((l) => String(l.id) === listId) ?? null;
  const audienceCount = audienceSource === "list" ? selectedList?.memberCount ?? 0 : emails.length;

  // What the recipient will get: direct input or the selected template's snapshot.
  const previewSubject = contentSource === "template" ? selectedTemplate?.subject ?? "" : subject;
  const previewHtml = contentSource === "template" ? selectedTemplate?.htmlBody ?? "" : body;
  const canPreview = contentSource === "direct" || !!selectedTemplate;

  async function submit() {
    if (contentSource === "template" && !templateId) {
      setError("사용할 템플릿을 선택하세요.");
      return;
    }
    if (audienceSource === "direct" && emails.length === 0) {
      setError("수신자를 한 명 이상 입력하세요.");
      return;
    }
    if (audienceSource === "list" && !listId) {
      setError("발송할 리스트를 선택하세요.");
      return;
    }
    let scheduledAt: string | null = null;
    if (timing === "scheduled") {
      if (!scheduledLocal) {
        setError("예약 발송 시각을 선택하세요.");
        return;
      }
      const when = new Date(scheduledLocal);
      if (Number.isNaN(when.getTime()) || when.getTime() <= Date.now()) {
        setError("예약 시각은 현재보다 이후여야 합니다.");
        return;
      }
      scheduledAt = when.toISOString();
    }
    setSubmitting(true);
    setError(null);
    try {
      // Campaign name is UI-only for now; sender + schedule ride the API contract.
      const res = await api("/api/campaigns", {
        method: "POST",
        body: JSON.stringify({
          subject: contentSource === "direct" ? subject : null,
          body: contentSource === "direct" ? body : null,
          templateId: contentSource === "template" ? Number(templateId) : null,
          recipients: audienceSource === "direct" ? emails : null,
          listId: audienceSource === "list" ? Number(listId) : null,
          senderName: senderName || null,
          senderEmail: senderEmail || null,
          scheduledAt,
        }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "발송 큐 등록에 실패했습니다.");
        return;
      }
      const created = await res.json().catch(() => null);
      if (created && typeof created.id !== "undefined") {
        nav(`/campaigns/${created.id}`);
      } else {
        nav("/");
      }
    } catch {
      setError("발송 큐 등록에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="op-container-narrow op-fade">
      <button className="op-back" onClick={() => nav("/campaigns")}>← 캠페인 목록</button>
      <div className="op-pagehead" style={{ marginBottom: 26, display: "block" }}>
        <h2 style={{ fontSize: 24 }}>새 캠페인</h2>
        <p>내용을 작성하고 수신자를 선택하세요.</p>
      </div>

      <label className="op-field">
        <span className="op-flabel">캠페인 이름</span>
        <input className="op-input" value={name} onChange={(e) => setName(e.target.value)} />
      </label>

      <div className="op-grid2" style={{ marginBottom: 18 }}>
        <label className="op-field" style={{ marginBottom: 0 }}>
          <span className="op-flabel">발신자 이름</span>
          <input className="op-input" value={senderName} onChange={(e) => setSenderName(e.target.value)} />
        </label>
        <label className="op-field" style={{ marginBottom: 0 }}>
          <span className="op-flabel">발신 이메일</span>
          <input className="op-input" value={senderEmail} onChange={(e) => setSenderEmail(e.target.value)} />
        </label>
      </div>

      <div className="op-field">
        <span className="op-flabel">내용</span>
        <div className="op-seg">
          <div className={`op-seg-opt${contentSource === "direct" ? " active" : ""}`} onClick={() => setContentSource("direct")}>직접 작성</div>
          <div className={`op-seg-opt${contentSource === "template" ? " active" : ""}`} onClick={() => setContentSource("template")}>템플릿 사용</div>
        </div>
      </div>

      {contentSource === "direct" ? (
        <>
          <label className="op-field">
            <span className="op-flabel">제목</span>
            <input className="op-input" value={subject} onChange={(e) => setSubject(e.target.value)} />
          </label>
          <label className="op-field">
            <span className="op-flabel">본문</span>
            <textarea className="op-input" rows={4} value={body} onChange={(e) => setBody(e.target.value)} />
          </label>
        </>
      ) : (
        <label className="op-field">
          <span className="op-flabel">템플릿</span>
          <select className="op-input" value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
            <option value="">템플릿을 선택하세요</option>
            {templates.map((t) => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
          {templates.length === 0 && <span className="op-hint">저장된 템플릿이 없습니다. 템플릿 메뉴에서 먼저 만들어 주세요.</span>}
          {selectedTemplate && <span className="op-hint">제목: {selectedTemplate.subject}</span>}
        </label>
      )}

      <div className="op-field">
        <span className="op-flabel">수신자</span>
        <div className="op-seg">
          <div className={`op-seg-opt${audienceSource === "direct" ? " active" : ""}`} onClick={() => setAudienceSource("direct")}>직접 입력</div>
          <div className={`op-seg-opt${audienceSource === "list" ? " active" : ""}`} onClick={() => setAudienceSource("list")}>리스트 선택</div>
        </div>
      </div>

      {audienceSource === "direct" ? (
        <div className="op-field">
          <div className="op-recipients-head">
            <span className="op-flabel" style={{ marginBottom: 0 }}>이메일 주소</span>
            <span className="op-pill">{fmt(emails.length)}명</span>
          </div>
          <div className="op-dropzone" onClick={() => recipientsRef.current?.focus()}>
            <div className="t">CSV 파일을 끌어다 놓거나 이메일을 붙여넣기</div>
            <div className="s">이메일, 이름 컬럼 · 줄바꿈 또는 쉼표로 구분</div>
          </div>
          <textarea
            ref={recipientsRef}
            className="op-input"
            rows={3}
            style={{ marginTop: 10 }}
            value={recipients}
            onChange={(e) => setRecipients(e.target.value)}
            placeholder="alice@example.com, bob@example.com"
          />
        </div>
      ) : (
        <label className="op-field">
          <span className="op-flabel">리스트</span>
          <select className="op-input" value={listId} onChange={(e) => setListId(e.target.value)}>
            <option value="">리스트를 선택하세요</option>
            {lists.map((l) => (
              <option key={l.id} value={l.id}>{l.name} ({fmt(l.memberCount)}명)</option>
            ))}
          </select>
          {lists.length === 0 && <span className="op-hint">리스트가 없습니다. 리스트 메뉴에서 먼저 만들어 주세요.</span>}
        </label>
      )}

      <div className="op-field">
        <span className="op-flabel">발송 시점</span>
        <div className="op-seg">
          <div className={`op-seg-opt${timing === "now" ? " active" : ""}`} onClick={() => setTiming("now")}>즉시 발송</div>
          <div className={`op-seg-opt${timing === "scheduled" ? " active" : ""}`} onClick={() => setTiming("scheduled")}>예약 발송</div>
        </div>
        {timing === "scheduled" && (
          <>
            <input
              className="op-input"
              type="datetime-local"
              style={{ marginTop: 10 }}
              value={scheduledLocal}
              min={minScheduleLocal()}
              onChange={(e) => setScheduledLocal(e.target.value)}
            />
            <span className="op-hint">지정한 시각에 발송 큐로 릴리스됩니다.</span>
          </>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      <div style={{ display: "flex", gap: 10, marginTop: 8 }}>
        <button className="op-btn op-btn-ghost" style={{ height: 48, padding: "0 20px", borderRadius: 11, fontSize: 14.5 }} onClick={() => nav("/campaigns")}>
          임시저장
        </button>
        <button
          className="op-btn op-btn-ghost"
          style={{ height: 48, padding: "0 20px", borderRadius: 11, fontSize: 14.5 }}
          disabled={!canPreview}
          title={canPreview ? undefined : "먼저 템플릿을 선택하세요"}
          onClick={() => setPreviewOpen(true)}
        >
          미리보기
        </button>
        <button className="op-btn" style={{ flex: 1, height: 48, borderRadius: 11 }} onClick={submit} disabled={submitting}>
          {submitting ? "발송 큐 등록 중…" : `${fmt(audienceCount)}명에게 발송 큐 등록`}
        </button>
      </div>

      {previewOpen && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setPreviewOpen(false); }}>
          <div className="op-modal op-modal-preview">
            <div className="op-pvhead">
              <div>
                <h3 style={{ margin: 0 }}>발송 미리보기</h3>
                <p className="op-modal-sub" style={{ margin: "6px 0 0" }}>
                  미리보기 전용입니다 — 수정은 {contentSource === "template" ? "템플릿 편집 화면" : "위 입력란"}에서만 가능해요.
                  {"{{변수}}"}는 발송 시 수신자별로 채워집니다.
                </p>
              </div>
              <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setPreviewOpen(false)}>닫기</button>
            </div>
            <div className="op-pvsubject">
              <span className="k">제목</span>
              <span className="v">{previewSubject || "(제목 없음)"}</span>
            </div>
            {/* sandbox with no permissions: content renders but can't be interacted with or edited */}
            <iframe
              className="op-pvframe"
              title="캠페인 발송 미리보기"
              sandbox=""
              srcDoc={renderPreview(previewHtml)}
            />
          </div>
        </div>
        </Portal>
      )}
    </div>
  );
}
