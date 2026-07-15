import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import type { CampaignView } from "../types";
import { badgeClass, fmt, pctOf } from "../outpace/format";
import { MOCK_CAMPAIGNS } from "../outpace/mock";

/* Column template shared by the header and body rows. */
const COLS = "minmax(140px, 2.4fr) 76px 56px 64px minmax(96px, 1.2fr) 60px 60px 118px";

type Tab = "all" | "sending" | "scheduled" | "done" | "canceled";

const TABS: { key: Tab; label: string }[] = [
  { key: "all", label: "전체" },
  { key: "sending", label: "발송 중" },
  { key: "scheduled", label: "예약됨" },
  { key: "done", label: "완료" },
  { key: "canceled", label: "취소" },
];

/* A QUEUED campaign with a future send time reads as "scheduled", not "waiting". */
function isScheduled(c: CampaignView): boolean {
  return c.status === "QUEUED" && !!c.scheduledAt && new Date(c.scheduledAt).getTime() > Date.now();
}

function statusLabel(c: CampaignView): string {
  if (isScheduled(c)) return "예약됨";
  switch (c.status) {
    case "QUEUED": return "대기 중";
    case "SENDING": return "발송 중";
    case "COMPLETED": return "완료";
    case "CANCELED": return "취소됨";
    default: return "초안";
  }
}

function whenOf(c: CampaignView): string {
  const d = new Date(c.scheduledAt ?? c.createdAt);
  const pad = (n: number) => String(n).padStart(2, "0");
  const s = `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return c.scheduledAt ? `예약 ${s}` : s;
}

/* Engagement rate over delivered mail; "–" until anything was sent. */
function rateOf(part: number, sent: number): string {
  return sent > 0 ? `${Math.round((part / sent) * 1000) / 10}%` : "–";
}

export default function Campaigns() {
  const nav = useNavigate();
  const [params] = useSearchParams();
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [tab, setTab] = useState<Tab>("all");
  // The top-nav search lands here with ?q=; afterwards the local input owns it.
  const [query, setQuery] = useState(params.get("q") ?? "");
  // Re-searching while already on this page only changes the URL — the
  // component stays mounted, so mirror ?q= into the input on every change.
  useEffect(() => {
    const q = params.get("q");
    if (q !== null) setQuery(q);
  }, [params]);

  useEffect(() => {
    let cancelled = false;
    async function refresh() {
      try {
        const res = await api("/api/campaigns");
        if (res.ok && !cancelled) setCampaigns(await res.json());
      } catch {
        /* transient / unauthorized handled by api() */
      } finally {
        if (!cancelled) setLoaded(true);
      }
    }
    refresh();
    // Skip background-tab polls; refresh immediately when the tab comes back.
    const id = setInterval(() => { if (!document.hidden) refresh(); }, 5000);
    const onVisible = () => { if (!document.hidden) refresh(); };
    document.addEventListener("visibilitychange", onVisible);
    return () => { cancelled = true; clearInterval(id); document.removeEventListener("visibilitychange", onVisible); };
  }, []);

  // Same demo fallback the dashboard uses, so the two screens always agree.
  const usingMock = loaded && campaigns.length === 0;
  const rows = campaigns.length > 0 ? campaigns : usingMock ? MOCK_CAMPAIGNS : [];

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows
      .filter((c) => {
        if (tab === "sending") return c.status === "SENDING";
        if (tab === "scheduled") return isScheduled(c);
        if (tab === "done") return c.status === "COMPLETED";
        if (tab === "canceled") return c.status === "CANCELED";
        return true;
      })
      .filter((c) =>
        !q
        || (c.name ?? "").toLowerCase().includes(q)
        || c.subject.toLowerCase().includes(q)
        || (c.senderName ?? "").toLowerCase().includes(q)
        || (c.senderEmail ?? "").toLowerCase().includes(q))
      .slice()
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }, [rows, tab, query]);

  const countOf = (t: Tab) =>
    t === "all" ? rows.length
      : t === "sending" ? rows.filter((c) => c.status === "SENDING").length
      : t === "scheduled" ? rows.filter(isScheduled).length
      : t === "canceled" ? rows.filter((c) => c.status === "CANCELED").length
      : rows.filter((c) => c.status === "COMPLETED").length;

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>캠페인</h2>
          <p>발송 캠페인을 관리하고 성과를 확인하세요.</p>
        </div>
        <button className="op-btn op-btn-sm" onClick={() => nav("/campaigns/new")}>
          <span className="op-btn-plus">+</span>새 캠페인
        </button>
      </div>

      <div className="op-tabs">
        {TABS.map((t) => (
          <button
            key={t.key}
            className={`op-tab${tab === t.key ? " active" : ""}`}
            onClick={() => setTab(t.key)}
          >
            {t.label} {loaded && <span className="cnt">{countOf(t.key)}</span>}
          </button>
        ))}
      </div>

      <div className="op-toolbar">
        <input
          className="op-input"
          style={{ maxWidth: 280 }}
          placeholder="제목·발신자 검색"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      <div className="op-card">
        <div className="op-thead" style={{ gridTemplateColumns: COLS }}>
          <span>캠페인</span>
          <span>상태</span>
          <span>A/B</span>
          <span>수신자</span>
          <span>발송</span>
          <span>오픈율</span>
          <span>클릭율</span>
          <span>일시</span>
        </div>
        {visible.length === 0 ? (
          <div className="op-trow" style={{ gridTemplateColumns: "1fr" }}>
            <span className="faint">
              {query || tab !== "all"
                ? "조건에 맞는 캠페인이 없습니다."
                : "아직 캠페인이 없습니다. ‘새 캠페인’으로 첫 발송을 시작하세요."}
            </span>
          </div>
        ) : (
          visible.map((c) => {
            const pct = pctOf(c.sent, c.total);
            return (
              <div
                key={c.id}
                className="op-trow clickable"
                style={{ gridTemplateColumns: COLS }}
                onClick={() => nav(`/campaigns/${c.id}`)}
              >
                <div style={{ minWidth: 0 }}>
                  <div className="strong op-ell">{c.name ?? c.subject}</div>
                  <div className="faint op-ell">
                    {c.senderName || c.senderEmail
                      ? `${c.senderName ?? ""}${c.senderEmail ? ` <${c.senderEmail}>` : ""}`
                      : `캠페인 #${c.id}`}
                  </div>
                </div>
                <span><span className={`op-badge ${badgeClass(c.status)}`}>{statusLabel(c)}</span></span>
                {/* A/B campaigns get a chip; once the winner flow decides, it shows who won */}
                <span>
                  {c.variants && c.variants.length > 0 ? (
                    <span className="op-badge" style={{ background: "var(--op-primary-soft)", color: "var(--op-primary)" }}>
                      {c.abWinner ? `${c.abWinner} 승` : "A/B"}
                    </span>
                  ) : (
                    <span className="faint">–</span>
                  )}
                </span>
                <span>{fmt(c.total)}</span>
                <span className="op-minibar">
                  <span className="op-bar"><span className="op-bar-fill" style={{ width: `${pct}%` }} /></span>
                  <span className="pct">{pct}%</span>
                </span>
                <span>{rateOf(c.opened, c.sent)}</span>
                <span>{rateOf(c.clicked, c.sent)}</span>
                <span className="faint">{whenOf(c)}</span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
