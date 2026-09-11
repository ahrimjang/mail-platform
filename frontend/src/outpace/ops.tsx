import { useState } from "react";
import Portal from "../components/Portal";
import { api } from "../api";
import type { CampaignStatus, OpsCampaignRow, OpsWorkspaceRow } from "../types";
import { badgeClass } from "./format";

/* 플랫폼 운영자 화면 공용 조각 — 목록(/ops)과 상세(/ops/workspaces/:id)가 같이 쓴다. */

export const PLAN_LABEL: Record<string, string> = {
  STARTER: "스타터", STANDARD: "스탠다드", PRO: "프로", ENTERPRISE: "엔터프라이즈",
};

export const STATUS_LABEL: Record<CampaignStatus, string> = {
  DRAFT: "임시저장", QUEUED: "대기", EXPANDING: "수신자 확장 중", SENDING: "발송 중",
  COMPLETED: "완료", CANCELED: "취소·중단",
};

export const ACTION_LABEL: Record<string, string> = {
  SUSPEND: "발송 정지", UNSUSPEND: "정지 해제", CHANGE_PLAN: "플랜 변경",
  REVOKE_API_KEY: "API 키 폐기", ABORT_CAMPAIGN: "캠페인 중단",
};

export const fmtDate = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleDateString("ko-KR", { year: "2-digit", month: "short", day: "numeric" }) : "–";

export const fmtDateTime = (iso: string | null | undefined) =>
  iso
    ? new Date(iso).toLocaleDateString("ko-KR", { month: "short", day: "numeric" }) + " " +
      new Date(iso).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })
    : "–";

export const pct = (v: number) => `${(v * 100).toFixed(1)}%`;

/** 30일 바운스율(0~1) — 시도가 없으면 0. 서버 record 의 파생 메서드는 직렬화되지 않아 여기서 계산. */
export const bounceRate = (w: Pick<OpsWorkspaceRow, "attempted30d" | "bounced30d">) =>
  w.attempted30d === 0 ? 0 : w.bounced30d / w.attempted30d;

/** 30일 바운스율 배지 — 자동 정지 임계(10%)의 절반부터 경고색. */
export function BounceBadge({ w }: { w: Pick<OpsWorkspaceRow, "attempted30d" | "bounced30d"> }) {
  if (w.attempted30d === 0) return <span className="op-minibadge gray">발송 없음</span>;
  const rate = bounceRate(w);
  const cls = rate >= 0.1 ? "red" : rate >= 0.05 ? "amber" : "green";
  return <span className={`op-minibadge ${cls}`}>{pct(rate)}</span>;
}

/** 사유를 받는 조치 모달 — 정지/해제/중단/키 폐기/플랜 변경 전부 이 하나로. */
export function ReasonModal({
  title, sub, confirmLabel, danger, planPicker, currentPlan, onClose, onSubmit,
}: {
  title: string;
  sub: string;
  confirmLabel: string;
  danger?: boolean;
  planPicker?: boolean;
  currentPlan?: string;
  onClose: () => void;
  onSubmit: (reason: string, plan?: string) => Promise<string | null>;  // 오류 문구 또는 null
}) {
  const [reason, setReason] = useState("");
  const [plan, setPlan] = useState(currentPlan === "STARTER" ? "STANDARD" : "STARTER");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!reason.trim()) { setError("사유를 입력해주세요."); return; }
    setBusy(true);
    setError(null);
    const err = await onSubmit(reason.trim(), planPicker ? plan : undefined);
    setBusy(false);
    if (err) setError(err); else onClose();
  }

  return (
    <Portal>
      <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
        <div className="op-modal">
          <h3>{title}</h3>
          <p className="op-modal-sub">{sub}</p>
          {planPicker && (
            <label className="op-field">
              <span className="op-flabel">새 플랜</span>
              <select className="op-input" value={plan} onChange={(e) => setPlan(e.target.value)}>
                {Object.entries(PLAN_LABEL).map(([k, v]) => (
                  <option key={k} value={k} disabled={k === currentPlan}>{v}{k === currentPlan ? " (현재)" : ""}</option>
                ))}
              </select>
            </label>
          )}
          <label className="op-field">
            <span className="op-flabel">사유 (감사 로그·테넌트 알림에 남습니다)</span>
            <textarea className="op-input" rows={3} value={reason} onChange={(e) => setReason(e.target.value)}
                      placeholder="예: 스팸 신고 3건 접수 — 명단 출처 확인 전까지 정지" style={{ resize: "vertical" }} />
          </label>
          {error && <div className="op-modal-error">{error}</div>}
          <div className="op-modal-foot">
            <button className="op-btn op-btn-ghost op-btn-sm" onClick={onClose} disabled={busy}>취소</button>
            <button className="op-btn op-btn-sm" onClick={submit} disabled={busy}
                    style={danger ? { background: "var(--op-red)" } : undefined}>
              {busy ? "처리 중…" : confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </Portal>
  );
}

