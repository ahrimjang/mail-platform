import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import VariableMenu from "../components/VariableMenu";
import Portal from "../components/Portal";
import { fmt } from "../outpace/format";
import { renderPreview } from "../outpace/starters";
import type { CampaignContentView, CampaignDraftView, CampaignView, ContactListView, TemplateView } from "../types";

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
  // ?draftId= — resume a draft saved from this form; 임시저장 then updates it.
  const draftId = searchParams.get("draftId");
  const [savingDraft, setSavingDraft] = useState(false);
  // 불러오기: start from a past campaign (settings + content snapshot) or a draft.
  const [importOpen, setImportOpen] = useState(false);
  const [importList, setImportList] = useState<CampaignView[] | null>(null);
  const [importQuery, setImportQuery] = useState("");
  const [importing, setImporting] = useState(false);
  // Picker selection: clicking a row previews it; the button applies it.
  const [importSel, setImportSel] = useState<CampaignView | null>(null);
  const [importVariant, setImportVariant] = useState<"A" | "B">("A");
  const [importContents, setImportContents] = useState<Record<number, CampaignContentView | null>>({});
  const recipientsRef = useRef<HTMLTextAreaElement>(null);
  const bodyARef = useRef<HTMLTextAreaElement>(null);
  const bodyBRef = useRef<HTMLTextAreaElement>(null);

  const [name, setName] = useState("월간 뉴스레터 7월호");
  const [description, setDescription] = useState("");
  const [senderName, setSenderName] = useState("Acme 팀");
  const [senderEmail, setSenderEmail] = useState("hello@acme.io");
  const [subject, setSubject] = useState("이번 달 새 소식과 단독 혜택을 확인하세요");
  const [body, setBody] = useState("안녕하세요, 이번 달 소식입니다. {{name}}님을 위한 단독 혜택을 준비했어요.");
  const [recipients, setRecipients] = useState("alice@example.com\nbob@example.com");
  const [timing, setTiming] = useState<Timing>("now");
  const [scheduledLocal, setScheduledLocal] = useState(""); // datetime-local value
  // Campaign period: opens/clicks observed after this end are not recorded.
  const [periodEnabled, setPeriodEnabled] = useState(false);
  const [endsLocal, setEndsLocal] = useState(""); // datetime-local value
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
  // Engagement segment: narrow the list to members above these rate floors.
  // Evaluated at fan-out (send) time; the preview below shows today's match.
  const [segEnabled, setSegEnabled] = useState(false);
  const [segOpenPct, setSegOpenPct] = useState(25);
  const [segClickPct, setSegClickPct] = useState(0);
  const [segPreview, setSegPreview] = useState<number | null>(null);

  useEffect(() => {
    if (!segEnabled || !listId) { setSegPreview(null); return; }
    let cancelled = false;
    // Small debounce so slider drags don't fire a request per tick.
    const timer = window.setTimeout(() => {
      api(`/api/contacts/engagement?listId=${listId}&minOpenPercent=${segOpenPct}&minClickPercent=${segClickPct}`)
        .then(async (res) => { if (res.ok && !cancelled) setSegPreview((await res.json()).length); })
        .catch(() => { /* preview is best-effort */ });
    }, 300);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [segEnabled, listId, segOpenPct, segClickPct]);

  // Resuming a draft: pour its saved fields back into the form once.
  useEffect(() => {
    if (!draftId) return;
    let cancelled = false;
    const toLocal = (iso: string) => {
      const d = new Date(iso);
      const pad = (n: number) => String(n).padStart(2, "0");
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    };
    api(`/api/campaigns/drafts/${draftId}`)
      .then(async (res) => {
        if (!res.ok || cancelled) return;
        const d: CampaignDraftView = await res.json();
        setName(d.name ?? "");
        setDescription(d.description ?? "");
        setSenderName(d.senderName ?? "");
        setSenderEmail(d.senderEmail ?? "");
        setSubject(d.subject ?? "");
        setBody(d.body ?? "");
        setContentSource(d.templateId != null ? "template" : "direct");
        setTemplateId(d.templateId != null ? String(d.templateId) : "");
        setAudienceSource(d.listId != null ? "list" : "direct");
        setListId(d.listId != null ? String(d.listId) : "");
        setRecipients(d.recipients.join("\n"));
        if (d.scheduledAt && new Date(d.scheduledAt).getTime() > Date.now()) {
          setTiming("scheduled");
          setScheduledLocal(toLocal(d.scheduledAt));
        }
        if (d.endsAt) {
          setPeriodEnabled(true);
          setEndsLocal(toLocal(d.endsAt));
        }
        if (d.abSubjectB || d.abBodyB) {
          setAbEnabled(true);
          setAbSubjectB(d.abSubjectB ?? "");
          setAbBodyB(d.abBodyB ?? "");
          if (d.abTestPercent != null) setAbTestPercent(d.abTestPercent);
          if (d.abEvalMetric === "OPEN" || d.abEvalMetric === "CLICK") setAbMetric(d.abEvalMetric);
          if (d.abEvalWaitMinutes != null) setAbEvalWait(d.abEvalWaitMinutes);
        }
        if (d.segMinOpenPercent != null || d.segMinClickPercent != null) {
          setSegEnabled(true);
          setSegOpenPct(d.segMinOpenPercent ?? 0);
          setSegClickPct(d.segMinClickPercent ?? 0);
        }
      })
      .catch(() => { /* a missing/launched draft just leaves the blank form */ });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftId]);

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

  // Lazy-load the campaign list the first time the import modal opens.
  useEffect(() => {
    if (!importOpen || importList !== null) return;
    let cancelled = false;
    api("/api/campaigns")
      .then(async (res) => { if (res.ok && !cancelled) setImportList(await res.json()); })
      .catch(() => { if (!cancelled) setImportList([]); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [importOpen]);

  // Preview pane: fetch the selected campaign's content snapshot once, cached.
  useEffect(() => {
    if (!importSel || importSel.id in importContents) return;
    let cancelled = false;
    api(`/api/campaigns/${importSel.id}/content`)
      .then(async (res) => {
        const content = res.ok ? await res.json() : null;
        if (cancelled) return;
        setImportContents((prev) => ({ ...prev, [importSel.id]: content }));
      })
      .catch(() => { if (!cancelled) setImportContents((prev) => ({ ...prev, [importSel.id]: null })); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [importSel]);

  /**
   * Fill the form from a past campaign: settings from the view, subject/body
   * from the (already previewed) content snapshot. Times (예약/기간) are
   * deliberately not copied — they belong to the original run. Drafts reroute
   * to their resume flow.
   */
  function importCampaign(c: CampaignView) {
    if (c.status === "DRAFT") {
      setImportOpen(false);
      nav(`/campaigns/new?draftId=${c.id}`);
      return;
    }
    setImporting(true);
    try {
      const content = importContents[c.id] ?? null;
      setName(c.name ? `${c.name} (복사)` : "");
      setDescription(c.description ?? "");
      setSenderName(c.senderName ?? "");
      setSenderEmail(c.senderEmail ?? "");
      // The snapshot is the source of truth for what was actually sent, so the
      // copy edits it directly instead of re-linking the template.
      setContentSource("direct");
      setTemplateId("");
      setSubject(content?.subject ?? c.subject ?? "");
      setBody(content?.htmlBody ?? "");
      setAudienceSource(c.listId != null ? "list" : "direct");
      setListId(c.listId != null ? String(c.listId) : "");
      if (c.segMinOpenPercent != null || c.segMinClickPercent != null) {
        setSegEnabled(true);
        setSegOpenPct(c.segMinOpenPercent ?? 0);
        setSegClickPct(c.segMinClickPercent ?? 0);
      } else {
        setSegEnabled(false);
      }
      if (content?.abSubjectB || content?.abBodyB) {
        setAbEnabled(true);
        setAbContentSource("direct");
        setAbSubjectB(content.abSubjectB ?? "");
        setAbBodyB(content.abBodyB ?? "");
        if (c.abTestPercent != null) setAbTestPercent(c.abTestPercent);
        if (c.abEvalMetric === "OPEN" || c.abEvalMetric === "CLICK") setAbMetric(c.abEvalMetric);
      } else {
        setAbEnabled(false);
      }
      setTiming("now");
      setScheduledLocal("");
      setPeriodEnabled(false);
      setEndsLocal("");
      setImportOpen(false);
      window.scrollTo({ top: 0 });
    } finally {
      setImporting(false);
    }
  }

  /** Insert a personalization token at a textarea's caret (falls back to append). */
  function insertVariableInto(ref: React.RefObject<HTMLTextAreaElement>, value: string,
                              set: (v: string) => void, token: string) {
    const area = ref.current;
    if (!area) { set(value + token); return; }
    const start = area.selectionStart ?? value.length;
    const end = area.selectionEnd ?? value.length;
    set(value.slice(0, start) + token + value.slice(end));
    requestAnimationFrame(() => {
      area.focus();
      area.setSelectionRange(start + token.length, start + token.length);
    });
  }

  /** The request body both 발송 등록 and 임시저장 send — drafts skip validation. */
  function payloadOf(scheduledAt: string | null, endsAt: string | null) {
    return {
      name: name || null,
      description: description || null,
      subject: contentSource === "direct" ? subject : null,
      body: contentSource === "direct" ? body : null,
      templateId: contentSource === "template" && templateId ? Number(templateId) : null,
      recipients: audienceSource === "direct" ? emails : null,
      listId: audienceSource === "list" && listId ? Number(listId) : null,
      senderName: senderName || null,
      senderEmail: senderEmail || null,
      scheduledAt,
      abSubjectB: abEnabled && abContentSource === "direct" && abSubjectB.trim() !== "" ? abSubjectB : null,
      abBodyB: abEnabled && abContentSource === "direct" && abBodyB.trim() !== "" ? abBodyB : null,
      abTemplateId: abEnabled && abContentSource === "template" && abTemplateId ? Number(abTemplateId) : null,
      abTestPercent: abEnabled ? abTestPercent : null,
      abEvalMetric: abEnabled ? abMetric : null,
      abEvalWaitMinutes: abEnabled ? abEvalWait : null,
      segMinOpenPercent: audienceSource === "list" && segEnabled && segOpenPct > 0 ? segOpenPct : null,
      segMinClickPercent: audienceSource === "list" && segEnabled && segClickPct > 0 ? segClickPct : null,
      endsAt,
    };
  }

  /** 임시저장: keep the form's state as a DRAFT — no validation, nothing queued. */
  async function saveDraft() {
    setSavingDraft(true);
    setError(null);
    try {
      const scheduledAt = timing === "scheduled" && scheduledLocal ? new Date(scheduledLocal).toISOString() : null;
      const endsAt = periodEnabled && endsLocal ? new Date(endsLocal).toISOString() : null;
      const res = await api(draftId ? `/api/campaigns/drafts/${draftId}` : "/api/campaigns/drafts", {
        method: draftId ? "PUT" : "POST",
        body: JSON.stringify(payloadOf(scheduledAt, endsAt)),
      });
      if (res.ok) {
        nav("/campaigns?tab=drafts");
      } else {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "임시저장에 실패했습니다. 이름·제목·템플릿 중 하나는 있어야 해요.");
      }
    } catch {
      setError("임시저장에 실패했습니다.");
    } finally {
      setSavingDraft(false);
    }
  }

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
    let endsAt: string | null = null;
    if (periodEnabled) {
      if (!endsLocal) {
        setError("캠페인 종료 시각을 선택하세요.");
        return;
      }
      const ends = new Date(endsLocal);
      const sendStart = scheduledAt ? new Date(scheduledAt).getTime() : Date.now();
      if (Number.isNaN(ends.getTime()) || ends.getTime() <= sendStart) {
        setError("캠페인 종료 시각은 발송 시각보다 이후여야 합니다.");
        return;
      }
      endsAt = ends.toISOString();
    }
    setSubmitting(true);
    setError(null);
    try {
      const res = await api("/api/campaigns", {
        method: "POST",
        body: JSON.stringify(payloadOf(scheduledAt, endsAt)),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "발송 큐 등록에 실패했습니다.");
        return;
      }
      // The draft is consumed by the launch — drop it (best-effort).
      if (draftId) {
        api(`/api/campaigns/drafts/${draftId}`, { method: "DELETE" }).catch(() => {});
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
    bodyRef: React.RefObject<HTMLTextAreaElement>,
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
            <div className="op-field" style={{ marginBottom: 0 }}>
              <span className="op-flabel" style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                본문
                <VariableMenu
                  buttonClass="op-varbtn-form"
                  onInsert={(token) => insertVariableInto(bodyRef, bod, setBod, token)}
                />
              </span>
              <textarea
                ref={bodyRef}
                className="op-input"
                rows={4}
                value={bod}
                onChange={(e) => setBod(e.target.value)}
                placeholder={variantB ? "B안 본문 (선택) — 비우면 본문은 공통" : undefined}
              />
            </div>
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
      <div className="op-pagehead" style={{ marginBottom: 26 }}>
        <div>
          <h2 style={{ fontSize: 24 }}>새 캠페인</h2>
          <p>내용을 작성하고 수신자를 선택하세요.</p>
        </div>
        <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setImportOpen(true)}>
          불러오기
        </button>
      </div>

      {importOpen && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setImportOpen(false); }}>
          <div className="op-modal" style={{ maxWidth: 780 }}>
            <h3>캠페인 불러오기</h3>
            <p className="op-modal-sub">
              이전 캠페인의 설정과 발송 당시 내용을 복사해서 시작해요. 예약·기간은 복사되지 않아요.
              초안을 고르면 이어서 편집합니다.
            </p>
            <div className="op-import2">
              <div className="op-import2-left">
                <input
                  className="op-input"
                  placeholder="캠페인 이름·제목 검색"
                  value={importQuery}
                  onChange={(e) => setImportQuery(e.target.value)}
                  style={{ marginBottom: 8, height: 40 }}
                />
                <div className="op-import-list">
                  {importList === null ? (
                    <div className="op-import-empty">불러오는 중…</div>
                  ) : (() => {
                    const q = importQuery.trim().toLowerCase();
                    const rows = importList
                      .filter((c) => !q || (c.name ?? "").toLowerCase().includes(q) || c.subject.toLowerCase().includes(q))
                      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
                      .slice(0, 30);
                    return rows.length === 0 ? (
                      <div className="op-import-empty">불러올 캠페인이 없습니다.</div>
                    ) : rows.map((c) => {
                      const isAb = (c.variants?.length ?? 0) > 0;
                      const hasSeg = c.segMinOpenPercent != null || c.segMinClickPercent != null;
                      return (
                        <button
                          key={c.id}
                          className={`op-import-row${importSel?.id === c.id ? " sel" : ""}`}
                          onClick={() => { setImportSel(c); setImportVariant("A"); }}
                        >
                          <span className="nm op-ell">
                            {c.name ?? c.subject}
                            {c.status === "DRAFT" && <span className="op-minibadge amber" style={{ marginLeft: 6 }}>초안</span>}
                            {isAb && <span className="op-minibadge blue" style={{ marginLeft: 6 }}>A/B</span>}
                            {hasSeg && " ⚡"}
                          </span>
                          <span className="sub op-ell">
                            {c.listName ?? (c.listId != null ? `#${c.listId}` : "직접 입력")}
                            {" · "}{fmt(c.total)}명 · {new Date(c.createdAt).toLocaleDateString("ko-KR")}
                          </span>
                          {c.sent > 0 && (
                            <span className="stats">오픈 {Math.round((c.opened / c.sent) * 100)}% · 클릭 {Math.round((c.clicked / c.sent) * 100)}%</span>
                          )}
                        </button>
                      );
                    });
                  })()}
                </div>
              </div>
              <div className="op-import2-right">
                {!importSel ? (
                  <div className="op-import-empty" style={{ marginTop: 120 }}>
                    왼쪽에서 캠페인을 선택하면<br />발송된 메일을 미리 볼 수 있어요.
                  </div>
                ) : (() => {
                  const content = importContents[importSel.id];
                  const hasB = !!(content?.abSubjectB || content?.abBodyB);
                  const subj = importVariant === "B" && content?.abSubjectB ? content.abSubjectB : content?.subject ?? importSel.subject;
                  const html = importVariant === "B" && content?.abBodyB ? content.abBodyB : content?.htmlBody ?? "";
                  return (
                    <>
                      <div className="op-import2-head">
                        <span className="subj op-ell" title={subj}>{subj}</span>
                        {hasB && (
                          <span className="op-acttabs" style={{ marginBottom: 0 }}>
                            <button className={`op-acttab${importVariant === "A" ? " on" : ""}`} onClick={() => setImportVariant("A")}>A안</button>
                            <button className={`op-acttab${importVariant === "B" ? " on" : ""}`} onClick={() => setImportVariant("B")}>B안</button>
                          </span>
                        )}
                      </div>
                      {content === undefined ? (
                        <div className="op-import-empty" style={{ marginTop: 120 }}>내용을 불러오는 중…</div>
                      ) : (
                        /* srcdoc updates may not repaint — remount per campaign/variant. */
                        <iframe
                          key={`${importSel.id}-${importVariant}`}
                          className="op-import2-frame"
                          sandbox=""
                          srcDoc={html || "<p style='color:#a1a1aa;font-family:sans-serif'>본문이 비어 있어요.</p>"}
                          title="캠페인 미리보기"
                        />
                      )}
                    </>
                  );
                })()}
              </div>
            </div>
            <div className="op-modal-foot">
              <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setImportOpen(false)}>닫기</button>
              <button
                className="op-btn op-btn-sm"
                disabled={!importSel || importing}
                onClick={() => importSel && importCampaign(importSel)}
              >
                {importSel?.status === "DRAFT" ? "이어서 편집" : "이 캠페인으로 시작"}
              </button>
            </div>
          </div>
        </div>
        </Portal>
      )}

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
          <>
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
            {listId && (
              <div className="op-field" style={{ marginTop: 14, marginBottom: 0 }}>
                <label className="op-check">
                  <input
                    type="checkbox"
                    checked={segEnabled}
                    onChange={(e) => setSegEnabled(e.target.checked)}
                  />
                  참여도 높은 구독자에게만 발송
                </label>
                {segEnabled && (
                  <>
                    <span className="op-flabel" style={{ marginTop: 14 }}>오픈율 최소</span>
                    <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                      <input
                        type="range"
                        min={0}
                        max={100}
                        step={5}
                        value={segOpenPct}
                        onChange={(e) => setSegOpenPct(Number(e.target.value))}
                        style={{ flex: 1 }}
                      />
                      <span className="op-pill" style={{ minWidth: 72, textAlign: "center" }}>
                        {segOpenPct === 0 ? "제한 없음" : `${segOpenPct}% 이상`}
                      </span>
                    </div>
                    <span className="op-flabel" style={{ marginTop: 12 }}>클릭율 최소</span>
                    <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                      <input
                        type="range"
                        min={0}
                        max={100}
                        step={5}
                        value={segClickPct}
                        onChange={(e) => setSegClickPct(Number(e.target.value))}
                        style={{ flex: 1 }}
                      />
                      <span className="op-pill" style={{ minWidth: 72, textAlign: "center" }}>
                        {segClickPct === 0 ? "제한 없음" : `${segClickPct}% 이상`}
                      </span>
                    </div>
                    <span className="op-hint" style={{ marginTop: 10 }}>
                      {segPreview !== null && selectedList
                        ? `현재 기준 예상 대상자 ${fmt(segPreview)}명 / 전체 ${fmt(selectedList.memberCount)}명 — `
                        : ""}
                      조건은 발송 시점의 참여도로 평가되고, 발송 이력이 없는 구독자는 제외돼요.
                    </span>
                  </>
                )}
              </div>
            )}
          </>
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
                  bodyARef, templateId, setTemplateId, selectedTemplate, false)}
              </div>
              <div style={{ border: "1px solid var(--op-border)", borderRadius: 12, padding: 14 }}>
                <span className="op-pill" style={{ marginBottom: 10, display: "inline-block" }}>B안 · 테스트 {abTestPercent / 2}%</span>
                {contentControls(abContentSource, setAbContentSource, abSubjectB, setAbSubjectB, abBodyB, setAbBodyB,
                  bodyBRef, abTemplateId, setAbTemplateId, selectedAbTemplate, true)}
              </div>
            </div>
          </div>
        ) : (
          <div className="op-field" style={{ marginBottom: 0 }}>
            {contentControls(contentSource, setContentSource, subject, setSubject, body, setBody,
              bodyARef, templateId, setTemplateId, selectedTemplate, false)}
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
        <div className="op-field" style={{ marginTop: 16, marginBottom: 0 }}>
          <label className="op-check">
            <input
              type="checkbox"
              checked={periodEnabled}
              onChange={(e) => setPeriodEnabled(e.target.checked)}
            />
            캠페인 기간 설정
          </label>
          {periodEnabled ? (
            <>
              <input
                className="op-input"
                type="datetime-local"
                style={{ marginTop: 10 }}
                value={endsLocal}
                min={timing === "scheduled" && scheduledLocal ? scheduledLocal : minScheduleLocal()}
                onChange={(e) => setEndsLocal(e.target.value)}
              />
              <span className="op-hint">
                종료 시각 이후의 오픈·클릭은 지표에 집계되지 않아요 — 클릭율 등이 이 기간의 성과로 고정됩니다.
                (링크 이동과 메일 표시는 계속 동작해요.)
              </span>
            </>
          ) : (
            <span className="op-hint">기간을 정하지 않으면 오픈·클릭을 계속 집계합니다.</span>
          )}
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      <div style={{ display: "flex", gap: 10, marginTop: 8, justifyContent: "flex-end" }}>
        <button
          className="op-btn op-btn-ghost"
          style={{ height: 48, padding: "0 20px", borderRadius: 11, fontSize: 14.5 }}
          disabled={savingDraft}
          onClick={saveDraft}
        >
          {savingDraft ? "저장 중…" : draftId ? "임시저장 (덮어쓰기)" : "임시저장"}
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
