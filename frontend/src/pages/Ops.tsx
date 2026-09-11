import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import type { CampaignStatus, OpsAuditEntry, OpsCampaignRow, OpsSignalsView, OpsWorkspaceRow } from "../types";
import {
  ACTION_LABEL, BounceBadge, CampaignTable, PLAN_LABEL, ReasonModal, fmtDate, fmtDateTime, opsAction,
} from "../outpace/ops";

/* 플랫폼 운영자 콘솔 — 매뉴얼 5·6절이 psql 로 하던 일을 화면으로. 네 탭:
   워크스페이스(정지/플랜) · 캠페인(전 테넌트 검색·중단) · 운영 신호 · 감사 로그.
   권한은 서버가 매 호출 검사(403)하고, 여기서는 403 이면 안내만 띄운다. */

type Tab = "workspaces" | "campaigns" | "signals" | "audit";
const TABS: { key: Tab; label: string }[] = [
  { key: "workspaces", label: "워크스페이스" },
  { key: "campaigns", label: "캠페인" },
  { key: "signals", label: "운영 신호" },
  { key: "audit", label: "감사 로그" },
];

export default function Ops() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get("tab") as Tab) || "workspaces";
  const [forbidden, setForbidden] = useState(false);

  function setTab(t: Tab) {
    const next = new URLSearchParams(params);
    next.set("tab", t);
    setParams(next, { replace: true });
  }

  if (forbidden) {
    return (
      <div className="op-container-mid op-fade">
        <div className="op-card op-card-pad" style={{ textAlign: "center", padding: 48 }}>
          <h2 style={{ margin: "0 0 8px" }}>플랫폼 운영자 전용 화면이에요</h2>
          <p style={{ color: "var(--op-muted)", margin: 0 }}>
            이 계정에는 운영 권한이 없습니다. 서버의 <code>APP_PLATFORM_OPERATORS</code> 에 이메일을 추가하고 재기동하면 부여됩니다.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h1 style={{ margin: "0 0 6px" }}>운영</h1>
          <p>전 워크스페이스를 넘나드는 화면이에요. 모든 조치는 감사 로그에 남고, 정지·플랜 변경은 해당 테넌트에 알림이 갑니다.</p>
        </div>
      </div>
      <div className="op-tabs">
        {TABS.map((t) => (
          <button key={t.key} className={`op-tab${tab === t.key ? " active" : ""}`} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>
      {tab === "workspaces" && <WorkspacesTab onForbidden={() => setForbidden(true)} />}
      {tab === "campaigns" && <CampaignsTab onForbidden={() => setForbidden(true)} />}
      {tab === "signals" && <SignalsTab onForbidden={() => setForbidden(true)} />}
      {tab === "audit" && <AuditTab onForbidden={() => setForbidden(true)} />}
    </div>
  );
}

/* 공용: 목록 조회 훅 — 403 은 화면 전체를 안내로 바꾼다 */
function useOpsFetch<T>(path: string, onForbidden: () => void, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api(path);
      if (res.status === 403) { onForbidden(); return; }
      if (res.ok) setData(await res.json());
    } catch { /* 일시 오류 — 다음 새로고침 */ }
    setLoading(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, ...deps]);
  useEffect(() => { reload(); }, [reload]);
  return { data, loading, reload };
}

const WS_COLS = "minmax(160px, 1.6fr) 90px minmax(150px, 1.4fr) 120px 110px 100px 90px";

function WorkspacesTab({ onForbidden }: { onForbidden: () => void }) {
  const nav = useNavigate();
  const { data, loading } = useOpsFetch<OpsWorkspaceRow[]>("/api/ops/workspaces", onForbidden);
  const [q, setQ] = useState("");
  const [onlySuspended, setOnlySuspended] = useState(false);

  const rows = useMemo(() => {
    const all = data ?? [];
    const needle = q.trim().toLowerCase();
    return all.filter((w) =>
      (!onlySuspended || w.suspendedAt != null) &&
      (!needle || w.name.toLowerCase().includes(needle) || (w.ownerEmail ?? "").toLowerCase().includes(needle) || String(w.id) === needle));
  }, [data, q, onlySuspended]);

  return (
    <>
      <div className="op-toolbar" style={{ flexWrap: "wrap" }}>
        <input className="op-input" style={{ maxWidth: 300 }} placeholder="워크스페이스 이름 · 소유자 이메일 · id"
               value={q} onChange={(e) => setQ(e.target.value)} />
        <label className="op-check" style={{ fontSize: 13 }}>
          <input type="checkbox" checked={onlySuspended} onChange={(e) => setOnlySuspended(e.target.checked)} /> 정지 중만
        </label>
        <span className="faint" style={{ marginLeft: "auto", fontSize: 13, color: "var(--op-faint)" }}>
          {loading ? "불러오는 중…" : `${rows.length}개 · 30일 바운스율 높은 순`}
        </span>
      </div>
      <div className="op-card">
        <div className="op-thead" style={{ gridTemplateColumns: WS_COLS }}>
          <span>워크스페이스</span><span>플랜</span><span>소유자</span><span>이번 달 발송</span>
          <span>30일 바운스</span><span>최근 활동</span><span>상태</span>
        </div>
        {!loading && rows.length === 0 && <div className="op-import-empty">조건에 맞는 워크스페이스가 없어요.</div>}
        {rows.map((w) => (
          <div key={w.id} className="op-trow clickable" style={{ gridTemplateColumns: WS_COLS }}
               onClick={() => nav(`/ops/workspaces/${w.id}`)}>
            <span style={{ minWidth: 0 }}>
              <span className="strong" style={{ display: "block", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{w.name}</span>
              <span className="faint">#{w.id} · 가입 {fmtDate(w.createdAt)} · 멤버 {w.memberCount}</span>
            </span>
            <span><span className="op-minibadge blue">{PLAN_LABEL[w.plan] ?? w.plan}</span></span>
            <span style={{ minWidth: 0 }}>
              <span style={{ display: "block", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{w.ownerEmail ?? "–"}</span>
              {w.ownerEmail && !w.ownerVerified && <span className="faint" style={{ color: "var(--op-amber)" }}>이메일 미인증</span>}
            </span>
            <span>
              {w.monthlySent.toLocaleString()}
              <span className="faint"> / {w.monthlySendLimit == null ? "∞" : w.monthlySendLimit.toLocaleString()}</span>
            </span>
            <span>
              <BounceBadge w={w} />
              {w.attempted30d > 0 && <span className="faint" style={{ marginLeft: 6 }}>{w.bounced30d}/{w.attempted30d}</span>}
            </span>
            <span className="faint">{fmtDate(w.lastActivityAt)}</span>
            <span>
              {w.suspendedAt
                ? <span className="op-badge off" title={w.suspensionReason ?? ""}>정지</span>
                : <span className="op-badge on">정상</span>}
            </span>
          </div>
        ))}
      </div>
    </>
  );
}

function CampaignsTab({ onForbidden }: { onForbidden: () => void }) {
  const nav = useNavigate();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<CampaignStatus | "">("");
  const [applied, setApplied] = useState({ q: "", status: "" as CampaignStatus | "" });
  const path = `/api/ops/campaigns?limit=100${applied.status ? `&status=${applied.status}` : ""}${applied.q ? `&q=${encodeURIComponent(applied.q)}` : ""}`;
  const { data, loading, reload } = useOpsFetch<OpsCampaignRow[]>(path, onForbidden);
  const [aborting, setAborting] = useState<OpsCampaignRow | null>(null);

  return (
    <>
      <div className="op-toolbar" style={{ flexWrap: "wrap" }}>
        <input className="op-input" style={{ maxWidth: 300 }} placeholder="캠페인 이름 · 제목 · 등록자 이메일"
               value={q} onChange={(e) => setQ(e.target.value)}
               onKeyDown={(e) => { if (e.key === "Enter" && !e.nativeEvent.isComposing) setApplied({ q: q.trim(), status }); }} />
        <select className="op-input" style={{ maxWidth: 170 }} value={status} onChange={(e) => setStatus(e.target.value as CampaignStatus | "")}>
          <option value="">전체 상태</option>
          <option value="QUEUED">대기</option>
          <option value="EXPANDING">수신자 확장 중</option>
          <option value="SENDING">발송 중</option>
          <option value="COMPLETED">완료</option>
          <option value="CANCELED">취소·중단</option>
        </select>
        <button className="op-btn op-btn-ghost op-btn-sm" onClick={() => setApplied({ q: q.trim(), status })}>검색</button>
        <span className="faint" style={{ marginLeft: "auto", fontSize: 13, color: "var(--op-faint)" }}>
          {loading ? "불러오는 중…" : `${data?.length ?? 0}건 · 최신순 최대 100건`}
        </span>
      </div>
      <CampaignTable rows={data ?? []} empty={loading ? "불러오는 중…" : "조건에 맞는 캠페인이 없어요."}
                     onAbort={setAborting} onOpenWorkspace={(id) => nav(`/ops/workspaces/${id}`)} />
      {aborting && (
        <ReasonModal
          title={`캠페인 #${aborting.id} 발송 중단`}
          sub={`"${aborting.name ?? aborting.subject}" 의 남은 발송을 멈춥니다. 이미 SMTP 로 넘어간 몇 통은 회수되지 않아요. 되돌릴 수 없습니다.`}
          confirmLabel="중단" danger
          onClose={() => setAborting(null)}
          onSubmit={async (reason) => {
            const err = await opsAction(`/api/ops/campaigns/${aborting.id}/abort`, { reason });
            if (!err) reload();
            return err;
          }}
        />
      )}
    </>
  );
}

function SignalsTab({ onForbidden }: { onForbidden: () => void }) {
  const nav = useNavigate();
  const { data, loading, reload } = useOpsFetch<OpsSignalsView>("/api/ops/signals", onForbidden);
  const [aborting, setAborting] = useState<OpsCampaignRow | null>(null);
  const s = data;
  const kpi = (k: string, v: string | number, tone?: "red" | "amber") => (
    <div className="op-statcard">
      <div className="k">{k}</div>
      <div className={`v${tone === "red" ? " red" : ""}`} style={tone === "amber" ? { color: "var(--op-amber)" } : undefined}>{v}</div>
    </div>
  );

  return (
    <>
      <div className="op-statcards">
        {kpi("워크스페이스", s ? `${s.workspaces} (+${s.signups7d}/7일)` : "–")}
        {kpi("발송 정지 중", s?.suspended ?? "–", s && s.suspended > 0 ? "amber" : undefined)}
        {kpi("진행 중 캠페인", s?.inFlight ?? "–")}
        {kpi("24시간 중단", s?.aborted24h ?? "–", s && s.aborted24h > 0 ? "amber" : undefined)}
      </div>
      <div className="op-statcards">
        {kpi("24시간 발송", s ? s.sent24h.toLocaleString() : "–")}
        {kpi("24시간 실패 (DLQ 포함)", s ? s.failed24h.toLocaleString() : "–", s && s.failed24h > 0 ? "red" : undefined)}
        {kpi("24시간 반송", s ? s.bounced24h.toLocaleString() : "–", s && s.sent24h > 0 && s.bounced24h / (s.sent24h + s.bounced24h) >= 0.03 ? "red" : undefined)}
        <div className="op-statcard">
          <div className="k">지표 원본</div>
          <div style={{ fontSize: 13, color: "var(--op-muted)", lineHeight: 1.5 }}>
            스위퍼·DLQ 카운터는 Grafana 의 <code>mail_recovery_total</code> · <code>mail_dlq_received_total</code>
          </div>
        </div>
      </div>

      <h3 style={{ margin: "26px 0 10px", fontSize: 15 }}>진행 중 캠페인 <span className="faint" style={{ fontWeight: 500, color: "var(--op-faint)" }}>— 릴리스 오래된 순. 10분 넘게 확장 중이면 스위퍼가 되돌린다</span></h3>
      <CampaignTable rows={s?.inFlightCampaigns ?? []} empty={loading ? "불러오는 중…" : "지금 진행 중인 캠페인이 없어요."}
                     onAbort={setAborting} onOpenWorkspace={(id) => nav(`/ops/workspaces/${id}`)} />

      <h3 style={{ margin: "26px 0 10px", fontSize: 15 }}>발송 정지 중인 워크스페이스</h3>
      <div className="op-card">
        {(s?.suspendedWorkspaces ?? []).length === 0 && <div className="op-import-empty">{loading ? "불러오는 중…" : "정지 중인 워크스페이스가 없어요."}</div>}
        {(s?.suspendedWorkspaces ?? []).map((w) => (
          <div key={w.id} className="op-trow clickable" style={{ gridTemplateColumns: "minmax(160px, 1fr) 2fr 140px" }}
               onClick={() => nav(`/ops/workspaces/${w.id}`)}>
            <span className="strong">{w.name} <span className="faint">#{w.id}</span></span>
            <span style={{ fontSize: 13 }}>{w.suspensionReason ?? "사유 없음"}</span>
            <span className="faint">{fmtDateTime(w.suspendedAt)}</span>
          </div>
        ))}
      </div>
      {aborting && (
        <ReasonModal
          title={`캠페인 #${aborting.id} 발송 중단`}
          sub={`"${aborting.name ?? aborting.subject}" 의 남은 발송을 멈춥니다. 되돌릴 수 없습니다.`}
          confirmLabel="중단" danger
          onClose={() => setAborting(null)}
          onSubmit={async (reason) => {
            const err = await opsAction(`/api/ops/campaigns/${aborting.id}/abort`, { reason });
            if (!err) reload();
            return err;
          }}
        />
      )}
    </>
  );
}

const AUDIT_COLS = "130px 120px minmax(150px, 1fr) 110px minmax(220px, 2fr)";

function AuditTab({ onForbidden }: { onForbidden: () => void }) {
  const nav = useNavigate();
  const { data, loading } = useOpsFetch<OpsAuditEntry[]>("/api/ops/audit?limit=200", onForbidden);
  return (
    <div className="op-card">
      <div className="op-thead" style={{ gridTemplateColumns: AUDIT_COLS }}>
        <span>시각</span><span>조치</span><span>운영자</span><span>대상</span><span>내용</span>
      </div>
      {!loading && (data ?? []).length === 0 && <div className="op-import-empty">아직 기록된 조치가 없어요.</div>}
      {(data ?? []).map((a) => (
        <div key={a.id} className="op-trow" style={{ gridTemplateColumns: AUDIT_COLS }}>
          <span className="faint">{fmtDateTime(a.createdAt)}</span>
          <span><span className={`op-minibadge ${a.action === "UNSUSPEND" ? "green" : a.action === "CHANGE_PLAN" ? "blue" : "red"}`}>{ACTION_LABEL[a.action] ?? a.action}</span></span>
          <span style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{a.actorEmail}</span>
          <span>
            {a.workspaceId != null && <button className="op-linkbtn" style={{ fontSize: 13 }} onClick={() => nav(`/ops/workspaces/${a.workspaceId}`)}>ws #{a.workspaceId}</button>}
            {a.campaignId != null && <span className="faint" style={{ display: "block" }}>캠페인 #{a.campaignId}</span>}
          </span>
          <span style={{ fontSize: 13, wordBreak: "break-word" }}>{a.detail}</span>
        </div>
      ))}
    </div>
  );
}
