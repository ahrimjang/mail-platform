import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import VariableMenu from "../components/VariableMenu";
import type { TemplateView } from "../types";
import { parseTextMarker, textToHtmlBody } from "../outpace/blocks";
import { autoLink, escapeHtml, paragraphize } from "../outpace/plaintext";
import { useDirtyTracker, useUnsavedGuard } from "../outpace/unsaved";

/* Plain-text template editor: what you type becomes a minimal HTML body
   (escaped, paragraphs from blank lines). Personalization vars pass through
   untouched — the send pipeline renders them per recipient. 평문 → HTML 규칙은
   캠페인 작성 화면의 직접 입력과 공유한다(outpace/plaintext). */

function textToHtml(text: string): string {
  const paragraphs = paragraphize(
    autoLink(escapeHtml(text.trim())), "margin:0 0 16px;font-size:15px;line-height:1.9");
  return `<table width="680" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif"><tr><td style="padding:32px;background:#ffffff">\n${paragraphs}\n</td></tr></table>`;
}

export default function TextEditor() {
  const nav = useNavigate();
  const { id } = useParams();
  // ?target=email — 이메일(캠페인용 콘텐츠)을 상대로 열린 경우 API 만 갈아탄다
  const isEmail = new URLSearchParams(window.location.search).get("target") === "email";
  const apiBase = isEmail ? "/api/emails" : "/api/templates";
  // 저장 후 URL 을 갈아탈 때도 ?target=email 을 잃지 않아야 한다 — 잃으면 다음 저장이
  // 엉뚱하게 /api/templates 로 가서 이메일이 아닌 다른 행을 건드린다.
  const editorQuery = isEmail ? "?target=email" : "";
  // 뒤로 가기는 열린 대상이 있던 화면으로 (이메일 편집 중 템플릿 관리로 빠지지 않게)
  const backTo = isEmail ? "/emails" : "/templates";
  const backLabel = isEmail ? "← 이메일" : "← 템플릿";
  const noun = isEmail ? "이메일" : "템플릿";
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const [name, setName] = useState(isEmail ? "텍스트 이메일" : "텍스트 템플릿");
  const [subject, setSubject] = useState("");
  const [text, setText] = useState("안녕하세요 {{name}}님,\n\n여기에 내용을 작성하세요. 디자인 없이 담백한 텍스트 메일로 발송됩니다.\n\n감사합니다.");
  const [saving, setSaving] = useState(false);
  const [savedId, setSavedId] = useState<number | null>(id ? Number(id) : null);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Edit mode: restore the plain-text source from the saved template's marker.
  const [loaded, setLoaded] = useState(!id);
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await api(`${apiBase}/${id}`);
        if (!res.ok) { if (!cancelled) { setError(`${noun}을 불러오지 못했습니다.`); setLoaded(true); } return; }
        const t: TemplateView = await res.json();
        if (cancelled) return;
        const source = parseTextMarker(t.htmlBody);
        if (source === null) {
          setError(`텍스트 에디터로 만든 ${noun}이 아니에요. HTML 에디터에서 열어주세요.`);
          setLoaded(true);
          return;
        }
        setName(t.name);
        setSubject(t.subject);
        setText(source);
        setLoaded(true);
      } catch {
        if (!cancelled) { setError(`${noun}을 불러오지 못했습니다.`); setLoaded(true); }
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // 저장하지 않은 편집 보호 — 수동 저장이라 이탈 한 번에 작업이 사라진다
  const snapshot = useMemo(
    () => JSON.stringify({ name: name.trim(), subject: subject.trim(), text }),
    [name, subject, text],
  );
  const { dirty, markSaved } = useDirtyTracker(snapshot, loaded);
  const confirmLeave = useUnsavedGuard(dirty);

  function insertVariable(token: string) {
    const area = areaRef.current;
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
        ? await api(`${apiBase}/${savedId}`, { method: "PUT", body: payload })
        : await api(apiBase, { method: "POST", body: payload });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(res.status === 409
          ? "기본 제공 템플릿은 읽기 전용이에요. 템플릿 목록에서 '복사해서 편집'으로 시작하세요."
          : data.error ?? "저장에 실패했습니다.");
        return null;
      }
      const view: TemplateView = await res.json();
      setSavedId(view.id);
      setSavedAt(new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }));
      markSaved(JSON.stringify({ name: name.trim(), subject: subject.trim(), text }));
      if (!id && !savedId) nav(`/editor/text/${view.id}${editorQuery}`, { replace: true });
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
          <span className="op-back" style={{ margin: 0 }}
                onClick={() => { if (confirmLeave()) nav(backTo); }}>{backLabel}</span>
          <span className="vsep" />
          <input
            className="op-title-input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={`${noun} 이름`}
            aria-label={`${noun} 이름`}
          />
          {/* 자동 저장은 없다 — 저장 안 된 변경이 있으면 그렇다고 분명히 말한다 */}
          <span className="op-autosave" style={dirty ? { color: "var(--op-amber)", fontWeight: 700 } : undefined}>
            {dirty ? "저장 안 됨 — 저장을 눌러주세요" : savedAt ? `저장됨 ${savedAt}` : savedId ? `저장된 ${noun}` : "저장 전"}
          </span>
        </div>
        <div className="op-editor-actions">
          {error && <span className="op-editor-error">{error}</span>}
          <button className="op-tbtn" disabled={saving} onClick={save}>{saving ? "저장 중…" : "저장"}</button>
          <button
            className="op-tbtn primary"
            disabled={saving}
            onClick={async () => {
              const tid = await save();
              if (!tid) return;
              if (isEmail) { nav(`/campaigns/new?emailId=${tid}`); return; }
              // 템플릿은 발송 대상이 아니다 — 내용을 복사한 이메일을 만들어 이어간다
              const res = await api("/api/emails", { method: "POST", body: JSON.stringify({ templateId: tid }) });
              if (res.ok) {
                const created = await res.json();
                nav(`/editor/text/${created.id}?target=email`);
              }
            }}
          >
            {isEmail ? "다음 · 발송 설정" : "이메일로 만들기"}
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
              <VariableMenu onInsert={insertVariable} />
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
