import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../outpace/auth";
import BrandPanel from "../components/BrandPanel";

export default function Login() {
  const nav = useNavigate();
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        setError(data.error ?? "로그인에 실패했습니다.");
        return;
      }
      login(data.token, data.email, data.role, data.workspaceName);
      nav("/", { replace: true });
    } catch {
      setError("로그인에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="op-root op-split">
      <BrandPanel
        heading={<>대량 이메일을<br />안정적으로, 빠르게.</>}
        checks={["API가 즉시 큐에 적재, 워커가 배치 발송", "실시간 발송 진행률 추적", "바운스 · 실패 자동 처리"]}
      />
      <div className="op-authpane">
        <form className="op-authform" onSubmit={submit}>
          <h2>로그인</h2>
          <p className="op-sub">Outpace 워크스페이스에 접속합니다.</p>

          <label className="op-field">
            <span className="op-flabel">이메일</span>
            <input
              className="op-input"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@company.com"
              autoFocus
            />
          </label>
          <label className="op-field" style={{ marginBottom: 10 }}>
            <span className="op-flabel">비밀번호</span>
            <input
              className="op-input"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
            />
          </label>
          <div className="op-forgot">
            <a className="op-linkbtn" href="#" onClick={(e) => e.preventDefault()}>비밀번호 찾기</a>
          </div>

          <button type="submit" className="op-btn op-btn-block" disabled={submitting}>
            {submitting ? "로그인 중…" : "로그인"}
          </button>

          {error && <p className="error">{error}</p>}

          <div className="op-divider-or"><span>또는</span></div>
          <button
            type="button"
            className="op-btn op-btn-block op-btn-ghost"
            onClick={() => setError("소셜 로그인은 데모에서 지원되지 않습니다. 이메일로 로그인하세요.")}
          >
            Google로 계속하기
          </button>

          <p className="op-switch">
            계정이 없으신가요?{" "}
            <a className="op-linkbtn" onClick={() => nav("/signup")}>회원가입</a>
          </p>
        </form>
      </div>
    </div>
  );
}
