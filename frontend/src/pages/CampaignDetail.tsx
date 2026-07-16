import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import Portal from "../components/Portal";
import type { CampaignContentView, CampaignView, ContactListView, LinkClicksView, MessageStatus, MessageView, SendLogEntry } from "../types";
import { badgeClass, fmt, pctOf, statusLabel } from "../outpace/format";
import { MOCK_CAMPAIGNS } from "../outpace/mock";

/* Dot color + Korean label per delivery outcome. */
const LOG_META: Record<MessageStatus, { tone: "green" | "red" | "blue" | "gray"; label: string }> = {
  SENT: { tone: "green", label: "발송 완료" },
  FAILED: { tone: "red", label: "발송 실패" },
  BOUNCED: { tone: "red", label: "바운스" },
  SUPPRESSED: { tone: "gray", label: "발송 제외 (수신거부)" },
  SENDING: { tone: "blue", label: "발송 중" },
  PENDING: { tone: "blue", label: "대기열 등록" },
  CANCELED: { tone: "gray", label: "발송 취소" },
};

function timeOf(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/* Engagement rate over delivered mail; "—" until anything was sent. */
function rateOf(part: number, sent: number): string {
  return sent > 0 ? `${Math.round((part / sent) * 1000) / 10}%` : "—";
}

/* Grid columns of the A/B comparison table. */
const AB_COLS = "80px 1fr 1fr 1.4fr 1.4fr";

export default function CampaignDetail() {
  const nav = useNavigate();
  const { id } = useParams();
  const [campaign, setCampaign] = useState<CampaignView | null>(null);
  const [log, setLog] = useState<SendLogEntry[]>([]);
  const [content, setContent] = useState<CampaignContentView | null>(null);
  const [links, setLinks] = useState<LinkClicksView[] | null>(null);
  // Current state of the campaign's target list (member count, shortcut).
  const [targetList, setTargetList] = useState<ContactListView | null>(null);
  const [recipients, setRecipients] = useState<MessageView[] | null>(null);
  // Throughput is derived from consecutive polls: Δsent / Δt.
  const [rate, setRate] = useState<number | null>(null);
  const lastPoll = useRef<{ sent: number; at: number } | null>(null);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [canceling, setCanceling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  // A/B variant preview popup ("A" | "B" | null = closed).
  const [previewVariant, setPreviewVariant] = useState<"A" | "B" | null>(null);

  async function cancelSchedule() {
    setCanceling(true);
    setCancelError(null);
    try {
      const res = await api(`/api/campaigns/${id}/cancel`, { method: "POST" });
      if (res.ok) {
        setCampaign(await res.json());
        setCancelOpen(false);
      } else if (res.status === 409) {
        setCancelError("이미 발송 큐로 릴리스되어 취소할 수 없습니다.");
      } else {
        setCancelError("취소에 실패했습니다.");
      }
    } catch {
      setCancelError("요청 중 오류가 발생했습니다.");
    } finally {
      setCanceling(false);
    }
  }

  // The content snapshot never changes after create — fetch it once, not per poll.
  useEffect(() => {
    let cancelled = false;
    api(`/api/campaigns/${id}/content`)
      .then(async (res) => { if (res.ok && !cancelled) setContent(await res.json()); })
      .catch(() => { /* mock campaigns have no content endpoint */ });
    api(`/api/campaigns/${id}/links`)
      .then(async (res) => { if (res.ok && !cancelled) setLinks(await res.json()); })
      .catch(() => { /* link table is best-effort */ });
    return () => { cancelled = true; };
  }, [id]);

  const listId = campaign?.listId ?? null;
  useEffect(() => {
    if (listId == null) return;
    let cancelled = false;
    api("/api/lists")
      .then(async (res) => {
        if (!res.ok || cancelled) return;
        const all: ContactListView[] = await res.json();
        setTargetList(all.find((l) => l.id === listId) ?? null);
      })
      .catch(() => { /* the audience card falls back to the snapshot name */ });
    return () => { cancelled = true; };
  }, [listId]);

  useEffect(() => {
    let cancelled = false;
    let lastStatus: string | null = null;
    async function refresh() {
      try {
        const [cRes, mRes, rRes] = await Promise.all([
          api(`/api/campaigns/${id}`),
          // 10s buckets: "N건 발송 완료" rows instead of one row per recipient
          api(`/api/campaigns/${id}/log?bucketSeconds=10&limit=50`),
          api(`/api/campaigns/${id}/messages?limit=100`),
        ]);
        if (cancelled) return;
        if (cRes.ok) {
          const view: CampaignView = await cRes.json();
          setCampaign(view);
          lastStatus = view.status; // steers the poll cadence below
          // messages feed is best-effort; the page works without it
          if (mRes.ok) setLog(await mRes.json());
          if (rRes.ok) setRecipients(await rRes.json());

          const now = Date.now();
          const prev = lastPoll.current;
          if (prev && now > prev.at) {
            const delta = view.sent - prev.sent;
            setRate(delta > 0 ? delta / ((now - prev.at) / 1000) : view.status === "SENDING" ? null : 0);
          }
          lastPoll.current = { sent: view.sent, at: now };
          return;
        }
      } catch {
        /* fall through to mock */
      }
      // Unknown id (demo campaign from the dashboard fallback) → matching mock if any.
      if (!cancelled) {
        setCampaign((prev) => prev ?? MOCK_CAMPAIGNS.find((c) => String(c.id) === id) ?? null);
      }
    }
    // Live progress needs a tight poll only while the campaign is active; a
    // finished campaign still gets slow refreshes (opens/clicks keep arriving),
    // and a hidden tab doesn't poll at all.
    let timer: number;
    async function tick() {
      if (cancelled) return;
      if (!document.hidden) {
        await refresh();
      }
      if (cancelled) return;
      const idle = lastStatus === "COMPLETED" || lastStatus === "CANCELED";
      timer = window.setTimeout(tick, idle ? 30_000 : 2_000);
    }
    tick();
    const onVisible = () => { if (!document.hidden) refresh(); };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [id]);

  if (!campaign) {
    return (
      <div className="op-container op-fade">
        <button className="op-back" onClick={() => nav("/campaigns")}>← 캠페인 목록</button>
        <p style={{ color: "var(--op-muted)" }}>캠페인을 불러오는 중…</p>
      </div>
    );
  }

  const pct = pctOf(campaign.sent, campaign.total);
  const done = campaign.status === "COMPLETED";
  const meta: string[] = [];
  if (campaign.senderName || campaign.senderEmail) {
    meta.push(`발신: ${campaign.senderName ?? ""}${campaign.senderEmail ? ` <${campaign.senderEmail}>` : ""}`);
  }
  meta.push(`캠페인 #${campaign.id}`);

  // 캠페인 정보 card rows: timestamps and provenance, in reading order.
  const at = (iso: string) => new Date(iso).toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" });
  const drainMins = campaign.completedAt
    ? Math.round((new Date(campaign.completedAt).getTime() - new Date(campaign.enqueuedAt ?? campaign.createdAt).getTime()) / 60000)
    : null;
  const ended = campaign.endsAt != null && new Date(campaign.endsAt).getTime() <= Date.now();
  const info: { k: string; v: string; badge?: { label: string; cls: string } }[] = [];
  info.push({ k: "생성", v: at(campaign.createdAt) });
  if (campaign.scheduledAt) info.push({ k: "예약", v: at(campaign.scheduledAt) });
  info.push({ k: "발송 시작", v: campaign.enqueuedAt ? at(campaign.enqueuedAt) : "대기 중" });
  info.push({
    k: "발송 완료",
    v: campaign.completedAt
      ? `${at(campaign.completedAt)}${drainMins != null && drainMins >= 1 ? ` (${drainMins}분 소요)` : ""}`
      : "-",
  });
  info.push(
    campaign.endsAt
      ? { k: "캠페인 기간", v: `${campaign.enqueuedAt ? at(campaign.enqueuedAt) : "발송 시작"} ~ ${at(campaign.endsAt)}`,
          badge: ended ? { label: "집계 종료", cls: "gray" } : { label: "집계 중", cls: "green" } }
      : { k: "캠페인 기간", v: "제한 없음 (오픈·클릭 계속 집계)" },
  );
  if (campaign.templateId) {
    info.push({ k: "템플릿", v: campaign.templateName ?? `#${campaign.templateId} (삭제됨)` });
  }
  if (campaign.createdBy) {
    info.push({ k: "등록자", v: campaign.createdBy });
  }

  return (
    <div className="op-container op-fade">
      <button className="op-back" onClick={() => nav("/campaigns")}>← 캠페인 목록</button>

      <div className="op-detail-head">
        <div>
          <div className="op-detail-title">
            <h2>{campaign.name ?? campaign.subject}</h2>
            <span className={`op-badge ${badgeClass(campaign.status)}`}>
              {statusLabel(campaign)}
            </span>
          </div>
          <p className="op-detail-meta">{meta.join(" · ")}</p>
          {campaign.description && <p className="op-detail-meta">{campaign.description}</p>}
        </div>
        {/* cancellable only while the scheduled release is still deferred */}
        {campaign.status === "QUEUED" && campaign.scheduledAt
          && new Date(campaign.scheduledAt).getTime() > Date.now() && (
          <button className="op-btn op-btn-sm op-btn-ghost danger" onClick={() => setCancelOpen(true)}>
            예약 취소
          </button>
        )}
      </div>

      {/* 캠페인 정보 — 기간·시각·출처를 한 곳에 (기존 메타 라인에서 승격) */}
      <div className="op-card">
        <div className="op-log-title">캠페인 정보</div>
        <div className="op-infogrid">
          {info.map((row) => (
            <div key={row.k} className="op-inforow">
              <span className="k">{row.k}</span>
              <span className="v">
                {row.v}
                {row.badge && <span className={`op-minibadge ${row.badge.cls}`} style={{ marginLeft: 8 }}>{row.badge.label}</span>}
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* 발송 대상 — 어떤 리스트에, 어떤 조건으로 나갔는지 (애드혹 캠페인은 생략) */}
      {campaign.listId != null && (
        <div className="op-card" style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
          <span className="op-log-title" style={{ marginBottom: 0 }}>발송 대상</span>
          {campaign.listName ? (
            <span
              className="op-minibadge blue link"
              title={`'${campaign.listName}' 리스트 보기`}
              onClick={() => nav(`/lists?focus=${campaign.listId}`)}
            >
              {campaign.listName}
            </span>
          ) : (
            <span className="op-minibadge gray">#{campaign.listId} (삭제됨)</span>
          )}
          {targetList && (
            <span className="meta" style={{ fontSize: 12.5 }}>현재 구독자 {fmt(targetList.memberCount)}명</span>
          )}
          <span className="meta" style={{ fontSize: 12.5 }}>이번 발송 대상 {fmt(campaign.total)}명</span>
          {(campaign.segMinOpenPercent != null || campaign.segMinClickPercent != null) && (
            <>
              {campaign.segMinOpenPercent != null && (
                <span className="op-minibadge amber" title="발송(팬아웃) 시점의 오픈율 기준으로 대상을 좁혔습니다">
                  오픈율 {campaign.segMinOpenPercent}%+
                </span>
              )}
              {campaign.segMinClickPercent != null && (
                <span className="op-minibadge amber" title="발송(팬아웃) 시점의 클릭율 기준으로 대상을 좁혔습니다">
                  클릭율 {campaign.segMinClickPercent}%+
                </span>
              )}
              <span className="faint" style={{ fontSize: 12 }}>참여도 조건은 발송 시점에 평가</span>
            </>
          )}
        </div>
      )}

      {cancelOpen && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setCancelOpen(false); }}>
          <div className="op-modal">
            <h3>예약 발송 취소</h3>
            <p className="op-modal-sub">
              <b>{campaign.subject}</b> 캠페인의 예약 발송을 취소할까요?
              대기 중인 {fmt(campaign.pending)}건이 발송되지 않으며, 취소 후 되돌릴 수 없습니다.
            </p>
            {cancelError && <div className="op-modal-error">{cancelError}</div>}
            <div className="op-modal-foot">
              <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setCancelOpen(false)}>닫기</button>
              <button className="op-btn op-btn-sm" style={{ background: "var(--op-red)" }} disabled={canceling} onClick={cancelSchedule}>
                {canceling ? "취소 중…" : "예약 취소"}
              </button>
            </div>
          </div>
        </div>
        </Portal>
      )}

      {/* A/B variant mail preview popup — variant B falls back to A's content
          where it isn't overridden (subject-only test shares the body). */}
      {previewVariant && content && (
        <Portal>
        <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setPreviewVariant(null); }}>
          <div className="op-modal op-modal-preview">
            <div className="op-pvhead">
              <div>
                <h3 style={{ margin: 0 }}>{previewVariant}안 메일 미리보기</h3>
                <p className="op-modal-sub" style={{ margin: "6px 0 0" }}>
                  발송 시점에 스냅샷된 내용입니다. {"{{변수}}"}는 수신자별로 채워집니다.
                  {previewVariant === "B" && content.abBodyB == null && " — B안은 제목만 다르고 본문은 A안과 공통입니다."}
                </p>
              </div>
              <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setPreviewVariant(null)}>닫기</button>
            </div>
            <div className="op-pvsubject">
              <span className="k">제목</span>
              <span className="v">
                {previewVariant === "B" ? content.abSubjectB ?? content.subject : content.subject}
              </span>
            </div>
            {/* keyed remount per variant: updating a sandbox iframe's srcDoc in place can skip the repaint */}
            <iframe
              key={previewVariant}
              className="op-pvframe"
              title={`${previewVariant}안 메일 미리보기`}
              sandbox=""
              srcDoc={previewVariant === "B" ? content.abBodyB ?? content.htmlBody : content.htmlBody}
            />
          </div>
        </div>
        </Portal>
      )}

      <div className="op-card op-bigprogress">
        <div className="op-bigprogress-head">
          <div>
            <div className="lead">발송 진행률</div>
            <div className="op-bigpct">{pct}<span>%</span></div>
          </div>
          <div className="op-throughput">
            <div className="lead">초당 처리량</div>
            <div className="val">
              {done ? "완료" : rate == null ? "측정 중" : Math.round(rate * 10) / 10}
              {!done && rate != null && <span className="unit"> 건/s</span>}
            </div>
          </div>
        </div>
        <div className="op-bar lg">
          <div className="op-bar-fill" style={{ width: `${pct}%` }} />
        </div>
      </div>

      <div className="op-statcards">
        <div className="op-statcard"><div className="k">전체 수신자</div><div className="v">{fmt(campaign.total)}</div></div>
        <div className="op-statcard"><div className="k">발송 완료</div><div className="v green">{fmt(campaign.sent)}</div></div>
        <div className="op-statcard"><div className="k">대기 중</div><div className="v blue">{fmt(campaign.pending)}</div></div>
        <div className="op-statcard"><div className="k">실패</div><div className="v red">{fmt(campaign.failed)}</div></div>
      </div>

      {/* engagement + exclusions — opened/clicked come from the Kafka-projected event stream */}
      <div className="op-statcards">
        <div className="op-statcard">
          <div className="k">오픈 {campaign.sent > 0 ? `· ${Math.round((campaign.opened / campaign.sent) * 1000) / 10}%` : ""}</div>
          <div className="v blue">{fmt(campaign.opened)}</div>
        </div>
        <div className="op-statcard">
          <div className="k">클릭 {campaign.sent > 0 ? `· ${Math.round((campaign.clicked / campaign.sent) * 1000) / 10}%` : ""}</div>
          <div className="v blue">{fmt(campaign.clicked)}</div>
        </div>
        <div className="op-statcard"><div className="k">바운스</div><div className="v red">{fmt(campaign.bounced)}</div></div>
        <div className="op-statcard"><div className="k">발송 제외</div><div className="v">{fmt(campaign.suppressed)}</div></div>
      </div>

      {/* A/B split test: side-by-side delivery + engagement per variant.
          The strictly leading open/click rate is highlighted. */}
      {campaign.variants && campaign.variants.length > 0 && (() => {
        const variants = campaign.variants;
        const leads = (rate: (v: (typeof variants)[number]) => number) => {
          const rates = variants.map((v) => (v.sent > 0 ? rate(v) : -1));
          const max = Math.max(...rates);
          return variants.map((_, i) => rates[i] >= 0 && rates[i] === max
            && rates.some((r) => r < max));
        };
        const openLead = leads((v) => v.opened / v.sent);
        const clickLead = leads((v) => v.clicked / v.sent);
        return (
          <div className="op-card">
            <div className="op-log-title" style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10 }}>
              <span>A/B 테스트 결과</span>
              {/* per-variant mail previews open in a popup (content comes from /content) */}
              <span style={{ display: "flex", gap: 8 }}>
                <button className="op-btn op-btn-sm op-btn-ghost" disabled={!content} onClick={() => setPreviewVariant("A")}>
                  A안 미리보기
                </button>
                <button className="op-btn op-btn-sm op-btn-ghost" disabled={!content} onClick={() => setPreviewVariant("B")}>
                  B안 미리보기
                </button>
              </span>
            </div>
            <div className="op-thead" style={{ gridTemplateColumns: AB_COLS }}>
              <span>변형</span>
              <span>발송대상</span>
              <span>발송</span>
              <span>오픈</span>
              <span>클릭</span>
            </div>
            {variants.map((v, i) => (
              <div key={v.variant} className="op-trow" style={{ gridTemplateColumns: AB_COLS }}>
                <span className="strong">{v.variant}안</span>
                <span>{fmt(v.total)}</span>
                <span>{fmt(v.sent)}</span>
                <span className={openLead[i] ? "strong" : undefined}>
                  {fmt(v.opened)} · {rateOf(v.opened, v.sent)}
                </span>
                <span className={clickLead[i] ? "strong" : undefined}>
                  {fmt(v.clicked)} · {rateOf(v.clicked, v.sent)}
                </span>
              </div>
            ))}
            {/* winner flow: the table above covers the test group only; the held-out
                remainder is released with the decided winner */}
            {campaign.abTestPercent != null && (
              campaign.abWinner ? (
                <div className="op-ab-note winner">
                  <span className="chip">승자 {campaign.abWinner}안</span>
                  <span>
                    테스트 그룹 {campaign.abTestPercent}%의 {campaign.abEvalMetric === "CLICK" ? "클릭율" : "오픈율"} 비교 결과,
                    나머지 {100 - campaign.abTestPercent}%는 {campaign.abWinner}안으로 자동 발송됩니다.
                    <span className="sub">위 표는 테스트 그룹 기준입니다.</span>
                  </span>
                </div>
              ) : (
                <div className="op-ab-note pending">
                  <span className="chip">평가 대기</span>
                  <span>
                    테스트 그룹 {campaign.abTestPercent}% · {campaign.abEvalMetric === "CLICK" ? "클릭율" : "오픈율"} 기준
                    {campaign.abEvaluateAt
                      ? ` · ${new Date(campaign.abEvaluateAt).toLocaleString("ko-KR")}에 승자를 평가합니다.`
                      : " · 테스트 발송이 끝나면 승자를 평가합니다."}
                    <span className="sub">위 표는 테스트 그룹 기준입니다.</span>
                  </span>
                </div>
              )
            )}
          </div>
        );
      })()}

      {/* 링크별 클릭 분석 — 추적된 클릭 URL 랭킹 (클릭이 있을 때만) */}
      {links && links.length > 0 && (() => {
        const maxClicks = Math.max(...links.map((l) => l.clicks), 1);
        const LINK_COLS = "minmax(200px, 3fr) minmax(120px, 1.4fr) 90px 80px 80px";
        return (
          <div className="op-card">
            <div className="op-log-title">링크 클릭</div>
            <div className="op-thead" style={{ gridTemplateColumns: LINK_COLS }}>
              <span>URL</span>
              <span />
              <span>클릭 수신자</span>
              <span>클릭율</span>
              <span>총 클릭</span>
            </div>
            {links.map((l) => (
              <div key={l.url} className="op-trow" style={{ gridTemplateColumns: LINK_COLS }}>
                <span className="op-ell" title={l.url} style={{ fontSize: 13 }}>{l.url}</span>
                <span className="op-bar" style={{ alignSelf: "center" }}>
                  <span className="op-bar-fill" style={{ width: `${(l.clicks / maxClicks) * 100}%` }} />
                </span>
                <span className="strong">{fmt(l.uniqueMessages)}</span>
                <span>{campaign.sent > 0 ? `${Math.round((l.uniqueMessages / campaign.sent) * 1000) / 10}%` : "–"}</span>
                <span>{fmt(l.clicks)}</span>
              </div>
            ))}
            <div className="op-list-row">
              <span className="meta" style={{ fontSize: 12 }}>클릭율 = 클릭 수신자 / 발송 성공({fmt(campaign.sent)}건)</span>
            </div>
          </div>
        );
      })()}

      {/* A/B campaigns hide the single-variant mail card — both variants are viewable
          from the A/B 결과 preview popups; recipients then take the full width. */}
      <div className="op-detail-cols" style={campaign.variants && campaign.variants.length > 0 ? { gridTemplateColumns: "1fr" } : undefined}>
        {!(campaign.variants && campaign.variants.length > 0) && (
        <div className="op-card op-mailpreview">
          <div className="op-log-title">메일 내용</div>
          {content ? (
            <>
              <div className="op-mail-subject">
                <span className="k">제목</span>
                <span className="v">{content.subject}</span>
              </div>
              <iframe
                className="op-mail-frame"
                title="캠페인 메일 미리보기"
                sandbox=""
                srcDoc={content.htmlBody}
              />
            </>
          ) : (
            <div className="op-log-row"><span className="msg" style={{ color: "var(--op-faint)" }}>내용을 불러오는 중…</span></div>
          )}
        </div>
        )}

        {/* who it went to: per-recipient delivery rows, newest first */}
        <div className="op-card op-recipients">
          <div className="op-log-title">
            수신자
            <span className="sub">
              {campaign.total > (recipients?.length ?? 0)
                ? ` 최근 ${recipients?.length ?? 0}건 / 전체 ${fmt(campaign.total)}명`
                : ` ${fmt(campaign.total)}명`}
            </span>
          </div>
          <div className="op-recipient-scroll">
            {recipients == null || recipients.length === 0 ? (
              <div className="op-log-row"><span className="msg" style={{ color: "var(--op-faint)" }}>
                {recipients == null ? "수신자를 불러오는 중…" : "수신자가 없습니다."}
              </span></div>
            ) : (
              recipients.map((m) => {
                const info = LOG_META[m.status] ?? LOG_META.PENDING;
                return (
                  <div key={m.id} className="op-log-row">
                    <span className={`ldot ${info.tone}`} />
                    <span className="msg op-ell" title={m.errorMessage ?? undefined}>
                      {m.recipient}
                      {m.errorMessage ? ` — ${m.errorMessage}` : ""}
                    </span>
                    <span className="rstatus">{info.label}</span>
                    <span className="time">{timeOf(m.updatedAt)}</span>
                  </div>
                );
              })
            )}
          </div>
        </div>
      </div>

      <div className="op-card">
        <div className="op-log-title">발송 로그</div>
        {log.length === 0 ? (
          <div className="op-log-row"><span className="msg" style={{ color: "var(--op-faint)" }}>아직 기록이 없습니다.</span></div>
        ) : (
          log.map((e) => {
            const info = LOG_META[e.status] ?? LOG_META.PENDING;
            return (
              <div key={`${e.time}-${e.status}`} className="op-log-row">
                <span className="time">{timeOf(e.time)}</span>
                <span className={`ldot ${info.tone}`} />
                <span className="msg">
                  {fmt(e.count)}건 {info.label}
                  {e.detail ? ` — ${e.detail}` : ""}
                </span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
