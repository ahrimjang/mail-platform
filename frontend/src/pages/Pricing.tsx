import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../outpace/auth";
import type { PlanView, WorkspaceView } from "../types";

/* 요금제 페이지 — 비로그인도 접근(공개 /api/plans). 로그인 상태면 현재 플랜을
   표시하고 이 자리에서 바로 변경(상향은 즉시 결제)까지 이어진다.
   헤더는 콘솔 셸(op-topnav)과 같은 프레임을 쓴다 — 별도 셸 없이 로고+CTA만. */

const PLAN_META: Record<string, { label: string; tagline: string; features: string[] }> = {
  STARTER: {
    label: "스타터",
    tagline: "이메일 제작부터 발송까지, 뉴스레터를 시작하고 싶을 때",
    features: [
      "드래그앤드롭 블록 에디터 + 기본 템플릿",
      "연락처·리스트 관리 (CSV 가져오기)",
      "오픈/클릭 추적과 분석 대시보드",
      "예약 발송·발송 취소",
      "수신거부·구독 관리 자동 처리",
    ],
  },
  STANDARD: {
    label: "스탠다드",
    tagline: "뉴스레터를 정기적으로 보내는 팀",
    features: [
      "스타터의 전부, 그리고",
      "참여도 세그먼트 (오픈/클릭률 타겟팅)",
      "A/B 테스트 (제목 반반 발송)",
      "팀 멤버 3명",
    ],
  },
  PRO: {
    label: "프로",
    tagline: "발송량과 정교함이 모두 필요할 때",
    features: [
      "스탠다드의 전부, 그리고",
      "A/B 테스트 본문·템플릿 + 승자 자동발송",
      "발송 속도 최대 50통/초",
      "팀 멤버 10명",
    ],
  },
  ENTERPRISE: {
    label: "엔터프라이즈",
    tagline: "대규모 발송과 맞춤 지원",
    features: ["전 기능 무제한", "발송량·속도 협의", "전담 지원"],
  },
};

/* 비교 표 — 수치 행은 서버 plans 값으로 채우고, 기능 행은 게이팅 정책 그대로. */
const FEATURE_ROWS: { label: string; value: (p: PlanView) => string }[] = [
  { label: "월 발송량", value: (p) => (p.monthlySendLimit != null ? `${p.monthlySendLimit.toLocaleString()}통` : "협의") },
  { label: "연락처", value: (p) => (p.contactLimit != null ? `${p.contactLimit.toLocaleString()}명` : "무제한") },
  { label: "팀 멤버", value: (p) => (p.memberLimit != null ? `${p.memberLimit}명` : "무제한") },
  { label: "발송 속도", value: (p) => (p.sendRateCap != null ? `초당 ${p.sendRateCap}통` : "협의") },
  { label: "블록 에디터·템플릿", value: () => "✓" },
  { label: "오픈/클릭 추적·분석", value: () => "✓" },
  { label: "예약 발송", value: () => "✓" },
  { label: "A/B 테스트 (제목)", value: (p) => (p.name === "STARTER" ? "—" : "✓") },
  { label: "A/B 테스트 (본문·템플릿)", value: (p) => (p.name === "STARTER" || p.name === "STANDARD" ? "—" : "✓") },
  { label: "승자 자동발송", value: (p) => (p.name === "STARTER" || p.name === "STANDARD" ? "—" : "✓") },
  { label: "참여도 세그먼트", value: (p) => (p.name === "STARTER" ? "—" : "✓") },
];

