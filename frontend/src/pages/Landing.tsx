import { Link, useNavigate } from "react-router-dom";

/* 로그인 전 첫 화면 — 서비스 소개 랜딩. 콘솔 셸과 같은 op-topnav 프레임을 쓰고,
   기능 설명은 실제 구현된 것만 담는다(과장 금지). */

const FEATURES: { icon: string; title: string; desc: string }[] = [
  {
    icon: "🚀",
    title: "큐 기반 대량 발송",
    desc: "캠페인을 등록하면 즉시 접수되고, 발송은 백그라운드 워커가 배치로 처리해요. 수만 통을 걸어도 화면이 멈추지 않습니다.",
  },
  {
    icon: "📈",
    title: "오픈·클릭 추적과 분석",
    desc: "누가 열고 눌렀는지 실시간으로 집계돼요. 참여 퍼널, 링크별 클릭 랭킹, 시간대별 오픈 히트맵까지 한 화면에서.",
  },
  {
    icon: "🧪",
    title: "A/B 테스트 + 승자 자동발송",
    desc: "제목이나 본문을 두 가지로 보내 반응을 비교하고, 참여율이 높은 쪽을 나머지 수신자에게 자동으로 발송합니다.",
  },
  {
    icon: "🎯",
    title: "참여도 세그먼트",
    desc: "최근 오픈율·클릭률로 수신자를 좁혀서 보낼 수 있어요. 잘 읽는 사람에게 더 자주, 조용한 사람에겐 덜 자주.",
  },
  {
    icon: "🧱",
    title: "블록 에디터와 템플릿",
    desc: "드래그앤드롭으로 이메일을 만들고, 빌트인 템플릿으로 바로 시작하세요. {{이름}} 같은 변수로 개인화도 됩니다.",
  },
  {
    icon: "🛡️",
    title: "수신거부·바운스 자동 처리",
    desc: "수신거부는 링크 클릭 즉시 반영되고, 반송(바운스)된 주소는 자동으로 걸러져요. 다시는 그 주소로 나가지 않습니다.",
  },
];

const STEPS: { n: string; title: string; desc: string }[] = [
  { n: "1", title: "가입하고 워크스페이스 만들기", desc: "이메일만 있으면 끝. 카드 등록 없이 바로 시작해요." },
  { n: "2", title: "연락처 가져오기", desc: "CSV 한 장으로 수신자 리스트를 올리면 동의 기록까지 함께 남아요." },
  { n: "3", title: "캠페인 발송", desc: "템플릿 고르고, 내게 먼저 테스트 발송해보고, 지금 또는 예약으로 보내세요." },
];

