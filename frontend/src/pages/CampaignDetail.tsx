import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import type { CampaignView, MessageStatus, SendLogEntry } from "../types";
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
  // Throughput is derived from consecutive polls: Δsent / Δt.
  const [rate, setRate] = useState<number | null>(null);
  const lastPoll = useRef<{ sent: number; at: number } | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function refresh() {
      try {
        const [cRes, mRes] = await Promise.all([
          api(`/api/campaigns/${id}`),
          // 10s buckets: "N건 발송 완료" rows instead of one row per recipient
          api(`/api/campaigns/${id}/log?bucketSeconds=10&limit=50`),
        ]);
        if (cancelled) return;
        if (cRes.ok) {
          const view: CampaignView = await cRes.json();
          setCampaign(view);
          // messages feed is best-effort; the page works without it
          if (mRes.ok) setLog(await mRes.json());

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
        <button className="op-back" onClick={() => nav("/")}>← 대시보드</button>
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
  meta.push(`생성: ${new Date(campaign.createdAt).toLocaleString("ko-KR")}`);
  if (campaign.scheduledAt) {
    meta.push(`예약: ${new Date(campaign.scheduledAt).toLocaleString("ko-KR")}`);
  }

  return (
    <div className="op-container op-fade">
      <button className="op-back" onClick={() => nav("/")}>← 대시보드</button>

      <div className="op-detail-head">
        <div>
          <div className="op-detail-title">
            <h2>{campaign.subject}</h2>
            <span className={`op-badge ${badgeClass(campaign.status)}`}>{campaign.status}</span>
          </div>
          <p className="op-detail-meta">{meta.join(" · ")}</p>
        </div>
      </div>

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
