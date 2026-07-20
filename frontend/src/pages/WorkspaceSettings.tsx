import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import Portal from "../components/Portal";
import { useAuth } from "../outpace/auth";
import type { WorkspaceUserView, WorkspaceView } from "../types";

/* BYO connector options: which SMTP relay / file storage the tenant brings.
   Selection only for now — the wiring lands with the SES/S3 integrations. */
const SMTP_OPTIONS = [
  { value: "MAILHOG", label: "MailHog (개발용 기본)" },
  { value: "AWS_SES", label: "AWS SES — 회사 AWS 계정" },
  { value: "SENDGRID", label: "SendGrid — 회사 계정" },
  { value: "CUSTOM_SMTP", label: "자체 SMTP 서버" },
];
const STORAGE_OPTIONS = [
  { value: "LOCAL", label: "로컬 디스크 (개발용 기본)" },
  { value: "AWS_S3", label: "AWS S3 — 회사 버킷" },
  { value: "NCP_OBJECT_STORAGE", label: "NCP Object Storage — 회사 버킷" },
];

const ROLE_LABEL: Record<string, string> = { ADMIN: "관리자", OPERATOR: "운영자" };

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
  const [smtp, setSmtp] = useState("MAILHOG");
  const [storage, setStorage] = useState("LOCAL");
  const [sendRate, setSendRate] = useState(""); // msgs/sec as text; "" = unlimited
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
        setSmtp(w.smtpProvider);
        setStorage(w.storageProvider);
        setSendRate(w.sendRatePerSec == null ? "" : String(w.sendRatePerSec));
      }
      const uRes = await api("/api/workspace/users");
      if (uRes.ok) setMembers(await uRes.json());
    } catch { /* transient */ }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

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
        body: JSON.stringify({ name: name.trim(), smtpProvider: smtp, storageProvider: storage, sendRatePerSec: rate }),
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
        <h3 className="op-sect-title">사용량</h3>
        <div style={{ display: "flex", alignItems: "baseline", gap: 14, flexWrap: "wrap" }}>
          <span style={{ fontSize: 32, fontWeight: 800, fontVariantNumeric: "tabular-nums" }}>
            {(workspace?.monthlySent ?? 0).toLocaleString()}
          </span>
          <span style={{ fontSize: 13.5, color: "var(--op-muted)" }}>이번 달 발송 성공 (매월 1일 기준 집계)</span>
        </div>
        <p style={{ margin: "12px 0 0", fontSize: 12.5, color: "var(--op-faint)", lineHeight: 1.6 }}>
          플랜·쿼터 산정의 기준 수치예요. BYO 커넥터를 연결하면 발송 인프라 비용 자체는 회사 계정에
          직접 청구되고, 플랫폼 요금은 이 사용량 구간으로 계산됩니다.
        </p>
      </div>

      <div className="op-form-card">
        <h3 className="op-sect-title">인프라 설정 (BYO)</h3>
        <p style={{ margin: "0 0 16px", fontSize: 13, color: "var(--op-muted)", lineHeight: 1.6 }}>
          발송·저장처럼 비용이 큰 인프라는 <b>회사 소유 계정을 연결</b>해 비용이 회사에 직접 청구되게 합니다.
          지금은 선택만 저장돼요 — 실제 연동(자격증명 등록)은 준비 중입니다.
        </p>
        <div className="op-grid2">
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">메일 발송 (SMTP)</span>
            <select className="op-input" value={smtp} onChange={(e) => setSmtp(e.target.value)}>
              {SMTP_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </label>
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">파일 저장소</span>
            <select className="op-input" value={storage} onChange={(e) => setStorage(e.target.value)}>
              {STORAGE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </label>
        </div>
        <div className="op-grid2" style={{ marginTop: 16 }}>
          <label className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">발송 속도 제한 (건/초)</span>
            <input
              className="op-input"
              type="number"
              min={1}
              placeholder="비워 두면 무제한"
              value={sendRate}
              onChange={(e) => setSendRate(e.target.value)}
            />
          </label>
          <div className="op-field" style={{ marginBottom: 0 }}>
            <span className="op-flabel">&nbsp;</span>
            <p style={{ margin: "12px 0 0", fontSize: 12.5, color: "var(--op-faint)", lineHeight: 1.6 }}>
              연결한 발송 인프라의 초당 한도에 맞춰 두면, 대량 캠페인도 그 속도로 나눠 발송돼요.
              제한에 걸린 메일은 실패가 아니라 잠시 대기 후 자동 재시도됩니다.
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
