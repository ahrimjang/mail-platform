import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import type { TemplateView } from "../types";
import { parseTextMarker, textToHtmlBody } from "../outpace/blocks";

/* Plain-text template editor: what you type becomes a minimal HTML body
   (escaped, paragraphs from blank lines). Personalization vars pass through
   untouched — the send pipeline renders them per recipient. */

function escapeHtml(s: string): string {
  return s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function textToHtml(text: string): string {
  const paragraphs = escapeHtml(text.trim())
    .split(/\n{2,}/)
    .map((p) => `<p style="margin:0 0 16px;font-size:15px;line-height:1.9">${p.replaceAll("\n", "<br>")}</p>`)
    .join("\n");
  return `<table width="680" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif"><tr><td style="padding:32px;background:#ffffff">\n${paragraphs}\n</td></tr></table>`;
}

export default function TextEditor() {
  const nav = useNavigate();
  const { id } = useParams();
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const [name, setName] = useState("텍스트 템플릿");
  const [subject, setSubject] = useState("");
  const [text, setText] = useState("안녕하세요 {{name}}님,\n\n여기에 내용을 작성하세요. 디자인 없이 담백한 텍스트 메일로 발송됩니다.\n\n감사합니다.");
  const [saving, setSaving] = useState(false);
  const [savedId, setSavedId] = useState<number | null>(id ? Number(id) : null);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Edit mode: restore the plain-text source from the saved template's marker.
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await api(`/api/templates/${id}`);
        if (!res.ok) { if (!cancelled) setError("템플릿을 불러오지 못했습니다."); return; }
        const t: TemplateView = await res.json();
        if (cancelled) return;
        const source = parseTextMarker(t.htmlBody);
        if (source === null) {
          setError("텍스트 에디터로 만든 템플릿이 아니에요. HTML 에디터에서 열어주세요.");
          return;
        }
        setName(t.name);
        setSubject(t.subject);
        setText(source);
      } catch {
        if (!cancelled) setError("템플릿을 불러오지 못했습니다.");
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  function insertVariable() {
    const area = areaRef.current;
    const token = "{{name}}";
    if (!area) { setText((t) => t + token); return; }
    const start = area.selectionStart ?? text.length;
    const end = area.selectionEnd ?? text.length;
    const next = text.slice(0, start) + token + text.slice(end);
    setText(next);
    requestAnimationFrame(() => {
      area.focus();
      area.setSelectionRange(start + token.length, start + token.length);
    });
  }

  async function save(): Promise<number | null> {
    if (!name.trim() || !subject.trim() || !text.trim()) {
      setError("이름, 제목, 본문을 모두 입력해 주세요.");
      return null;
    }
    setSaving(true);
    setError(null);
    try {
      const payload = JSON.stringify({
        name: name.trim(),
        subject: subject.trim(),
        // marker keeps the plain-text source so the template reopens in this editor
        htmlBody: textToHtmlBody(text, textToHtml(text)),
      });
      const res = savedId
        ? await api(`/api/templates/${savedId}`, { method: "PUT", body: payload })
        : await api("/api/templates", { method: "POST", body: payload });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "저장에 실패했습니다.");
        return null;
      }
      const view: TemplateView = await res.json();
      setSavedId(view.id);
      setSavedAt(new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }));
      if (!id && !savedId) nav(`/editor/text/${view.id}`, { replace: true });
      return view.id;
    } catch {
      setError("저장에 실패했습니다.");
      return null;
    } finally {
      setSaving(false);
    }
  }

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
          <span className="op-autosave">{savedAt ? `저장됨 ${savedAt}` : "저장 전"}</span>
        </div>
        <div className="op-editor-actions">
          {error && <span className="op-editor-error">{error}</span>}
          <button className="op-tbtn" disabled={saving} onClick={save}>{saving ? "저장 중…" : "저장"}</button>
          <button
            className="op-tbtn primary"
            disabled={saving}
            onClick={async () => { const tid = await save(); if (tid) nav(`/campaigns/new?templateId=${tid}`); }}
          >
            다음 · 발송 설정
          </button>
        </div>
      </div>

      <div className="op-editor-sub">
        <span className="lbl">제목</span>
        <input
          className="op-subject-input"
          value={subject}
          onChange={(e) => setSubject(e.target.value)}
          placeholder="이메일 제목 — {{name}} 같은 개인화 변수를 쓸 수 있어요"
        />
      </div>

      <div className="op-canvas">
        <div className="op-sheet text">
          <div className="op-texttool">
            <div className="op-texttool-left">
              <span className="op-tt wide varbtn" onClick={insertVariable}>＋ 개인화 변수</span>
            </div>
            <span className="op-tt-note">이미지는 추가할 수 없어요 · 빈 줄로 문단을 나눕니다</span>
          </div>
          <textarea
            ref={areaRef}
            className="op-text-input"
            value={text}
            onChange={(e) => setText(e.target.value)}
            spellCheck={false}
          />
        </div>
      </div>
    </div>
  );
}
