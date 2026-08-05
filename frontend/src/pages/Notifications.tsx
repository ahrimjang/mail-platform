import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import type { NotificationFeedView, NotificationView } from "../types";

/* 알림 이력 페이지 — 벨 드롭다운(최근 20건, 즉시 확인)의 짝. 방문 시 전부 읽음
   처리되고, 항목 클릭은 해당 캠페인으로 간다. */
export default function Notifications() {
  const nav = useNavigate();
  const [feed, setFeed] = useState<NotificationFeedView>({ unread: 0, items: [] });
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const res = await api("/api/notifications?limit=100");
        if (res.ok && alive) {
          const d: NotificationFeedView = await res.json();
          setFeed(d);
          if (d.unread > 0) {
            await api("/api/notifications/read-all", { method: "POST" });
          }
        }
      } catch { /* transient */ }
      if (alive) setLoaded(true);
    })();
    return () => { alive = false; };
  }, []);

  const fmt = (iso: string) =>
    new Date(iso).toLocaleDateString("ko-KR", { year: "numeric", month: "short", day: "numeric" }) +
    " " + new Date(iso).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" });

  /* 날짜별 그룹 — 이력 페이지는 "언제"가 1차 축이다 */
  const groups = feed.items.reduce<Map<string, NotificationView[]>>((acc, n) => {
    const day = new Date(n.createdAt).toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric", weekday: "short" });
    if (!acc.has(day)) acc.set(day, []);
    acc.get(day)!.push(n);
    return acc;
  }, new Map());

  return (
    <div className="op-container-mid op-fade">
      <div className="op-pagehead">
        <div>
          <h2>알림</h2>
          <p>발송 완료 등 워크스페이스에서 일어난 일들이에요. 최근 100건까지 보관해서 보여드려요.</p>
        </div>
      </div>

      {loaded && feed.items.length === 0 && (
        <div className="op-card op-card-pad" style={{ textAlign: "center", padding: "48px 24px" }}>
          <p style={{ margin: 0, color: "var(--op-muted)", fontSize: 14 }}>
            아직 알림이 없어요 — 캠페인 발송이 완료되면 여기에 쌓입니다.
          </p>
        </div>
      )}

      {[...groups.entries()].map(([day, items]) => (
        <section key={day} style={{ marginBottom: 22 }}>
          <h3 style={{ fontSize: 13, fontWeight: 700, color: "var(--op-faint)", margin: "0 0 8px" }}>{day}</h3>
          <div className="op-card" style={{ overflow: "hidden" }}>
            {items.map((n) => (
              <div key={n.id} className="op-trow clickable"
                   style={{ gridTemplateColumns: "1fr 150px", cursor: n.campaignId != null ? "pointer" : "default" }}
                   onClick={() => { if (n.campaignId != null) nav(`/campaigns/${n.campaignId}`); }}>
                <span className="strong op-ell" style={{ fontWeight: n.readAt == null ? 700 : 500 }}>
                  {n.title}
                </span>
                <span className="faint" style={{ textAlign: "right", fontSize: 12.5 }}>{fmt(n.createdAt)}</span>
              </div>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
