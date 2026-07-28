import { useState } from "react";
import { Link } from "react-router-dom";

/* 비밀번호 재설정 요청 — 계정 유무와 무관하게 같은 안내를 보여준다(열거 방지와 짝). */
export default function ForgotPassword() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await fetch("/api/auth/password-reset/request", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email }),
      });
    } catch { /* 안내는 동일 */ }
    setSent(true);
    setBusy(false);
  }

  return (
    <div className="op-root" style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center" }}>
      <div className="op-authform">
        <h2>비밀번호 찾기</h2>
        {sent ? (
          <>
            <p style={{ color: "var(--op-muted)", lineHeight: 1.7, fontSize: 14 }}>
              <b>{email}</b> 이(가) 가입된 주소라면 재설정 링크를 보냈어요.
              메일함(스팸함 포함)을 확인해주세요. 링크는 30분 동안 유효합니다.
            </p>
            <p style={{ fontSize: 13 }}><Link to="/login" className="op-linkbtn">로그인으로 돌아가기</Link></p>
          </>
        ) : (
          <form onSubmit={submit}>
            <p style={{ color: "var(--op-muted)", fontSize: 13.5, lineHeight: 1.6 }}>
              가입한 이메일 주소를 입력하면 재설정 링크를 보내드려요.
            </p>
            <label className="op-field">
              <span className="op-flabel">이메일</span>
              <input className="op-input" type="email" required autoFocus
                     placeholder="you@company.com"
                     value={email} onChange={(e) => setEmail(e.target.value)} />
            </label>
            <button className="op-btn" style={{ width: "100%" }} disabled={busy || !email}>
              {busy ? "보내는 중…" : "재설정 링크 보내기"}
            </button>
            <p style={{ marginTop: 14, fontSize: 13, textAlign: "center" }}>
              <Link to="/login" className="op-linkbtn">로그인으로 돌아가기</Link>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
