import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";

/* 재설정 메일의 링크가 도착하는 곳 — 토큰(쿼리) + 새 비밀번호로 확정한다. */
export default function ResetPassword() {
  const [params] = useSearchParams();
  const nav = useNavigate();
  const token = params.get("token") ?? "";
  const [password, setPassword] = useState("");
  const [password2, setPassword2] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (password.length < 8) { setError("비밀번호는 8자 이상이어야 합니다."); return; }
    if (password !== password2) { setError("비밀번호가 서로 달라요."); return; }
    setBusy(true);
    try {
      const res = await fetch("/api/auth/password-reset/confirm", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token, newPassword: password }),
      });
      if (res.ok) {
        nav("/login", { replace: true });
        return;
      }
      const data = await res.json().catch(() => ({}));
      setError(data.error ?? "재설정에 실패했습니다. 링크를 다시 요청해주세요.");
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <div className="op-root" style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center" }}>
        <div className="op-authform">
          <h2>링크가 올바르지 않아요</h2>
          <p style={{ color: "var(--op-muted)", fontSize: 14 }}>
            재설정 메일의 버튼(또는 링크)으로 다시 들어와 주세요.
          </p>
          <p style={{ fontSize: 13 }}><Link to="/forgot-password" className="op-linkbtn">재설정 다시 요청하기</Link></p>
        </div>
      </div>
    );
  }

  return (
    <div className="op-root" style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center" }}>
      <div className="op-authform">
        <h2>새 비밀번호 설정</h2>
        <form onSubmit={submit}>
          <label className="op-field">
            <span className="op-flabel">새 비밀번호 (8자 이상)</span>
            <input className="op-input" type="password" required autoFocus
                   value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>
          <label className="op-field">
            <span className="op-flabel">새 비밀번호 확인</span>
            <input className="op-input" type="password" required
                   value={password2} onChange={(e) => setPassword2(e.target.value)} />
          </label>
          {error && <p className="error" style={{ fontSize: 13 }}>{error}</p>}
          <button className="op-btn" style={{ width: "100%" }} disabled={busy}>
            {busy ? "변경 중…" : "비밀번호 변경"}
          </button>
        </form>
      </div>
    </div>
  );
}