const FAQS: { q: string; a: React.ReactNode }[] = [
  {
    q: "결제 전에 무료로 체험해볼 수 있나요?",
    a: "네. 스타터 요금제는 회원가입만 하면 카드 등록 없이 바로 쓸 수 있어요. 이메일 제작·발송·추적 등 핵심 기능이 모두 포함되어 있으니, 먼저 보내보고 발송량이 늘어나면 그때 올리세요.",
  },
  {
    q: "구독자 수 기준 요금제와 뭐가 다른가요?",
    a: "대부분의 이메일 서비스는 보유 구독자 수로 요금 구간이 정해져서, 한 통도 안 보낸 달에도 명단 크기만큼 냅니다. Outpace 는 한 달 동안 실제로 발송한 통수 기준이에요. 예를 들어 구독자 2만 명에게 월 1회 공지를 보내면, 그 달 발송량 2만 통에 해당하는 플랜이면 충분하고 안 보내는 달엔 무료 플랜으로도 됩니다. 연락처 수는 플랜별 보관 한도일 뿐 청구 기준이 아니에요.",
  },
  {
    q: "월 발송량 한도에 도달하면 어떻게 되나요?",
    a: "새 캠페인 등록만 잠기고, 이미 발송 중인 캠페인은 끝까지 나갑니다. 발송이 중간에 끊기는 일은 없어요. 한도의 80%에 도달하면 미리 안내해드리고, 상향하면 즉시 새 한도가 적용돼 차단이 풀립니다.",
  },
  {
    q: "결제는 어떻게 하나요?",
    a: (
      <>관리 페이지에서 카드를 한 번 등록하면(토스페이먼츠), 플랜 상향 시 월정액이 즉시 결제되고
      바로 적용돼요. 카드 정보는 저희 서버에 저장되지 않습니다. 결제 이력은{" "}
      <Link to="/settings">관리 페이지</Link>에서 확인할 수 있어요.</>
    ),
  },
  {
    q: "플랜을 내리면 어떻게 되나요?",
    a: "하향은 결제 없이 적용됩니다. 다만 새 플랜의 한도(연락처 수 등)를 이미 넘겨 쓰고 있으면 정리 후 하향할 수 있고, 발송 속도는 새 플랜의 상한으로 조정돼요.",
  },
  {
    q: "환불 정책이 어떻게 되나요?",
    a: "결제 후 해당 주기에 발송한 메일이 없다면 7일 이내 전액 환불해드려요. 그 외의 경우는 사용량에 따라 달라지니 메일로 문의해주세요.",
  },
];

