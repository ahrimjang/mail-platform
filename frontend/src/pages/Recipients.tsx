import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import Portal from "../components/Portal";
import type {
  ContactListView,
  ContactView,
  ImportResult,
  SubscriptionView,
  UpdateContactListsRequest,
  UpdateSubscriptionRequest,
} from "../types";

/* Column template shared by the header and body rows. */
const COLS = "minmax(0, 2.2fr) minmax(0, 2fr) 110px 110px";

function dateOf(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString("ko-KR") : "-";
}

function SubBadge({ sub }: { sub: SubscriptionView | undefined }) {
  if (!sub) return <span className="faint">확인 중…</span>;
  return sub.suppressed
    ? <span className="op-badge off">수신거부</span>
    : <span className="op-badge on">활성</span>;
}

/* ------------------------------ add contact ------------------------------- */

function AddContactModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [email, setEmail] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    if (!email.trim()) { setError("이메일을 입력해 주세요."); return; }
    setSaving(true);
    setError(null);
    try {
      const res = await api("/api/contacts", {
        method: "POST",
        body: JSON.stringify({
          email: email.trim(),
          firstName: firstName.trim() || null,
          lastName: lastName.trim() || null,
          attributes: {},
        }),
      });
      if (res.ok) { onSaved(); onClose(); }
      else setError("저장에 실패했습니다. 이미 등록된 이메일인지 확인해 주세요.");
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
        <h3>수신자 추가</h3>
        <p className="op-modal-sub">이메일 주소는 수신자마다 하나씩, 중복 없이 등록됩니다.</p>
        <label className="op-field">
          <span className="op-flabel">이메일</span>
          <input className="op-input" type="email" placeholder="hello@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <div className="op-grid2">
          <label className="op-field">
            <span className="op-flabel">이름</span>
            <input className="op-input" placeholder="길동" value={firstName} onChange={(e) => setFirstName(e.target.value)} />
          </label>
          <label className="op-field">
            <span className="op-flabel">성</span>
            <input className="op-input" placeholder="홍" value={lastName} onChange={(e) => setLastName(e.target.value)} />
          </label>
        </div>
        {error && <div className="op-modal-error">{error}</div>}
        <div className="op-modal-foot">
          <button className="op-btn op-btn-sm op-btn-ghost" onClick={onClose}>취소</button>
          <button className="op-btn op-btn-sm" disabled={saving} onClick={save}>{saving ? "저장 중…" : "추가"}</button>
        </div>
      </div>
    </div>
    </Portal>
  );
}

/* ------------------------------- csv import ------------------------------- */

function ImportModal({ lists, onClose, onImported }: {
  lists: ContactListView[];
  onClose: () => void;
  onImported: () => void;
}) {
  const [listId, setListId] = useState<string>("");
  const [csv, setCsv] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    if (!listId) { setError("가져올 리스트를 선택해 주세요."); return; }
    if (!csv.trim()) { setError("CSV 내용을 붙여넣어 주세요."); return; }
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const res = await api(`/api/contacts/import?listId=${listId}`, {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: csv,
      });
      if (res.ok) { setResult(await res.json()); onImported(); }
      else setError("가져오기에 실패했습니다.");
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
        <h3>CSV 가져오기</h3>
        <p className="op-modal-sub">한 줄에 한 명씩 <b>email,firstName,lastName</b> 형식으로 붙여넣으세요. 중복·잘못된 줄은 건너뜁니다.</p>
        <label className="op-field">
          <span className="op-flabel">추가할 리스트</span>
          <select className="op-input" value={listId} onChange={(e) => setListId(e.target.value)}>
            <option value="">리스트 선택…</option>
            {lists.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
        </label>
        <label className="op-field">
          <span className="op-flabel">CSV 내용</span>
          <textarea
            className="op-input"
            rows={7}
            placeholder={"hong@example.com,길동,홍\nkim@example.com,철수,김"}
            value={csv}
            onChange={(e) => setCsv(e.target.value)}
          />
        </label>
        {result && (
          <div className="op-modal-result">
            가져오기 완료 — <b>{result.imported}명</b> 추가, <b>{result.skipped}명</b> 건너뜀
          </div>
        )}
        {error && <div className="op-modal-error">{error}</div>}
        <div className="op-modal-foot">
          <button className="op-btn op-btn-sm op-btn-ghost" onClick={onClose}>닫기</button>
          <button className="op-btn op-btn-sm" disabled={busy} onClick={run}>{busy ? "가져오는 중…" : "가져오기"}</button>
        </div>
      </div>
    </div>
    </Portal>
  );
}

