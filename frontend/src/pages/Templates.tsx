import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import type { TemplateView } from "../types";
import Portal from "../components/Portal";
import { editorRouteFor } from "../outpace/blocks";
import { DESIGN_TEMPLATES, TEMPLATE_CATEGORIES, type DesignTemplate } from "../outpace/mock";

type Tab = "design" | "mine" | "scratch";

const ACCENT: Record<DesignTemplate["badge"], string> = {
  blue: "#2563eb",
  amber: "#d97706",
  green: "#16a34a",
  gray: "#7c3aed",
};

/* CSS-shape thumbnail (per handoff — no real imagery). Accent varies by category. */
function Thumb({ accent }: { accent: string }) {
  return (
    <div className="op-thumb">
      <div style={{ height: 24, width: "60%", borderRadius: 6, background: accent }} />
      <div style={{ height: 8, width: "100%", borderRadius: 3, background: "#d4d4d8" }} />
      <div style={{ height: 8, width: "86%", borderRadius: 3, background: "#e4e4e7" }} />
      <div style={{ marginTop: "auto", height: 20, width: "42%", borderRadius: 6, background: accent, opacity: 0.85 }} />
    </div>
  );
}

/* Kebab menu on a my-template card (edit / delete). */
function CardMenu({ onEdit, onDelete }: { onEdit: () => void; onDelete: () => void }) {
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
          <button className="danger" onClick={() => { setOpen(false); onDelete(); }}>삭제</button>
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

export default function Templates() {
  const nav = useNavigate();
  const [tab, setTab] = useState<Tab>("design");
  const [category, setCategory] = useState<(typeof TEMPLATE_CATEGORIES)[number]>("전체");
  const [myTemplates, setMyTemplates] = useState<TemplateView[]>([]);
  const [myLoaded, setMyLoaded] = useState(false);
  const [deleting, setDeleting] = useState<TemplateView | null>(null);

  const refresh = useCallback(async () => {
    try {
      const res = await api("/api/templates");
      if (res.ok) setMyTemplates(await res.json());
    } catch {
      /* transient / unauthorized handled by api() */
    } finally {
      setMyLoaded(true);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const designShown = category === "전체"
    ? DESIGN_TEMPLATES
    : DESIGN_TEMPLATES.filter((t) => t.category === category);

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>템플릿</h2>
          <p>자주 쓰는 이메일을 저장하고 캠페인에 바로 적용하세요.</p>
        </div>
        <button className="op-btn op-btn-sm" onClick={() => nav("/editor/html")}>
          <span className="op-btn-plus">+</span>새 템플릿
        </button>
      </div>

      <div className="op-tabs">
        <button className={`op-tab${tab === "design" ? " active" : ""}`} onClick={() => setTab("design")}>디자인 템플릿</button>
        <button className={`op-tab${tab === "mine" ? " active" : ""}`} onClick={() => setTab("mine")}>내 템플릿</button>
        <button className={`op-tab${tab === "scratch" ? " active" : ""}`} onClick={() => setTab("scratch")}>직접 만들기</button>
      </div>

      {tab === "design" && (
        <>
          <div className="op-chips">
            {TEMPLATE_CATEGORIES.map((c) => (
              <button key={c} className={`op-chip${category === c ? " active" : ""}`} onClick={() => setCategory(c)}>
                {c === "전체" ? `전체 ${DESIGN_TEMPLATES.length}` : c}
              </button>
            ))}
          </div>
          <div className="op-tpl-grid">
            {designShown.map((t) => (
              <div key={t.id} className="op-tpl-card" onClick={() => nav(`/editor/html?starter=${t.id}`)}>
                <Thumb accent={ACCENT[t.badge]} />
                <div className="op-tpl-body">
                  <div className="name">{t.name}</div>
                  <div className="op-tpl-meta">
                    <span className={`op-minibadge ${t.badge === "gray" ? "gray" : t.badge}`}>{t.category}</span>
                    <span className="date">이 디자인으로 시작 →</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      {tab === "mine" && (
        <>
          <p className="op-tab-hint">직접 만든 템플릿입니다. 카드를 클릭하면 편집 화면이 열려요.</p>
          <div className="op-tpl-grid">
            {myTemplates.map((t) => (
              // marker-aware routing: block/text templates reopen in their own editor
              <div key={t.id} className="op-tpl-card" onClick={() => nav(editorRouteFor(t))}>
                <Thumb accent="#2563eb" />
                <div className="op-tpl-body between">
                  <div style={{ minWidth: 0 }}>
                    <div className="name">{t.name}</div>
                    <div className="used">
                      {t.subject} · {new Date(t.updatedAt).toLocaleDateString("ko-KR")} 수정
                    </div>
                  </div>
                  <CardMenu onEdit={() => nav(editorRouteFor(t))} onDelete={() => setDeleting(t)} />
                </div>
              </div>
            ))}
            {myLoaded && myTemplates.length === 0 && (
              <div className="op-dash-card" onClick={() => nav("/editor/html")}>
                <div className="plus">+</div>
                <div className="t">첫 템플릿 만들기</div>
                <div className="s">디자인 템플릿에서 시작하거나 직접 작성해 보세요.</div>
              </div>
            )}
            {myTemplates.length > 0 && (
              <div className="op-dash-card" onClick={() => nav("/editor/html")}>
                <div className="plus">+</div>
                <div className="t">새 템플릿</div>
                <div className="s">HTML 에디터에서 새로 작성합니다.</div>
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
    </div>
  );
}
