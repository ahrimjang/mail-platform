import { useEffect, useState } from "react";
import { api } from "../api";
import type { RenderedTemplate, TemplateView } from "../types";

export default function Templates() {
  const [templates, setTemplates] = useState<TemplateView[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [name, setName] = useState("");
  const [subject, setSubject] = useState("");
  const [htmlBody, setHtmlBody] = useState("");
  const [varsText, setVarsText] = useState("firstName=아림\nemail=alice@example.com");
  const [preview, setPreview] = useState<RenderedTemplate | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    try {
      const res = await api("/api/templates");
      if (res.ok) setTemplates(await res.json());
    } catch {
      /* ignore */
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  function select(t: TemplateView) {
    setSelectedId(t.id);
    setName(t.name);
    setSubject(t.subject);
    setHtmlBody(t.htmlBody);
    setPreview(null);
    setError(null);
  }

  function clearForm() {
    setSelectedId(null);
    setName("");
    setSubject("");
    setHtmlBody("");
    setPreview(null);
    setError(null);
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const payload = JSON.stringify({ name, subject, htmlBody });
      const res =
        selectedId === null
          ? await api("/api/templates", { method: "POST", body: payload })
          : await api(`/api/templates/${selectedId}`, { method: "PUT", body: payload });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "저장에 실패했습니다.");
        return;
      }
      await refresh();
      if (selectedId === null) clearForm();
    } catch {
      setError("저장에 실패했습니다.");
    }
  }

  async function remove() {
    if (selectedId === null) return;
    try {
      const res = await api(`/api/templates/${selectedId}`, { method: "DELETE" });
      if (res.ok || res.status === 204) {
        clearForm();
        await refresh();
      }
    } catch {
      /* ignore */
    }
  }

  function parseVars(): Record<string, string> {
    const vars: Record<string, string> = {};
    for (const line of varsText.split(/\r?\n/)) {
      const idx = line.indexOf("=");
      if (idx <= 0) continue;
      vars[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
    }
    return vars;
  }

  async function runPreview() {
    if (selectedId === null) return;
    setError(null);
    try {
      const res = await api(`/api/templates/${selectedId}/preview`, {
        method: "POST",
        body: JSON.stringify(parseVars()),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "미리보기에 실패했습니다.");
        return;
      }
      setPreview(await res.json());
    } catch {
      setError("미리보기에 실패했습니다.");
    }
  }

  return (
    <div className="two-col">
      <section className="card">
        <h2>템플릿 목록</h2>
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>이름</th>
              <th>제목</th>
            </tr>
          </thead>
          <tbody>
            {templates.length === 0 && (
              <tr>
                <td colSpan={3} className="empty">
                  아직 템플릿이 없습니다.
                </td>
              </tr>
            )}
            {templates.map((t) => (
              <tr
                key={t.id}
                className={t.id === selectedId ? "selected" : "clickable"}
                onClick={() => select(t)}
              >
                <td>{t.id}</td>
                <td>{t.name}</td>
                <td>{t.subject}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <button type="button" onClick={clearForm}>
          새 템플릿
        </button>
      </section>

      <section className="card">
        <h2>{selectedId === null ? "템플릿 생성" : `템플릿 수정 (#${selectedId})`}</h2>
        <form onSubmit={save} className="form-grid">
          <label>
            이름
            <input value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label>
            제목
            <input value={subject} onChange={(e) => setSubject(e.target.value)} />
          </label>
          <label>
            본문 (HTML, {"{{변수}}"} 사용 가능)
            <textarea value={htmlBody} onChange={(e) => setHtmlBody(e.target.value)} rows={6} />
          </label>
          <div className="button-row">
            <button type="submit">{selectedId === null ? "생성" : "수정"}</button>
            {selectedId !== null && (
              <button type="button" className="danger" onClick={remove}>
                삭제
              </button>
            )}
          </div>
        </form>
        {error && <p className="error">{error}</p>}

        {selectedId !== null && (
          <div className="preview-section">
            <h3>미리보기</h3>
            <label>
              변수 (한 줄당 key=value)
              <textarea value={varsText} onChange={(e) => setVarsText(e.target.value)} rows={3} />
            </label>
            <button type="button" onClick={runPreview}>
              미리보기 실행
            </button>
            {preview && (
              <div className="preview-box">
                <p>
                  <strong>제목:</strong> {preview.subject}
                </p>
                <div dangerouslySetInnerHTML={{ __html: preview.htmlBody }} />
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
