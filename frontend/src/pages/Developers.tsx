import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../outpace/auth";

/* 구독 API 연동 가이드 — 외부 사이트의 구독 폼을 Outpace 명단으로 잇는 방법.
   공개 페이지(비로그인 방문자도 연동 방법을 보고 가입을 결정한다). */

const CODE_STYLE: React.CSSProperties = {
  background: "var(--op-panel)", borderRadius: 10, padding: 16,
  fontSize: 12.5, lineHeight: 1.75, overflowX: "auto", margin: "10px 0 0",
};

function Row({ code, name, desc }: { code?: boolean; name: string; desc: string }) {
  return (
    <tr>
      <td style={{ padding: "9px 14px", whiteSpace: "nowrap", fontFamily: code ? "ui-monospace, monospace" : undefined, fontSize: 13 }}>{name}</td>
      <td style={{ padding: "9px 14px", fontSize: 13, color: "var(--op-muted)", lineHeight: 1.7 }}>{desc}</td>
    </tr>
  );
}

export default function Developers() {
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
          <Link className="op-btn op-btn-sm op-btn-ghost" to="/pricing">요금제</Link>
          {token
            ? <Link className="op-btn op-btn-sm" to="/">대시보드로</Link>
            : <>
                <Link className="op-btn op-btn-sm op-btn-ghost" to="/login">로그인</Link>
                <Link className="op-btn op-btn-sm" to="/signup">무료로 시작</Link>
              </>}
        </div>
      </header>

      <main className="op-container-mid op-fade" style={{ padding: "44px 24px 72px", flex: 1, width: "100%" }}>
        <h2 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>구독 API</h2>
        <p style={{ fontSize: 14.5, color: "var(--op-muted)", margin: "8px 0 32px", lineHeight: 1.75 }}>
          이미 운영 중인 사이트에 구독 폼만 붙이면, 신청자가 Outpace 연락처 명단에
          자동으로 쌓입니다. API 호출 한 번이면 되고, 등록 시 수신 동의 기록(출처·시각)이
          함께 남아요.
        </p>

        <article className="op-policy">
          <h3>1. API 키 발급</h3>
          <p>
            로그인 후 <b>관리 → 구독 연동 API</b>에서 관리자(ADMIN)가 발급합니다.
            키는 워크스페이스마다 하나이고, 재발급하면 이전 키는 즉시 무효가 돼요.
          </p>

          <h3>2. 엔드포인트</h3>
          <p><code>POST /api/public/subscribe</code> — 헤더 <code>X-Api-Key</code>로 인증합니다.</p>
          <div className="op-card" style={{ overflow: "hidden", margin: "10px 0 0" }}>
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ background: "var(--op-panel)" }}>
                  <th style={{ padding: "9px 14px", textAlign: "left", fontSize: 12.5 }}>필드</th>
                  <th style={{ padding: "9px 14px", textAlign: "left", fontSize: 12.5 }}>설명</th>
                </tr>
              </thead>
              <tbody>
                <Row code name="email" desc="구독자 이메일 (필수)" />
                <Row code name="firstName / lastName" desc="이름 (선택)" />
                <Row code name="listId" desc="가입시킬 리스트 ID (선택) — 콘솔의 리스트 화면에서 확인. 키 소유 워크스페이스의 리스트여야 합니다" />
              </tbody>
            </table>
          </div>

          <h3>3. 요청 예시</h3>
          <pre style={CODE_STYLE}>{`curl -X POST https://<서비스 주소>/api/public/subscribe \\
  -H "X-Api-Key: opk_..." \\
  -H "Content-Type: application/json" \\
  -d '{"email":"reader@example.com","firstName":"길동","listId":1}'`}</pre>

          <h3>4. 응답</h3>
          <div className="op-card" style={{ overflow: "hidden", margin: "10px 0 0" }}>
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <tbody>
                <Row code name="201" desc='신규 등록 — {"created": true}. 동의 출처가 API 로 기록됩니다' />
                <Row code name="200" desc='이미 등록된 주소 — {"created": false}. 연락처는 그대로 두고 listId 가 있으면 리스트 가입만 보장(같은 요청을 여러 번 보내도 안전한 멱등 동작)' />
                <Row code name="400" desc="이메일 형식 오류" />
                <Row code name="401" desc="API 키 누락·오류 (재발급 후 이전 키 사용 포함)" />
                <Row code name="404" desc="listId 가 없거나 내 워크스페이스의 리스트가 아님" />
                <Row code name="409" desc="플랜의 연락처 한도 도달 — 플랜을 올리면 해소" />
              </tbody>
            </table>
          </div>

          <h3>5. 웹사이트 폼 연동 (중요: 키는 서버에 두세요)</h3>
          <p>
            <b>API 키를 브라우저 코드에 직접 넣으면 안 됩니다</b> — 페이지 소스에서
            누구나 키를 볼 수 있고, 그 키로 명단에 마음대로 등록할 수 있게 돼요.
            폼은 여러분의 서버(백엔드나 서버리스 함수)로 제출하고, 서버가 키를 붙여
            Outpace 를 호출하는 구조를 권장합니다.
          </p>
          <pre style={CODE_STYLE}>{`<!-- ① 사이트의 구독 폼 → 내 서버로 제출 -->
<form action="/my-subscribe" method="post">
  <input type="email" name="email" required placeholder="이메일 주소">
  <button>구독하기</button>
</form>

// ② 내 서버(예: Node.js) → 키를 붙여 Outpace 호출
app.post("/my-subscribe", async (req, res) => {
  await fetch("https://<서비스 주소>/api/public/subscribe", {
    method: "POST",
    headers: {
      "X-Api-Key": process.env.OUTPACE_API_KEY,   // 키는 환경변수로
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ email: req.body.email, listId: 1 }),
  });
  res.redirect("/thanks");
});`}</pre>

          <h3>6. 알아두면 좋은 것</h3>
          <ul>
            <li>신규 등록은 <b>수신 동의 기록</b>(출처 API, 동의 시각)이 자동으로 남습니다 — 광고성 메일 발송의 법적 근거가 돼요.</li>
            <li>과거에 <b>수신거부한 주소는 다시 활성화하지 않습니다.</b> 수신거부 해제는 수신자 본인의 별도 의사표시가 필요한 영역이라서요.</li>
            <li>등록된 연락처는 콘솔의 <b>수신자</b> 화면에 바로 나타나고, 플랜의 연락처 한도에 포함됩니다.</li>
          </ul>
        </article>
      </main>

      <footer style={{ borderTop: "1px solid var(--op-border)", padding: "22px 24px", textAlign: "center",
                       fontSize: 12.5, color: "var(--op-faint)" }}>
        © 2026 Outpace · <Link to="/pricing" className="op-linkbtn" style={{ fontWeight: 600 }}>요금제</Link> ·{" "}
        <Link to="/terms" className="op-linkbtn" style={{ fontWeight: 600 }}>이용약관</Link> ·{" "}
        <Link to="/privacy" className="op-linkbtn" style={{ fontWeight: 600 }}>개인정보처리방침</Link> ·{" "}
        <a className="op-linkbtn" style={{ fontWeight: 600 }} href="mailto:support@outpacemail.com?subject=Outpace 문의">문의</a>
      </footer>
    </div>
  );
}
