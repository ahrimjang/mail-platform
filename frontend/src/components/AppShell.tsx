import { useEffect, useRef, useState } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../outpace/auth";

/* Top navigation shell shared by dashboard / campaigns / templates.
   Editors render outside this shell (full-screen), matching the handoff. */
export default function AppShell() {
  const nav = useNavigate();
  const { pathname } = useLocation();
  const { email, logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const [search, setSearch] = useState("");
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  const isDash = pathname === "/";
  const isCamp = pathname.startsWith("/campaigns");
  const isTpl = pathname.startsWith("/templates");
  const isRcp = pathname.startsWith("/recipients");
  const isList = pathname.startsWith("/lists");
  const isAnal = pathname.startsWith("/analytics");
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
            <button className={`op-navlink${isTpl ? " active" : ""}`} onClick={() => nav("/templates")}>템플릿</button>
            <button className={`op-navlink${isRcp ? " active" : ""}`} onClick={() => nav("/recipients")}>수신자</button>
            <button className={`op-navlink${isList ? " active" : ""}`} onClick={() => nav("/lists")}>리스트</button>
            <button className={`op-navlink${isAnal ? " active" : ""}`} onClick={() => nav("/analytics")}>분석</button>
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
                <div className="op-menu-email">{email}</div>
                <button onClick={() => { setMenuOpen(false); logout(); nav("/login"); }}>로그아웃</button>
              </div>
            )}
          </div>
        </div>
      </header>
      <main className="op-main">
        <Outlet />
      </main>
    </div>
  );
}
