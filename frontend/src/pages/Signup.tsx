import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../outpace/auth";
import BrandPanel from "../components/BrandPanel";

export default function Signup() {
  const nav = useNavigate();
  const { login } = useAuth();
  const [name, setName] = useState("");
  const [workspace, setWorkspace] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [agreed, setAgreed] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!agreed) {
      setError("이용약관 및 개인정보 처리방침에 동의해 주세요.");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const res = await fetch("/api/auth/signup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        // A signup registers the company: the workspace becomes the tenant and
        // this account runs it as ADMIN.
        body: JSON.stringify({ email, password, displayName: name, companyName: workspace }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        setError(data.error ?? "계정 생성에 실패했습니다.");
        return;
      }
      login(data.token, data.email, data.role, data.workspaceName);
      nav("/", { replace: true });
    } catch {
      setError("계정 생성에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="op-root op-split">
      <BrandPanel
        heading={<>몇 분이면<br />첫 캠페인을 보냅니다.</>}
        checks={["카드 등록 없이 무료로 시작", "월 1만 건까지 무료 발송", "5분 만에 발송 환경 구성"]}
      />
      <div className="op-authpane">
        <form className="op-authform" onSubmit={submit}>
          <h2>계정 만들기</h2>
          <p className="op-sub">업무용 이메일로 워크스페이스를 시작하세요.</p>

          <div className="op-grid2" style={{ marginBottom: 16 }}>
            <label className="op-field" style={{ marginBottom: 0 }}>
              <span className="op-flabel">이름</span>
              <input className="op-input" value={name} onChange={(e) => setName(e.target.value)} placeholder="박지민" autoFocus />
            </label>
            <label className="op-field" style={{ marginBottom: 0 }}>
              <span className="op-flabel">회사 (워크스페이스)</span>
              <input className="op-input" value={workspace} onChange={(e) => setWorkspace(e.target.value)} placeholder="Acme" />
            </label>
          </div>

          <label className="op-field" style={{ marginBottom: 16 }}>
            <span className="op-flabel">업무용 이메일</span>
            <input className="op-input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@acme.io" />
          </label>

          <label className="op-field">
            <span className="op-flabel">비밀번호</span>
            <input className="op-input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" />
            <span className="op-hint">영문·숫자 포함 8자 이상</span>
          </label>

          <label className="op-terms">
            <span className="op-check-static" style={{ background: agreed ? "var(--op-primary)" : "#fff", border: agreed ? "none" : "1.5px solid #cbd5e1" }}>
              {agreed && <span className="op-tickmark" />}
            </span>
            <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} style={{ display: "none" }} />
            <span className="op-t-copy">
              <a href="#" onClick={(e) => e.preventDefault()}>이용약관</a> 및 <a href="#" onClick={(e) => e.preventDefault()}>개인정보 처리방침</a>에 동의합니다.
            </span>
          </label>

          <button type="submit" className="op-btn op-btn-block" disabled={submitting}>
            {submitting ? "계정 만드는 중…" : "계정 만들기"}
          </button>

          {error && <p className="error">{error}</p>}

          <div className="op-divider-or"><span>또는</span></div>
          <button
            type="button"
            className="op-btn op-btn-block op-btn-ghost"
            onClick={() => setError("소셜 로그인은 데모에서 지원되지 않습니다. 이메일로 가입하세요.")}
          >
            Google로 시작하기
          </button>

          <p className="op-switch">
            이미 계정이 있으신가요?{" "}
            <a className="op-linkbtn" onClick={() => nav("/login")}>로그인</a>
          </p>
        </form>
      </div>
    </div>
  );
}
