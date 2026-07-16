import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import ActivityChart from "../components/ActivityChart";
import { api } from "../api";
import type { CampaignView, DashboardDay, DashboardView } from "../types";
import { useAuth } from "../outpace/auth";
import { badgeClass, fmt, pctOf, statusLabel } from "../outpace/format";
import { MOCK_CAMPAIGNS } from "../outpace/mock";

const CHART_DAYS = 14;

/* Deterministic demo series so the chart still reads when the backend is empty. */
function mockDaily(): DashboardDay[] {
  const base = [820, 930, 1240, 760, 1580, 1210, 990, 1430, 1720, 880, 1310, 1650, 1240, 1490];
  const out: DashboardDay[] = [];
  for (let i = 0; i < CHART_DAYS; i++) {
    const d = new Date();
    d.setDate(d.getDate() - (CHART_DAYS - 1 - i));
    const sent = base[i % base.length];
    out.push({
      date: d.toISOString().slice(0, 10),
      sent,
      failed: Math.round(sent * 0.008),
      opened: Math.round(sent * 0.36),
      clicked: Math.round(sent * 0.09),
    });
  }
  return out;
}

/* Handoff icon placeholders, dependency-free: 16px inline SVGs. */
const ICONS = {
  mail: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="2" y="4" width="20" height="16" rx="2" /><path d="m22 7-10 6L2 7" />
    </svg>
  ),
  check: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  ),
  bars: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
      <path d="M6 20V10" /><path d="M12 20V4" /><path d="M18 20v-6" />
    </svg>
  ),
};

