import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import Portal from "../components/Portal";
import type { ContactListView } from "../types";

/* Column template shared by the header and body rows. */
const COLS = "minmax(0, 1.6fr) minmax(0, 2fr) 90px 100px 110px";

function dateOf(iso: string): string {
  return new Date(iso).toLocaleDateString("ko-KR");
}

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
  const [lists, setLists] = useState<ContactListView[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<ContactListView | null>(null);
  const [deleting, setDeleting] = useState<ContactListView | null>(null);

  const refresh = useCallback(async () => {
    try {
      const res = await api("/api/lists");
      if (res.ok) setLists(await res.json());
    } catch {
      /* transient / unauthorized handled by api() */
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

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
        {lists.map((l) => (
          <div key={l.id} className="op-trow" style={{ gridTemplateColumns: COLS }}>
            <span className="strong">{l.name}</span>
            <span className="faint" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              {l.description || "-"}
            </span>
            <span>{l.memberCount.toLocaleString()}명</span>
            <span className="faint">{dateOf(l.createdAt)}</span>
            <span className="op-row-actions">
              <button className="op-linkbtn" style={{ fontSize: 13 }} onClick={() => setEditing(l)}>수정</button>
              <button className="op-linkbtn" style={{ fontSize: 13, color: "var(--op-red)" }} onClick={() => setDeleting(l)}>삭제</button>
            </span>
          </div>
        ))}
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
