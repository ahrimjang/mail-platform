import { useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useAuth } from "../outpace/auth";

/* 인증 메일의 링크가 도달하는 곳 — 진입 즉시 토큰을 확인한다. 공개 라우트
   (메일 링크는 로그인 세션이 없는 브라우저에서도 열린다). */
export default function VerifyEmail() {
  const [params] = useSearchParams();
  const { token: session } = useAuth();
  const token = params.get("token") ?? "";
  const [state, setState] = useState<"working" | "done" | "failed">("working");
  const [error, setError] = useState<string | null>(null);
  const fired = useRef(false);   // StrictMode 이중 실행 방지 — 토큰이 1회용이라 중요

  useEffect(() => {
    if (!token || fired.current) return;
    fired.current = true;
    (async () => {
      try {
        const res = await fetch("/api/auth/verify-email", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ token }),
        });
        if (res.ok) {
          setState("done");
        } else {
          const data = await res.json().catch(() => ({}));
          setError(data.error ?? "인증에 실패했습니다.");
          setState("failed");
        }
      } catch {
        setError("요청 중 오류가 발생했습니다.");
        setState("failed");
      }
    })();
  }, [token]);

  return (
    <div className="op-root" style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center" }}>
      <div className="op-authform" style={{ textAlign: "center" }}>
        {!token ? (
          <>
            <h2>링크가 올바르지 않아요</h2>
            <p style={{ color: "var(--op-muted)", fontSize: 14 }}>
              인증 메일의 버튼(또는 링크)으로 다시 들어와 주세요.
            </p>
          </>
        ) : state === "working" ? (
          <h2>인증 확인 중…</h2>
        ) : state === "done" ? (
          <>
            <div style={{ fontSize: 40, marginBottom: 8 }}>✅</div>
            <h2>이메일 인증 완료!</h2>
            <p style={{ color: "var(--op-muted)", fontSize: 14, lineHeight: 1.7 }}>
              이제 캠페인을 발송할 수 있어요.
            </p>
            <Link className="op-btn op-btn-sm" to={session ? "/" : "/login"} style={{ marginTop: 8 }}>
              {session ? "콘솔로 가기" : "로그인하기"}
            </Link>
          </>
        ) : (
          <>
            <h2>인증에 실패했어요</h2>
            <p style={{ color: "var(--op-muted)", fontSize: 14, lineHeight: 1.7 }}>{error}</p>
            <p style={{ fontSize: 13, color: "var(--op-faint)" }}>
              콘솔 상단 배너의 "인증 메일 재발송"으로 새 링크를 받을 수 있어요.
            </p>
            <Link className="op-btn op-btn-sm op-btn-ghost" to={session ? "/" : "/login"}>
              {session ? "콘솔로 가기" : "로그인하기"}
            </Link>
          </>
        )}
      </div>
    </div>
  );
}
