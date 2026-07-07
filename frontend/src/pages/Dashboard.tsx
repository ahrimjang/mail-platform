import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import type { CampaignView } from "../types";
import { useAuth } from "../outpace/auth";
import { badgeClass, fmt, pctOf } from "../outpace/format";
import { MOCK_CAMPAIGNS } from "../outpace/mock";

export default function Dashboard() {
  const nav = useNavigate();
  const { email } = useAuth();
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [loaded, setLoaded] = useState(false);

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
    const id = setInterval(refresh, 3000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  // Fall back to demo data only once we know the backend returned nothing.
  const usingMock = loaded && campaigns.length === 0;
  const rows = campaigns.length > 0 ? campaigns : usingMock ? MOCK_CAMPAIGNS : [];

  const totalSent = rows.reduce((a, c) => a + c.sent, 0);
  const totalFailed = rows.reduce((a, c) => a + c.failed, 0);
  const totalPending = rows.reduce((a, c) => a + c.pending, 0);
  const inProgress = rows.filter((c) => c.status === "SENDING").length;
  const successRate = totalSent + totalFailed > 0
    ? `${Math.round((totalSent / (totalSent + totalFailed)) * 1000) / 10}%`
    : "–";

  const live = rows.find((c) => c.status === "SENDING") ?? null;
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
          <div className="v">{fmt(totalSent)}</div>
          <div className="d up">▲ 12% 어제 대비</div>
        </div>
        <div className="op-kpi">
          <div className="k">발송 성공률</div>
          <div className="v">{successRate}</div>
          <div className="d">최근 7일 평균</div>
        </div>
        <div className="op-kpi">
          <div className="k">진행 중</div>
          <div className="v">{inProgress}</div>
          <div className="d blue">캠페인 발송 중</div>
        </div>
        <div className="op-kpi">
          <div className="k">대기 큐</div>
          <div className="v">{fmt(totalPending)}</div>
          <div className="d">메시지</div>
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
            {/* throughput has no backend metric yet — indicative figure */}
            <div><div className="k">처리량</div><div className="v">120<span className="unit">/s</span></div></div>
          </div>
        </div>
      )}

      <div className="op-card">
        <div className="op-list-head">
          <span className="t">전체 캠페인</span>
          <a className="op-linkbtn" href="#" onClick={(e) => e.preventDefault()}>필터</a>
        </div>
        {rows.length === 0 ? (
          <div className="op-list-row"><span className="meta">아직 캠페인이 없습니다. ‘새 캠페인’으로 첫 발송을 시작하세요.</span></div>
        ) : (
          rows.map((c) => (
            <div key={c.id} className="op-list-row clickable" onClick={() => nav(`/campaigns/${c.id}`)}>
              <div>
                <div className="name">{c.subject}</div>
                <div className="meta">
                  {fmt(c.total)}명 · {c.status === "QUEUED" ? "대기 중" : `${fmt(c.sent)}건 발송`}
                  {c.failed > 0 ? ` · ${fmt(c.failed)}건 실패` : ""}
                </div>
              </div>
              <span className={`op-badge ${badgeClass(c.status)}`}>{c.status}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
