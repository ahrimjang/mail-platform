import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import type { OpsCampaignRow, OpsWorkspaceDetail } from "../types";
import { BounceBadge, CampaignTable, PLAN_LABEL, ReasonModal, fmtDate, fmtDateTime, opsAction } from "../outpace/ops";

/* 운영자용 워크스페이스 상세 — 정지/해제·플랜 변경·API 키 폐기와 멤버·최근 캠페인.
   모든 조치는 사유가 필수이고, 결과는 즉시 다시 조회해 반영한다. */

type Action = "suspend" | "unsuspend" | "plan" | "revokeKey" | null;

export default function OpsWorkspace() {
  const { id } = useParams();
  const nav = useNavigate();
  const [d, setD] = useState<OpsWorkspaceDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [action, setAction] = useState<Action>(null);
  const [aborting, setAborting] = useState<OpsCampaignRow | null>(null);

  const reload = useCallback(async () => {
    try {
      const res = await api(`/api/ops/workspaces/${id}`);
      if (res.ok) { setD(await res.json()); setError(null); return; }
      const body = await res.json().catch(() => ({}));
      setError(body.error ?? (res.status === 403 ? "플랫폼 운영자 권한이 필요합니다." : `조회 실패 (${res.status})`));
    } catch { setError("요청 중 오류가 발생했습니다."); }
  }, [id]);
  useEffect(() => { reload(); }, [reload]);

  if (error) {
    return (
      <div className="op-container-mid op-fade">
        <button className="op-back" onClick={() => nav("/ops")}>← 운영</button>
        <div className="op-card op-card-pad" style={{ color: "var(--op-red)" }}>{error}</div>
      </div>
    );
  }
  if (!d) return <div className="op-container op-fade"><p className="faint">불러오는 중…</p></div>;

  const w = d.summary;
  const row = (k: string, v: React.ReactNode) => (
    <div className="op-inforow"><span className="k">{k}</span><span>{v}</span></div>
  );

  return (
    <div className="op-container op-fade">
      <button className="op-back" onClick={() => nav("/ops")}>← 운영</button>
      <div className="op-detail-head">
        <div>
          <div className="op-detail-title">
            <h1 style={{ margin: 0 }}>{w.name}</h1>
            {w.suspendedAt ? <span className="op-badge off">발송 정지</span> : <span className="op-badge on">정상</span>}
            <span className="op-minibadge blue">{PLAN_LABEL[w.plan] ?? w.plan}</span>
          </div>
          <p className="op-detail-meta">#{w.id} · 가입 {fmtDate(w.createdAt)} · 소유자 {w.ownerEmail ?? "–"}{w.ownerEmail && !w.ownerVerified ? " (이메일 미인증)" : ""}</p>
        </div>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", justifyContent: "flex-end" }}>
          {w.suspendedAt
            ? <button className="op-btn op-btn-sm" onClick={() => setAction("unsuspend")}>정지 해제</button>
            : <button className="op-btn op-btn-sm" style={{ background: "var(--op-red)" }} onClick={() => setAction("suspend")}>발송 정지</button>}
          <button className="op-btn op-btn-ghost op-btn-sm" onClick={() => setAction("plan")}>플랜 변경</button>
          {w.apiKeyIssued && <button className="op-btn op-btn-ghost op-btn-sm" onClick={() => setAction("revokeKey")}>API 키 폐기</button>}
        </div>
      </div>

      {w.suspendedAt && (
        <div className="op-card op-card-pad" style={{ marginBottom: 18, borderColor: "#fecaca", background: "#fff7f7" }}>
          <b style={{ color: "var(--op-red)" }}>정지 중</b> — {fmtDateTime(w.suspendedAt)} · {w.suspensionReason ?? "사유 없음"}
          <div className="faint" style={{ marginTop: 4, color: "var(--op-faint)", fontSize: 12.5 }}>
            자동 정지(7일 바운스율 10%↑)든 운영자 정지든 신규 캠페인 등록과 트랜잭셔널 발송이 막힙니다. 진행 중 캠페인은 끝까지 나갑니다.
          </div>
        </div>
      )}

      <div className="op-statcards">
        <div className="op-statcard"><div className="k">이번 달 발송</div>
          <div className="v">{w.monthlySent.toLocaleString()}<span className="faint" style={{ fontSize: 13, fontWeight: 500 }}> / {w.monthlySendLimit == null ? "∞" : w.monthlySendLimit.toLocaleString()}</span></div></div>
        <div className="op-statcard"><div className="k">30일 바운스율</div>
          <div className="v" style={{ display: "flex", alignItems: "center", gap: 8 }}><BounceBadge w={w} /><span className="faint" style={{ fontSize: 13, fontWeight: 500 }}>{w.bounced30d}/{w.attempted30d}</span></div></div>
        <div className="op-statcard"><div className="k">발송 속도</div>
          <div className="v">{d.sendRatePerSec ?? "∞"}<span className="faint" style={{ fontSize: 13, fontWeight: 500 }}> /초 (상한 {d.sendRateCap ?? "협의"})</span></div></div>
        <div className="op-statcard"><div className="k">멤버</div><div className="v">{w.memberCount}</div></div>
      </div>

      <div className="op-card" style={{ marginBottom: 18 }}>
        <div className="op-list-head"><b>계정·연동</b></div>
        <div className="op-infogrid">
          {row("결제 카드", w.billingRegistered ? "등록됨" : "미등록")}
          {row("구독 API 키", w.apiKeyIssued ? "발급됨" : "미발급")}
          {row("최근 활동", fmtDateTime(w.lastActivityAt))}
          {row("가입", fmtDateTime(w.createdAt))}
        </div>
      </div>

      <div className="op-card" style={{ marginBottom: 18 }}>
        <div className="op-list-head"><b>멤버</b><span className="faint" style={{ color: "var(--op-faint)", fontSize: 13 }}>{d.members.length}명</span></div>
        {d.members.map((m) => (
          <div key={m.id} className="op-list-row" style={{ fontSize: 13.5 }}>
            <span><span className="strong">{m.email}</span>{m.displayName && <span className="faint" style={{ marginLeft: 8 }}>{m.displayName}</span>}</span>
            <span><span className={`op-minibadge ${m.role === "ADMIN" ? "blue" : "gray"}`}>{m.role === "ADMIN" ? "관리자" : "운영자"}</span><span className="faint" style={{ marginLeft: 10 }}>{fmtDate(m.createdAt)}</span></span>
          </div>
        ))}
      </div>

      <h3 style={{ margin: "0 0 10px", fontSize: 15 }}>최근 캠페인 <span className="faint" style={{ fontWeight: 500, color: "var(--op-faint)" }}>— 최신 20건</span></h3>
      <CampaignTable rows={d.recentCampaigns} empty="아직 캠페인이 없어요." onAbort={setAborting} />

      {action === "suspend" && (
        <ReasonModal title="발송 정지" confirmLabel="정지" danger
          sub="신규 캠페인 등록과 트랜잭셔널 발송이 즉시 막힙니다. 사유는 테넌트 알림에 그대로 실려요."
          onClose={() => setAction(null)}
          onSubmit={async (reason) => { const e = await opsAction(`/api/ops/workspaces/${w.id}/suspend`, { reason }); if (!e) reload(); return e; }} />
      )}
      {action === "unsuspend" && (
        <ReasonModal title="정지 해제" confirmLabel="해제"
          sub="원인을 확인한 내용을 사유로 남겨주세요. 해제 즉시 캠페인 등록이 다시 열립니다."
          onClose={() => setAction(null)}
          onSubmit={async (reason) => { const e = await opsAction(`/api/ops/workspaces/${w.id}/unsuspend`, { reason }); if (!e) reload(); return e; }} />
      )}
      {action === "plan" && (
        <ReasonModal title="플랜 변경 (무결제)" confirmLabel="변경" planPicker currentPlan={w.plan}
          sub="결제 없이 적용됩니다 — 보상·체험·엔터프라이즈 계약용. 발송 속도 설정이 새 상한을 넘으면 상한으로 맞춰져요."
          onClose={() => setAction(null)}
          onSubmit={async (reason, plan) => { const e = await opsAction(`/api/ops/workspaces/${w.id}/plan`, { reason, plan }, "PUT"); if (!e) reload(); return e; }} />
      )}
      {action === "revokeKey" && (
        <ReasonModal title="구독 API 키 폐기" confirmLabel="폐기" danger
          sub="외부 구독 폼 연동이 즉시 끊깁니다. 테넌트는 관리 화면에서 다시 발급할 수 있어요."
          onClose={() => setAction(null)}
          onSubmit={async (reason) => { const e = await opsAction(`/api/ops/workspaces/${w.id}/api-key`, { reason }, "DELETE"); if (!e) reload(); return e; }} />
      )}
      {aborting && (
        <ReasonModal title={`캠페인 #${aborting.id} 발송 중단`} confirmLabel="중단" danger
          sub={`"${aborting.name ?? aborting.subject}" 의 남은 발송을 멈춥니다. 되돌릴 수 없습니다.`}
          onClose={() => setAborting(null)}
          onSubmit={async (reason) => { const e = await opsAction(`/api/ops/campaigns/${aborting.id}/abort`, { reason }); if (!e) reload(); return e; }} />
      )}
    </div>
  );
}
