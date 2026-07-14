import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import Portal from "../components/Portal";
import { fmt } from "../outpace/format";
import { renderPreview } from "../outpace/starters";
import type { ContactListView, TemplateView } from "../types";

type Timing = "now" | "scheduled";
type ContentSource = "direct" | "template";
type AudienceSource = "direct" | "list";

/** Evaluation wait choices for the A/B winner flow. */
const AB_WAIT_OPTIONS = [
  { minutes: 10, label: "10분" },
  { minutes: 30, label: "30분" },
  { minutes: 60, label: "1시간" },
  { minutes: 240, label: "4시간" },
  { minutes: 1440, label: "24시간" },
];

/** now+1min as a datetime-local value (local time, not UTC — the input is local). */
function minScheduleLocal(): string {
  const d = new Date(Date.now() + 60_000);
  d.setSeconds(0, 0);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function NewCampaign() {
  const nav = useNavigate();
  // ?templateId= — the editors' "다음 · 발송 설정" hands over the just-saved
  // template so it arrives here already selected.
  const [searchParams] = useSearchParams();
  const initialTemplateId = searchParams.get("templateId") ?? "";
  const recipientsRef = useRef<HTMLTextAreaElement>(null);

  const [name, setName] = useState("월간 뉴스레터 7월호");
  const [description, setDescription] = useState("");
  const [senderName, setSenderName] = useState("Acme 팀");
  const [senderEmail, setSenderEmail] = useState("hello@acme.io");
  const [subject, setSubject] = useState("이번 달 새 소식과 단독 혜택을 확인하세요");
  const [body, setBody] = useState("안녕하세요, 이번 달 소식입니다. {{name}}님을 위한 단독 혜택을 준비했어요.");
  const [recipients, setRecipients] = useState("alice@example.com\nbob@example.com");
  const [timing, setTiming] = useState<Timing>("now");
  const [scheduledLocal, setScheduledLocal] = useState(""); // datetime-local value
  // A/B winner flow: variant B content (direct or template), the audience share
  // entering the test, the winner metric and the evaluation wait. The A:B split
  // inside the test group is fixed at 50:50 (the backend defaults it).
  const [abEnabled, setAbEnabled] = useState(false);
  const [abSubjectB, setAbSubjectB] = useState("");
  const [abBodyB, setAbBodyB] = useState("");
  const [abTestPercent, setAbTestPercent] = useState(20);
  const [abMetric, setAbMetric] = useState<"OPEN" | "CLICK">("OPEN");
  const [abEvalWait, setAbEvalWait] = useState(60);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  // Template preview popup: which template + the variant label it belongs to.
  const [tplPreview, setTplPreview] = useState<{ tpl: TemplateView; label: string } | null>(null);

  // Content can come from a saved template (snapshotted server-side at create),
  // and the audience from a contact list (fanned out server-side). Variant B
  // mirrors the same direct/template choice.
  const [contentSource, setContentSource] = useState<ContentSource>(initialTemplateId ? "template" : "direct");
  const [templateId, setTemplateId] = useState<string>(initialTemplateId);
  const [abContentSource, setAbContentSource] = useState<ContentSource>("direct");
  const [abTemplateId, setAbTemplateId] = useState<string>("");
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
  const selectedAbTemplate = templates.find((t) => String(t.id) === abTemplateId) ?? null;
  const selectedList = lists.find((l) => String(l.id) === listId) ?? null;
  const audienceCount = audienceSource === "list" ? selectedList?.memberCount ?? 0 : emails.length;
  const abWaitLabel = AB_WAIT_OPTIONS.find((o) => o.minutes === abEvalWait)?.label ?? `${abEvalWait}분`;

  // What the recipient will get: direct input or the selected template's snapshot.
  const previewSubject = contentSource === "template" ? selectedTemplate?.subject ?? "" : subject;
  const previewHtml = contentSource === "template" ? selectedTemplate?.htmlBody ?? "" : body;
  const canPreview = contentSource === "direct" || !!selectedTemplate;

  async function submit() {
    if (contentSource === "template" && !templateId) {
      setError(abEnabled ? "A안에서 사용할 템플릿을 선택하세요." : "사용할 템플릿을 선택하세요.");
      return;
    }
    if (abEnabled) {
      if (abContentSource === "template" && !abTemplateId) {
        setError("B안에서 사용할 템플릿을 선택하세요.");
        return;
      }
      if (abContentSource === "direct" && abSubjectB.trim() === "" && abBodyB.trim() === "") {
        setError("B안 제목이나 본문 중 하나 이상을 입력하세요.");
        return;
      }
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
      const res = await api("/api/campaigns", {
        method: "POST",
        body: JSON.stringify({
          name: name || null,
          description: description || null,
          subject: contentSource === "direct" ? subject : null,
          body: contentSource === "direct" ? body : null,
          templateId: contentSource === "template" ? Number(templateId) : null,
          recipients: audienceSource === "direct" ? emails : null,
          listId: audienceSource === "list" ? Number(listId) : null,
          senderName: senderName || null,
          senderEmail: senderEmail || null,
          scheduledAt,
          abSubjectB: abEnabled && abContentSource === "direct" && abSubjectB.trim() !== "" ? abSubjectB : null,
          abBodyB: abEnabled && abContentSource === "direct" && abBodyB.trim() !== "" ? abBodyB : null,
          abTemplateId: abEnabled && abContentSource === "template" ? Number(abTemplateId) : null,
          abTestPercent: abEnabled ? abTestPercent : null,
          abEvalMetric: abEnabled ? abMetric : null,
          abEvalWaitMinutes: abEnabled ? abEvalWait : null,
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

  /** One variant's content controls: direct subject/body or a template pick with inline preview. */
  function contentControls(
    source: ContentSource,
    setSource: (s: ContentSource) => void,
    subj: string,
    setSubj: (v: string) => void,
    bod: string,
    setBod: (v: string) => void,
    tplId: string,
    setTplId: (v: string) => void,
    selectedTpl: TemplateView | null,
    variantB: boolean,
  ) {
    return (
      <>
        <div style={{ marginBottom: 12 }}>
          <label className="op-check">
            <input
              type="checkbox"
              checked={source === "template"}
              onChange={(e) => setSource(e.target.checked ? "template" : "direct")}
            />
            템플릿 사용
          </label>
        </div>
        {source === "direct" ? (
          <>
            <label className="op-field">
              <span className="op-flabel">제목</span>
              <input
                className="op-input"
                value={subj}
                onChange={(e) => setSubj(e.target.value)}
                placeholder={variantB ? "B안 제목 — 비우면 A와 동일" : undefined}
              />
            </label>
            <label className="op-field" style={{ marginBottom: 0 }}>
              <span className="op-flabel">본문</span>
              <textarea
                className="op-input"
                rows={4}
                value={bod}
                onChange={(e) => setBod(e.target.value)}
                placeholder={variantB ? "B안 본문 (선택) — 비우면 본문은 공통" : undefined}
              />
            </label>
          </>
        ) : (
          // div, not label: the preview button below must not re-trigger the select.
          <div className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">템플릿</span>
            <select className="op-input" value={tplId} onChange={(e) => setTplId(e.target.value)}>
              <option value="">템플릿을 선택하세요</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
            {templates.length === 0 && <span className="op-hint">저장된 템플릿이 없습니다. 템플릿 메뉴에서 먼저 만들어 주세요.</span>}
            {selectedTpl && (
              <>
                <span className="op-hint">제목: {selectedTpl.subject}</span>
                <button
                  type="button"
                  className="op-btn op-btn-sm op-btn-ghost"
                  style={{ marginTop: 8 }}
                  onClick={() => setTplPreview({
                    tpl: selectedTpl,
                    label: abEnabled ? (variantB ? "B안" : "A안") : "템플릿",
                  })}
                >
                  템플릿 미리보기
                </button>
              </>
            )}
          </div>
        )}
      </>
    );
  }

  return (
    <div className="op-container-mid op-fade">
      <button className="op-back" onClick={() => nav("/campaigns")}>← 캠페인 목록</button>
      <div className="op-pagehead" style={{ marginBottom: 26, display: "block" }}>
        <h2 style={{ fontSize: 24 }}>새 캠페인</h2>
        <p>내용을 작성하고 수신자를 선택하세요.</p>
      </div>

      <div className="op-form-card">
        <h3 className="op-sect-title">기본 정보</h3>
        <label className="op-field">
          <span className="op-flabel">캠페인 이름</span>
          <input className="op-input" value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="op-field">
          <span className="op-flabel">캠페인 설명</span>
          <textarea
            className="op-input"
            rows={2}
            placeholder="이 캠페인의 목적/맥락 메모 (선택)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>
        <div className="op-grid2">
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">발신자 이름</span>
            <input className="op-input" value={senderName} onChange={(e) => setSenderName(e.target.value)} />
          </label>
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">발신 이메일</span>
            <input className="op-input" value={senderEmail} onChange={(e) => setSenderEmail(e.target.value)} />
          </label>
        </div>
      </div>

      <div className="op-form-card">
        <h3 className="op-sect-title">수신자</h3>
        <div style={{ marginBottom: 14 }}>
          <label className="op-check">
            <input
              type="checkbox"
              checked={audienceSource === "list"}
              onChange={(e) => setAudienceSource(e.target.checked ? "list" : "direct")}
            />
            리스트에서 선택
          </label>
        </div>
        {audienceSource === "direct" ? (
          <div className="op-field" style={{ marginBottom: 0 }}>
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
          <label className="op-field" style={{ marginBottom: 0 }}>
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
      </div>

      <div className="op-form-card">
        <h3 className="op-sect-title">내용</h3>
        <div className="op-field">
          <label className="op-check">
            <input
              type="checkbox"
              checked={abEnabled}
              onChange={(e) => setAbEnabled(e.target.checked)}
            />
            A/B 테스트 사용
          </label>
          {abEnabled && (
            <>
              <span className="op-flabel" style={{ marginTop: 14 }}>테스트 그룹 비율</span>
              <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                <input
                  type="range"
                  min={10}
                  max={50}
                  step={5}
                  style={{ flex: 1, maxWidth: 320 }}
                  value={abTestPercent}
                  onChange={(e) => setAbTestPercent(Number(e.target.value))}
                />
                <span style={{ fontWeight: 700 }}>{abTestPercent}%</span>
              </div>
              <span className="op-hint">
                전체 수신자의 {abTestPercent}%에게 A/B 테스트를 보내고, {abWaitLabel} 후
                성과가 좋은 안을 나머지 {100 - abTestPercent}%에게 자동 발송합니다.
              </span>
              <div style={{ marginTop: 14 }}>
                <label className="op-check">
                  <input
                    type="checkbox"
                    checked={abMetric === "CLICK"}
                    onChange={(e) => setAbMetric(e.target.checked ? "CLICK" : "OPEN")}
                  />
                  클릭율 기준으로 평가
                </label>
              </div>
              <span className="op-flabel" style={{ marginTop: 14 }}>평가 대기 시간</span>
              <select
                className="op-input"
                style={{ maxWidth: 200 }}
                value={abEvalWait}
                onChange={(e) => setAbEvalWait(Number(e.target.value))}
              >
                {AB_WAIT_OPTIONS.map((o) => (
                  <option key={o.minutes} value={o.minutes}>{o.label}</option>
                ))}
              </select>
            </>
          )}
        </div>

        {abEnabled ? (
          <div className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">A안 / B안</span>
            <div className="op-grid2" style={{ gap: 18, alignItems: "start" }}>
              <div style={{ border: "1px solid var(--op-border)", borderRadius: 12, padding: 14 }}>
                <span className="op-pill" style={{ marginBottom: 10, display: "inline-block" }}>A안 · 테스트 {abTestPercent / 2}%</span>
                {contentControls(contentSource, setContentSource, subject, setSubject, body, setBody,
                  templateId, setTemplateId, selectedTemplate, false)}
              </div>
              <div style={{ border: "1px solid var(--op-border)", borderRadius: 12, padding: 14 }}>
                <span className="op-pill" style={{ marginBottom: 10, display: "inline-block" }}>B안 · 테스트 {abTestPercent / 2}%</span>
                {contentControls(abContentSource, setAbContentSource, abSubjectB, setAbSubjectB, abBodyB, setAbBodyB,
                  abTemplateId, setAbTemplateId, selectedAbTemplate, true)}
              </div>
            </div>
          </div>
        ) : (
          <div className="op-field" style={{ marginBottom: 0 }}>
            {contentControls(contentSource, setContentSource, subject, setSubject, body, setBody,
              templateId, setTemplateId, selectedTemplate, false)}
          </div>
        )}
      </div>

      <div className="op-form-card">
        <h3 className="op-sect-title">발송 옵션</h3>
        <div className="op-field" style={{ marginBottom: 0 }}>
          <label className="op-check">
            <input
              type="checkbox"
              checked={timing === "scheduled"}
              onChange={(e) => setTiming(e.target.checked ? "scheduled" : "now")}
            />
            예약 발송
          </label>
          {timing === "scheduled" ? (
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
          ) : (
            <span className="op-hint">지금 바로 발송 큐에 등록됩니다.</span>
          )}
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      <div style={{ display: "flex", gap: 10, marginTop: 8, justifyContent: "flex-end" }}>
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
        <button className="op-btn" style={{ height: 48, padding: "0 22px", borderRadius: 11 }} onClick={submit} disabled={submitting}>
          {submitting ? "발송 큐 등록 중…" : `${fmt(audienceCount)}명에게 발송 큐 등록`}
        </button>
      </div>

      {previewOpen && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setPreviewOpen(false); }}>
          <div className="op-modal op-modal-preview">
            <div className="op-pvhead">
              <div>
                <h3 style={{ margin: 0 }}>발송 미리보기{abEnabled ? " — A안" : ""}</h3>
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

      {/* Per-variant template preview popup (opened from the 템플릿 미리보기 button). */}
      {tplPreview && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setTplPreview(null); }}>
          <div className="op-modal op-modal-preview">
            <div className="op-pvhead">
              <div>
                <h3 style={{ margin: 0 }}>{tplPreview.label} 미리보기 — {tplPreview.tpl.name}</h3>
                <p className="op-modal-sub" style={{ margin: "6px 0 0" }}>
                  미리보기 전용입니다 — 수정은 템플릿 편집 화면에서만 가능해요.
                  {"{{변수}}"}는 발송 시 수신자별로 채워집니다.
                </p>
              </div>
              <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setTplPreview(null)}>닫기</button>
            </div>
            <div className="op-pvsubject">
              <span className="k">제목</span>
              <span className="v">{tplPreview.tpl.subject}</span>
            </div>
            {/* keyed remount per template: updating a sandbox iframe's srcDoc in place can skip the repaint */}
            <iframe
              key={tplPreview.tpl.id}
              className="op-pvframe"
              title={`템플릿 미리보기 — ${tplPreview.tpl.name}`}
              sandbox=""
              srcDoc={renderPreview(tplPreview.tpl.htmlBody)}
            />
          </div>
        </div>
        </Portal>
      )}
    </div>
  );
}
