import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import Portal from "../components/Portal";
import { useAuth } from "../outpace/auth";
import type { PaymentView, UsageSnapshotView, WorkspaceUserView, WorkspaceView } from "../types";

/* 토스 빌링 위젯 SDK 를 필요할 때만 로드 — 카드 등록 버튼을 누를 때 한 번. */
declare global {
  interface Window { TossPayments?: (clientKey: string) => { requestBillingAuth: (method: string, opts: Record<string, string>) => void } }
}
function loadTossSdk(): Promise<void> {
  if (window.TossPayments) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = "https://js.tosspayments.com/v1/payment";
    s.onload = () => resolve();
    s.onerror = () => reject(new Error("toss sdk load failed"));
    document.head.appendChild(s);
  });
}

const ROLE_LABEL: Record<string, string> = { ADMIN: "관리자", OPERATOR: "운영자" };
const PLAN_LABEL: Record<string, string> = {
  STARTER: "스타터 (무료)", STANDARD: "스탠다드", PRO: "프로", ENTERPRISE: "엔터프라이즈",
};

function AddMemberModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState("OPERATOR");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const res = await api("/api/workspace/users", {
        method: "POST",
        body: JSON.stringify({ email, password, displayName: displayName || null, role }),
      });
      if (res.ok) { onSaved(); onClose(); return; }
      const data = await res.json().catch(() => ({}));
      setError(data.error ?? "멤버 추가에 실패했습니다.");
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Portal>
    <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="op-modal">
        <h3>멤버 추가</h3>
        <p className="op-modal-sub">이 워크스페이스에서 일할 계정을 만들어요. 관리자는 설정·멤버를, 운영자는 캠페인을 다룹니다.</p>
        <label className="op-field">
          <span className="op-flabel">이메일</span>
          <input className="op-input" type="email" placeholder="member@company.com" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <div className="op-grid2" style={{ marginBottom: 18 }}>
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">이름 (선택)</span>
            <input className="op-input" placeholder="김운영" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </label>
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">역할</span>
            <select className="op-input" value={role} onChange={(e) => setRole(e.target.value)}>
              <option value="OPERATOR">운영자 — 캠페인 운영</option>
              <option value="ADMIN">관리자 — 설정·멤버 관리</option>
            </select>
          </label>
        </div>
        <label className="op-field">
          <span className="op-flabel">초기 비밀번호</span>
          <input className="op-input" type="password" placeholder="••••••••" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && <div className="op-modal-error">{error}</div>}
        <div className="op-modal-foot">
          <button className="op-btn op-btn-sm op-btn-ghost" onClick={onClose}>취소</button>
          <button className="op-btn op-btn-sm" disabled={saving} onClick={save}>{saving ? "추가 중…" : "멤버 추가"}</button>
        </div>
      </div>
    </div>
    </Portal>
  );
}

