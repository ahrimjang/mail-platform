import { useEffect, useRef, useState } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../outpace/auth";
import type { NotificationFeedView } from "../types";

/* Top navigation shell shared by dashboard / campaigns / templates.
   Editors render outside this shell (full-screen), matching the handoff. */
export default function AppShell() {
  const nav = useNavigate();
  const { pathname } = useLocation();
  const { email, role, workspaceName, logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const [search, setSearch] = useState("");
  const menuRef = useRef<HTMLDivElement>(null);
  // 가이드 드롭다운 — 요금제·구독 API·약관 같은 공개 문서로 가는 통로
  const [guideOpen, setGuideOpen] = useState(false);
  const guideRef = useRef<HTMLDivElement>(null);
  // 알림 피드 — 벨 아이콘의 점은 안 읽은 알림이 있을 때만
  const [notif, setNotif] = useState<NotificationFeedView>({ unread: 0, items: [] });
  const [notifOpen, setNotifOpen] = useState(false);
  const notifRef = useRef<HTMLDivElement>(null);
  // 가입 이메일 인증 배너 — null 은 아직 모름(배너 미표시). 페이지 이동마다
  // 가볍게 재확인해서 다른 탭에서 인증을 마치면 배너가 사라지게 한다.
  const [emailVerified, setEmailVerified] = useState<boolean | null>(null);
  const [resent, setResent] = useState(false);

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
      if (guideRef.current && !guideRef.current.contains(e.target as Node)) setGuideOpen(false);
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) setNotifOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  useEffect(() => {
    let alive = true;
    api("/api/me/email-verification")
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => { if (alive && d) setEmailVerified(d.verified); })
      .catch(() => {});
    return () => { alive = false; };
  }, [pathname]);

  useEffect(() => {
    let alive = true;
    api("/api/notifications")
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => { if (alive && d) setNotif(d); })
      .catch(() => {});
    return () => { alive = false; };
  }, [pathname]);

  /* 벨 클릭 — 여는 순간 전부 읽음 처리(점 소등), 목록은 그대로 보여준다. */
  async function toggleNotifications() {
    const opening = !notifOpen;
    setNotifOpen(opening);
    if (opening && notif.unread > 0) {
      try {
        await api("/api/notifications/read-all", { method: "POST" });
        setNotif((n) => ({ ...n, unread: 0 }));
      } catch { /* 다음 조회에서 재시도 */ }
    }
  }

  /* "3분 전" 식 상대 시각 — 알림 목록용 */
  function timeAgo(iso: string): string {
    const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 60) return "방금 전";
    if (s < 3600) return `${Math.floor(s / 60)}분 전`;
    if (s < 86400) return `${Math.floor(s / 3600)}시간 전`;
    return `${Math.floor(s / 86400)}일 전`;
  }

  async function resendVerification() {
    try {
      await api("/api/me/email-verification/resend", { method: "POST" });
      setResent(true);
    } catch { /* 배너 유지 */ }
  }

  const isDash = pathname === "/";
  const isCamp = pathname.startsWith("/campaigns");
  // 템플릿 관리는 이메일 허브 하위 화면 — 네비 하이라이트도 이메일로 묶는다
  const isEml = pathname.startsWith("/emails") || pathname.startsWith("/templates");
  const isRcp = pathname.startsWith("/recipients");
  const isList = pathname.startsWith("/lists");
  const isAnal = pathname.startsWith("/analytics");
  const isAdminPage = pathname.startsWith("/settings");
  const avatar = (email?.trim()?.[0] ?? "U").toUpperCase();

  return (
    <div className="op-root op-shell">
      <header className="op-topnav">
        <div className="op-topnav-left">
          <div className="op-logo" onClick={() => nav("/")}>
            <div className="op-logo-badge"><span className="tri" /></div>
            <span>Outpace</span>
          </div>
          <nav className="op-navlinks">
            <button className={`op-navlink${isDash ? " active" : ""}`} onClick={() => nav("/")}>대시보드</button>
            <button className={`op-navlink${isCamp ? " active" : ""}`} onClick={() => nav("/campaigns")}>캠페인</button>
            <button className={`op-navlink${isEml ? " active" : ""}`} onClick={() => nav("/emails")}>이메일</button>
            <button className={`op-navlink${isRcp ? " active" : ""}`} onClick={() => nav("/recipients")}>수신자</button>
            <button className={`op-navlink${isList ? " active" : ""}`} onClick={() => nav("/lists")}>리스트</button>
            <button className={`op-navlink${isAnal ? " active" : ""}`} onClick={() => nav("/analytics")}>분석</button>
            {role === "ADMIN" && (
              <button className={`op-navlink${isAdminPage ? " active" : ""}`} onClick={() => nav("/settings")}>관리</button>
            )}
            <div className="op-avatar-menu" ref={guideRef} style={{ display: "inline-block" }}>
              <button className="op-navlink" onClick={() => setGuideOpen((o) => !o)}>가이드 ▾</button>
              {guideOpen && (
                <div className="op-menu" style={{ minWidth: 170 }}>
                  <button onClick={() => { setGuideOpen(false); nav("/pricing"); }}>요금제 안내</button>
                  <button onClick={() => { setGuideOpen(false); nav("/developers"); }}>구독 API 가이드</button>
                  <button onClick={() => { setGuideOpen(false); nav("/terms"); }}>이용약관</button>
                  <button onClick={() => { setGuideOpen(false); nav("/privacy"); }}>개인정보처리방침</button>
                </div>
              )}
            </div>
          </nav>
        </div>
        <div className="op-topnav-right">
          <div className="op-nav-search">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
              <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
            </svg>
            <input
              placeholder="캠페인 검색"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => {
                // The Enter that commits a Korean IME composition also fires
                // keydown — searching then would use half-typed text.
                if (e.key === "Enter" && !e.nativeEvent.isComposing) {
                  nav(`/campaigns?q=${encodeURIComponent(search.trim())}`);
                  setSearch("");
                }
              }}
            />
          </div>
          <div className="op-avatar-menu" ref={notifRef}>
            <button className="op-bell" title="알림" aria-label="알림" onClick={toggleNotifications}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
              </svg>
              {notif.unread > 0 && <span className="dot" />}
            </button>
            {notifOpen && (
              <div className="op-menu" style={{ minWidth: 280, maxHeight: 360, overflowY: "auto" }}>
                {notif.items.length === 0 && (
                  <div className="op-menu-email">알림이 없어요 — 캠페인 발송이 완료되면 여기로 알려드릴게요.</div>
                )}
                {notif.items.map((n) => (
                  <button key={n.id}
                          onClick={() => { setNotifOpen(false); if (n.campaignId != null) nav(`/campaigns/${n.campaignId}`); }}>
                    <span style={{ display: "block", fontWeight: n.readAt == null ? 700 : 500 }}>{n.title}</span>
                    <span style={{ display: "block", fontSize: 11.5, color: "var(--op-faint)", marginTop: 2 }}>{timeAgo(n.createdAt)}</span>
                  </button>
                ))}
                <button style={{ borderTop: "1px solid var(--op-border)", borderRadius: 0, textAlign: "center", fontWeight: 700 }}
                        onClick={() => { setNotifOpen(false); nav("/notifications"); }}>
                  알림 전체 보기
                </button>
              </div>
            )}
          </div>
          <span className="op-nav-divider" />
          <div className="op-avatar-menu" ref={menuRef}>
            <div className="op-avatar" onClick={() => setMenuOpen((o) => !o)}>{avatar}</div>
            {menuOpen && (
              <div className="op-menu">
                <div className="op-menu-email">
                  {workspaceName && <b style={{ color: "var(--op-ink-2)", display: "block" }}>{workspaceName}</b>}
                  {email}
                  {role && <span style={{ display: "block", marginTop: 2 }}>{role === "ADMIN" ? "관리자" : "운영자"}</span>}
                </div>
                <button onClick={() => { setMenuOpen(false); logout(); nav("/login"); }}>로그아웃</button>
              </div>
            )}
          </div>
        </div>
      </header>
      {emailVerified === false && (
        <div style={{ background: "#fef9c3", borderBottom: "1px solid #fde047", padding: "9px 24px",
                      fontSize: 13, textAlign: "center", color: "#713f12" }}>
          가입 이메일 인증이 아직이에요 — 인증을 마쳐야 캠페인을 보낼 수 있어요.{" "}
          {resent
            ? <b>인증 메일을 다시 보냈어요. 받은편지함을 확인해주세요.</b>
            : <button onClick={resendVerification}
                      style={{ background: "none", border: "none", cursor: "pointer", font: "inherit",
                               fontWeight: 700, color: "#713f12", textDecoration: "underline", padding: 0 }}>
                인증 메일 재발송
              </button>}
        </div>
      )}
      <main className="op-main">
        <Outlet />
      </main>
    </div>
  );
}
