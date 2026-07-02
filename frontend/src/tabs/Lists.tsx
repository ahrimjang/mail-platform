import { useEffect, useState } from "react";
import { api } from "../api";
import type { ContactListView, ContactView } from "../types";

export default function Lists() {
  const [lists, setLists] = useState<ContactListView[]>([]);
  const [contacts, setContacts] = useState<ContactView[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [members, setMembers] = useState<ContactView[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [addContactId, setAddContactId] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    try {
      const [l, c] = await Promise.all([api("/api/lists"), api("/api/contacts")]);
      if (l.ok) setLists(await l.json());
      if (c.ok) setContacts(await c.json());
    } catch {
      /* ignore */
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  async function loadMembers(listId: number) {
    try {
      const res = await api(`/api/lists/${listId}/members`);
      if (res.ok) setMembers(await res.json());
    } catch {
      /* ignore */
    }
  }

  async function select(listId: number) {
    setSelectedId(listId);
    setAddContactId("");
    await loadMembers(listId);
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const res = await api("/api/lists", {
        method: "POST",
        body: JSON.stringify({ name, description }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "리스트 생성에 실패했습니다.");
        return;
      }
      setName("");
      setDescription("");
      await refresh();
    } catch {
      setError("리스트 생성에 실패했습니다.");
    }
  }

  async function removeList(id: number) {
    try {
      const res = await api(`/api/lists/${id}`, { method: "DELETE" });
      if (res.ok || res.status === 204) {
        if (selectedId === id) {
          setSelectedId(null);
          setMembers([]);
        }
        await refresh();
      }
    } catch {
      /* ignore */
    }
  }

  async function addMember() {
    if (selectedId === null || !addContactId) return;
    try {
      const res = await api(`/api/lists/${selectedId}/members/${addContactId}`, {
        method: "POST",
      });
      if (res.ok || res.status === 204) {
        setAddContactId("");
        await Promise.all([loadMembers(selectedId), refresh()]);
      }
    } catch {
      /* ignore */
    }
  }

  async function removeMember(contactId: number) {
    if (selectedId === null) return;
    try {
      const res = await api(`/api/lists/${selectedId}/members/${contactId}`, {
        method: "DELETE",
      });
      if (res.ok || res.status === 204) {
        await Promise.all([loadMembers(selectedId), refresh()]);
      }
    } catch {
      /* ignore */
    }
  }

  const memberIds = new Set(members.map((m) => m.id));
  const candidates = contacts.filter((c) => !memberIds.has(c.id));
  const selectedList = lists.find((l) => l.id === selectedId) ?? null;

  return (
    <div>
      <section className="card">
        <h2>리스트 생성</h2>
        <form onSubmit={create} className="form-grid">
          <label>
            이름
            <input value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label>
            설명
            <input value={description} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <button type="submit">생성</button>
        </form>
        {error && <p className="error">{error}</p>}
      </section>

      <section className="card">
        <h2>리스트</h2>
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>이름</th>
              <th>설명</th>
              <th>멤버 수</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {lists.length === 0 && (
              <tr>
                <td colSpan={5} className="empty">
                  아직 리스트가 없습니다.
                </td>
              </tr>
            )}
            {lists.map((l) => (
              <tr
                key={l.id}
                className={l.id === selectedId ? "selected" : "clickable"}
                onClick={() => select(l.id)}
              >
                <td>{l.id}</td>
                <td>{l.name}</td>
                <td>{l.description ?? ""}</td>
                <td>{l.memberCount}</td>
                <td>
                  <button
                    type="button"
                    className="danger"
                    onClick={(e) => {
                      e.stopPropagation();
                      removeList(l.id);
                    }}
                  >
                    삭제
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {selectedList && (
        <section className="card">
          <h2>멤버 — {selectedList.name}</h2>
          <table>
            <thead>
              <tr>
                <th>이메일</th>
                <th>이름</th>
                <th>성</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {members.length === 0 && (
                <tr>
                  <td colSpan={4} className="empty">
                    멤버가 없습니다.
                  </td>
                </tr>
              )}
              {members.map((m) => (
                <tr key={m.id}>
                  <td>{m.email}</td>
                  <td>{m.firstName ?? ""}</td>
                  <td>{m.lastName ?? ""}</td>
                  <td>
                    <button type="button" className="danger" onClick={() => removeMember(m.id)}>
                      제거
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="button-row">
            <select value={addContactId} onChange={(e) => setAddContactId(e.target.value)}>
              <option value="">-- 연락처 추가 --</option>
              {candidates.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.email}
                </option>
              ))}
            </select>
            <button type="button" onClick={addMember} disabled={!addContactId}>
              추가
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