/* KPI card footer sparkline (handoff: 2px primary line + 8% area fill). */
function Sparkline({ values }: { values: number[] }) {
  if (values.length < 2) return null;
  const max = Math.max(...values, 1);
  const pts = values.map((v, i) =>
    `${(i / (values.length - 1)) * 100},${24 - (v / max) * 20}`);
  return (
    <svg className="op-spark" viewBox="0 0 100 26" preserveAspectRatio="none" aria-hidden>
      <polygon points={`0,26 ${pts.join(" ")} 100,26`} fill="rgba(37, 99, 235, 0.08)" />
      <polyline points={pts.join(" ")} fill="none" stroke="#2563eb" strokeWidth="2" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/* -------------------------------- dashboard -------------------------------- */

export default function Dashboard() {
  const nav = useNavigate();
  const { email } = useAuth();
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [stats, setStats] = useState<DashboardView | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    async function refresh() {
      try {
        const [cRes, sRes] = await Promise.all([
          api("/api/campaigns"),
          api(`/api/dashboard?days=${CHART_DAYS}`),
        ]);
        if (cancelled) return;
        if (cRes.ok) setCampaigns(await cRes.json());
        if (sRes.ok) setStats(await sRes.json());
      } catch {
        /* transient / unauthorized handled by api() */
      } finally {
        if (!cancelled) setLoaded(true);
      }
    }
    refresh();
    const id = setInterval(refresh, 5000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  // Fall back to demo data only once we know the backend returned nothing.
  const usingMock = loaded && campaigns.length === 0;
  const rows = campaigns.length > 0 ? campaigns : usingMock ? MOCK_CAMPAIGNS : [];
  const daily = useMemo(
    () => (usingMock ? mockDaily() : stats?.daily ?? []),
    [usingMock, stats],
  );
  const today = daily.length > 0 ? daily[daily.length - 1] : null;

  // Handoff KPI derivations: today vs yesterday, 7-day success, live queue.
  const yesterday = daily.length > 1 ? daily[daily.length - 2] : null;
  const delta = today && yesterday && yesterday.sent > 0
    ? Math.round(((today.sent - yesterday.sent) / yesterday.sent) * 100)
    : null;
  const last7 = daily.slice(-7);
  const sent7 = last7.reduce((a, d) => a + d.sent, 0);
  const failed7 = last7.reduce((a, d) => a + d.failed, 0);
  const successRate = sent7 > 0 ? Math.round(((sent7 - failed7) / sent7) * 1000) / 10 : null;
  const sendingCount = rows.filter((c) => c.status === "SENDING").length;
  const queued = rows.reduce((a, c) => a + c.pending, 0);

  const live = rows.find((c) => c.status === "SENDING") ?? null;
  // Campaigns whose engagement is still moving: sending/expanding now, or
  // drained within the last 24h (opens/clicks keep arriving after COMPLETED).
  // The 5s poll makes their rates effectively live.
  const engaging = rows
    .filter((c) => {
      if (c.id === live?.id) return false; // already featured in the live card
      if (c.status === "SENDING" || c.status === "EXPANDING") return true;
      if (c.status !== "COMPLETED" || !c.completedAt) return false;
      return Date.now() - new Date(c.completedAt).getTime() < 24 * 3600_000;
    })
    .sort((a, b) => new Date(b.completedAt ?? b.createdAt).getTime() - new Date(a.completedAt ?? a.createdAt).getTime())
    .slice(0, 3);
  // Throughput of the live card, derived from consecutive polls: Δsent / Δt.
  const lastLive = useRef<{ id: number; sent: number; at: number } | null>(null);
  const [liveRate, setLiveRate] = useState<number | null>(null);
  useEffect(() => {
    if (!live) { lastLive.current = null; setLiveRate(null); return; }
    const now = Date.now();
    const prev = lastLive.current;
    if (prev && prev.id === live.id && now > prev.at && live.sent >= prev.sent) {
      setLiveRate((live.sent - prev.sent) / ((now - prev.at) / 1000));
    }
    lastLive.current = { id: live.id, sent: live.sent, at: now };
  }, [live?.id, live?.sent]); // eslint-disable-line react-hooks/exhaustive-deps

  // Newest first; six cards fill the grid evenly.
  const recent = rows
    .filter((c) => c.status !== "DRAFT") // drafts live on the campaigns page's 임시 tab
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    .slice(0, 6);
  const name = (email?.split("@")[0] || "회원");

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>안녕하세요, {name}님</h2>
          <p>오늘도 안정적으로 발송되고 있어요.</p>
        </div>
        <button className="op-btn op-btn-sm" onClick={() => nav("/campaigns/new")}>
          <span className="op-btn-plus">+</span>새 캠페인
        </button>
      </div>

      <div className="op-kpis">
        <div className="op-kpi has-spark">
          <div className="head">
            <span className="k">오늘 발송</span>
            <span className="op-kpi-ico blue">{ICONS.mail}</span>
          </div>
          <div className="v">{fmt(today?.sent ?? 0)}</div>
          <div className="d">
            {delta != null && (
              <span className={`op-kpi-pill ${delta >= 0 ? "up" : "down"}`}>
                {delta >= 0 ? "▲" : "▼"} {Math.abs(delta)}%
              </span>
            )}
            {delta != null ? " 어제 대비" : `실패 ${fmt(today?.failed ?? 0)}건`}
          </div>
          <Sparkline values={daily.map((d) => d.sent)} />
        </div>
        <div className="op-kpi">
          <div className="head">
            <span className="k">발송 성공률</span>
            <span className="op-kpi-ico green">{ICONS.check}</span>
          </div>
          <div className="v">{successRate != null ? `${successRate}%` : "–"}</div>
          <div className="op-gauge"><span className="op-gauge-fill" style={{ width: `${successRate ?? 0}%` }} /></div>
          <div className="d">최근 7일 평균</div>
        </div>
        <div className="op-kpi">
          <div className="head">
            <span className="k">진행 중</span>
            <span className="op-kpi-ico blue"><span className="op-pulse" /></span>
          </div>
          <div className="v">{fmt(sendingCount)}</div>
          {sendingCount > 0
            ? <div className="d blue">● 캠페인 발송 중</div>
            : <div className="d">발송 중인 캠페인 없음</div>}
        </div>
        <div className="op-kpi">
          <div className="head">
            <span className="k">대기 큐</span>
            <span className="op-kpi-ico gray">{ICONS.bars}</span>
          </div>
          <div className="v">{fmt(queued)}</div>
          <div className="d">메시지 대기</div>
        </div>
      </div>

      {live && (
        <div className="op-card op-card-pad op-live" onClick={() => nav(`/campaigns/${live.id}`)}>
          <div className="op-live-head">
            <div className="op-live-title">
              <span className="op-live-ico"><span className="op-pulse" /></span>
              {live.name ?? live.subject}
              <span className={`op-badge ${badgeClass(live.status)}`}>실시간 발송 중</span>
            </div>
            <span className="op-live-pct">{pctOf(live.sent, live.total)}<span className="unit">%</span></span>
          </div>
          <div className="op-bar lg" style={{ marginBottom: 16 }}>
            <div className="op-bar-fill" style={{ width: `${pctOf(live.sent, live.total)}%` }} />
          </div>
          <div className="op-metrics4 tiles">
            <div><div className="k">발송</div><div className="v green">{fmt(live.sent)}</div></div>
            <div><div className="k">대기</div><div className="v">{fmt(live.pending)}</div></div>
            <div><div className="k">실패</div><div className="v red">{fmt(live.failed)}</div></div>
            <div><div className="k">처리량</div><div className="v">{liveRate != null ? Math.round(liveRate) : "–"}<span className="unit">/s</span></div></div>
          </div>
        </div>
      )}

      {engaging.length > 0 && (
        <div className="op-card op-engage">
          <div className="op-engage-head">
            <span className="op-live-title" style={{ fontSize: 15 }}>
              <span className="op-pulse" />진행 중인 캠페인
            </span>
            <span className="faint" style={{ fontSize: 12 }}>오픈 · 클릭 실시간 집계</span>
          </div>
          {engaging.map((c) => {
            const openPct = c.sent > 0 ? pctOf(c.opened, c.sent) : 0;
            const clickPct = c.sent > 0 ? pctOf(c.clicked, c.sent) : 0;
            const done = new Date(c.completedAt ?? c.createdAt);
            return (
              <div key={c.id} className="op-engage-row" onClick={() => nav(`/campaigns/${c.id}`)}>
                <div className="who">
                  <div className="name">{c.name ?? c.subject}</div>
                  <div className="sub">
                    {c.status === "COMPLETED"
                      ? `발송 완료 ${done.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })} · ${fmt(c.sent)}건`
                      : `발송 중 · ${fmt(c.sent)}/${fmt(c.total)}건`}
                  </div>
                </div>
                <div className="rate">
                  <span className="k">오픈율</span>
                  <span className="op-bar sm"><span className="op-bar-fill" style={{ width: `${Math.min(openPct, 100)}%` }} /></span>
                  <span className="v">{c.sent > 0 ? `${openPct}%` : "–"}</span>
                </div>
                <div className="rate">
                  <span className="k">클릭율</span>
                  <span className="op-bar sm green"><span className="op-bar-fill" style={{ width: `${Math.min(clickPct, 100)}%` }} /></span>
                  <span className="v">{c.sent > 0 ? `${clickPct}%` : "–"}</span>
                </div>
                <span className="go">›</span>
              </div>
            );
          })}
        </div>
      )}

      <div className="op-card op-card-pad op-chart-card">
        <div className="op-chart-head">
          <span className="t">발송 추이 · 최근 {CHART_DAYS}일</span>
          <div className="op-chart-legend">
            <span><span className="swatch bar" />발송</span>
            <span><span className="swatch open" />오픈</span>
            <span><span className="swatch click" />클릭</span>
          </div>
        </div>
        {daily.length === 0 ? (
          <p className="op-chart-empty">아직 발송 데이터가 없습니다.</p>
        ) : (
          <ActivityChart daily={daily} />
        )}
      </div>

      <div className="op-card">
        <div className="op-list-head">
          <span className="t">최근 캠페인</span>
          <button className="op-linkbtn" onClick={() => nav("/campaigns")}>전체 보기 →</button>
        </div>
        {recent.length === 0 ? (
          <div className="op-list-row"><span className="meta">아직 캠페인이 없습니다. ‘새 캠페인’으로 첫 발송을 시작하세요.</span></div>
        ) : (
          <div className="op-recent-grid">
            {recent.map((c) => {
              const pct = pctOf(c.sent, c.total);
              return (
                <div key={c.id} className="op-camp-card" onClick={() => nav(`/campaigns/${c.id}`)}>
                  <div className="head">
                    <div className="name">{c.name ?? c.subject}</div>
                    <span className={`op-badge ${badgeClass(c.status)}`}>
                      {statusLabel(c)}
                    </span>
                  </div>
                  <div className="meta">
                    {new Date(c.createdAt).toLocaleDateString("ko-KR")} · 수신자 {fmt(c.total)}명
                    {c.variants && c.variants.length > 0 ? " · A/B" : ""}
                  </div>
                  <div className="op-bar">
                    <div className="op-bar-fill" style={{ width: `${pct}%` }} />
                  </div>
                  <div className="stats">
                    <span>발송 <b>{fmt(c.sent)}</b></span>
                    <span>오픈 <b>{c.sent > 0 ? `${pctOf(c.opened, c.sent)}%` : "–"}</b></span>
                    <span>클릭 <b>{c.sent > 0 ? `${pctOf(c.clicked, c.sent)}%` : "–"}</b></span>
                    {c.failed > 0 && <span style={{ color: "var(--op-red)" }}>실패 <b>{fmt(c.failed)}</b></span>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