/* --------------------------------- drawer --------------------------------- */

function ContactDrawer({ contact, lists, sub, memberIds, onClose, onChanged }: {
  contact: ContactView;
  lists: ContactListView[];
  sub: SubscriptionView | undefined;
  memberIds: number[];
  onClose: () => void;
  onChanged: (sub: SubscriptionView | null, listIds: number[] | null) => void;
}) {
  const [checked, setChecked] = useState<Set<number>>(new Set(memberIds));
  const [savingLists, setSavingLists] = useState(false);
  const [togglingSub, setTogglingSub] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fullName = [contact.lastName, contact.firstName].filter(Boolean).join("") || "-";
  const dirty = checked.size !== memberIds.length || memberIds.some((id) => !checked.has(id));

  function toggle(id: number) {
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function toggleSubscription() {
    if (!sub) return;
    setTogglingSub(true);
    setError(null);
    try {
      const body: UpdateSubscriptionRequest = { suppressed: !sub.suppressed };
      const res = await api(`/api/contacts/${contact.id}/subscription`, {
        method: "PUT",
        body: JSON.stringify(body),
      });
      if (res.ok) onChanged(await res.json(), null);
      else setError("구독 상태 변경에 실패했습니다.");
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setTogglingSub(false);
    }
  }

  async function saveLists() {
    setSavingLists(true);
    setError(null);
    try {
      const body: UpdateContactListsRequest = { listIds: [...checked] };
      const res = await api(`/api/contacts/${contact.id}/lists`, {
        method: "PUT",
        body: JSON.stringify(body),
      });
      if (res.ok) onChanged(null, await res.json());
      else setError("리스트 저장에 실패했습니다.");
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setSavingLists(false);
    }
  }

  return (
    <Portal>
      <div className="op-drawer-backdrop" onMouseDown={onClose} />
      <aside className="op-drawer">
        <div className="op-drawer-head">
          <div>
            <h3>{contact.email}</h3>
            <span className="sub">수신자 상세</span>
          </div>
          <button className="op-drawer-close" onClick={onClose} aria-label="닫기">✕</button>
        </div>
        <div className="op-drawer-body">
          <div className="op-drawer-section">
            <div className="st">기본 정보</div>
            <div className="op-drawer-kv"><span className="k">이름</span><span className="v">{fullName}</span></div>
            <div className="op-drawer-kv"><span className="k">등록일</span><span className="v">{dateOf(contact.createdAt)}</span></div>
          </div>

          <div className="op-drawer-section">
            <div className="st">구독 상태</div>
            <div className="op-sub-row">
              <SubBadge sub={sub} />
              {sub && (
                <button
                  className="op-btn op-btn-sm op-btn-ghost"
                  style={{ height: 36, padding: "0 14px", fontSize: 13 }}
                  disabled={togglingSub}
                  onClick={toggleSubscription}
                >
                  {togglingSub ? "변경 중…" : sub.suppressed ? "구독 복원" : "수신거부 처리"}
                </button>
              )}
            </div>
            {sub?.suppressed && (
              <p className="op-sub-note">
                사유: {sub.reason ?? "-"}{sub.since ? ` · ${dateOf(sub.since)}부터` : ""}
              </p>
            )}
          </div>

          <div className="op-drawer-section">
            <div className="st">리스트</div>
            {lists.length === 0 && <p className="op-sub-note">아직 만든 리스트가 없어요.</p>}
            {lists.map((l) => (
              <label key={l.id} className="op-checkrow">
                <input type="checkbox" checked={checked.has(l.id)} onChange={() => toggle(l.id)} />
                <span>{l.name}</span>
                <span className="cnt">{l.memberCount}명</span>
              </label>
            ))}
            {lists.length > 0 && (
              <div className="op-modal-foot" style={{ marginTop: 14 }}>
                <button className="op-btn op-btn-sm" disabled={!dirty || savingLists} onClick={saveLists}>
                  {savingLists ? "저장 중…" : "리스트 저장"}
                </button>
              </div>
            )}
          </div>

          {error && <div className="op-modal-error">{error}</div>}
        </div>
      </aside>
    </Portal>
  );
}

/* ---------------------------------- page ----------------------------------- */

export default function Recipients() {
  const [contacts, setContacts] = useState<ContactView[]>([]);
  const [lists, setLists] = useState<ContactListView[]>([]);
  const [subs, setSubs] = useState<Record<number, SubscriptionView>>({});
  const [memberships, setMemberships] = useState<Record<number, number[]>>({});
  const [loaded, setLoaded] = useState(false);
  const [adding, setAdding] = useState(false);
  const [importing, setImporting] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [cRes, lRes] = await Promise.all([api("/api/contacts"), api("/api/lists")]);
      const nextContacts: ContactView[] = cRes.ok ? await cRes.json() : [];
      const nextLists: ContactListView[] = lRes.ok ? await lRes.json() : [];
      setContacts(nextContacts);
      setLists(nextLists);

      // One parallel pass for per-contact subscription + memberships (POC scale).
      const details = await Promise.all(nextContacts.map(async (c) => {
        try {
          const [sRes, mRes] = await Promise.all([
            api(`/api/contacts/${c.id}/subscription`),
            api(`/api/contacts/${c.id}/lists`),
          ]);
          return {
            id: c.id,
            sub: sRes.ok ? ((await sRes.json()) as SubscriptionView) : null,
            listIds: mRes.ok ? ((await mRes.json()) as number[]) : null,
          };
        } catch {
          return { id: c.id, sub: null, listIds: null };
        }
      }));
      const nextSubs: Record<number, SubscriptionView> = {};
      const nextMemberships: Record<number, number[]> = {};
      for (const d of details) {
        if (d.sub) nextSubs[d.id] = d.sub;
        if (d.listIds) nextMemberships[d.id] = d.listIds;
      }
      setSubs(nextSubs);
      setMemberships(nextMemberships);
    } catch {
      /* transient / unauthorized handled by api() */
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const listName = (id: number) => lists.find((l) => l.id === id)?.name ?? `#${id}`;
  const selected = contacts.find((c) => c.id === selectedId) ?? null;

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>수신자</h2>
          <p>{loaded ? `총 ${contacts.length.toLocaleString()}명의 수신자를 관리하고 있어요.` : "수신자를 불러오는 중이에요."}</p>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setImporting(true)}>CSV 가져오기</button>
          <button className="op-btn op-btn-sm" onClick={() => setAdding(true)}>
            <span className="op-btn-plus">+</span>수신자 추가
          </button>
        </div>
      </div>

      <div className="op-card">
        <div className="op-thead" style={{ gridTemplateColumns: COLS }}>
          <span>이메일</span>
          <span>리스트</span>
          <span>구독 상태</span>
          <span>생성일</span>
        </div>
        {contacts.map((c) => (
          <div
            key={c.id}
            className="op-trow clickable"
            style={{ gridTemplateColumns: COLS }}
            onClick={() => setSelectedId(c.id)}
          >
            <span className="strong" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{c.email}</span>
            <span className="op-cell-chips">
              {(memberships[c.id] ?? []).map((id) => (
                <span key={id} className="op-minibadge blue">{listName(id)}</span>
              ))}
              {(memberships[c.id] ?? []).length === 0 && <span className="faint">-</span>}
            </span>
            <span><SubBadge sub={subs[c.id]} /></span>
            <span className="faint">{dateOf(c.createdAt)}</span>
          </div>
        ))}
        {loaded && contacts.length === 0 && (
          <div className="op-list-row">
            <span className="meta">아직 수신자가 없어요. 오른쪽 위 버튼으로 추가하거나 CSV를 가져와 보세요.</span>
          </div>
        )}
      </div>

      {adding && <AddContactModal onClose={() => setAdding(false)} onSaved={refresh} />}
      {importing && <ImportModal lists={lists} onClose={() => setImporting(false)} onImported={refresh} />}
      {selected && (
        <ContactDrawer
          key={selected.id}
          contact={selected}
          lists={lists}
          sub={subs[selected.id]}
          memberIds={memberships[selected.id] ?? []}
          onClose={() => setSelectedId(null)}
          onChanged={(sub, listIds) => {
            if (sub) setSubs((prev) => ({ ...prev, [selected.id]: sub }));
            if (listIds) {
              setMemberships((prev) => ({ ...prev, [selected.id]: listIds }));
              // Member counts changed — refetch lists only.
              api("/api/lists").then(async (r) => { if (r.ok) setLists(await r.json()); }).catch(() => {});
            }
          }}
        />
      )}
    </div>
  );
}
