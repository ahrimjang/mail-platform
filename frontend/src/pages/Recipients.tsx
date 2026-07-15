import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import Portal from "../components/Portal";
import type {
  ContactActivityType,
  ContactActivityView,
  ContactListView,
  ContactMessageView,
  ContactView,
  ImportResult,
  MessageStatus,
  SubscriptionView,
  UpdateContactListsRequest,
  UpdateContactRequest,
  UpdateSubscriptionRequest,
} from "../types";

/* Column template shared by the header and body rows. */
const COLS = "minmax(0, 2.2fr) minmax(0, 2fr) 110px 110px";

function dateOf(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString("ko-KR") : "-";
}

/* Badge tone + Korean label per activity timeline row type. */
const ACTIVITY_META: Record<ContactActivityType, { tone: string; label: string }> = {
  SIGNUP: { tone: "blue", label: "등록" },
  SENT: { tone: "green", label: "발송" },
  BOUNCED: { tone: "red", label: "바운스" },
  SUPPRESSED_SKIP: { tone: "gray", label: "발송 제외" },
  OPENED: { tone: "amber", label: "오픈" },
  CLICKED: { tone: "blue", label: "클릭" },
  UNSUBSCRIBED: { tone: "red", label: "수신거부" },
  LIST_OPTOUT: { tone: "gray", label: "리스트 해지" },
};

/* Badge tone + Korean label per delivery outcome (drawer's 메시지 tab). */
const MESSAGE_META: Record<MessageStatus, { tone: string; label: string }> = {
  SENT: { tone: "green", label: "발송 완료" },
  FAILED: { tone: "red", label: "발송 실패" },
  BOUNCED: { tone: "red", label: "바운스" },
  SUPPRESSED: { tone: "gray", label: "발송 제외" },
  SENDING: { tone: "blue", label: "발송 중" },
  PENDING: { tone: "blue", label: "대기 중" },
  CANCELED: { tone: "gray", label: "발송 취소" },
};

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

