import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import ActivityChart from "../components/ActivityChart";
import { api } from "../api";
import type { CampaignView, DashboardDay, DashboardView } from "../types";
import { useAuth } from "../outpace/auth";
import { badgeClass, fmt, pctOf } from "../outpace/format";
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
  const contacts = usingMock ? 3204 : stats?.contacts ?? 0;
  const suppressed = usingMock ? 48 : stats?.suppressed ?? 0;

  const today = daily.length > 0 ? daily[daily.length - 1] : null;
  const periodSent = daily.reduce((a, d) => a + d.sent, 0);
  const periodOpened = daily.reduce((a, d) => a + d.opened, 0);
  const periodClicked = daily.reduce((a, d) => a + d.clicked, 0);
  const openRate = periodSent > 0 ? `${Math.round((periodOpened / periodSent) * 1000) / 10}%` : "–";
  const clickRate = periodSent > 0 ? `${Math.round((periodClicked / periodSent) * 1000) / 10}%` : "–";

  const live = rows.find((c) => c.status === "SENDING") ?? null;
  // Newest first; six cards fill the grid evenly.
  const recent = rows
    .slice()
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
        <div className="op-kpi">
          <div className="k">오늘 발송</div>
          <div className="v">{fmt(today?.sent ?? 0)}</div>
          <div className={`d${(today?.failed ?? 0) > 0 ? " red" : ""}`}>
            실패 {fmt(today?.failed ?? 0)}건
          </div>
        </div>
        <div className="op-kpi">
          <div className="k">최근 {CHART_DAYS}일 발송</div>
          <div className="v">{fmt(periodSent)}</div>
          <div className="d">일 평균 {fmt(Math.round(periodSent / Math.max(daily.length, 1)))}건</div>
        </div>
        <div className="op-kpi">
          <div className="k">오픈율</div>
          <div className="v">{openRate}</div>
          <div className="d blue">클릭율 {clickRate}</div>
        </div>
        <div className="op-kpi">
          <div className="k">수신자</div>
          <div className="v">{fmt(contacts)}</div>
          <div className="d">수신거부 {fmt(suppressed)}명</div>
        </div>
      </div>

      {live && (
        <div className="op-card op-card-pad op-live" onClick={() => nav(`/campaigns/${live.id}`)}>
          <div className="op-live-head">
            <div className="op-live-title">
              <span className="op-pulse" />
              {live.subject}
              <span className={`op-badge ${badgeClass(live.status)}`}>발송 중</span>
            </div>
            <span className="op-live-pct">{pctOf(live.sent, live.total)}%</span>
          </div>
          <div className="op-bar">
            <div className="op-bar-fill" style={{ width: `${pctOf(live.sent, live.total)}%` }} />
          </div>
          <div className="op-metrics4">
            <div><div className="k">발송</div><div className="v">{fmt(live.sent)}</div></div>
            <div><div className="k">대기</div><div className="v">{fmt(live.pending)}</div></div>
            <div><div className="k">실패</div><div className="v red">{fmt(live.failed)}</div></div>
            <div><div className="k">오픈</div><div className="v">{fmt(live.opened)}</div></div>
          </div>
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
                      {c.status === "CANCELED" ? "취소됨" : c.status}
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
