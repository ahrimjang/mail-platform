import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api } from "../api";
import type { TemplateView } from "../types";
import { STARTERS, renderPreview } from "../outpace/starters";

/* Real HTML template editor: code pane + live preview, saved via the
   templates API. /editor/html creates, /editor/html/:id edits, and
   ?starter=<key> pre-fills a design-gallery starter. */
export default function HtmlEditor() {
  const nav = useNavigate();
  const { id } = useParams();
  const [params] = useSearchParams();

  const [name, setName] = useState("");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [loading, setLoading] = useState(!!id);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [device, setDevice] = useState<"desktop" | "mobile">("desktop");

  // Load target template (edit) or a starter (create from gallery).
  useEffect(() => {
    let cancelled = false;
    if (id) {
      (async () => {
        try {
          const res = await api(`/api/templates/${id}`);
          if (res.ok && !cancelled) {
            const t: TemplateView = await res.json();
            setName(t.name);
            setSubject(t.subject);
            setBody(t.htmlBody);
          } else if (!cancelled) {
            setError("템플릿을 불러오지 못했습니다.");
          }
        } catch {
          if (!cancelled) setError("템플릿을 불러오지 못했습니다.");
        } finally {
          if (!cancelled) setLoading(false);
        }
      })();
    } else {
      const starter = STARTERS[params.get("starter") ?? "blank"] ?? STARTERS.blank;
      setName(starter.name);
      setSubject(starter.subject);
      setBody(starter.htmlBody);
    }
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  async function save(): Promise<boolean> {
    if (!name.trim() || !subject.trim() || !body.trim()) {
      setError("이름, 제목, 본문을 모두 입력해 주세요.");
      return false;
    }
    setSaving(true);
    setError(null);
    try {
      const payload = JSON.stringify({ name: name.trim(), subject: subject.trim(), htmlBody: body });
      const res = id
        ? await api(`/api/templates/${id}`, { method: "PUT", body: payload })
        : await api("/api/templates", { method: "POST", body: payload });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "저장에 실패했습니다.");
        return false;
      }
      const view: TemplateView = await res.json();
      setSavedAt(new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }));
      if (!id) nav(`/editor/html/${view.id}`, { replace: true }); // keep editing the saved row
      return true;
    } catch {
      setError("저장에 실패했습니다.");
      return false;
    } finally {
      setSaving(false);
    }
  }

  const preview = useMemo(() => renderPreview(body), [body]);
  // Debounced full document for the preview iframe. Sandboxed iframes repaint
  // unreliably when srcdoc is *updated* in place (Chromium: the new doc loads
  // but can stay blank), so the iframe below is remounted per doc via key= —
  // debounced here so typing doesn't recreate the frame on every keystroke.
  const [previewDoc, setPreviewDoc] = useState("");
  useEffect(() => {
    const doc = `<!doctype html><html><head><meta charset="utf-8"></head><body style="margin:0;background:#f4f4f5;padding:16px">${preview}</body></html>`;
    const t = setTimeout(() => setPreviewDoc(doc), 250);
    return () => clearTimeout(t);
  }, [preview]);
  const lineCount = useMemo(() => body.split("\n").length, [body]);
  const gutter = useMemo(
    () => Array.from({ length: lineCount }, (_, i) => i + 1).join("\n"),
    [lineCount],
  );

  return (
    <div className="op-editor">
      <div className="op-editor-bar">
        <div className="op-editor-bar-left">
          <span className="op-back" style={{ margin: 0 }} onClick={() => nav("/templates")}>← 템플릿</span>
          <span className="vsep" />
          <input
            className="op-title-input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="템플릿 이름"
            aria-label="템플릿 이름"
          />
          <span className="op-autosave">{savedAt ? `저장됨 ${savedAt}` : id ? "저장된 템플릿" : "저장 전"}</span>
        </div>
        <div className="op-editor-actions">
          {error && <span className="op-editor-error">{error}</span>}
          <button className="op-tbtn" disabled={saving} onClick={save}>{saving ? "저장 중…" : "저장"}</button>
          <button
            className="op-tbtn primary"
            disabled={saving}
            onClick={async () => { if (await save()) nav("/campaigns/new"); }}
          >
            다음 · 발송 설정
          </button>
        </div>
      </div>

      {/* subject line */}
      <div className="op-editor-sub">
        <span className="lbl">제목</span>
        <input
          className="op-subject-input"
          value={subject}
          onChange={(e) => setSubject(e.target.value)}
          placeholder="이메일 제목 — {{name}} 같은 개인화 변수를 쓸 수 있어요"
        />
      </div>

      <div className="op-workspace">
        {/* code pane */}
        <div className="op-code-pane">
          <div className="op-code-tab"><span className="cdot" /><span className="fname">email.html</span></div>
          <div className="op-code-body">
            <pre className="op-gutter">{gutter}</pre>
            <textarea
              className="op-code-input"
              value={body}
              onChange={(e) => setBody(e.target.value)}
              spellCheck={false}
              disabled={loading}
              rows={Math.max(lineCount + 2, 16)}
            />
          </div>
          <div className="op-code-status">
            <span className="sdot" />
            <span>인라인 스타일 권장 · {"{{변수}}"}는 발송 시 수신자별로 렌더링됩니다</span>
          </div>
        </div>

        {/* preview pane */}
        <div className="op-preview-pane">
          <div className="op-preview-bar">
            <span className="lbl">미리보기 {subject ? `· ${renderPreview(subject).replace(/<[^>]+>/g, "")}` : ""}</span>
            <div className="op-toggles">
              <button className={`op-toggle${device === "desktop" ? " active" : ""}`} onClick={() => setDevice("desktop")}>데스크톱</button>
              <button className={`op-toggle${device === "mobile" ? " active" : ""}`} onClick={() => setDevice("mobile")}>모바일</button>
            </div>
          </div>
          <div className="op-preview-body">
            {previewDoc && (
              <iframe
                key={previewDoc}
                title="template-preview"
                className={`op-preview-frame${device === "mobile" ? " mobile" : ""}`}
                sandbox=""
                srcDoc={previewDoc}
              />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
