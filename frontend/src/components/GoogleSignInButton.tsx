import { useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../outpace/auth";

/* VITE_GOOGLE_CLIENT_ID 가 설정된 배포에서만 진짜 구글 버튼을 그린다 —
   미설정(로컬 기본)이면 안내 버튼으로 폴백. 로그인·가입 화면이 공유한다:
   백엔드는 같은 /api/auth/google 하나로 "있으면 로그인, 없으면 즉석 가입"을
   처리하므로(AuthService.loginWithGoogle) 화면별로 다르게 굴 이유가 없다. */
const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined;

interface Props {
  /** 폴백/실패 문구를 화면의 에러 영역에 띄우기 위한 콜백. */
  onError: (message: string) => void;
}

export default function GoogleSignInButton({ onError }: Props) {
  const nav = useNavigate();
  const { login } = useAuth();
  const slot = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!GOOGLE_CLIENT_ID) return;
    const script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.onload = () => {
      const gsi = (window as any).google?.accounts?.id;
      if (!gsi || !slot.current) return;
      gsi.initialize({
        client_id: GOOGLE_CLIENT_ID,
        callback: async (resp: { credential: string }) => {
          try {
            const res = await fetch("/api/auth/google", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ idToken: resp.credential }),
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
              onError(data.error ?? "Google 로그인에 실패했습니다.");
              return;
            }
            login(data.token, data.email, data.role, data.workspaceName);
            nav("/", { replace: true });
          } catch {
            onError("Google 로그인에 실패했습니다.");
          }
        },
      });
      gsi.renderButton(slot.current, {
        theme: "outline", size: "large", text: "continue_with", locale: "ko", width: 360,
      });
    };
    document.head.appendChild(script);
    return () => { document.head.removeChild(script); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!GOOGLE_CLIENT_ID) {
    return (
      <button
        type="button"
        className="op-btn op-btn-block op-btn-ghost"
        onClick={() => onError("소셜 로그인은 준비 중입니다. 이메일로 계속해 주세요.")}
      >
        Google로 계속하기
      </button>
    );
  }
  return <div ref={slot} style={{ display: "flex", justifyContent: "center" }} />;
}
