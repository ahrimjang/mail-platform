import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import ActivityChart from "../components/ActivityChart";
import { api } from "../api";
import type { AudienceHealthView, CampaignView, DashboardView, LinkClicksView, OpenHeatmapCell } from "../types";
import { badgeClass, fmt, pctOf, statusLabel } from "../outpace/format";

type Period = 7 | 30 | 90;
type SortKey = "openRate" | "clickRate" | "sent" | "createdAt";

const PERIODS: Period[] = [7, 30, 90];

/* Engagement rate over delivered mail; -1 sorts unsent campaigns to the bottom. */
function rateOf(part: number, sent: number): number {
  return sent > 0 ? part / sent : -1;
}

export default function Analytics() {
  const nav = useNavigate();
  const [days, setDays] = useState<Period>(30);
  const [stats, setStats] = useState<DashboardView | null>(null);
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [sortKey, setSortKey] = useState<SortKey>("openRate");
  const [links, setLinks] = useState<LinkClicksView[] | null>(null);
  const [health, setHealth] = useState<AudienceHealthView | null>(null);
  const [heatmap, setHeatmap] = useState<OpenHeatmapCell[] | null>(null);

  // Campaign list once; the daily series refetches per selected period.
  useEffect(() => {
    let cancelled = false;
    api("/api/campaigns")
      .then(async (res) => { if (res.ok && !cancelled) setCampaigns(await res.json()); })
      .catch(() => { /* transient / unauthorized handled by api() */ });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoaded(false);
    Promise.allSettled([
      api(`/api/dashboard?days=${days}`)
        .then(async (res) => { if (res.ok && !cancelled) setStats(await res.json()); }),
      api(`/api/analytics/links?days=${days}&limit=10`)
        .then(async (res) => { if (res.ok && !cancelled) setLinks(await res.json()); }),
      api(`/api/analytics/audience-health?days=${days}`)
        .then(async (res) => { if (res.ok && !cancelled) setHealth(await res.json()); }),
      api(`/api/analytics/open-heatmap?days=${days}`)
        .then(async (res) => { if (res.ok && !cancelled) setHeatmap(await res.json()); }),
    ]).finally(() => { if (!cancelled) setLoaded(true); });
    return () => { cancelled = true; };
  }, [days]);

  const daily = stats?.daily ?? [];
  const sent = daily.reduce((a, d) => a + d.sent, 0);
  const opened = daily.reduce((a, d) => a + d.opened, 0);
  const clicked = daily.reduce((a, d) => a + d.clicked, 0);
  const failed = daily.reduce((a, d) => a + d.failed, 0);

  const funnel = [
    { label: "발송", value: sent, rate: null as string | null },
    { label: "오픈", value: opened, rate: sent > 0 ? `${Math.round((opened / sent) * 1000) / 10}%` : "–" },
    { label: "클릭", value: clicked, rate: sent > 0 ? `${Math.round((clicked / sent) * 1000) / 10}%` : "–" },
  ];
  const funnelMax = Math.max(sent, 1);

  const ranked = useMemo(() => {
    const arr = campaigns.slice();
    switch (sortKey) {
      case "openRate": arr.sort((a, b) => rateOf(b.opened, b.sent) - rateOf(a.opened, a.sent)); break;
      case "clickRate": arr.sort((a, b) => rateOf(b.clicked, b.sent) - rateOf(a.clicked, a.sent)); break;
      case "sent": arr.sort((a, b) => b.sent - a.sent); break;
      case "createdAt": arr.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()); break;
    }
    return arr;
  }, [campaigns, sortKey]);

  const COLS = "minmax(150px, 2fr) 80px 64px 80px 76px 76px 96px";
  const sortBtn = (key: SortKey, label: string) => (
    <button
      className={`op-linkbtn${sortKey === key ? "" : " muted"}`}
      style={{ fontSize: 12.5, fontWeight: sortKey === key ? 800 : 500, color: sortKey === key ? "var(--op-primary)" : "var(--op-muted)" }}
      onClick={() => setSortKey(key)}
    >
      {label}{sortKey === key ? " ↓" : ""}
    </button>
  );

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>분석</h2>
          <p>캠페인 횡단 성과를 기간별로 살펴보세요.</p>
        </div>
        <div className="op-seg">
          {PERIODS.map((p) => (
            <div key={p} className={`op-seg-opt${days === p ? " active" : ""}`} onClick={() => setDays(p)}>
              {p}일
            </div>
          ))}
        </div>
      </div>

      {/* 발송 → 오픈 → 클릭 퍼널 (선택 기간 합계) */}
      <div className="op-card op-card-pad">
        <div className="op-chart-head">
          <span className="t">참여 퍼널 · 최근 {days}일</span>
          {failed > 0 && <span style={{ fontSize: 12.5, color: "var(--op-red)" }}>실패 {fmt(failed)}건</span>}
        </div>
        {!loaded ? (
          <p className="op-chart-empty">집계 중…</p>
        ) : sent === 0 ? (
          <p className="op-chart-empty">이 기간에 발송 데이터가 없습니다.</p>
        ) : (
          <div className="op-funnel">
            {funnel.map((s) => (
              <div key={s.label} className="op-funnel-step">
                <div className="lab">
                  <span className="name">{s.label}</span>
                  <b>{fmt(s.value)}</b>
                  {s.rate && <span className="rate">{s.rate}</span>}
                </div>
                <div className="track">
                  <div className="fill" style={{ width: `${Math.max((s.value / funnelMax) * 100, s.value > 0 ? 2 : 0)}%` }} />
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 일별 추이 (대시보드와 같은 차트, 기간 확장판) */}
      <div className="op-card op-card-pad op-chart-card">
        <div className="op-chart-head">
          <span className="t">발송 추이 · 최근 {days}일</span>
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

      {/* 오픈 시간 히트맵 — 요일×시간대별 오픈 분포 */}
      <div className="op-card op-card-pad">
        <div className="op-chart-head">
          <span className="t">오픈 시간 히트맵 · 최근 {days}일</span>
          <span style={{ fontSize: 12.5, color: "var(--op-faint)" }}>언제 메일을 열어보는지</span>
        </div>
        {heatmap === null ? (
          <p className="op-chart-empty">집계 중…</p>
        ) : heatmap.length === 0 ? (
          <p className="op-chart-empty">이 기간에 오픈 데이터가 없습니다.</p>
        ) : (() => {
          const byCell = new Map(heatmap.map((c) => [`${c.dayOfWeek}-${c.hour}`, c.opens]));
          const max = Math.max(...heatmap.map((c) => c.opens), 1);
          const DAYS_KO = ["월", "화", "수", "목", "금", "토", "일"];
          return (
            <div className="op-heatmap">
              <div className="hm-row hm-head">
                <span className="hm-day" />
                {Array.from({ length: 24 }, (_, h) => (
                  <span key={h} className="hm-hour">{h % 3 === 0 ? h : ""}</span>
                ))}
              </div>
              {DAYS_KO.map((label, i) => (
                <div key={label} className="hm-row">
                  <span className="hm-day">{label}</span>
                  {Array.from({ length: 24 }, (_, h) => {
                    const v = byCell.get(`${i + 1}-${h}`) ?? 0;
                    return (
                      <span
                        key={h}
                        className="hm-cell"
                        title={`${label} ${h}시 · 오픈 ${fmt(v)}`}
                        style={{ background: v > 0 ? `rgba(37, 99, 235, ${0.12 + 0.78 * (v / max)})` : undefined }}
                      />
                    );
                  })}
                </div>
              ))}
            </div>
          );
        })()}
      </div>

      {/* 링크 클릭 Top 10 — email_events의 클릭 URL 랭킹 */}
      <div className="op-card">
        <div className="op-list-head">
          <span className="t">링크 클릭 Top 10 · 최근 {days}일</span>
        </div>
        {links === null ? (
          <div className="op-list-row"><span className="meta">집계 중…</span></div>
        ) : links.length === 0 ? (
          <div className="op-list-row"><span className="meta">이 기간에 클릭된 링크가 없습니다.</span></div>
        ) : (
          <>
            <div className="op-thead" style={{ gridTemplateColumns: "minmax(200px, 3fr) 90px 110px" }}>
              <span>URL</span>
              <span>클릭</span>
              <span>클릭 수신자</span>
            </div>
            {links.map((l) => (
              <div key={l.url} className="op-trow" style={{ gridTemplateColumns: "minmax(200px, 3fr) 90px 110px" }}>
                <span className="op-ell" title={l.url} style={{ fontSize: 13 }}>{l.url}</span>
                <span className="strong">{fmt(l.clicks)}</span>
                <span>{fmt(l.uniqueMessages)}</span>
              </div>
            ))}
          </>
        )}
      </div>

      {/* 수신자 건강도 — 발송이 멈추는 이유(억제 사유별) + 리스트별 해지 */}
      <div className="op-card op-card-pad">
        <div className="op-chart-head">
          <span className="t">수신자 건강도</span>
          <span style={{ fontSize: 12.5, color: "var(--op-faint)" }}>신규 = 최근 {days}일</span>
        </div>
        {health === null ? (
          <p className="op-chart-empty">집계 중…</p>
        ) : (
          <div className="op-health">
            <div className="col">
              <div className="ct">발송 중단 사유 (전역 억제)</div>
              {health.suppressionReasons.length === 0 && <p className="op-chart-empty">억제된 주소가 없습니다.</p>}
              {health.suppressionReasons.map((r) => (
                <div key={r.reason} className="row">
                  <span className={`op-minibadge ${r.reason === "bounce" ? "amber" : r.reason === "unsubscribe" ? "gray" : "blue"}`}>
                    {r.reason === "bounce" ? "바운스" : r.reason === "unsubscribe" ? "수신거부" : "수동"}
                  </span>
                  <b>{fmt(r.total)}</b>
                  <span className="new">{r.recent > 0 ? `+${fmt(r.recent)} 신규` : ""}</span>
                </div>
              ))}
            </div>
            <div className="col">
              <div className="ct">리스트별 구독 해지</div>
              {health.listOptOuts.length === 0 && <p className="op-chart-empty">리스트 해지가 없습니다.</p>}
              {health.listOptOuts.map((o) => (
                <div key={o.listId} className="row">
                  <span
                    className="op-minibadge blue link"
                    title={`'${o.listName}' 리스트 보기`}
                    onClick={() => nav(`/lists?focus=${o.listId}`)}
                  >
                    {o.listName}
                  </span>
                  <b>{fmt(o.count)}명</b>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* 캠페인 성과 비교 — 열 이름 클릭으로 정렬 */}
      <div className="op-card">
        <div className="op-list-head">
          <span className="t">캠페인 성과 비교</span>
          <span style={{ fontSize: 12.5, color: "var(--op-faint)" }}>열 이름을 눌러 정렬</span>
        </div>
        <div className="op-thead" style={{ gridTemplateColumns: COLS }}>
          <span>캠페인</span>
          <span>상태</span>
          <span>A/B</span>
          <span>{sortBtn("sent", "발송")}</span>
          <span>{sortBtn("openRate", "오픈율")}</span>
          <span>{sortBtn("clickRate", "클릭율")}</span>
          <span>{sortBtn("createdAt", "생성일")}</span>
        </div>
        {ranked.length === 0 && (
          <div className="op-list-row"><span className="meta">아직 캠페인이 없습니다.</span></div>
        )}
        {ranked.map((c) => (
          <div
            key={c.id}
            className="op-trow clickable"
            style={{ gridTemplateColumns: COLS }}
            onClick={() => nav(`/campaigns/${c.id}`)}
          >
            <span className="strong op-ell">{c.name ?? c.subject}</span>
            <span><span className={`op-badge ${badgeClass(c.status)}`}>{statusLabel(c)}</span></span>
            <span>
              {c.variants && c.variants.length > 0 ? (
                <span className="op-minibadge blue">{c.abWinner ? `${c.abWinner} 승` : "A/B"}</span>
              ) : (
                <span className="faint">–</span>
              )}
            </span>
            <span>{fmt(c.sent)}</span>
            <span>{c.sent > 0 ? `${pctOf(c.opened, c.sent)}%` : "–"}</span>
            <span>{c.sent > 0 ? `${pctOf(c.clicked, c.sent)}%` : "–"}</span>
            <span className="faint">{new Date(c.createdAt).toLocaleDateString("ko-KR")}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
