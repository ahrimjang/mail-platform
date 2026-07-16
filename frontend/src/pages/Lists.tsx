import { useCallback, useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import Portal from "../components/Portal";
import type { CampaignView, ContactListView } from "../types";
import { badgeClass, fmt, pctOf, statusLabel } from "../outpace/format";

/* Column template shared by the header and body rows. */
const COLS = "minmax(0, 1.6fr) minmax(0, 2fr) 90px 100px 110px";

function dateOf(iso: string): string {
  return new Date(iso).toLocaleDateString("ko-KR");
}

/* Engagement rate over delivered mail; "-" until anything was sent. */
function rateOf(part: number, sent: number): string {
  return sent > 0 ? `${Math.round((part / sent) * 1000) / 10}%` : "–";
}

/* Grid of the expanded per-list campaign rows. */
const SUB_COLS = "minmax(140px, 2fr) 72px minmax(90px, 1.1fr) 56px 56px 84px 18px";

/* --------------------------- create / edit modal --------------------------- */

function ListModal({ target, onClose, onSaved }: {
  target: ContactListView | null; // null = create
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(target?.name ?? "");
  const [description, setDescription] = useState(target?.description ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    if (!name.trim()) { setError("리스트 이름을 입력해 주세요."); return; }
    setSaving(true);
    setError(null);
    try {
      const res = await api(target ? `/api/lists/${target.id}` : "/api/lists", {
        method: target ? "PUT" : "POST",
        body: JSON.stringify({ name: name.trim(), description: description.trim() || null }),
      });
      if (res.ok) { onSaved(); onClose(); }
      else setError("저장에 실패했습니다.");
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
        <h3>{target ? "리스트 수정" : "새 리스트"}</h3>
        <p className="op-modal-sub">캠페인 발송 대상을 묶어 관리하는 단위예요.</p>
        <label className="op-field">
          <span className="op-flabel">이름</span>
          <input className="op-input" placeholder="뉴스레터 구독자" value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="op-field">
          <span className="op-flabel">설명 (선택)</span>
          <textarea className="op-input" rows={3} placeholder="어떤 수신자를 담는 리스트인지 적어 두세요." value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
        {error && <div className="op-modal-error">{error}</div>}
        <div className="op-modal-foot">
          <button className="op-btn op-btn-sm op-btn-ghost" onClick={onClose}>취소</button>
          <button className="op-btn op-btn-sm" disabled={saving} onClick={save}>{saving ? "저장 중…" : target ? "저장" : "만들기"}</button>
        </div>
      </div>
    </div>
    </Portal>
  );
}

/* ------------------------------ delete confirm ------------------------------ */

function DeleteModal({ target, onClose, onDeleted }: {
  target: ContactListView;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const res = await api(`/api/lists/${target.id}`, { method: "DELETE" });
      if (res.ok) { onDeleted(); onClose(); }
      else setError("삭제에 실패했습니다.");
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Portal>
    <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="op-modal">
        <h3>리스트 삭제</h3>
        <p className="op-modal-sub">
          <b>{target.name}</b> 리스트를 삭제할까요? 리스트의 멤버십도 함께 삭제됩니다.
          (수신자 자체는 삭제되지 않아요.)
        </p>
        {error && <div className="op-modal-error">{error}</div>}
        <div className="op-modal-foot">
          <button className="op-btn op-btn-sm op-btn-ghost" onClick={onClose}>취소</button>
          <button className="op-btn op-btn-sm" style={{ background: "var(--op-red)" }} disabled={busy} onClick={run}>
            {busy ? "삭제 중…" : "삭제"}
          </button>
        </div>
      </div>
    </div>
    </Portal>
  );
}

/* ---------------------------------- page ----------------------------------- */

export default function Lists() {
  const nav = useNavigate();
  const [lists, setLists] = useState<ContactListView[]>([]);
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<ContactListView | null>(null);
  const [deleting, setDeleting] = useState<ContactListView | null>(null);
  // Row expanded to show the campaigns that targeted that list.
  const [expandedId, setExpandedId] = useState<number | null>(null);
  // ?focus={id} — a shortcut from another page (recipient list chips) highlights that row.
  const [searchParams] = useSearchParams();
  const focusId = Number(searchParams.get("focus")) || null;

  const refresh = useCallback(async () => {
    try {
      const [lRes, cRes] = await Promise.all([api("/api/lists"), api("/api/campaigns")]);
      if (lRes.ok) setLists(await lRes.json());
      if (cRes.ok) setCampaigns(await cRes.json());
    } catch {
      /* transient / unauthorized handled by api() */
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);
  // Arriving via a focus shortcut opens that list's campaign panel right away.
  useEffect(() => { if (focusId) setExpandedId(focusId); }, [focusId]);

  const campaignsOf = (listId: number) =>
    campaigns
      .filter((c) => c.listId === listId)
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>리스트</h2>
          <p>{loaded ? `총 ${lists.length}개의 리스트로 수신자를 관리하고 있어요.` : "리스트를 불러오는 중이에요."}</p>
        </div>
        <button className="op-btn op-btn-sm" onClick={() => setCreating(true)}>
          <span className="op-btn-plus">+</span>새 리스트
        </button>
      </div>

      <div className="op-card">
        <div className="op-thead" style={{ gridTemplateColumns: COLS }}>
          <span>이름</span>
          <span>설명</span>
          <span>멤버 수</span>
          <span>생성일</span>
          <span />
        </div>
        {lists.map((l) => {
          const expanded = expandedId === l.id;
          const listCampaigns = expanded ? campaignsOf(l.id) : [];
          return (
            <div key={l.id}>
              <div
                className={`op-trow clickable${l.id === focusId ? " focused" : ""}`}
                style={{ gridTemplateColumns: COLS }}
                ref={l.id === focusId ? (el) => el?.scrollIntoView({ block: "center" }) : undefined}
                onClick={() => setExpandedId(expanded ? null : l.id)}
              >
                <span className="strong">
                  <span className={`op-rowcaret${expanded ? " open" : ""}`}>▸</span>
                  {l.name}
                </span>
                <span className="faint" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {l.description || "-"}
                </span>
                <span>{l.memberCount.toLocaleString()}명</span>
                <span className="faint">{dateOf(l.createdAt)}</span>
                <span className="op-row-actions">
                  <button className="op-linkbtn" style={{ fontSize: 13 }} onClick={(e) => { e.stopPropagation(); setEditing(l); }}>수정</button>
                  <button className="op-linkbtn" style={{ fontSize: 13, color: "var(--op-red)" }} onClick={(e) => { e.stopPropagation(); setDeleting(l); }}>삭제</button>
                </span>
              </div>
              {/* Expanded: campaigns that fanned out to this list, newest first. */}
              {expanded && (
                <div className="op-sublist">
                  {listCampaigns.length === 0 ? (
                    <div className="op-sublist-empty">이 리스트로 발송한 캠페인이 아직 없어요.</div>
                  ) : (
                    <>
                      <div className="op-sublist-head" style={{ gridTemplateColumns: SUB_COLS }}>
                        <span>캠페인 ({listCampaigns.length})</span>
                        <span>상태</span>
                        <span>발송 진행</span>
                        <span>오픈율</span>
                        <span>클릭율</span>
                        <span>일시</span>
                        <span />
                      </div>
                      {listCampaigns.map((c) => {
                        const pct = pctOf(c.sent, c.total);
                        return (
                          <div
                            key={c.id}
                            className="op-sublist-row"
                            style={{ gridTemplateColumns: SUB_COLS }}
                            onClick={(e) => { e.stopPropagation(); nav(`/campaigns/${c.id}`); }}
                          >
                            <span className="strong op-ell">{c.name ?? c.subject}</span>
                            <span><span className={`op-badge ${badgeClass(c.status)}`}>{statusLabel(c)}</span></span>
                            <span className="op-minibar">
                              <span className="op-bar sm"><span className="op-bar-fill" style={{ width: `${pct}%` }} /></span>
                              <span className="pct">{fmt(c.sent)}/{fmt(c.total)}</span>
                            </span>
                            <span>{rateOf(c.opened, c.sent)}</span>
                            <span>{rateOf(c.clicked, c.sent)}</span>
                            <span className="faint">{new Date(c.createdAt).toLocaleDateString("ko-KR")}</span>
                            <span className="go">›</span>
                          </div>
                        );
                      })}
                    </>
                  )}
                </div>
              )}
            </div>
          );
        })}
        {loaded && lists.length === 0 && (
          <div className="op-list-row">
            <span className="meta">아직 리스트가 없어요. 오른쪽 위 버튼으로 첫 리스트를 만들어 보세요.</span>
          </div>
        )}
      </div>

      {creating && <ListModal target={null} onClose={() => setCreating(false)} onSaved={refresh} />}
      {editing && <ListModal key={editing.id} target={editing} onClose={() => setEditing(null)} onSaved={refresh} />}
      {deleting && <DeleteModal target={deleting} onClose={() => setDeleting(null)} onDeleted={refresh} />}
    </div>
  );
}
