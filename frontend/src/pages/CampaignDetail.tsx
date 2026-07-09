import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import Portal from "../components/Portal";
import type { CampaignContentView, CampaignView, MessageStatus, MessageView, SendLogEntry } from "../types";
import { badgeClass, fmt, pctOf } from "../outpace/format";
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

export default function CampaignDetail() {
  const nav = useNavigate();
  const { id } = useParams();
  const [campaign, setCampaign] = useState<CampaignView | null>(null);
  const [log, setLog] = useState<SendLogEntry[]>([]);
  const [content, setContent] = useState<CampaignContentView | null>(null);
  const [recipients, setRecipients] = useState<MessageView[] | null>(null);
  // Throughput is derived from consecutive polls: Δsent / Δt.
  const [rate, setRate] = useState<number | null>(null);
  const lastPoll = useRef<{ sent: number; at: number } | null>(null);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [canceling, setCanceling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

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
    return () => { cancelled = true; };
  }, [id]);

  useEffect(() => {
    let cancelled = false;
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
    refresh();
    const timer = setInterval(refresh, 2000);
    return () => { cancelled = true; clearInterval(timer); };
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
  if (campaign.templateId) {
    meta.push(`템플릿: ${campaign.templateName ?? `#${campaign.templateId} (삭제됨)`}`);
  }
  if (campaign.listId) {
    meta.push(`리스트: ${campaign.listName ?? `#${campaign.listId} (삭제됨)`}`);
  }
  meta.push(`생성: ${new Date(campaign.createdAt).toLocaleString("ko-KR")}`);
  if (campaign.scheduledAt) {
    meta.push(`예약: ${new Date(campaign.scheduledAt).toLocaleString("ko-KR")}`);
  }

  return (
    <div className="op-container op-fade">
      <button className="op-back" onClick={() => nav("/campaigns")}>← 캠페인 목록</button>

      <div className="op-detail-head">
        <div>
          <div className="op-detail-title">
            <h2>{campaign.subject}</h2>
            <span className={`op-badge ${badgeClass(campaign.status)}`}>
              {campaign.status === "CANCELED" ? "취소됨" : campaign.status}
            </span>
          </div>
          <p className="op-detail-meta">{meta.join(" · ")}</p>
        </div>
        {/* cancellable only while the scheduled release is still deferred */}
        {campaign.status === "QUEUED" && campaign.scheduledAt
          && new Date(campaign.scheduledAt).getTime() > Date.now() && (
          <button className="op-btn op-btn-sm op-btn-ghost danger" onClick={() => setCancelOpen(true)}>
            예약 취소
          </button>
        )}
      </div>

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

      <div className="op-detail-cols">
        {/* what was sent: subject + HTML body snapshot (variables render per recipient) */}
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
