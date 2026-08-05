import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../outpace/auth";
import VariableMenu from "../components/VariableMenu";
import Portal from "../components/Portal";
import { fmt } from "../outpace/format";
import { renderPreview } from "../outpace/starters";
import type { CampaignContentView, CampaignDraftView, CampaignView, ContactListView, EmailDraftView } from "../types";

type Timing = "now" | "scheduled";
type ContentSource = "direct" | "email";
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
  const { email: myEmail } = useAuth();
  // ?emailId= — 이메일 목록/에디터의 "다음 · 발송 설정"이 방금 저장한 이메일을
  // 넘겨서 도착 즉시 선택돼 있다. ?templateId= 는 템플릿 에디터의 레거시 핸드오프 —
  // 도착하면 그 템플릿을 복사한 이메일을 만들어 같은 흐름에 태운다.
  const [searchParams] = useSearchParams();
  const initialEmailId = searchParams.get("emailId") ?? "";
  const legacyTemplateId = searchParams.get("templateId") ?? "";
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
  const [replyTo, setReplyTo] = useState("");
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
  // 플랜 기능 게이팅 — 로드 전엔 잠그지 않는다(최종 방어는 백엔드 409)
  const [plan, setPlan] = useState<string | null>(null);
  const planRank = plan ? ({ STARTER: 0, STANDARD: 1, PRO: 2, ENTERPRISE: 3 }[plan] ?? 3) : 3;
  const abAllowed = planRank >= 1;       // A/B: 스탠다드부터 (제목)
  const winnerAllowed = planRank >= 2;   // 본문 B·승자 자동발송: 프로부터
  const segAllowed = planRank >= 1;      // 참여도 세그먼트: 스탠다드부터
  const [abSubjectB, setAbSubjectB] = useState("");
  const [abBodyB, setAbBodyB] = useState("");
  const [abTestPercent, setAbTestPercent] = useState(20);
  const [abMetric, setAbMetric] = useState<"OPEN" | "CLICK">("OPEN");
  const [abEvalWait, setAbEvalWait] = useState(60);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Confirm-before-send: validated times park here while the summary is shown.
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [pendingTimes, setPendingTimes] = useState<{ scheduledAt: string | null; endsAt: string | null } | null>(null);
  // 내게 먼저 보내기
  const [testOpen, setTestOpen] = useState(false);
  const [testRecipient, setTestRecipient] = useState("");
  const [testVariant, setTestVariant] = useState<"A" | "B">("A");
  const [testSending, setTestSending] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  // 미리보기 팝업: 어느 이메일 + 어떤 변형(A/B) 라벨인지.
  const [tplPreview, setTplPreview] = useState<{ tpl: EmailDraftView; label: string } | null>(null);

  // Content can come from a saved email (snapshotted server-side at create),
  // and the audience from a contact list (fanned out server-side). Variant B
  // mirrors the same direct/email choice.
  const [contentSource, setContentSource] = useState<ContentSource>(initialEmailId ? "email" : "direct");
  const [emailId, setEmailId] = useState<string>(initialEmailId);
  const [abContentSource, setAbContentSource] = useState<ContentSource>("direct");
  const [abEmailId, setAbEmailId] = useState<string>("");
  const [emailDrafts, setEmailDrafts] = useState<EmailDraftView[]>([]);
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

  // 플랜 조회 — A/B·세그먼트 섹션의 잠금 판정용
  useEffect(() => {
    api("/api/workspace")
      .then(async (res) => { if (res.ok) setPlan((await res.json()).plan); })
      .catch(() => { /* 실패 시 잠그지 않음 — 백엔드가 최종 방어 */ });
  }, []);

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
        setReplyTo(d.replyTo ?? "");
        setSubject(d.subject ?? "");
        setBody(d.body ?? "");
        // 초안은 제목·본문 스냅샷을 함께 저장하므로 항상 직접 입력으로 복원한다
        // (템플릿/이메일 참조는 등록 시점 스냅샷용 — 초안 편집에는 원본이 필요 없다)
        setContentSource("direct");
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
    Promise.all([api("/api/emails"), api("/api/lists")])
      .then(async ([eRes, lRes]) => {
        if (cancelled) return;
        if (eRes.ok) setEmailDrafts(await eRes.json());
        if (lRes.ok) setLists(await lRes.json());
      })
      .catch(() => { /* pickers just stay empty */ });
    return () => { cancelled = true; };
  }, []);

  // 템플릿 에디터의 레거시 핸드오프(?templateId=) — 템플릿을 복사한 이메일을
  // 만들어 선택 상태로 전환한다 (캠페인은 이메일만 소비하는 개념).
  useEffect(() => {
    if (!legacyTemplateId || initialEmailId) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await api("/api/emails", {
          method: "POST",
          body: JSON.stringify({ templateId: Number(legacyTemplateId) }),
        });
        if (!res.ok || cancelled) return;
        const created: EmailDraftView = await res.json();
        setEmailDrafts((prev) => [created, ...prev]);
        setEmailId(String(created.id));
        setContentSource("email");
      } catch { /* 선택 없이 두면 사용자가 직접 고른다 */ }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const emails = useMemo(
    () => recipients.split(/[\n,]/).map((r) => r.trim()).filter(Boolean),
    [recipients],
  );
  const selectedEmail = emailDrafts.find((t) => String(t.id) === emailId) ?? null;
  const selectedAbEmail = emailDrafts.find((t) => String(t.id) === abEmailId) ?? null;
  const selectedList = lists.find((l) => String(l.id) === listId) ?? null;
  const audienceCount = audienceSource === "list" ? selectedList?.memberCount ?? 0 : emails.length;
  const abWaitLabel = AB_WAIT_OPTIONS.find((o) => o.minutes === abEvalWait)?.label ?? `${abEvalWait}분`;

  // What the recipient will get: direct input or the selected email's snapshot.
  const previewSubject = contentSource === "email" ? selectedEmail?.subject ?? "" : subject;
  const previewHtml = contentSource === "email" ? selectedEmail?.htmlBody ?? "" : body;
  const canPreview = contentSource === "direct" || !!selectedEmail;

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
      setEmailId("");
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
      // 이메일 선택 시에도 제목·본문 스냅샷을 함께 싣는다 — 등록은 서버가 emailId 를
      // 우선하므로 무해하고, 임시저장(초안)은 스냅샷 덕에 내용이 보존된다.
      subject: contentSource === "direct" ? subject : selectedEmail?.subject ?? null,
      body: contentSource === "direct" ? body : selectedEmail?.htmlBody ?? null,
      emailId: contentSource === "email" && emailId ? Number(emailId) : null,
      recipients: audienceSource === "direct" ? emails : null,
      listId: audienceSource === "list" && listId ? Number(listId) : null,
      senderName: senderName || null,
      senderEmail: senderEmail || null,
      replyTo: replyTo.trim() || null,
      scheduledAt,
      abSubjectB: abEnabled && (winnerAllowed ? abContentSource === "direct" : true) && abSubjectB.trim() !== "" ? abSubjectB : null,
      // 본문 B·이메일 B·승자 플로우는 프로부터 — 스탠다드는 제목 A/B(반반 분배)로 제출
      abBodyB: abEnabled && winnerAllowed && abContentSource === "direct" && abBodyB.trim() !== "" ? abBodyB : null,
      abEmailId: abEnabled && winnerAllowed && abContentSource === "email" && abEmailId ? Number(abEmailId) : null,
      abTestPercent: abEnabled && winnerAllowed ? abTestPercent : null,
      abEvalMetric: abEnabled && winnerAllowed ? abMetric : null,
      abEvalWaitMinutes: abEnabled && winnerAllowed ? abEvalWait : null,
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
    if (contentSource === "email" && !emailId) {
      setError(abEnabled ? "A안에서 사용할 이메일을 선택하세요." : "사용할 이메일을 선택하세요.");
      return;
    }
    if (abEnabled) {
      if (abContentSource === "email" && !abEmailId) {
        setError("B안에서 사용할 이메일을 선택하세요.");
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
    // Everything validated — show the summary instead of firing immediately.
    // A bulk mis-send is this domain's worst accident; one look costs a click.
    setError(null);
    setPendingTimes({ scheduledAt, endsAt });
    setConfirmOpen(true);
  }

  async function confirmSend() {
    if (!pendingTimes) return;
    const { scheduledAt, endsAt } = pendingTimes;
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
      setConfirmOpen(false);
    }
  }

  /** Content the test mail should carry, honoring the A/B variant choice. */
  function testPayload() {
    if (testVariant === "B" && abEnabled) {
      if (abContentSource === "email" && selectedAbEmail) {
        // 테스트 발송 API 는 제목·본문을 직접 받는다 — 이메일의 현재 내용을 실어 보낸다
        return { subject: selectedAbEmail.subject, body: selectedAbEmail.htmlBody };
      }
      // A subject-only (or body-only) B test falls back to A's content.
      return {
        subject: abSubjectB.trim() || previewSubject,
        body: abBodyB.trim() || previewHtml,
      };
    }
    return { subject: previewSubject, body: previewHtml };
  }

  async function sendTest() {
    setTestSending(true);
    setTestResult(null);
    try {
      const res = await api("/api/campaigns/test-send", {
        method: "POST",
        body: JSON.stringify({
          recipient: testRecipient.trim(),
          senderName: senderName || null,
          senderEmail: senderEmail || null,
          ...testPayload(),
        }),
      });
      if (res.ok) {
        setTestResult(`${testRecipient.trim()} 앞으로 보냈어요 — 받은편지함(개발 환경은 MailHog)을 확인하세요.`);
      } else {
        const data = await res.json().catch(() => ({}));
        setTestResult(`실패: ${data.error ?? "테스트 발송에 실패했습니다."}`);
      }
    } catch {
      setTestResult("실패: 테스트 발송에 실패했습니다.");
    } finally {
      setTestSending(false);
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
    emlId: string,
    setEmlId: (v: string) => void,
    selectedEml: EmailDraftView | null,
    variantB: boolean,
  ) {
    return (
      <>
        <div style={{ marginBottom: 12 }}>
          <label className="op-check">
            <input
              type="checkbox"
              checked={source === "email"}
              onChange={(e) => setSource(e.target.checked ? "email" : "direct")}
            />
            만들어 둔 이메일 사용
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
            <span className="op-flabel">이메일</span>
            <select className="op-input" value={emlId} onChange={(e) => setEmlId(e.target.value)}>
              <option value="">이메일을 선택하세요</option>
              {emailDrafts.map((t) => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
            {emailDrafts.length === 0 && <span className="op-hint">만들어 둔 이메일이 없습니다. 이메일 메뉴에서 템플릿을 불러와 먼저 만들어 주세요.</span>}
            {selectedEml && (
              <>
                <span className="op-hint">제목: {selectedEml.subject}</span>
                <button
                  type="button"
                  className="op-btn op-btn-sm op-btn-ghost"
                  style={{ marginTop: 8 }}
                  onClick={() => setTplPreview({
                    tpl: selectedEml,
                    label: abEnabled ? (variantB ? "B안" : "A안") : "이메일",
                  })}
                >
                  이메일 미리보기
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
            <span className="op-hint">운영 환경에서는 서비스 발송 도메인의 주소만 쓸 수 있어요.</span>
          </label>
        </div>
        <div className="op-grid2" style={{ marginTop: 14 }}>
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">회신 주소 (Reply-To · 선택)</span>
            <input className="op-input" placeholder="답장을 받을 주소 — 비우면 발신 주소로"
                   value={replyTo} onChange={(e) => setReplyTo(e.target.value)} />
          </label>
          <div className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">&nbsp;</span>
            <p style={{ margin: "10px 0 0", fontSize: 12.5, color: "var(--op-faint)", lineHeight: 1.6 }}>
              수신자가 답장을 누르면 이 주소로 갑니다. 발신은 서비스 도메인으로 나가도
              회신은 여러분의 메일함으로 받을 수 있어요.
            </p>
          </div>
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
                <label className="op-check" style={!segAllowed ? { opacity: 0.55 } : undefined}>
                  <input
                    type="checkbox"
                    checked={segEnabled}
                    disabled={!segAllowed}
                    onChange={(e) => setSegEnabled(e.target.checked)}
                  />
                  참여도 높은 구독자에게만 발송
                  {!segAllowed && (
                    <span className="op-pill" style={{ marginLeft: 8, fontSize: 11.5 }}>
                      🔒 스탠다드부터 · <a href="/pricing" style={{ color: "inherit" }}>요금제 보기</a>
                    </span>
                  )}
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
          <label className="op-check" style={!abAllowed ? { opacity: 0.55 } : undefined}>
            <input
              type="checkbox"
              checked={abEnabled}
              disabled={!abAllowed}
              onChange={(e) => setAbEnabled(e.target.checked)}
            />
            A/B 테스트 사용
            {!abAllowed && (
              <span className="op-pill" style={{ marginLeft: 8, fontSize: 11.5 }}>
                🔒 스탠다드부터 · <a href="/pricing" style={{ color: "inherit" }}>요금제 보기</a>
              </span>
            )}
          </label>
          {abEnabled && !winnerAllowed && (
            <span className="op-hint" style={{ marginTop: 8 }}>
              스탠다드는 <b>제목 A/B</b>예요 — 대상을 반씩 나눠 두 제목으로 보내고 성과를 비교합니다.
              본문 A/B와 승자 자동발송은 <a href="/pricing">프로 플랜</a>부터.
            </span>
          )}
          {abEnabled && winnerAllowed && (
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
                <span className="op-pill" style={{ marginBottom: 10, display: "inline-block" }}>
                  {winnerAllowed ? `A안 · 테스트 ${abTestPercent / 2}%` : "A안 · 50%"}
                </span>
                {contentControls(contentSource, setContentSource, subject, setSubject, body, setBody,
                  bodyARef, emailId, setEmailId, selectedEmail, false)}
              </div>
              <div style={{ border: "1px solid var(--op-border)", borderRadius: 12, padding: 14 }}>
                <span className="op-pill" style={{ marginBottom: 10, display: "inline-block" }}>
                  {winnerAllowed ? `B안 · 테스트 ${abTestPercent / 2}%` : "B안 · 50%"}
                </span>
                {winnerAllowed ? (
                  contentControls(abContentSource, setAbContentSource, abSubjectB, setAbSubjectB, abBodyB, setAbBodyB,
                    bodyBRef, abEmailId, setAbEmailId, selectedAbEmail, true)
                ) : (
                  <>
                    {/* 스탠다드: 제목 A/B — B안은 제목만 다르고 본문은 A안을 공유 */}
                    <span className="op-flabel">B안 제목</span>
                    <input className="op-input" placeholder="다르게 시험할 제목"
                           value={abSubjectB} onChange={(e) => setAbSubjectB(e.target.value)} />
                    <span className="op-hint" style={{ marginTop: 8 }}>
                      본문은 A안과 같은 내용으로 발송돼요. 본문까지 다르게 보내려면 프로 플랜으로.
                    </span>
                  </>
                )}
              </div>
            </div>
          </div>
        ) : (
          <div className="op-field" style={{ marginBottom: 0 }}>
            {contentControls(contentSource, setContentSource, subject, setSubject, body, setBody,
              bodyARef, emailId, setEmailId, selectedEmail, false)}
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
          onClick={() => { setTestRecipient(myEmail ?? ""); setTestVariant("A"); setTestResult(null); setTestOpen(true); }}
        >
          테스트 발송
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

      {confirmOpen && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setConfirmOpen(false); }}>
          <div className="op-modal" style={{ maxWidth: 460 }}>
            <h3>발송 전 마지막 확인</h3>
            <p className="op-modal-sub">아래 내용으로 발송 큐에 등록합니다. 등록 후에는 예약 취소 외에 되돌릴 수 없어요.</p>
            <div className="op-confirm-rows">
              <div><span className="k">캠페인</span><span className="v">{name || subject || "(제목 없음)"}</span></div>
              <div>
                <span className="k">대상</span>
                <span className="v">
                  {audienceSource === "direct"
                    ? `직접 입력 ${fmt(emails.length)}명`
                    : `리스트 '${selectedList?.name ?? "-"}' (${fmt(selectedList?.memberCount ?? 0)}명)`}
                  {audienceSource === "list" && segEnabled && (
                    <> · 참여도 조건{segOpenPct > 0 ? ` 오픈율 ${segOpenPct}%+` : ""}{segClickPct > 0 ? ` 클릭율 ${segClickPct}%+` : ""}
                      {segPreview !== null ? ` → 예상 ${fmt(segPreview)}명` : ""}</>
                  )}
                </span>
              </div>
              <div>
                <span className="k">발송 시점</span>
                <span className="v">{timing === "scheduled" && scheduledLocal ? new Date(scheduledLocal).toLocaleString("ko-KR") : "지금 즉시"}</span>
              </div>
              {periodEnabled && endsLocal && (
                <div><span className="k">수집 종료</span><span className="v">{new Date(endsLocal).toLocaleString("ko-KR")}</span></div>
              )}
              {abEnabled && (
                <div><span className="k">A/B</span><span className="v">테스트 {abTestPercent}% · {abMetric === "OPEN" ? "오픈율" : "클릭율"} 기준 · {abEvalWait}분 후 승자 발송</span></div>
              )}
            </div>
            {error && <div className="op-modal-error">{error}</div>}
            <div className="op-modal-foot">
              <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setConfirmOpen(false)}>다시 확인</button>
              <button className="op-btn op-btn-sm" disabled={submitting} onClick={confirmSend}>
                {submitting ? "등록 중…" : "발송 등록"}
              </button>
            </div>
          </div>
        </div>
        </Portal>
      )}

      {testOpen && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setTestOpen(false); }}>
          <div className="op-modal" style={{ maxWidth: 440 }}>
            <h3>테스트 발송</h3>
            <p className="op-modal-sub">
              지금 작성 중인 내용을 한 명에게 먼저 보내 봅니다. 제목에 [테스트]가 붙고,
              캠페인·지표에는 아무 기록도 남지 않아요.
            </p>
            <label className="op-field">
              <span className="op-flabel">받는 사람</span>
              <input className="op-input" type="email" value={testRecipient} onChange={(e) => setTestRecipient(e.target.value)} placeholder="me@company.com" />
            </label>
            {abEnabled && (
              <label className="op-field">
                <span className="op-flabel">보낼 안</span>
                <select className="op-input" value={testVariant} onChange={(e) => setTestVariant(e.target.value as "A" | "B")}>
                  <option value="A">A안</option>
                  <option value="B">B안</option>
                </select>
              </label>
            )}
            {testResult && (
              <p style={{ fontSize: 13, color: testResult.startsWith("실패") ? "var(--op-red)" : "var(--op-green-700)", margin: "0 0 8px" }}>
                {testResult}
              </p>
            )}
            <div className="op-modal-foot">
              <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setTestOpen(false)}>닫기</button>
              <button className="op-btn op-btn-sm" disabled={testSending || !testRecipient.includes("@")} onClick={sendTest}>
                {testSending ? "발송 중…" : "테스트 발송"}
              </button>
            </div>
          </div>
        </div>
        </Portal>
      )}

      {previewOpen && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setPreviewOpen(false); }}>
          <div className="op-modal op-modal-preview">
            <div className="op-pvhead">
              <div>
                <h3 style={{ margin: 0 }}>발송 미리보기{abEnabled ? " — A안" : ""}</h3>
                <p className="op-modal-sub" style={{ margin: "6px 0 0" }}>
                  미리보기 전용입니다 — 수정은 {contentSource === "email" ? "이메일 편집 화면" : "위 입력란"}에서만 가능해요.
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
