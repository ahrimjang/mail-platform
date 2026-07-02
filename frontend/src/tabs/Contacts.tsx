import { useEffect, useState } from "react";
import { api } from "../api";
import type { ContactListView, ContactView } from "../types";

export default function Contacts() {
  const [contacts, setContacts] = useState<ContactView[]>([]);
  const [lists, setLists] = useState<ContactListView[]>([]);
  const [email, setEmail] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [csv, setCsv] = useState("");
  const [csvListId, setCsvListId] = useState("");
  const [importMessage, setImportMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    try {
      const [c, l] = await Promise.all([api("/api/contacts"), api("/api/lists")]);
      if (c.ok) setContacts(await c.json());
      if (l.ok) setLists(await l.json());
    } catch {
      /* ignore */
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const res = await api("/api/contacts", {
        method: "POST",
        body: JSON.stringify({ email, firstName, lastName, attributes: {} }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "연락처 추가에 실패했습니다.");
        return;
      }
      setEmail("");
      setFirstName("");
      setLastName("");
      await refresh();
    } catch {
      setError("연락처 추가에 실패했습니다.");
    }
  }

  async function remove(id: number) {
    try {
      const res = await api(`/api/contacts/${id}`, { method: "DELETE" });
      if (res.ok || res.status === 204) await refresh();
    } catch {
      /* ignore */
    }
  }

  async function importCsv() {
    setImportMessage(null);
    setError(null);
    try {
      const query = csvListId ? `?listId=${csvListId}` : "";
      const res = await api(`/api/contacts/import${query}`, {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: csv,
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "임포트에 실패했습니다.");
        return;
      }
      const result = await res.json();
      setImportMessage(`${result.imported}명 추가, ${result.skipped}건 건너뜀`);
      setCsv("");
      await refresh();
    } catch {
      setError("임포트에 실패했습니다.");
    }
  }

  return (
    <div>
      <section className="card">
        <h2>연락처 추가</h2>
        <form onSubmit={add} className="form-grid">
          <label>
            이메일
            <input value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label>
            이름
            <input value={firstName} onChange={(e) => setFirstName(e.target.value)} />
          </label>
          <label>
            성
            <input value={lastName} onChange={(e) => setLastName(e.target.value)} />
          </label>
          <button type="submit">추가</button>
        </form>
        {error && <p className="error">{error}</p>}
      </section>

      <section className="card">
        <h2>연락처</h2>
        <table>
          <thead>
            <tr>
              <th>이메일</th>
              <th>이름</th>
              <th>성</th>
              <th>속성 수</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {contacts.length === 0 && (
              <tr>
                <td colSpan={5} className="empty">
                  아직 연락처가 없습니다.
                </td>
              </tr>
            )}
            {contacts.map((c) => (
              <tr key={c.id}>
                <td>{c.email}</td>
                <td>{c.firstName ?? ""}</td>
                <td>{c.lastName ?? ""}</td>
                <td>{Object.keys(c.attributes ?? {}).length}</td>
                <td>
                  <button type="button" className="danger" onClick={() => remove(c.id)}>
                    삭제
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="card">
        <h2>CSV 임포트</h2>
        <p className="muted">email,firstName,lastName 형식, 한 줄당 하나</p>
        <div className="form-grid">
          <textarea
            value={csv}
            onChange={(e) => setCsv(e.target.value)}
            rows={5}
            placeholder={"alice@example.com,앨리스,김\nbob@example.com,밥,이"}
          />
          <label>
            리스트에 추가 (옵션)
            <select value={csvListId} onChange={(e) => setCsvListId(e.target.value)}>
              <option value="">-- 리스트 없음 --</option>
              {lists.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </select>
          </label>
          <button type="button" onClick={importCsv}>
            임포트
          </button>
        </div>
        {importMessage && <p className="success">{importMessage}</p>}
      </section>
    </div>
  );
}