export default function Pricing() {
  const { token } = useAuth();
  const navigate = useNavigate();
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    const res = await fetch("/api/plans");
    if (res.ok) setPlans(await res.json());
    if (token) {
      const wRes = await api("/api/workspace");
      if (wRes.ok) setWorkspace(await wRes.json());
    }
  }, [token]);

  useEffect(() => { refresh(); }, [refresh]);

  async function choose(plan: PlanView) {
    setNotice(null);
    if (plan.name === "ENTERPRISE") {
      window.location.href = "mailto:support@outpacemail.com?subject=Outpace 엔터프라이즈 도입 문의";
      return;
    }
    if (!token) {
      navigate("/signup");
      return;
    }
    setBusy(true);
    try {
      const res = await api("/api/billing/plan", { method: "POST", body: JSON.stringify({ plan: plan.name }) });
      if (res.ok) {
        setNotice(`${PLAN_META[plan.name].label} 플랜으로 변경됐어요.`);
        refresh();
      } else {
        const data = await res.json().catch(() => ({}));
        setNotice(data.error ?? "플랜 변경에 실패했습니다.");
      }
    } catch {
      setNotice("요청 중 오류가 발생했습니다.");
    } finally {
      setBusy(false);
    }
  }

  function priceLine(p: PlanView) {
    if (p.monthlyPriceKrw == null) return "협의";
    if (p.monthlyPriceKrw === 0) return "무료";
    return `월 ${p.monthlyPriceKrw.toLocaleString()}원`;
  }

  function ctaLabel(p: PlanView) {
    if (p.name === "ENTERPRISE") return "도입 문의";
    if (!token) return p.monthlyPriceKrw === 0 ? "무료로 시작하기" : "가입하고 시작하기";
    if (workspace?.plan === p.name) return "사용 중";
    return workspace && p.name !== "ENTERPRISE"
      ? (plans.findIndex((x) => x.name === p.name) > plans.findIndex((x) => x.name === workspace.plan)
          ? "이 플랜으로 상향 (즉시 결제)" : "이 플랜으로 변경")
      : "선택";
  }

  return (
    <div className="op-root" style={{ minHeight: "100vh" }}>
      {/* 콘솔 셸과 같은 topnav 프레임 — 공개 페이지라 로고 + CTA만 얹는다 */}
      <header className="op-topnav">
        <div className="op-topnav-left">
          <div className="op-logo" onClick={() => navigate("/")}>
            <div className="op-logo-badge"><span className="tri" /></div>
            <span>Outpace</span>
          </div>
        </div>
        <div className="op-topnav-right">
          {token
            ? <Link className="op-btn op-btn-sm op-btn-ghost" to="/">대시보드로</Link>
            : <>
                <Link className="op-btn op-btn-sm op-btn-ghost" to="/login">로그인</Link>
                <Link className="op-btn op-btn-sm" to="/signup">무료로 시작</Link>
              </>}
        </div>
      </header>

      <main className="op-container op-fade" style={{ padding: "44px 24px 64px" }}>
        {/* 히어로 */}
        <div style={{ textAlign: "center", marginBottom: 36 }}>
          <h2 style={{ fontSize: 30, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>
            구독자 수가 아니라, 보낸 만큼만
          </h2>
          <p style={{ color: "var(--op-muted)", marginTop: 10, fontSize: 15, lineHeight: 1.7 }}>
            요금 기준은 <b>월 발송량</b>입니다. 구독자가 아무리 많아도 안 보내는 달엔
            요금이 늘지 않아요.<br />
            무료로 시작하고, 실제로 보내는 양이 늘면 그때 올리세요 — 한도에 닿아도
            보내던 캠페인은 끝까지 나갑니다.
          </p>
          {notice && <p style={{ marginTop: 12, fontSize: 13.5, color: "var(--op-accent, #4f46e5)" }}>{notice}</p>}
        </div>

        {/* 플랜 카드 */}
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))", gap: 16 }}>
          {plans.map((p) => {
            const meta = PLAN_META[p.name];
            const current = workspace?.plan === p.name;
            const highlight = p.name === "STANDARD";
            return (
              <div key={p.name} className="op-card op-card-pad"
                   style={{ display: "flex", flexDirection: "column", gap: 10,
                            border: highlight ? "2px solid var(--op-accent, #4f46e5)" : undefined,
                            position: "relative" }}>
                {highlight && (
                  <span style={{ position: "absolute", top: -11, left: "50%", transform: "translateX(-50%)",
                                 fontSize: 11.5, fontWeight: 700, padding: "2px 10px", borderRadius: 999,
                                 background: "var(--op-accent, #4f46e5)", color: "#fff" }}>가장 인기</span>
                )}
                <div>
                  <h3 style={{ margin: 0, fontSize: 17 }}>{meta.label}
                    {current && <span style={{ marginLeft: 8, fontSize: 11.5, color: "var(--op-accent, #4f46e5)" }}>현재 플랜</span>}
                  </h3>
                  <p style={{ margin: "4px 0 0", fontSize: 12.5, color: "var(--op-muted)", minHeight: 34 }}>{meta.tagline}</p>
                </div>
                <div style={{ fontSize: 26, fontWeight: 800 }}>{priceLine(p)}</div>
                <div style={{ fontSize: 13, color: "var(--op-muted)", lineHeight: 1.9 }}>
                  <div><b style={{ color: "inherit" }}>{p.monthlySendLimit != null ? `월 ${p.monthlySendLimit.toLocaleString()}통` : "발송량 협의"}</b></div>
                  <div>연락처 {p.contactLimit != null ? `${p.contactLimit.toLocaleString()}명` : "무제한"}</div>
                  <div>멤버 {p.memberLimit != null ? `${p.memberLimit}명` : "무제한"}</div>
                  <div>발송 속도 {p.sendRateCap != null ? `초당 ${p.sendRateCap}통` : "협의"}</div>
                </div>
                <ul style={{ margin: 0, padding: "0 0 0 18px", fontSize: 12.5, color: "var(--op-faint)", lineHeight: 1.8, flex: 1 }}>
                  {meta.features.map((f) => <li key={f}>{f}</li>)}
                </ul>
                <button
                  className={highlight ? "op-btn" : "op-btn op-btn-ghost"}
                  disabled={busy || current}
                  onClick={() => choose(p)}
                  style={{ width: "100%" }}>
                  {ctaLabel(p)}
                </button>
              </div>
            );
          })}
        </div>

        <p style={{ textAlign: "center", marginTop: 22, fontSize: 12.5, color: "var(--op-faint)", lineHeight: 1.8 }}>
          상향은 새 플랜 월정액을 즉시 결제하고 바로 적용돼요 (카드 미등록이면 설정에서 먼저 등록).
          하향은 결제 없이 적용되며, 새 플랜 한도를 넘겨 쓰고 있으면 정리 후 가능합니다.
        </p>

        {/* 한눈 비교 표 */}
        {plans.length > 0 && (
          <section style={{ marginTop: 56 }}>
            <h3 style={{ textAlign: "center", fontSize: 21, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 20px" }}>
              플랜 한눈에 비교하기
            </h3>
            <div className="op-card" style={{ overflowX: "auto" }}>
              <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13.5, minWidth: 640 }}>
                <thead>
                  <tr>
                    <th style={{ textAlign: "left", padding: "14px 18px", borderBottom: "1px solid var(--op-border)" }} />
                    {plans.map((p) => (
                      <th key={p.name}
                          style={{ padding: "14px 12px", borderBottom: "1px solid var(--op-border)",
                                   fontWeight: 800, color: p.name === "STANDARD" ? "var(--op-accent, #4f46e5)" : "inherit" }}>
                        {PLAN_META[p.name].label}
                        <div style={{ fontSize: 12, fontWeight: 600, color: "var(--op-muted)", marginTop: 2 }}>{priceLine(p)}</div>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {FEATURE_ROWS.map((row, i) => (
                    <tr key={row.label} style={{ background: i % 2 ? "var(--op-page, #fbfbfc)" : undefined }}>
                      <td style={{ padding: "11px 18px", color: "var(--op-muted)", whiteSpace: "nowrap" }}>{row.label}</td>
                      {plans.map((p) => (
                        <td key={p.name} style={{ padding: "11px 12px", textAlign: "center",
                                                  color: row.value(p) === "—" ? "var(--op-faint)" : "inherit" }}>
                          {row.value(p)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {/* 자주 묻는 질문 */}
        <section style={{ marginTop: 56, maxWidth: 760, marginLeft: "auto", marginRight: "auto" }}>
          <h3 style={{ textAlign: "center", fontSize: 21, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 20px" }}>
            자주 묻는 질문
          </h3>
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            {FAQS.map((f) => (
              <details key={f.q} className="op-card" style={{ padding: "0 18px" }}>
                <summary style={{ cursor: "pointer", fontWeight: 700, fontSize: 14.5, padding: "15px 0", listStyle: "none" }}>
                  {f.q}
                </summary>
                <p style={{ margin: "0 0 16px", fontSize: 13.5, color: "var(--op-muted)", lineHeight: 1.8 }}>{f.a}</p>
              </details>
            ))}
          </div>
          <p style={{ textAlign: "center", marginTop: 18, fontSize: 13, color: "var(--op-faint)" }}>
            더 궁금하신 게 있으신가요?{" "}
            <a className="op-linkbtn" href="mailto:support@outpacemail.com?subject=Outpace 문의">메일로 물어보세요</a>
          </p>
        </section>

        {/* 마무리 CTA */}
        <section className="op-card" style={{ marginTop: 56, padding: "40px 24px", textAlign: "center" }}>
          <h3 style={{ margin: 0, fontSize: 21, fontWeight: 800, letterSpacing: "-0.02em" }}>
            일단 무료로 보내보세요
          </h3>
          <p style={{ color: "var(--op-muted)", margin: "10px 0 20px", fontSize: 14 }}>
            카드 등록 없이 가입 즉시 월 1,000통을 보낼 수 있어요. 올릴지는 그다음에 정하세요.
          </p>
          <div style={{ display: "flex", gap: 10, justifyContent: "center", flexWrap: "wrap" }}>
            {token
              ? <Link className="op-btn op-btn-sm" to="/">대시보드로 가기</Link>
              : <Link className="op-btn op-btn-sm" to="/signup">무료로 시작하기</Link>}
            <a className="op-btn op-btn-sm op-btn-ghost" href="mailto:support@outpacemail.com?subject=Outpace 엔터프라이즈 도입 문의">도입 문의</a>
          </div>
        </section>
      </main>
    </div>
  );
}
