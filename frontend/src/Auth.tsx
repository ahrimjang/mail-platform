import { useState } from "react";

interface AuthProps {
  onAuthed: (token: string, email: string) => void;
}

export default function Auth({ onAuthed }: AuthProps) {
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const path = mode === "login" ? "/api/auth/login" : "/api/auth/signup";
      const payload =
        mode === "login"
          ? { email, password }
          : { email, password, displayName };
      const res = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "오류가 발생했습니다.");
        return;
      }
      onAuthed(data.token, data.email);
    } catch {
      setError("오류가 발생했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main style={{ maxWidth: 360, margin: "4rem auto", fontFamily: "system-ui", padding: "0 1rem" }}>
      <h1>Mail Platform</h1>
      <h2>{mode === "login" ? "로그인" : "회원가입"}</h2>

      <form onSubmit={submit} style={{ display: "grid", gap: 8, marginBottom: 16 }}>
        <label>
          이메일
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            style={{ width: "100%" }}
          />
        </label>
        <label>
          비밀번호
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            style={{ width: "100%" }}
          />
        </label>
        {mode === "signup" && (
          <label>
            이름
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              style={{ width: "100%" }}
            />
          </label>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? "처리 중…" : mode === "login" ? "로그인" : "회원가입"}
        </button>
      </form>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      <button
        type="button"
        onClick={() => {
          setMode(mode === "login" ? "signup" : "login");
          setError(null);
        }}
        style={{ background: "none", border: "none", color: "#06c", cursor: "pointer", padding: 0 }}
      >
        {mode === "login" ? "계정이 없으신가요? 회원가입" : "이미 계정이 있으신가요? 로그인"}
      </button>
    </main>
  );
}
