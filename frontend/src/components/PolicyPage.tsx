import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../outpace/auth";

/* 약관류 공개 문서의 공용 프레임 — 랜딩/요금제와 같은 op-topnav + 본문 아티클.
   본문 타이포는 outpace.css 의 .op-policy 규칙을 쓴다. */
export default function PolicyPage({ title, updated, children }: {
  title: string;
  updated: string;          // 시행일 표기 (예: "2026년 7월 28일 시행")
  children: React.ReactNode;
}) {
  const navigate = useNavigate();
  const { token } = useAuth();

  return (
    <div className="op-root" style={{ minHeight: "100vh", display: "flex", flexDirection: "column" }}>
      <header className="op-topnav">
        <div className="op-topnav-left">
          <div className="op-logo" onClick={() => navigate("/")}>
            <div className="op-logo-badge"><span className="tri" /></div>
            <span>Outpace</span>
          </div>
        </div>
        <div className="op-topnav-right">
          {token
            ? <Link className="op-btn op-btn-sm" to="/">대시보드로</Link>
            : <>
                <Link className="op-btn op-btn-sm op-btn-ghost" to="/login">로그인</Link>
                <Link className="op-btn op-btn-sm" to="/signup">무료로 시작</Link>
              </>}
        </div>
      </header>

      <main className="op-container-mid op-fade" style={{ padding: "44px 24px 72px", flex: 1, width: "100%" }}>
        <h2 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>{title}</h2>
        <p style={{ fontSize: 12.5, color: "var(--op-faint)", margin: "6px 0 28px" }}>{updated}</p>
        <article className="op-policy">{children}</article>
      </main>

      <footer style={{ borderTop: "1px solid var(--op-border)", padding: "22px 24px", textAlign: "center",
                       fontSize: 12.5, color: "var(--op-faint)" }}>
        © 2026 Outpace · <Link to="/terms" className="op-linkbtn" style={{ fontWeight: 600 }}>이용약관</Link> ·{" "}
        <Link to="/privacy" className="op-linkbtn" style={{ fontWeight: 600 }}>개인정보처리방침</Link> ·{" "}
        <a className="op-linkbtn" style={{ fontWeight: 600 }} href="mailto:support@outpacemail.com?subject=Outpace 문의">문의</a>
      </footer>
    </div>
  );
}
