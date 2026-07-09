import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import type { TemplateView } from "../types";
import Portal from "../components/Portal";
import { editorRouteFor } from "../outpace/blocks";
import { renderPreview } from "../outpace/starters";

type Tab = "list" | "scratch";
type SourceFilter = "all" | "builtin" | "mine";

/* Display category per built-in seed key (badge on the card). */
const BUILTIN_CATEGORY: Record<string, { label: string; badge: "blue" | "amber" | "green" | "gray" }> = {
  newsletter: { label: "뉴스레터", badge: "blue" },
  promo: { label: "프로모션", badge: "amber" },
  welcome: { label: "온보딩", badge: "green" },
  repurchase: { label: "프로모션", badge: "amber" },
  receipt: { label: "트랜잭션", badge: "gray" },
};

/* Real thumbnail: the template's actual HTML, scaled down in an inert iframe.
   pointer-events off so the whole card stays one click target. */
function LiveThumb({ html }: { html: string }) {
  return (
    <div className="op-thumb-live">
      <iframe title="템플릿 미리보기" sandbox="" tabIndex={-1} srcDoc={html} scrolling="no" />
    </div>
  );
}

/* Kebab menu on a template card: built-ins offer reset, user templates delete. */
function CardMenu({ builtin, onEdit, onReset, onDelete }: {
  builtin: boolean;
  onEdit: () => void;
  onReset: () => void;
  onDelete: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  return (
    <div className="op-cardmenu" ref={ref} onClick={(e) => e.stopPropagation()}>
      <span className="op-dots" onClick={() => setOpen((o) => !o)}>···</span>
      {open && (
        <div className="op-menu" style={{ top: 26 }}>
          <button onClick={() => { setOpen(false); onEdit(); }}>수정</button>
          {builtin
            ? <button onClick={() => { setOpen(false); onReset(); }}>원본 복원</button>
            : <button className="danger" onClick={() => { setOpen(false); onDelete(); }}>삭제</button>}
        </div>
      )}
    </div>
  );
}

function DeleteTemplateModal({ target, onClose, onDeleted }: {
  target: TemplateView;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const res = await api(`/api/templates/${target.id}`, { method: "DELETE" });
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
        <h3>템플릿 삭제</h3>
        <p className="op-modal-sub">
          <b>{target.name}</b> 템플릿을 삭제할까요? 이미 발송된 캠페인에는 영향이 없습니다
          (캠페인은 생성 시점의 내용을 복사해 둡니다).
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

function ResetTemplateModal({ target, onClose, onReset }: {
  target: TemplateView;
  onClose: () => void;
  onReset: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const res = await api(`/api/templates/${target.id}/reset`, { method: "POST" });
      if (res.ok) { onReset(); onClose(); }
      else setError("복원에 실패했습니다.");
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
        <h3>원본 복원</h3>
        <p className="op-modal-sub">
          <b>{target.name}</b> 템플릿을 기본 제공 원본으로 되돌릴까요?
          지금까지 수정한 내용은 사라집니다. 이미 발송된 캠페인에는 영향이 없습니다.
        </p>
        {error && <div className="op-modal-error">{error}</div>}
        <div className="op-modal-foot">
          <button className="op-btn op-btn-sm op-btn-ghost" onClick={onClose}>취소</button>
          <button className="op-btn op-btn-sm" disabled={busy} onClick={run}>
            {busy ? "복원 중…" : "원본 복원"}
          </button>
        </div>
      </div>
    </div>
    </Portal>
  );
}

export default function Templates() {
  const nav = useNavigate();
  const [tab, setTab] = useState<Tab>("list");
  const [source, setSource] = useState<SourceFilter>("all");
  const [templates, setTemplates] = useState<TemplateView[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [deleting, setDeleting] = useState<TemplateView | null>(null);
  const [resetting, setResetting] = useState<TemplateView | null>(null);

  const refresh = useCallback(async () => {
    try {
      const res = await api("/api/templates");
      if (res.ok) setTemplates(await res.json());
    } catch {
      /* transient / unauthorized handled by api() */
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const builtins = templates
    .filter((t) => t.builtinKey)
    .sort((a, b) => a.id - b.id);
  const mine = templates
    .filter((t) => !t.builtinKey)
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime());
  const shown = source === "builtin" ? builtins : source === "mine" ? mine : [...builtins, ...mine];

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>템플릿</h2>
          <p>자주 쓰는 이메일을 저장하고 캠페인에 바로 적용하세요.</p>
        </div>
        {/* default entry is the block editor — raw HTML stays an explicit choice */}
        <button className="op-btn op-btn-sm" onClick={() => nav("/editor")}>
          <span className="op-btn-plus">+</span>새 템플릿
        </button>
      </div>

      <div className="op-tabs">
        <button className={`op-tab${tab === "list" ? " active" : ""}`} onClick={() => setTab("list")}>템플릿</button>
        <button className={`op-tab${tab === "scratch" ? " active" : ""}`} onClick={() => setTab("scratch")}>직접 만들기</button>
      </div>

      {tab === "list" && (
        <>
          <div className="op-chips">
            <button className={`op-chip${source === "all" ? " active" : ""}`} onClick={() => setSource("all")}>
              전체 {loaded ? templates.length : ""}
            </button>
            <button className={`op-chip${source === "builtin" ? " active" : ""}`} onClick={() => setSource("builtin")}>
              기본 제공 {loaded ? builtins.length : ""}
            </button>
            <button className={`op-chip${source === "mine" ? " active" : ""}`} onClick={() => setSource("mine")}>
              내 템플릿 {loaded ? mine.length : ""}
            </button>
          </div>
          <p className="op-tab-hint">
            카드를 클릭하면 편집 화면이 열려요. 기본 제공 템플릿은 코드 없이 블록(편집상자)을
            끌어다 수정하고, 언제든 메뉴(···)에서 원본으로 복원할 수 있습니다.
          </p>
          <div className="op-tpl-grid">
            {shown.map((t) => {
              const cat = t.builtinKey ? BUILTIN_CATEGORY[t.builtinKey] : null;
              return (
                // marker-aware routing: block/text templates reopen in their own editor
                <div key={t.id} className="op-tpl-card" onClick={() => nav(editorRouteFor(t))}>
                  <LiveThumb html={renderPreview(t.htmlBody)} />
                  <div className="op-tpl-body between">
                    <div style={{ minWidth: 0 }}>
                      <div className="name">{t.name}</div>
                      <div className="op-tpl-meta">
                        {cat
                          ? <span className={`op-minibadge ${cat.badge === "gray" ? "gray" : cat.badge}`}>기본 · {cat.label}</span>
                          : <span className="used">{t.subject} · {new Date(t.updatedAt).toLocaleDateString("ko-KR")} 수정</span>}
                      </div>
                    </div>
                    <CardMenu
                      builtin={!!t.builtinKey}
                      onEdit={() => nav(editorRouteFor(t))}
                      onReset={() => setResetting(t)}
                      onDelete={() => setDeleting(t)}
                    />
                  </div>
                </div>
              );
            })}
            {loaded && shown.length === 0 && (
              <div className="op-dash-card" onClick={() => nav("/editor")}>
                <div className="plus">+</div>
                <div className="t">첫 템플릿 만들기</div>
                <div className="s">기본 제공 템플릿에서 시작하거나 직접 작성해 보세요.</div>
              </div>
            )}
            {shown.length > 0 && source !== "builtin" && (
              <div className="op-dash-card" onClick={() => nav("/editor")}>
                <div className="plus">+</div>
                <div className="t">새 템플릿</div>
                <div className="s">블록 에디터에서 새로 작성합니다.</div>
              </div>
            )}
          </div>
        </>
      )}

      {tab === "scratch" && (
        <>
          <p className="op-tab-hint">디자인 없이 빈 화면에서 시작하거나, 텍스트·HTML 에디터로 직접 작성할 수 있어요.</p>
          <div className="op-tpl-grid">
            <div className="op-scratch-card" onClick={() => nav("/editor")}>
              <div className="op-scratch-icon">▤</div>
              <div className="name">빈 템플릿</div>
              <div className="desc">텍스트·이미지·버튼 상자를 쌓아 처음부터 디자인합니다. 코드는 필요 없어요.</div>
              <div className="go">선택하기 →</div>
            </div>
            <div className="op-scratch-card" onClick={() => nav("/editor/text")}>
              <div className="op-scratch-icon">≡</div>
              <div className="name">텍스트 에디터</div>
              <div className="desc">디자인 없이 텍스트 중심으로. 지메일·네이버 메일처럼 담백하게 작성합니다.</div>
              <div className="go">선택하기 →</div>
            </div>
            <div className="op-scratch-card" onClick={() => nav("/editor/html")}>
              <div className="op-scratch-icon" style={{ fontFamily: "ui-monospace, monospace" }}>&lt;/&gt;</div>
              <div className="name">HTML 에디터</div>
              <div className="desc">HTML 코드로 이메일 전체를 직접 편집합니다. 기존 코드를 붙여넣어도 됩니다.</div>
              <div className="go">선택하기 →</div>
            </div>
          </div>
        </>
      )}

      {deleting && (
        <DeleteTemplateModal target={deleting} onClose={() => setDeleting(null)} onDeleted={refresh} />
      )}
      {resetting && (
        <ResetTemplateModal target={resetting} onClose={() => setResetting(null)} onReset={refresh} />
      )}
    </div>
  );
}
