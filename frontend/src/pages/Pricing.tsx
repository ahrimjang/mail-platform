import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../outpace/auth";
import type { PlanView, WorkspaceView } from "../types";

/* 요금제 페이지 — 비로그인도 접근(공개 /api/plans). 로그인 상태면 현재 플랜을
   표시하고 이 자리에서 바로 변경(상향은 즉시 결제)까지 이어진다. */

const PLAN_META: Record<string, { label: string; tagline: string; features: string[] }> = {
  STARTER: {
    label: "스타터",
    tagline: "무료로 시작하고, 보내보고 결정하세요",
    features: ["캠페인·템플릿·수신자 관리 전체", "오픈/클릭 추적과 분석 대시보드", "예약 발송"],
  },
  STANDARD: {
    label: "스탠다드",
    tagline: "뉴스레터를 정기적으로 보내는 팀",
    features: ["스타터의 전부, 그리고", "참여도 세그먼트 (오픈/클릭률 타겟팅)", "A/B 테스트 (제목)", "팀 멤버 3명"],
  },
  PRO: {
    label: "프로",
    tagline: "발송량과 정교함이 모두 필요할 때",
    features: ["스탠다드의 전부, 그리고", "A/B 테스트 전체 + 승자 자동발송", "발송 속도 최대 50통/초", "팀 멤버 10명"],
  },
  ENTERPRISE: {
    label: "엔터프라이즈",
    tagline: "대규모 발송과 맞춤 지원",
    features: ["전 기능 무제한", "발송량·속도 협의", "전담 지원"],
  },
};

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
      window.location.href = "mailto:ahrim1220@gmail.com?subject=Outpace 엔터프라이즈 도입 문의";
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
    <div style={{ minHeight: "100vh", background: "var(--op-bg, #f8fafc)" }}>
      {/* 독립 페이지라 셸 없이 얇은 헤더만 */}
      <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center",
                       padding: "18px 32px", borderBottom: "1px solid var(--op-line, #e5e7eb)", background: "#fff" }}>
        <Link to="/" style={{ fontWeight: 800, fontSize: 18, textDecoration: "none", color: "inherit" }}>Outpace</Link>
        <nav style={{ display: "flex", gap: 14 }}>
          {token
            ? <Link className="op-btn op-btn-sm op-btn-ghost" to="/">대시보드로</Link>
            : <>
                <Link className="op-btn op-btn-sm op-btn-ghost" to="/login">로그인</Link>
                <Link className="op-btn op-btn-sm" to="/signup">무료로 시작</Link>
              </>}
        </nav>
      </header>

      <div className="op-container op-fade" style={{ maxWidth: 1100, margin: "0 auto", padding: "40px 24px" }}>
        <div style={{ textAlign: "center", marginBottom: 32 }}>
          <h2 style={{ fontSize: 28, margin: 0 }}>성장 단계에 맞는 요금제</h2>
          <p style={{ color: "var(--op-muted)", marginTop: 8 }}>
            무료로 시작하고, 발송량이 늘면 그때 올리세요. 요금은 월 발송량 기준 — 한도에 닿으면
            새 캠페인 등록만 잠기고, 보내던 캠페인은 끝까지 나갑니다.
          </p>
          {notice && <p style={{ marginTop: 12, fontSize: 13.5, color: "var(--op-accent, #4f46e5)" }}>{notice}</p>}
        </div>

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

        <p style={{ textAlign: "center", marginTop: 28, fontSize: 12.5, color: "var(--op-faint)", lineHeight: 1.8 }}>
          상향은 새 플랜 월정액을 즉시 결제하고 바로 적용돼요 (카드 미등록이면 설정에서 먼저 등록).
          하향은 결제 없이 적용되며, 새 플랜 한도를 넘겨 쓰고 있으면 정리 후 가능합니다.
          결제 카드 관리와 이력은 <Link to="/settings">관리 페이지</Link>에서.
        </p>
      </div>
    </div>
  );
}
