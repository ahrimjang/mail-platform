import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import type { TemplateView } from "../types";
import Portal from "../components/Portal";
import { renderPreview } from "../outpace/starters";
import {
  BG_SWATCHES,
  BLOCK_NAMES,
  BTN_COLORS,
  DEFAULTS,
  TEXT_COLORS,
  type Align,
  type Block,
  type BlockType,
  blocksToHtmlBody,
  defaultBlocks,
  newBlock,
  parseBlocksMarker,
  renderBlocksHtml,
  safeImageUrl,
  sanitizeRich,
} from "../outpace/blocks";

const PALETTE: BlockType[] = ["text", "image", "button", "two", "divider", "footer"];

type DragState = { kind: "move"; id: string } | { kind: "new"; t: BlockType } | null;

function escText(s: string): string {
  return s.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

/* Canvas background for a block: color + optional cover image (mirrors bgOf). */
function bgStyle(b: Block): React.CSSProperties {
  const img = safeImageUrl(b.bgImage);
  return {
    backgroundColor: b.bg,
    ...(img ? { backgroundImage: `url('${img}')`, backgroundSize: "cover", backgroundPosition: "center" } : {}),
  };
}

/* Hidden-input file picker → POST /api/uploads → public URL callback. */
function UploadButton({ onUploaded, label }: { onUploaded: (url: string) => void; label?: string }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function handle(file: File) {
    setBusy(true);
    setErr(null);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const res = await api("/api/uploads", { method: "POST", body: fd });
      if (res.ok) {
        onUploaded((await res.json()).url);
      } else {
        const data = await res.json().catch(() => ({}));
        setErr(data.error ?? "업로드에 실패했습니다.");
      }
    } catch {
      setErr("업로드에 실패했습니다.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="op-uploadrow">
      <input
        ref={inputRef}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp"
        style={{ display: "none" }}
        onChange={(e) => { const f = e.target.files?.[0]; if (f) handle(f); e.target.value = ""; }}
      />
      <button type="button" className="op-upload-btn" disabled={busy} onClick={() => inputRef.current?.click()}>
        {busy ? "업로드 중…" : (label ?? "이미지 업로드")}
      </button>
      {err && <span className="op-editor-error">{err}</span>}
    </div>
  );
}

/* Inline-editable region. Uncontrolled while focused (state commits on blur),
   so typing never fights React re-renders. rich=false keeps content plain. */
function Editable({ html, rich, onCommit, onRichFocus, style, tag }: {
  html: string;
  rich?: boolean;
  onCommit: (value: string) => void;
  onRichFocus?: (active: boolean) => void;
  style?: React.CSSProperties;
  tag?: "div" | "span";
}) {
  const Tag = tag ?? "div";
  return (
    <Tag
      contentEditable
      suppressContentEditableWarning
      className="op-editable"
      style={style}
      dangerouslySetInnerHTML={{ __html: html }}
      onFocus={() => { if (rich) onRichFocus?.(true); }}
      onBlur={(e) => {
        if (rich) onRichFocus?.(false);
        const el = e.currentTarget;
        onCommit(rich ? sanitizeRich(el.innerHTML) : (el.textContent ?? ""));
      }}
      onKeyDown={(e) => {
        if (!rich && e.key === "Enter") { e.preventDefault(); (e.currentTarget as HTMLElement).blur(); }
      }}
    />
  );
}

/* Selection controls floating above a selected box; ⋮⋮ is the drag handle. */
function BoxControls({ onUp, onDown, onDup, onDel, onDragStart, onDragEnd }: {
  onUp: () => void; onDown: () => void; onDup: () => void; onDel: () => void;
  onDragStart: (e: React.DragEvent) => void; onDragEnd: () => void;
}) {
  return (
    <>
      <div className="op-box-controls left" onClick={(e) => e.stopPropagation()}>
        <span
          className="op-drag-handle"
          title="드래그해서 이동"
          draggable
          onDragStart={onDragStart}
          onDragEnd={onDragEnd}
        >⋮⋮</span>
        <span title="위로" onClick={onUp}>↑</span>
        <span title="아래로" onClick={onDown}>↓</span>
      </div>
      <div className="op-box-controls right" onClick={(e) => e.stopPropagation()}>
        <span title="복제" onClick={onDup}>⧉</span>
        <span title="삭제" onClick={onDel}>✕</span>
      </div>
    </>
  );
}

/* Canvas rendering of one block — text-ish parts are edited inline. */
function BlockView({ b, patch, onRichFocus }: {
  b: Block;
  patch: (updates: Partial<Block>) => void;
  onRichFocus: (active: boolean) => void;
}) {
  const pad = (defY: number, defX: number) => `${b.padY ?? defY}px ${b.padX ?? defX}px`;
  switch (b.type) {
    case "text": {
      const d = DEFAULTS.text;
      return (
        <div style={{ padding: pad(d.padY, d.padX), ...bgStyle(b), textAlign: b.align }}>
          <Editable
            html={b.heading.trim() ? escText(b.heading) : ""}
            onCommit={(v) => patch({ heading: v })}
            style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", marginBottom: 12, minHeight: b.heading.trim() ? undefined : 0 }}
          />
          <Editable
            html={b.body}
            rich
            onRichFocus={onRichFocus}
            onCommit={(v) => patch({ body: v })}
            style={{ fontSize: b.fontSize ?? d.fontSize, color: b.color ?? d.color, lineHeight: 1.75 }}
          />
        </div>
      );
    }
    case "image":
      return b.url.trim() ? (
        <div style={bgStyle(b)}><img src={b.url} alt={b.alt} style={{ display: "block", width: "100%" }} /></div>
      ) : (
        <div className="op-hatch" style={{ height: 150, display: "flex", alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 6 }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: "#9a9aa2", fontFamily: "ui-monospace, monospace" }}>이미지 · 권장 1200×400</div>
          <div style={{ fontSize: 11, color: "#b4b4bb" }}>오른쪽 패널에 이미지 URL을 입력하세요</div>
        </div>
      );
    case "button": {
      const d = DEFAULTS.button;
      return (
        <div style={{ padding: pad(d.padY, d.padX), ...bgStyle(b), textAlign: b.align }}>
          <span style={{ display: "inline-block", background: b.btnColor ?? d.btnColor, color: "#fff", fontSize: 14.5, fontWeight: 700, padding: "13px 32px", borderRadius: b.btnRadius ?? d.btnRadius }}>
            <Editable tag="span" html={escText(b.label)} onCommit={(v) => patch({ label: v })} />
          </span>
        </div>
      );
    }
    case "two": {
      const d = DEFAULTS.two;
      return (
        <div style={{ padding: pad(d.padY, d.padX), ...bgStyle(b), display: "grid", gridTemplateColumns: "1fr 1fr", gap: 20 }}>
          <div>
            <Editable html={escText(b.leftTitle)} onCommit={(v) => patch({ leftTitle: v })} style={{ fontSize: 14, fontWeight: 700, marginBottom: 5 }} />
            <Editable html={b.leftBody} rich onRichFocus={onRichFocus} onCommit={(v) => patch({ leftBody: v })} style={{ fontSize: b.fontSize ?? d.fontSize, color: b.color ?? d.color, lineHeight: 1.6 }} />
          </div>
          <div>
            <Editable html={escText(b.rightTitle)} onCommit={(v) => patch({ rightTitle: v })} style={{ fontSize: 14, fontWeight: 700, marginBottom: 5 }} />
            <Editable html={b.rightBody} rich onRichFocus={onRichFocus} onCommit={(v) => patch({ rightBody: v })} style={{ fontSize: b.fontSize ?? d.fontSize, color: b.color ?? d.color, lineHeight: 1.6 }} />
          </div>
        </div>
      );
    }
    case "divider": {
      const d = DEFAULTS.divider;
      return <div style={{ padding: pad(d.padY, d.padX), ...bgStyle(b) }}><hr style={{ border: "none", borderTop: "1px solid #e4e4e7", margin: 0 }} /></div>;
    }
    case "footer": {
      const d = DEFAULTS.footer;
      return (
        <div style={{ padding: pad(d.padY, d.padX), ...bgStyle(b), textAlign: "center" }}>
          <Editable html={b.text} rich onRichFocus={onRichFocus} onCommit={(v) => patch({ text: v })} style={{ fontSize: 12, color: b.color ?? d.color, lineHeight: 1.75 }} />
        </div>
      );
    }
  }
}

/* Right-panel fields for the selected block: content that can't be edited
   inline (URLs) plus the style controls (padding / fonts / colors / shape). */
function BlockPanel({ b, patch }: { b: Block; patch: (updates: Partial<Block>) => void }) {
  const numOr = (v: string): number | undefined => (v === "" ? undefined : Math.max(0, Number(v)));
  const d = DEFAULTS[b.type];

  return (
    <>
      {b.type === "image" && (
        <>
          <label className="op-rp-field"><span>이미지 URL</span>
            <input className="op-input rp" value={b.url} onChange={(e) => patch({ url: e.target.value })} placeholder="https://… 또는 아래에서 업로드" />
          </label>
          <UploadButton onUploaded={(url) => patch({ url })} />
          <label className="op-rp-field" style={{ marginTop: 12 }}><span>대체 텍스트</span>
            <input className="op-input rp" value={b.alt} onChange={(e) => patch({ alt: e.target.value })} />
          </label>
        </>
      )}
      {b.type === "button" && (
        <label className="op-rp-field"><span>링크 URL</span>
          <input className="op-input rp" value={b.url} onChange={(e) => patch({ url: e.target.value })} placeholder="https://…" />
        </label>
      )}
      {b.type !== "image" && b.type !== "button" && b.type !== "divider" && (
        <p className="op-rp-note" style={{ marginTop: 0 }}>텍스트는 캔버스에서 직접 수정하세요. 본문은 드래그 후 <b>Ctrl+B</b>(굵게)·<b>Ctrl+I</b>(기울임)·상단 툴바로 꾸밀 수 있어요.</p>
      )}

      {/* alignment (text/button) */}
      {(b.type === "text" || b.type === "button") && (
        <>
          <div className="op-rp-section">정렬</div>
          <div className="op-aligns">
            {(["left", "center", "right"] as Align[]).map((a, i) => (
              <div
                key={a}
                className={`op-align ${(["start", "center", "end"] as const)[i]}${b.align === a ? " active" : ""}`}
                onClick={() => patch({ align: a })}
              >
                <span className="l1" /><span className="l2" />
              </div>
            ))}
          </div>
        </>
      )}

      {/* padding */}
      <div className="op-rp-section">여백 (px)</div>
      <div className="op-stylerow">
        <span className="sl">상하</span>
        <input className="op-num" type="number" min={0} max={80} placeholder={String(d.padY)}
          value={b.padY ?? ""} onChange={(e) => patch({ padY: numOr(e.target.value) })} />
        <span className="sl">좌우</span>
        <input className="op-num" type="number" min={0} max={80} placeholder={String(d.padX)}
          value={b.padX ?? ""} onChange={(e) => patch({ padX: numOr(e.target.value) })} />
      </div>

      {/* font size + text color */}
      {(b.type === "text" || b.type === "two") && (
        <>
          <div className="op-rp-section">본문 글자</div>
          <div className="op-stylerow">
            <span className="sl">크기</span>
            <input className="op-num" type="number" min={10} max={28}
              placeholder={String((DEFAULTS[b.type] as { fontSize: number }).fontSize)}
              value={b.fontSize ?? ""} onChange={(e) => patch({ fontSize: numOr(e.target.value) })} />
          </div>
          <div className="op-swatches">
            {TEXT_COLORS.map((c) => (
              <span key={c} className={`op-swatch${(b.color ?? (DEFAULTS[b.type] as { color: string }).color) === c ? " active" : ""}`}
                style={{ background: c }} onClick={() => patch({ color: c })} />
            ))}
          </div>
        </>
      )}
      {b.type === "footer" && (
        <>
          <div className="op-rp-section">글자색</div>
          <div className="op-swatches">
            {TEXT_COLORS.map((c) => (
              <span key={c} className={`op-swatch${(b.color ?? DEFAULTS.footer.color) === c ? " active" : ""}`}
                style={{ background: c }} onClick={() => patch({ color: c })} />
            ))}
          </div>
        </>
      )}

      {/* button shape */}
      {b.type === "button" && (
        <>
          <div className="op-rp-section">버튼 색</div>
          <div className="op-swatches">
            {BTN_COLORS.map((c) => (
              <span key={c} className={`op-swatch${(b.btnColor ?? DEFAULTS.button.btnColor) === c ? " active" : ""}`}
                style={{ background: c }} onClick={() => patch({ btnColor: c })} />
            ))}
          </div>
          <div className="op-rp-section">모서리 라운드</div>
          <div className="op-stylerow">
            <input className="op-range" type="range" min={0} max={24}
              value={b.btnRadius ?? DEFAULTS.button.btnRadius}
              onChange={(e) => patch({ btnRadius: Number(e.target.value) })} />
            <span className="sl">{b.btnRadius ?? DEFAULTS.button.btnRadius}px</span>
          </div>
        </>
      )}

      {/* background: swatches + free color + optional cover image */}
      <div className="op-rp-section">배경색</div>
      <div className="op-swatches">
        {BG_SWATCHES.map((c) => (
          <span key={c} className={`op-swatch${b.bg === c ? " active" : ""}`} style={{ background: c }} onClick={() => patch({ bg: c })} />
        ))}
        <label className={`op-swatch custom${!BG_SWATCHES.includes(b.bg) ? " active" : ""}`} title="직접 선택">
          <input
            type="color"
            value={/^#[0-9a-fA-F]{6}$/.test(b.bg) ? b.bg : "#ffffff"}
            onChange={(e) => patch({ bg: e.target.value })}
          />
        </label>
      </div>

      <div className="op-rp-section">배경 이미지</div>
      <label className="op-rp-field"><span>이미지 URL (선택)</span>
        <input
          className="op-input rp"
          value={b.bgImage ?? ""}
          onChange={(e) => patch({ bgImage: e.target.value || undefined })}
          placeholder="https://… 또는 아래에서 업로드"
        />
      </label>
      <div className="op-stylerow" style={{ marginBottom: 4 }}>
        <UploadButton label="배경 이미지 업로드" onUploaded={(url) => patch({ bgImage: url })} />
        {b.bgImage && (
          <button type="button" className="op-linkbtn" style={{ fontSize: 12.5, color: "var(--op-red)" }} onClick={() => patch({ bgImage: undefined })}>
            제거
          </button>
        )}
      </div>
    </>
  );
}

export default function EmailEditor() {
  const nav = useNavigate();
  const { id } = useParams();

  const [name, setName] = useState("새 이메일");
  const [subject, setSubject] = useState("");
  const [blocks, setBlocks] = useState<Block[]>(() => defaultBlocks());
  const [sel, setSel] = useState<string | null>(null);
  const [drag, setDrag] = useState<DragState>(null);
  const [overIdx, setOverIdx] = useState<number | null>(null);
  const [richActive, setRichActive] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);

  // Edit mode: restore block structure from the saved template's marker.
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await api(`/api/templates/${id}`);
        if (!res.ok) { if (!cancelled) setLoadError("템플릿을 불러오지 못했습니다."); return; }
        const t: TemplateView = await res.json();
        if (cancelled) return;
        const restored = parseBlocksMarker(t.htmlBody);
        if (!restored) {
          setLoadError("이 템플릿은 블록 에디터로 만든 템플릿이 아니에요. HTML 에디터에서 열어주세요.");
          return;
        }
        setName(t.name);
        setSubject(t.subject);
        setBlocks(restored);
      } catch {
        if (!cancelled) setLoadError("템플릿을 불러오지 못했습니다.");
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  const selected = blocks.find((b) => b.id === sel) ?? null;

  function patchBlock(blockId: string, updates: Partial<Block>) {
    setBlocks((prev) => prev.map((b) => (b.id === blockId ? ({ ...b, ...updates } as Block) : b)));
  }

  function move(blockId: string, dir: -1 | 1) {
    setBlocks((prev) => {
      const i = prev.findIndex((b) => b.id === blockId);
      const j = i + dir;
      if (i < 0 || j < 0 || j >= prev.length) return prev;
      const next = [...prev];
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });
  }

  function duplicate(blockId: string) {
    setBlocks((prev) => {
      const i = prev.findIndex((b) => b.id === blockId);
      if (i < 0) return prev;
      const copy = { ...prev[i], id: newBlock(prev[i].type).id };
      const next = [...prev];
      next.splice(i + 1, 0, copy);
      return next;
    });
  }

  function remove(blockId: string) {
    setBlocks((prev) => prev.filter((b) => b.id !== blockId));
    setSel((s) => (s === blockId ? null : s));
  }

  function add(type: BlockType, at?: number) {
    const b = newBlock(type);
    setBlocks((prev) => {
      const next = [...prev];
      next.splice(at ?? next.length, 0, b);
      return next;
    });
    setSel(b.id);
  }

  function clearDrag() {
    setDrag(null);
    setOverIdx(null);
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    if (!drag || overIdx === null) { clearDrag(); return; }
    if (drag.kind === "new") {
      add(drag.t, overIdx);
    } else {
      setBlocks((prev) => {
        const from = prev.findIndex((b) => b.id === drag.id);
        if (from < 0) return prev;
        const next = [...prev];
        const [moved] = next.splice(from, 1);
        next.splice(overIdx > from ? overIdx - 1 : overIdx, 0, moved);
        return next;
      });
    }
    clearDrag();
  }

  /* rich-text toolbar actions (operate on the current selection) */
  function execRich(cmd: "bold" | "italic") {
    document.execCommand(cmd);
  }
  function execLink() {
    const selApi = window.getSelection();
    if (!selApi || selApi.rangeCount === 0 || selApi.isCollapsed) return;
    const range = selApi.getRangeAt(0);
    const url = window.prompt("링크 URL (https://…)");
    if (!url || !/^(https?:|mailto:)/i.test(url)) return;
    selApi.removeAllRanges();
    selApi.addRange(range);
    document.execCommand("createLink", false, url);
  }

  async function save(): Promise<boolean> {
    // commit any in-progress inline edit before serializing
    (document.activeElement as HTMLElement | null)?.blur?.();
    await new Promise((r) => setTimeout(r, 0));
    if (!name.trim() || !subject.trim() || blocks.length === 0) {
      setError("이름, 제목을 입력하고 상자를 1개 이상 두세요.");
      return false;
    }
    setSaving(true);
    setError(null);
    try {
      const payload = JSON.stringify({ name: name.trim(), subject: subject.trim(), htmlBody: blocksToHtmlBody(blocks) });
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
      if (!id) nav(`/editor/${view.id}`, { replace: true });
      return true;
    } catch {
      setError("저장에 실패했습니다.");
      return false;
    } finally {
      setSaving(false);
    }
  }

  if (loadError) {
    return (
      <div className="op-editor">
        <div className="op-editor-bar">
          <div className="op-editor-bar-left">
            <span className="op-back" style={{ margin: 0 }} onClick={() => nav("/templates")}>← 템플릿</span>
          </div>
        </div>
        <div style={{ padding: 40, color: "var(--op-muted)", fontSize: 14 }}>{loadError}</div>
      </div>
    );
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
          <span className="op-autosave">{savedAt ? `저장됨 ${savedAt}` : id ? "저장된 템플릿" : "저장 전"}</span>
        </div>
        <div className="op-editor-actions">
          {error && <span className="op-editor-error">{error}</span>}
          <button className="op-tbtn" onClick={() => setPreviewOpen(true)}>미리보기</button>
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
        {/* canvas */}
        <div
          className="op-canvas"
          onClick={() => setSel(null)}
          onDragOver={(e) => { if (drag) { e.preventDefault(); } }}
          onDrop={handleDrop}
        >
          {/* floating rich-text toolbar (visible while a rich field is focused) */}
          {richActive && (
            <div className="op-richbar" onMouseDown={(e) => e.preventDefault()}>
              <button onClick={() => execRich("bold")}><b>B</b></button>
              <button onClick={() => execRich("italic")}><i>I</i></button>
              <button onClick={execLink}>링크</button>
            </div>
          )}
          <div
            className="op-sheet"
            onDragOver={(e) => {
              if (!drag) return;
              e.preventDefault();
              if (e.target === e.currentTarget) setOverIdx(blocks.length);
            }}
          >
            {blocks.map((b, i) => {
              const indTop = drag && overIdx === i;
              const indBottom = drag && overIdx === blocks.length && i === blocks.length - 1;
              return (
                <div
                  key={b.id}
                  className={`op-box${sel === b.id ? " selected" : ""}${indTop ? " drop-ind-top" : ""}${indBottom ? " drop-ind-bottom" : ""}`}
                  onClick={(e) => { e.stopPropagation(); setSel(b.id); }}
                  onDragOver={(e) => {
                    if (!drag) return;
                    e.preventDefault();
                    const r = e.currentTarget.getBoundingClientRect();
                    setOverIdx(i + (e.clientY > r.top + r.height / 2 ? 1 : 0));
                  }}
                >
                  {sel === b.id && (
                    <BoxControls
                      onUp={() => move(b.id, -1)}
                      onDown={() => move(b.id, 1)}
                      onDup={() => duplicate(b.id)}
                      onDel={() => remove(b.id)}
                      onDragStart={(e) => {
                        e.dataTransfer.setData("text/plain", b.id);
                        e.dataTransfer.effectAllowed = "move";
                        setDrag({ kind: "move", id: b.id });
                      }}
                      onDragEnd={clearDrag}
                    />
                  )}
                  <BlockView b={b} patch={(u) => patchBlock(b.id, u)} onRichFocus={setRichActive} />
                </div>
              );
            })}
            {blocks.length === 0 && (
              <div style={{ padding: 60, textAlign: "center", color: "var(--op-faint)", fontSize: 13 }}>
                오른쪽에서 상자를 추가하거나 끌어다 놓으세요.
              </div>
            )}
          </div>
        </div>

        {/* right panel */}
        <div className="op-rpanel">
          {selected === null ? (
            <div className="op-rpanel-pad">
              <h3>편집 상자 추가</h3>
              <p className="op-rp-sub">클릭하면 맨 아래에 추가되고, 원하는 위치로 끌어다 놓을 수도 있어요.</p>
              <div className="op-palette-grid">
                {PALETTE.map((t) => (
                  <div
                    key={t}
                    className="op-palette-tile"
                    draggable
                    onClick={() => add(t)}
                    onDragStart={(e) => {
                      e.dataTransfer.setData("text/plain", t);
                      e.dataTransfer.effectAllowed = "copy";
                      setDrag({ kind: "new", t });
                    }}
                    onDragEnd={clearDrag}
                  >
                    <span className="lbl">{BLOCK_NAMES[t]}</span>
                  </div>
                ))}
              </div>
              <div className="op-rp-note" style={{ marginTop: 18 }}>
                본문 텍스트는 캔버스에서 바로 수정합니다. {"{{name}}"} 같은 개인화 변수는 발송 시 수신자별로 렌더링되고, 수신거부 링크는 발송 파이프라인이 자동으로 붙어요.
              </div>
            </div>
          ) : (
            <div className="op-rpanel-pad">
              <button className="op-rp-back" onClick={() => setSel(null)}>← 상자 추가로 돌아가기</button>
              <h3>{BLOCK_NAMES[selected.type]}</h3>
              <p className="op-rp-sub">선택한 상자의 스타일을 조정합니다.</p>

              <BlockPanel b={selected} patch={(u) => patchBlock(selected.id, u)} />

              <div className="op-rp-foot">
                <button onClick={() => duplicate(selected.id)}>상자 복제</button>
                <button className="danger" onClick={() => remove(selected.id)}>삭제</button>
              </div>
            </div>
          )}
        </div>
      </div>

      {previewOpen && (
        <Portal>
          <div className="op-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setPreviewOpen(false); }}>
            <div className="op-modal wide">
              <h3>미리보기 {subject ? `· ${subject}` : ""}</h3>
              <p className="op-modal-sub">개인화 변수는 샘플 값으로 치환해 보여드려요.</p>
              <iframe
                title="block-preview"
                className="op-preview-frame boxed"
                sandbox=""
                srcDoc={`<!doctype html><html><head><meta charset="utf-8"></head><body style="margin:0;background:#f4f4f5;padding:16px">${renderPreview(renderBlocksHtml(blocks))}</body></html>`}
              />
              <div className="op-modal-foot">
                <button className="op-btn op-btn-sm op-btn-ghost" onClick={() => setPreviewOpen(false)}>닫기</button>
              </div>
            </div>
          </div>
        </Portal>
      )}
    </div>
  );
}
