import { useEffect, useRef, useState } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../outpace/auth";

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
  // 가입 이메일 인증 배너 — null 은 아직 모름(배너 미표시). 페이지 이동마다
  // 가볍게 재확인해서 다른 탭에서 인증을 마치면 배너가 사라지게 한다.
  const [emailVerified, setEmailVerified] = useState<boolean | null>(null);
  const [resent, setResent] = useState(false);

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
      if (guideRef.current && !guideRef.current.contains(e.target as Node)) setGuideOpen(false);
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
          <button className="op-bell" title="알림" aria-label="알림">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
            </svg>
            <span className="dot" />
          </button>
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
