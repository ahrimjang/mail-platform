import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import type { CampaignView } from "../types";
import { badgeClass, fmt, pctOf, statusLabel } from "../outpace/format";
import { MOCK_CAMPAIGNS } from "../outpace/mock";

/* Column template shared by the header and body rows. */
const COLS = "minmax(140px, 2fr) minmax(90px, 1.1fr) 76px 56px 64px minmax(90px, 1.1fr) 60px 60px 118px";

type Tab = "all" | "sending" | "scheduled" | "done" | "canceled" | "drafts";

const TABS: { key: Tab; label: string }[] = [
  { key: "all", label: "전체" },
  { key: "sending", label: "발송 중" },
  { key: "scheduled", label: "예약됨" },
  { key: "done", label: "완료" },
  { key: "canceled", label: "취소" },
  { key: "drafts", label: "임시" },
];

/* A QUEUED campaign with a future send time reads as "scheduled", not "waiting". */
function isScheduled(c: CampaignView): boolean {
  return c.status === "QUEUED" && !!c.scheduledAt && new Date(c.scheduledAt).getTime() > Date.now();
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
  const [tab, setTab] = useState<Tab>(params.get("tab") === "drafts" ? "drafts" : "all");
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
        if (tab === "drafts") return c.status === "DRAFT";
        if (c.status === "DRAFT") return false; // drafts live only in their own tab
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
    t === "all" ? rows.filter((c) => c.status !== "DRAFT").length
      : t === "sending" ? rows.filter((c) => c.status === "SENDING").length
      : t === "scheduled" ? rows.filter(isScheduled).length
      : t === "canceled" ? rows.filter((c) => c.status === "CANCELED").length
      : t === "drafts" ? rows.filter((c) => c.status === "DRAFT").length
      : rows.filter((c) => c.status === "COMPLETED").length;

  async function discardDraft(id: number) {
    try {
      const res = await api(`/api/campaigns/drafts/${id}`, { method: "DELETE" });
      if (res.ok) setCampaigns((prev) => prev.filter((c) => c.id !== id));
    } catch { /* transient */ }
  }

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
          <span>대상</span>
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
              {tab === "drafts"
                ? "임시저장된 캠페인이 없습니다. 새 캠페인 작성 중 ‘임시저장’을 누르면 여기에 보관돼요."
                : query || tab !== "all"
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
                onClick={() => nav(c.status === "DRAFT" ? `/campaigns/new?draftId=${c.id}` : `/campaigns/${c.id}`)}
              >
                <div style={{ minWidth: 0 }}>
                  <div className="strong op-ell">{c.name ?? c.subject}</div>
                  <div className="faint op-ell">
                    {c.senderName || c.senderEmail
                      ? `${c.senderName ?? ""}${c.senderEmail ? ` <${c.senderEmail}>` : ""}`
                      : `캠페인 #${c.id}`}
                  </div>
                </div>
                {/* Audience: the target list (chip links to it) or ad-hoc addresses. */}
                <span style={{ minWidth: 0 }}>
                  {c.listId != null ? (
                    c.listName ? (
                      <span
                        className="op-minibadge blue link op-ell"
                        style={{ display: "inline-block", maxWidth: "100%" }}
                        title={`'${c.listName}' 리스트 보기${c.segMinOpenPercent != null || c.segMinClickPercent != null
                          ? ` · 참여도 조건: ${[
                              c.segMinOpenPercent != null ? `오픈율 ${c.segMinOpenPercent}%+` : null,
                              c.segMinClickPercent != null ? `클릭율 ${c.segMinClickPercent}%+` : null,
                            ].filter(Boolean).join(" · ")}`
                          : ""}`}
                        onClick={(e) => { e.stopPropagation(); nav(`/lists?focus=${c.listId}`); }}
                      >
                        {c.listName}{(c.segMinOpenPercent != null || c.segMinClickPercent != null) ? " ⚡" : ""}
                      </span>
                    ) : (
                      <span className="op-minibadge gray">#{c.listId} (삭제됨)</span>
                    )
                  ) : (
                    <span className="faint">직접 입력</span>
                  )}
                </span>
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
                <span className="faint">
                  {whenOf(c)}
                  {c.status === "DRAFT" && (
                    <button
                      className="op-linkbtn"
                      style={{ fontSize: 12, color: "var(--op-red)", marginLeft: 8 }}
                      onClick={(e) => { e.stopPropagation(); discardDraft(c.id); }}
                    >
                      삭제
                    </button>
                  )}
                </span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