function ContactDrawer({ contact, lists, sub, memberIds, optOutIds, onClose, onChanged, onOptOutsChanged, onContactChanged }: {
  contact: ContactView;
  lists: ContactListView[];
  sub: SubscriptionView | undefined;
  memberIds: number[];
  optOutIds: number[];
  onClose: () => void;
  onChanged: (sub: SubscriptionView | null, listIds: number[] | null) => void;
  onOptOutsChanged: (ids: number[]) => void;
  onContactChanged: (updated: ContactView) => void;
}) {
  // Per-list subscription status; the status modal applies changes immediately.
  const [members, setMembers] = useState<Set<number>>(new Set(memberIds));
  const [statusTarget, setStatusTarget] = useState<ContactListView | null>(null);
  const [statusChoice, setStatusChoice] = useState<"active" | "opted" | "none">("active");
  const [applyingStatus, setApplyingStatus] = useState(false);
  const [togglingSub, setTogglingSub] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Editable name fields (the email is the contact's identity and stays read-only).
  const [firstName, setFirstName] = useState(contact.firstName ?? "");
  const [lastName, setLastName] = useState(contact.lastName ?? "");
  const [savingName, setSavingName] = useState(false);
  // Activity view: timeline + per-delivery list, fetched lazily per tab.
  const [actTab, setActTab] = useState<"timeline" | "messages">("timeline");
  const [activity, setActivity] = useState<ContactActivityView[] | null>(null);
  const [deliveries, setDeliveries] = useState<ContactMessageView[] | null>(null);

  const nameDirty = firstName !== (contact.firstName ?? "") || lastName !== (contact.lastName ?? "");

  useEffect(() => {
    let alive = true;
    api(`/api/contacts/${contact.id}/activity`)
      .then(async (res) => { if (alive) setActivity(res.ok ? await res.json() : []); })
      .catch(() => { if (alive) setActivity([]); });
    return () => { alive = false; };
  }, [contact.id]);

  useEffect(() => {
    if (actTab !== "messages" || deliveries !== null) return;
    let alive = true;
    api(`/api/contacts/${contact.id}/messages`)
      .then(async (res) => { if (alive) setDeliveries(res.ok ? await res.json() : []); })
      .catch(() => { if (alive) setDeliveries([]); });
    return () => { alive = false; };
  }, [actTab, deliveries, contact.id]);

  async function saveName() {
    setSavingName(true);
    setError(null);
    try {
      const body: UpdateContactRequest = {
        firstName: firstName.trim() || null,
        lastName: lastName.trim() || null,
      };
      const res = await api(`/api/contacts/${contact.id}`, {
        method: "PUT",
        body: JSON.stringify(body),
      });
      if (res.ok) {
        const updated: ContactView = await res.json();
        onContactChanged(updated);
        setFirstName(updated.firstName ?? "");
        setLastName(updated.lastName ?? "");
      } else setError("이름 저장에 실패했습니다.");
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setSavingName(false);
    }
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

  /** Current per-list status: an opt-out wins the display, then membership. */
  function statusOf(listId: number): "active" | "opted" | "none" {
    if (optOutIds.includes(listId)) return "opted";
    return members.has(listId) ? "active" : "none";
  }

  /** Apply the status modal's choice — membership PUT plus opt-out POST/DELETE as needed. */
  async function applyStatus() {
    if (!statusTarget) return;
    const listId = statusTarget.id;
    setApplyingStatus(true);
    setError(null);
    try {
      const wantMember = statusChoice !== "none";
      if (wantMember !== members.has(listId)) {
        const body: UpdateContactListsRequest = {
          listIds: wantMember ? [...members, listId] : [...members].filter((id) => id !== listId),
        };
        const res = await api(`/api/contacts/${contact.id}/lists`, { method: "PUT", body: JSON.stringify(body) });
        if (!res.ok) { setError("리스트 변경에 실패했습니다."); return; }
        const saved: number[] = await res.json();
        setMembers(new Set(saved));
        onChanged(null, saved);
      }
      const isOpted = optOutIds.includes(listId);
      if (statusChoice === "opted" && !isOpted) {
        const res = await api(`/api/contacts/${contact.id}/list-unsubscribes/${listId}`, { method: "POST" });
        if (!res.ok) { setError("해지 처리에 실패했습니다."); return; }
        onOptOutsChanged(await res.json());
      } else if (statusChoice === "active" && isOpted) {
        const res = await api(`/api/contacts/${contact.id}/list-unsubscribes/${listId}`, { method: "DELETE" });
        if (!res.ok) { setError("해지 취소에 실패했습니다."); return; }
        onOptOutsChanged(await res.json());
      }
      setStatusTarget(null);
    } catch {
      setError("요청 중 오류가 발생했습니다.");
    } finally {
      setApplyingStatus(false);
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
            <div className="op-drawer-kv"><span className="k">이메일</span><span className="v">{contact.email}</span></div>
            <p className="op-sub-note" style={{ marginTop: 0, marginBottom: 10 }}>이메일은 변경할 수 없어요.</p>
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
            <div className="op-drawer-kv"><span className="k">등록일</span><span className="v">{dateOf(contact.createdAt)}</span></div>
            <div className="op-modal-foot" style={{ marginTop: 8 }}>
              <button className="op-btn op-btn-sm" disabled={!nameDirty || savingName} onClick={saveName}>
                {savingName ? "저장 중…" : "저장"}
              </button>
            </div>
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
            <div className="st">리스트 구독</div>
            {lists.length === 0 && <p className="op-sub-note">아직 만든 리스트가 없어요.</p>}
            {lists.map((l) => {
              const st = statusOf(l.id);
              return (
                <div key={l.id} className="op-checkrow" style={{ cursor: "default" }}>
                  <span>{l.name}</span>
                  {st === "active" && <span className="op-minibadge green">구독중</span>}
                  {st === "opted" && (
                    <span className="op-minibadge gray" title="발송에서 제외됩니다 — 멤버십은 유지">해지</span>
                  )}
                  {st === "none" && <span className="faint" style={{ fontSize: 12 }}>미구독</span>}
                  <button
                    type="button"
                    className="op-linkbtn"
                    style={{ fontSize: 12.5, marginLeft: "auto" }}
                    onClick={() => { setStatusTarget(l); setStatusChoice(st); setError(null); }}
                  >
                    상태 변경
                  </button>
                </div>
              );
            })}
          </div>

          <div className="op-drawer-section">
            <div className="st">활동</div>
            <div className="op-acttabs">
              <button className={`op-acttab${actTab === "timeline" ? " on" : ""}`} onClick={() => setActTab("timeline")}>타임라인</button>
              <button className={`op-acttab${actTab === "messages" ? " on" : ""}`} onClick={() => setActTab("messages")}>메시지</button>
            </div>
            {actTab === "timeline" && (
              activity === null ? <p className="op-sub-note">활동을 불러오는 중…</p>
              : activity.length === 0 ? <p className="op-sub-note">아직 활동 기록이 없어요.</p>
              : activity.map((a, i) => (
                <div key={i} className="op-actrow">
                  <span className={`op-minibadge ${ACTIVITY_META[a.type].tone}`}>{ACTIVITY_META[a.type].label}</span>
                  <span className="what">
                    {(a.campaignName || a.campaignId != null) && (
                      <span className="camp">{a.campaignName ?? `캠페인 #${a.campaignId}`}</span>
                    )}
                    {a.detail && <span className="det" title={a.detail}>{a.detail}</span>}
                  </span>
                  <span className="when">{new Date(a.occurredAt).toLocaleString("ko-KR")}</span>
                </div>
              ))
            )}
            {actTab === "messages" && (
              deliveries === null ? <p className="op-sub-note">메시지를 불러오는 중…</p>
              : deliveries.length === 0 ? <p className="op-sub-note">아직 발송된 메일이 없어요.</p>
              : deliveries.map((m) => (
                <div key={m.messageId} className="op-actrow">
                  <span className="what">
                    <span className="camp">{m.campaignName ?? `캠페인 #${m.campaignId}`}</span>
                  </span>
                  <span className={`op-minibadge ${MESSAGE_META[m.status].tone}`}>{MESSAGE_META[m.status].label}</span>
                  <span className="when">{new Date(m.updatedAt).toLocaleString("ko-KR")}</span>
                </div>
              ))
            )}
          </div>

          {error && <div className="op-modal-error">{error}</div>}
        </div>
      </aside>

      {statusTarget && (
        <div
          className="op-modal-backdrop"
          style={{ zIndex: 60 }}
          onMouseDown={(e) => { if (e.target === e.currentTarget) setStatusTarget(null); }}
        >
          <div className="op-modal">
            <h3>‘{statusTarget.name}’ 구독 상태 변경</h3>
            <p className="op-modal-sub">{contact.email} 수신자의 이 리스트 구독 상태를 바꿉니다.</p>
            <label className="op-field">
              <span className="op-flabel">구독 상태</span>
              <select
                className="op-input"
                value={statusChoice}
                onChange={(e) => setStatusChoice(e.target.value as typeof statusChoice)}
              >
                <option value="active">구독중 — 발송 대상에 포함</option>
                <option value="opted">해지 — 리스트엔 남지만 발송 제외</option>
                <option value="none">미구독 — 리스트에서 제거</option>
              </select>
            </label>
            <p className="op-sub-note">
              {statusChoice === "active" && "이 리스트의 캠페인을 다시 받습니다. 해지 기록이 있다면 삭제됩니다."}
              {statusChoice === "opted" && "수신자가 직접 해지한 것과 같은 효과이며 사유가 manual로 남습니다. CSV 재가져오기로도 되돌아가지 않습니다."}
              {statusChoice === "none" && "리스트에서 제거합니다. 해지 기록이 있다면 그대로 남아, 나중에 다시 추가돼도 발송은 제외됩니다."}
            </p>
            {error && <div className="op-modal-error">{error}</div>}
            <div className="op-modal-foot">
              <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setStatusTarget(null)}>취소</button>
              <button className="op-btn op-btn-sm" disabled={applyingStatus} onClick={applyStatus}>
                {applyingStatus ? "적용 중…" : "적용"}
              </button>
            </div>
          </div>
        </div>
      )}
    </Portal>
  );
}

/* ---------------------------------- page ----------------------------------- */

export default function Recipients() {
  const nav = useNavigate();
  const [contacts, setContacts] = useState<ContactView[]>([]);
  const [lists, setLists] = useState<ContactListView[]>([]);
  const [subs, setSubs] = useState<Record<number, SubscriptionView>>({});
  const [memberships, setMemberships] = useState<Record<number, number[]>>({});
  // Lists each contact opted out of via the unsubscribe page (membership stays).
  const [optOuts, setOptOuts] = useState<Record<number, number[]>>({});
  const [loaded, setLoaded] = useState(false);
  const [adding, setAdding] = useState(false);
  const [importing, setImporting] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  // Table filters: free-text search, list membership, subscription state.
  const [query, setQuery] = useState("");
  const [listFilter, setListFilter] = useState<string>("all"); // "all" | "none" | list id
  const [subFilter, setSubFilter] = useState<"all" | "active" | "suppressed">("all");

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
          const [sRes, mRes, uRes] = await Promise.all([
            api(`/api/contacts/${c.id}/subscription`),
            api(`/api/contacts/${c.id}/lists`),
            api(`/api/contacts/${c.id}/list-unsubscribes`),
          ]);
          return {
            id: c.id,
            sub: sRes.ok ? ((await sRes.json()) as SubscriptionView) : null,
            listIds: mRes.ok ? ((await mRes.json()) as number[]) : null,
            optOutIds: uRes.ok ? ((await uRes.json()) as number[]) : null,
          };
        } catch {
          return { id: c.id, sub: null, listIds: null, optOutIds: null };
        }
      }));
      const nextSubs: Record<number, SubscriptionView> = {};
      const nextMemberships: Record<number, number[]> = {};
      const nextOptOuts: Record<number, number[]> = {};
      for (const d of details) {
        if (d.sub) nextSubs[d.id] = d.sub;
        if (d.listIds) nextMemberships[d.id] = d.listIds;
        if (d.optOutIds) nextOptOuts[d.id] = d.optOutIds;
      }
      setSubs(nextSubs);
      setMemberships(nextMemberships);
      setOptOuts(nextOptOuts);
    } catch {
      /* transient / unauthorized handled by api() */
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const listName = (id: number) => lists.find((l) => l.id === id)?.name ?? `#${id}`;
  const selected = contacts.find((c) => c.id === selectedId) ?? null;

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return contacts.filter((c) => {
      if (q) {
        const name = [c.lastName, c.firstName].filter(Boolean).join("");
        if (!c.email.toLowerCase().includes(q) && !name.toLowerCase().includes(q)) return false;
      }
      if (listFilter !== "all") {
        const ids = memberships[c.id] ?? [];
        if (listFilter === "none" ? ids.length > 0 : !ids.includes(Number(listFilter))) return false;
      }
      if (subFilter !== "all") {
        const sub = subs[c.id];
        if (!sub) return false;
        if (subFilter === "suppressed" ? !sub.suppressed : sub.suppressed) return false;
      }
      return true;
    });
  }, [contacts, query, listFilter, subFilter, memberships, subs]);
  const filtering = query.trim() !== "" || listFilter !== "all" || subFilter !== "all";

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

      <div className="op-toolbar" style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
        <input
          className="op-input"
          style={{ maxWidth: 260 }}
          placeholder="이메일·이름 검색"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <select className="op-input" style={{ maxWidth: 210 }} value={listFilter} onChange={(e) => setListFilter(e.target.value)}>
          <option value="all">모든 리스트</option>
          <option value="none">리스트 없음</option>
          {lists.map((l) => <option key={l.id} value={l.id}>{l.name} ({l.memberCount}명)</option>)}
        </select>
        <select className="op-input" style={{ maxWidth: 150 }} value={subFilter} onChange={(e) => setSubFilter(e.target.value as typeof subFilter)}>
          <option value="all">구독 전체</option>
          <option value="active">활성</option>
          <option value="suppressed">수신거부</option>
        </select>
        {filtering && (
          <span className="faint" style={{ fontSize: 13 }}>
            {visible.length}명 표시 · <button className="op-linkbtn" style={{ fontSize: 13 }}
              onClick={() => { setQuery(""); setListFilter("all"); setSubFilter("all"); }}>필터 초기화</button>
          </span>
        )}
      </div>

      <div className="op-card">
        <div className="op-thead" style={{ gridTemplateColumns: COLS }}>
          <span>이메일</span>
          <span>리스트</span>
          <span>구독 상태</span>
          <span>생성일</span>
        </div>
        {visible.map((c) => (
          <div
            key={c.id}
            className="op-trow clickable"
            style={{ gridTemplateColumns: COLS }}
            onClick={() => setSelectedId(c.id)}
          >
            <span className="strong" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{c.email}</span>
            <span className="op-cell-chips">
              {(memberships[c.id] ?? []).map((id) => {
                // Chip doubles as a shortcut to the list's row on the 리스트 page;
                // an opted-out list stays a member (operator grouping) but greys out.
                const opted = (optOuts[c.id] ?? []).includes(id);
                return (
                  <span
                    key={id}
                    className={`op-minibadge ${opted ? "gray" : "blue"} link`}
                    title={opted ? `'${listName(id)}' — 수신자가 이 리스트를 해지했습니다` : `'${listName(id)}' 리스트 보기`}
                    onClick={(e) => { e.stopPropagation(); nav(`/lists?focus=${id}`); }}
                  >
                    {listName(id)}{opted ? " (해지)" : ""}
                  </span>
                );
              })}
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
        {loaded && contacts.length > 0 && visible.length === 0 && (
          <div className="op-list-row">
            <span className="meta">조건에 맞는 수신자가 없습니다.</span>
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
          optOutIds={optOuts[selected.id] ?? []}
          onClose={() => setSelectedId(null)}
          onOptOutsChanged={(ids) => setOptOuts((prev) => ({ ...prev, [selected.id]: ids }))}
          onContactChanged={(updated) => setContacts((prev) => prev.map((c) => (c.id === updated.id ? updated : c)))}
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