export default function Landing() {
  const navigate = useNavigate();

  return (
    <div className="op-root" style={{ minHeight: "100vh" }}>
      <header className="op-topnav">
        <div className="op-topnav-left">
          <div className="op-logo" onClick={() => navigate("/")}>
            <div className="op-logo-badge"><span className="tri" /></div>
            <span>Outpace</span>
          </div>
        </div>
        <div className="op-topnav-right">
          <Link className="op-btn op-btn-sm op-btn-ghost" to="/pricing">요금제</Link>
          <Link className="op-btn op-btn-sm op-btn-ghost" to="/login">로그인</Link>
          <Link className="op-btn op-btn-sm" to="/signup">무료로 시작</Link>
        </div>
      </header>

      <main className="op-fade">
        {/* 히어로 */}
        <section style={{ textAlign: "center", padding: "84px 24px 64px" }}>
          <h1 style={{ fontSize: 42, fontWeight: 800, letterSpacing: "-0.03em", margin: 0, lineHeight: 1.25 }}>
            대량 이메일을<br />안정적으로, 빠르게.
          </h1>
          <p style={{ color: "var(--op-muted)", fontSize: 16.5, lineHeight: 1.75, margin: "18px auto 0", maxWidth: 560 }}>
            Outpace는 뉴스레터부터 수만 통짜리 캠페인까지 밀어내는 이메일 마케팅
            플랫폼이에요. 등록은 즉시, 발송은 백그라운드에서 — 열람·클릭까지
            자동으로 따라옵니다.
          </p>
          <div style={{ display: "flex", gap: 10, justifyContent: "center", marginTop: 28, flexWrap: "wrap" }}>
            <Link className="op-btn op-btn-sm" to="/signup">무료로 시작하기</Link>
            <Link className="op-btn op-btn-sm op-btn-ghost" to="/pricing">요금제 보기</Link>
          </div>
          <p style={{ marginTop: 14, fontSize: 12.5, color: "var(--op-faint)" }}>
            카드 등록 없이 가입 즉시 월 1,000통 무료
          </p>
        </section>

        {/* 핵심 기능 */}
        <section className="op-container" style={{ padding: "0 24px 64px" }}>
          <h2 style={{ textAlign: "center", fontSize: 24, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 26px" }}>
            보내는 일의 전부를 한곳에서
          </h2>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", gap: 16 }}>
            {FEATURES.map((f) => (
              <div key={f.title} className="op-card op-card-pad">
                <div style={{ fontSize: 26, marginBottom: 10 }}>{f.icon}</div>
                <h3 style={{ margin: "0 0 6px", fontSize: 16 }}>{f.title}</h3>
                <p style={{ margin: 0, fontSize: 13.5, color: "var(--op-muted)", lineHeight: 1.75 }}>{f.desc}</p>
              </div>
            ))}
          </div>
        </section>

        {/* 시작 3단계 */}
        <section style={{ background: "var(--op-panel)", padding: "56px 24px" }}>
          <div className="op-container">
            <h2 style={{ textAlign: "center", fontSize: 24, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 26px" }}>
              5분이면 첫 캠페인이 나갑니다
            </h2>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))", gap: 16 }}>
              {STEPS.map((s) => (
                <div key={s.n} className="op-card op-card-pad" style={{ textAlign: "center" }}>
                  <div style={{ width: 34, height: 34, borderRadius: "50%", background: "var(--op-grad-primary)",
                                color: "#fff", fontWeight: 800, display: "flex", alignItems: "center",
                                justifyContent: "center", margin: "0 auto 12px" }}>{s.n}</div>
                  <h3 style={{ margin: "0 0 6px", fontSize: 15.5 }}>{s.title}</h3>
                  <p style={{ margin: 0, fontSize: 13, color: "var(--op-muted)", lineHeight: 1.7 }}>{s.desc}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* 요금 티저 + 마무리 CTA */}
        <section style={{ textAlign: "center", padding: "72px 24px 80px" }}>
          <h2 style={{ fontSize: 24, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>
            일단 무료로 보내보세요
          </h2>
          <p style={{ color: "var(--op-muted)", margin: "12px auto 0", fontSize: 14.5, maxWidth: 480, lineHeight: 1.7 }}>
            스타터는 무료로 월 1,000통. 발송량이 늘어나면 그때 요금제를 올리면 돼요.
          </p>
          <div style={{ display: "flex", gap: 10, justifyContent: "center", marginTop: 24, flexWrap: "wrap" }}>
            <Link className="op-btn op-btn-sm" to="/signup">무료로 시작하기</Link>
            <a className="op-btn op-btn-sm op-btn-ghost"
               href="mailto:ahrim1220@gmail.com?subject=Outpace 도입 문의">도입 문의</a>
          </div>
        </section>
      </main>

      <footer style={{ borderTop: "1px solid var(--op-border)", padding: "22px 24px", textAlign: "center",
                       fontSize: 12.5, color: "var(--op-faint)" }}>
        © 2026 Outpace · <Link to="/pricing" className="op-linkbtn" style={{ fontWeight: 600 }}>요금제</Link> ·{" "}
        <Link to="/terms" className="op-linkbtn" style={{ fontWeight: 600 }}>이용약관</Link> ·{" "}
        <Link to="/privacy" className="op-linkbtn" style={{ fontWeight: 600 }}>개인정보처리방침</Link> ·{" "}
        <a className="op-linkbtn" style={{ fontWeight: 600 }} href="mailto:ahrim1220@gmail.com?subject=Outpace 문의">문의</a>
      </footer>
    </div>
  );
}