/** 조치 API 호출 — 오류 문구를 돌려주고 성공이면 null. */
export async function opsAction(path: string, body: Record<string, unknown>, method = "POST"): Promise<string | null> {
  try {
    const res = await api(path, { method, body: JSON.stringify(body) });
    if (res.ok) return null;
    const d = await res.json().catch(() => ({}));
    return d.error ?? `요청 실패 (${res.status})`;
  } catch {
    return "요청 중 오류가 발생했습니다.";
  }
}

const CAMPAIGN_COLS = "minmax(180px, 2fr) minmax(120px, 1.2fr) 110px 90px 90px 90px 120px";

/** 전 테넌트 캠페인 표 — 검색·상세·신호 패널이 공유. 행 클릭은 워크스페이스 상세로. */
export function CampaignTable({
  rows, onAbort, onOpenWorkspace, empty,
}: {
  rows: OpsCampaignRow[];
  onAbort?: (c: OpsCampaignRow) => void;
  onOpenWorkspace?: (workspaceId: number) => void;
  empty: string;
}) {
  return (
    <div className="op-card">
      <div className="op-thead" style={{ gridTemplateColumns: CAMPAIGN_COLS }}>
        <span>캠페인</span><span>워크스페이스</span><span>상태</span>
        <span>대상</span><span>발송</span><span>실패·반송</span><span>등록</span>
      </div>
      {rows.length === 0 && <div className="op-import-empty">{empty}</div>}
      {rows.map((c) => {
        const abortable = c.status === "QUEUED" || c.status === "EXPANDING" || c.status === "SENDING";
        return (
          <div key={c.id} className="op-trow" style={{ gridTemplateColumns: CAMPAIGN_COLS }}>
            <span style={{ minWidth: 0 }}>
              <span className="strong" style={{ display: "block", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {c.name ?? c.subject}
              </span>
              <span className="faint">#{c.id} · {c.createdBy ?? "시스템"}</span>
            </span>
            <span style={{ minWidth: 0 }}>
              {onOpenWorkspace
                ? <button className="op-linkbtn" style={{ fontSize: 13 }} onClick={() => onOpenWorkspace(c.workspaceId)}>
                    {c.workspaceName ?? `#${c.workspaceId}`}
                  </button>
                : (c.workspaceName ?? `#${c.workspaceId}`)}
            </span>
            <span><span className={`op-badge ${badgeClass(c.status)}`}>{STATUS_LABEL[c.status]}</span></span>
            <span>{c.total.toLocaleString()}</span>
            <span>{c.sent.toLocaleString()}</span>
            <span className={c.failed + c.bounced > 0 ? "strong" : ""}>{(c.failed + c.bounced).toLocaleString()}</span>
            <span style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 6 }}>
              <span className="faint">{fmtDateTime(c.createdAt)}</span>
              {onAbort && abortable && (
                <button className="op-linkbtn" style={{ fontSize: 12.5, color: "var(--op-red)" }} onClick={() => onAbort(c)}>중단</button>
              )}
            </span>
          </div>
        );
      })}
    </div>
  );
}