export default function WorkspaceSettings() {
  const { role } = useAuth();
  const isAdmin = role === "ADMIN";
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [members, setMembers] = useState<WorkspaceUserView[]>([]);
  const [name, setName] = useState("");
  const [sendRate, setSendRate] = useState(""); // 건/초 텍스트; "" = 무제한
  const [usageHistory, setUsageHistory] = useState<UsageSnapshotView[]>([]);
  const [paymentHistory, setPaymentHistory] = useState<PaymentView[]>([]);
  const [planChoice, setPlanChoice] = useState("");
  const [billingBusy, setBillingBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const wRes = await api("/api/workspace");
      if (wRes.ok) {
        const w: WorkspaceView = await wRes.json();
        setWorkspace(w);
        setName(w.name);
        setSendRate(w.sendRatePerSec == null ? "" : String(w.sendRatePerSec));
      }
      const uRes = await api("/api/workspace/users");
      if (uRes.ok) setMembers(await uRes.json());
      const hRes = await api("/api/workspace/usage-history");
      if (hRes.ok) setUsageHistory(await hRes.json());
      const pRes = await api("/api/billing/payments");
      if (pRes.ok) setPaymentHistory(await pRes.json());
    } catch { /* transient */ }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  // 카드 등록 위젯이 successUrl 로 돌아온 경우 — authKey 를 빌링키로 교환
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const authKey = params.get("authKey");
    if (params.get("billing") !== "card" || !authKey) return;
    window.history.replaceState(null, "", "/settings");   // 새로고침 재실행 방지
    (async () => {
      const res = await api("/api/billing/card", { method: "POST", body: JSON.stringify({ authKey }) });
      if (res.ok) { setSavedAt(Date.now()); refresh(); }
      else setError((await res.json().catch(() => ({}))).error ?? "카드 등록에 실패했습니다.");
    })();
  }, [refresh]);

  async function registerCard() {
    setError(null);
    setBillingBusy(true);
    try {
      const cfgRes = await api("/api/billing/config");
      if (!cfgRes.ok) throw new Error();
      const cfg = await cfgRes.json();
      await loadTossSdk();
      // 위젯이 카드 인증 후 successUrl 로 authKey 를 붙여 돌아온다 (위 useEffect 가 마무리)
      window.TossPayments!(cfg.clientKey).requestBillingAuth("카드", {
        customerKey: cfg.customerKey,
        successUrl: `${window.location.origin}/settings?billing=card`,
        failUrl: `${window.location.origin}/settings?billing=fail`,
      });
    } catch {
      setError("결제 위젯을 여는 데 실패했습니다.");
      setBillingBusy(false);
    }
  }

  async function changePlan() {
    if (!planChoice) return;
    setError(null);
    setBillingBusy(true);
    try {
      const res = await api("/api/billing/plan", { method: "POST", body: JSON.stringify({ plan: planChoice }) });
      if (res.ok) { setPlanChoice(""); refresh(); }
      else setError((await res.json().catch(() => ({}))).error ?? "플랜 변경에 실패했습니다.");
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setBillingBusy(false);
    }
  }

  async function saveSettings() {
    setSaving(true);
    setError(null);
    try {
      const rate = sendRate.trim() === "" ? null : Number(sendRate);
      if (rate !== null && (!Number.isInteger(rate) || rate < 1)) {
        setError("발송 속도 제한은 1 이상의 정수이거나 비워 두세요(무제한).");
        setSaving(false);
        return;
      }
      const res = await api("/api/workspace", {
        method: "PUT",
        body: JSON.stringify({ name: name.trim(), sendRatePerSec: rate }),
      });
      if (res.ok) {
        setWorkspace(await res.json());
        setSavedAt(Date.now());
      } else {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "저장에 실패했습니다.");
      }
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setSaving(false);
    }
  }

  async function changeRole(userId: number, newRole: string) {
    setError(null);
    try {
      const res = await api(`/api/workspace/users/${userId}/role`, {
        method: "PUT",
        body: JSON.stringify({ role: newRole }),
      });
      if (res.ok) {
        const updated: WorkspaceUserView = await res.json();
        setMembers((prev) => prev.map((m) => (m.id === updated.id ? updated : m)));
      } else {
        const data = await res.json().catch(() => ({}));
        setError(data.error === "cannot demote the last admin"
          ? "마지막 관리자는 운영자로 바꿀 수 없어요. 먼저 다른 관리자를 지정하세요."
          : data.error ?? "역할 변경에 실패했습니다.");
      }
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    }
  }

  if (!isAdmin) {
    return (
      <div className="op-container op-fade">
        <div className="op-pagehead">
          <div>
            <h2>관리</h2>
            <p>워크스페이스 설정과 멤버를 관리하는 화면이에요.</p>
          </div>
        </div>
        <div className="op-card op-card-pad">
          <p style={{ margin: 0, color: "var(--op-muted)", fontSize: 14 }}>
            이 화면은 <b>관리자</b> 역할만 사용할 수 있어요. 설정 변경이 필요하면 워크스페이스 관리자에게 요청하세요.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>관리</h2>
          <p>{workspace ? `${workspace.name} 워크스페이스의 설정과 멤버를 관리하세요.` : "워크스페이스를 불러오는 중이에요."}</p>
        </div>
      </div>

      {error && <div className="op-modal-error" style={{ marginBottom: 14 }}>{error}</div>}

      <div className="op-form-card">
        <h3 className="op-sect-title">워크스페이스</h3>
        <div className="op-grid2">
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">이름</span>
            <input className="op-input" value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <div className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">정보</span>
            <p style={{ margin: "12px 0 0", fontSize: 13.5, color: "var(--op-muted)" }}>
              멤버 {workspace?.memberCount ?? "–"}명 · 생성 {workspace ? new Date(workspace.createdAt).toLocaleDateString("ko-KR") : "–"}
            </p>
          </div>
        </div>
      </div>

      <div className="op-form-card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
          <h3 className="op-sect-title">사용량 · 요금</h3>
          <span style={{ fontSize: 12.5, fontWeight: 700, padding: "3px 10px", borderRadius: 999,
                         background: "var(--op-accent-soft, #eef2ff)", color: "var(--op-accent, #4f46e5)" }}>
            {PLAN_LABEL[workspace?.plan ?? ""] ?? workspace?.plan ?? "—"} 플랜
          </span>
        </div>
        <div style={{ display: "flex", alignItems: "baseline", gap: 14, flexWrap: "wrap" }}>
          <span style={{ fontSize: 32, fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>
            {(workspace?.monthlySent ?? 0).toLocaleString()}
          </span>
          <span style={{ fontSize: 13.5, color: "var(--op-muted)" }}>
            {workspace?.monthlySendLimit != null
              ? `/ ${workspace.monthlySendLimit.toLocaleString()}통 · 잔여 ${Math.max(0, workspace.monthlySendLimit - workspace.monthlySent).toLocaleString()}통`
              : "이번 달 발송 성공 (한도 없음)"}
          </span>
        </div>
        {workspace?.monthlySendLimit != null && (
          <div style={{ marginTop: 10, height: 8, borderRadius: 999, background: "var(--op-line, #e5e7eb)", overflow: "hidden" }}>
            <div style={{
              height: "100%", borderRadius: 999,
              width: `${Math.min(100, (workspace.monthlySent / workspace.monthlySendLimit) * 100)}%`,
              background: workspace.monthlySent >= workspace.monthlySendLimit
                ? "var(--op-danger, #dc2626)"
                : workspace.monthlySent >= workspace.monthlySendLimit * 0.8
                  ? "var(--op-warn, #d97706)" : "var(--op-accent, #4f46e5)",
              transition: "width .3s",
            }} />
          </div>
        )}
        <p style={{ margin: "12px 0 0", fontSize: 12.5, color: "var(--op-faint)", lineHeight: 1.6 }}>
          발송 인프라(SMTP/SES·저장소)는 플랫폼이 제공하고, <b>요금은 이 월 발송량 기준으로 청구</b>됩니다.
          한도에 도달하면 새 캠페인 등록이 잠기고, 진행 중인 캠페인은 끝까지 발송돼요.
          {workspace?.contactLimit != null && (
            <> 이 플랜의 그 외 한도: 연락처 {workspace.contactLimit.toLocaleString()}명 · 멤버 {workspace.memberLimit}명.</>
          )}
        </p>
        <div style={{ marginTop: 16, borderTop: "1px solid var(--op-line, #e5e7eb)", paddingTop: 12 }}>
          <span className="op-flabel">플랜 변경</span>
          <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap", marginTop: 6 }}>
            <select className="op-input" style={{ maxWidth: 220, height: 38, fontSize: 13.5 }}
                    value={planChoice} onChange={(e) => setPlanChoice(e.target.value)}>
              <option value="">플랜 선택…</option>
              {["STARTER", "STANDARD", "PRO"].filter((p) => p !== workspace?.plan).map((p) => (
                <option key={p} value={p}>{PLAN_LABEL[p]}{p === "STANDARD" ? " — 월 9,900원" : p === "PRO" ? " — 월 29,000원" : ""}</option>
              ))}
            </select>
            <button className="op-btn op-btn-sm" disabled={billingBusy || !planChoice} onClick={changePlan}>
              {billingBusy ? "처리 중…" : "변경 (상향은 즉시 결제)"}
            </button>
            <button className="op-btn op-btn-sm op-btn-ghost" disabled={billingBusy} onClick={registerCard}>
              {workspace?.billingRegistered ? "결제 카드 재등록" : "결제 카드 등록"}
            </button>
            {workspace?.billingRegistered && (
              <span className="faint" style={{ fontSize: 12.5 }}>카드 등록됨 ✓</span>
            )}
          </div>
        </div>
        {paymentHistory.length > 0 && (
          <div style={{ marginTop: 14 }}>
            <span className="op-flabel">결제 이력</span>
            {paymentHistory.map((p) => (
              <div key={p.orderId}
                   style={{ display: "flex", justifyContent: "space-between", fontSize: 13, padding: "6px 0",
                            fontVariantNumeric: "tabular-nums" }}>
                <span className="faint">{new Date(p.createdAt).toLocaleDateString("ko-KR")}</span>
                <span className="faint">{PLAN_LABEL[p.plan] ?? p.plan}</span>
                <span className={p.status === "APPROVED" ? "strong" : "error"}>
                  {p.status === "APPROVED" ? `₩${p.amountKrw.toLocaleString()} 승인` : `실패 — ${p.failReason ?? ""}`}
                </span>
              </div>
            ))}
          </div>
        )}
        {usageHistory.length > 0 && (
          <div style={{ marginTop: 16, borderTop: "1px solid var(--op-line, #e5e7eb)", paddingTop: 12 }}>
            <span className="op-flabel">청구 이력 (월 마감 시점에 고정된 수치)</span>
            {usageHistory.map((h) => (
              <div key={h.periodMonth}
                   style={{ display: "flex", justifyContent: "space-between", fontSize: 13, padding: "6px 0",
                            fontVariantNumeric: "tabular-nums" }}>
                <span className="strong">{h.periodMonth}</span>
                <span className="faint">{h.sentCount.toLocaleString()}통 · {PLAN_LABEL[h.plan] ?? h.plan}</span>
                <span className="strong">
                  {h.amountKrw != null ? `₩${h.amountKrw.toLocaleString()}` : "협의"}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="op-form-card">
        <h3 className="op-sect-title">발송 설정</h3>
        <div className="op-grid2">
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">
              발송 속도 (건/초{workspace?.sendRateCap != null ? ` · 플랜 상한 ${workspace.sendRateCap}` : ""})
            </span>
            <input
              className="op-input"
              type="number"
              min={1}
              max={workspace?.sendRateCap ?? undefined}
              placeholder={workspace?.sendRateCap != null ? `1~${workspace.sendRateCap}` : "비워 두면 무제한"}
              value={sendRate}
              onChange={(e) => setSendRate(e.target.value)}
            />
          </label>
          <div className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">&nbsp;</span>
            <p style={{ margin: "12px 0 0", fontSize: 12.5, color: "var(--op-faint)", lineHeight: 1.6 }}>
              대량 캠페인을 이 속도로 나눠 발송해요. 제한에 걸린 메일은 실패가 아니라
              잠시 대기 후 자동 재시도됩니다.
              {workspace?.sendRateCap != null && <> 상한을 올리려면 플랜을 업그레이드하세요.</>}
            </p>
          </div>
        </div>
        <div style={{ display: "flex", justifyContent: "flex-end", alignItems: "center", gap: 12, marginTop: 18 }}>
          {savedAt && <span className="faint" style={{ fontSize: 12.5 }}>저장됨</span>}
          <button className="op-btn op-btn-sm" disabled={saving} onClick={saveSettings}>
            {saving ? "저장 중…" : "설정 저장"}
          </button>
        </div>
      </div>

      <div className="op-card">
        <div className="op-list-head">
          <span className="t">멤버 ({members.length})</span>
          <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setAdding(true)}>
            <span className="op-btn-plus">+</span>멤버 추가
          </button>
        </div>
        <div className="op-thead" style={{ gridTemplateColumns: "minmax(0, 2fr) minmax(0, 1.2fr) 170px 110px" }}>
          <span>이메일</span>
          <span>이름</span>
          <span>역할</span>
          <span>가입일</span>
        </div>
        {members.map((m) => (
          <div key={m.id} className="op-trow" style={{ gridTemplateColumns: "minmax(0, 2fr) minmax(0, 1.2fr) 170px 110px" }}>
            <span className="strong op-ell">{m.email}</span>
            <span className="faint">{m.displayName || "-"}</span>
            <span>
              <select
                className="op-input"
                style={{ height: 36, fontSize: 13, maxWidth: 150 }}
                value={m.role}
                onChange={(e) => changeRole(m.id, e.target.value)}
              >
                <option value="ADMIN">{ROLE_LABEL.ADMIN}</option>
                <option value="OPERATOR">{ROLE_LABEL.OPERATOR}</option>
              </select>
            </span>
            <span className="faint">{new Date(m.createdAt).toLocaleDateString("ko-KR")}</span>
          </div>
        ))}
        {members.length === 0 && (
          <div className="op-list-row"><span className="meta">멤버를 불러오는 중이에요…</span></div>
        )}
      </div>

      {adding && <AddMemberModal onClose={() => setAdding(false)} onSaved={refresh} />}
    </div>
  );
}
