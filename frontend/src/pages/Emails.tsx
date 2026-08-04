import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import { editorRouteFor } from "../outpace/blocks";
import { badgeClass, statusLabel } from "../outpace/format";
import { renderPreview } from "../outpace/starters";
import { BUILTIN_CATEGORY, LiveThumb } from "./Templates";
import type { CampaignView, EmailDraftView, TemplateView } from "../types";

/* 이메일 허브 — 캠페인에 실제 쓸 콘텐츠 계층. 하위에 이메일 목록 / 만들기(템플릿
   선택 또는 직접 작성) / 템플릿 관리(별도 화면) / 캠페인 매핑이 묶인다. */
export default function Emails() {
  const nav = useNavigate();
  const [searchParams] = useSearchParams();
  const initialTab = searchParams.get("tab");
  const [emails, setEmails] = useState<EmailDraftView[]>([]);
  const [templates, setTemplates] = useState<TemplateView[]>([]);
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [tab, setTab] = useState<"list" | "create">(initialTab === "create" ? "create" : "list");
  // "캠페인 N개" 클릭으로 펼쳐지는 카드 — 이메일 하나만 열린다
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    const [eRes, tRes, cRes] = await Promise.all([
      api("/api/emails"), api("/api/templates"), api("/api/campaigns"),
    ]);
    if (eRes.ok) setEmails(await eRes.json());
    if (tRes.ok) setTemplates(await tRes.json());
    if (cRes.ok) setCampaigns(await cRes.json());
    setLoaded(true);
  }

  useEffect(() => { refresh(); }, []);

  /* 템플릿에서 시작 — 내용을 복사한 이메일을 만들고 곧장 에디터로. */
  async function createFromTemplate(t: TemplateView) {
    setBusy(true);
    setError(null);
    try {
      const res = await api("/api/emails", { method: "POST", body: JSON.stringify({ templateId: t.id }) });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        setError(data.error ?? "이메일 생성에 실패했습니다.");
        return;
      }
      const created: EmailDraftView = await res.json();
      nav(editorRouteFor(created, "email"));
    } catch {
      setError("이메일 생성에 실패했습니다.");
    } finally {
      setBusy(false);
    }
  }

  async function remove(e: EmailDraftView) {
    if (!window.confirm(`'${e.name}' 이메일을 삭제할까요? 이미 등록된 캠페인에는 영향이 없어요.`)) return;
    const res = await api(`/api/emails/${e.id}`, { method: "DELETE" });
    if (res.ok) refresh();
  }

  const fmt = (iso: string) =>
    new Date(iso).toLocaleDateString("ko-KR", { month: "short", day: "numeric" }) +
    " " + new Date(iso).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" });

  return (
    <div className="op-container op-fade">
      <div className="op-pagehead">
        <div>
          <h2>이메일</h2>
          <p>캠페인에 사용할 이메일이에요. 템플릿을 불러와 다듬거나 새로 작성하세요.</p>
        </div>
        <button className="op-btn op-btn-sm" onClick={() => setTab("create")}>
          <span className="op-btn-plus">+</span>새 이메일
        </button>
      </div>

      {/* 허브 탭 — 템플릿 관리는 별도 화면(/templates)이지만 같은 탭줄로 묶인다.
          캠페인 매핑은 카드의 "캠페인 N개" 클릭 확장으로 흡수했다. */}
      <div className="op-tabs">
        <button className={`op-tab${tab === "list" ? " active" : ""}`} onClick={() => setTab("list")}>이메일</button>
        <button className={`op-tab${tab === "create" ? " active" : ""}`} onClick={() => setTab("create")}>이메일 만들기</button>
        <button className="op-tab" onClick={() => nav("/templates")}>템플릿 관리</button>
      </div>

      {error && <p className="error" style={{ fontSize: 13 }}>{error}</p>}

      {tab === "create" && (
        <>
          <p className="op-tab-hint">
            처음부터 직접 작성하거나, 아래에서 템플릿을 고르면 내용을 복사한 이메일이
            만들어져 바로 편집을 시작해요 — 원본 템플릿은 그대로 남습니다.
          </p>
          <h3 style={{ margin: "0 0 12px", fontSize: 15.5 }}>직접 만들기</h3>
          <div className="op-tpl-grid" style={{ marginBottom: 28 }}>
            <div className="op-scratch-card" onClick={() => nav("/editor?target=email")}>
              <div className="op-scratch-icon">▤</div>
              <div className="name">빈 이메일</div>
              <div className="desc">텍스트·이미지·버튼 상자를 쌓아 처음부터 디자인합니다. 코드는 필요 없어요.</div>
              <div className="go">선택하기 →</div>
            </div>
            <div className="op-scratch-card" onClick={() => nav("/editor/text?target=email")}>
              <div className="op-scratch-icon">≡</div>
              <div className="name">텍스트 에디터</div>
              <div className="desc">디자인 없이 텍스트 중심으로. 지메일·네이버 메일처럼 담백하게 작성합니다.</div>
              <div className="go">선택하기 →</div>
            </div>
            <div className="op-scratch-card" onClick={() => nav("/editor/html?target=email")}>
              <div className="op-scratch-icon">&lt;/&gt;</div>
              <div className="name">HTML 에디터</div>
              <div className="desc">HTML 을 직접 붙여 넣거나 작성합니다. 외부에서 만든 메일도 그대로 쓸 수 있어요.</div>
              <div className="go">선택하기 →</div>
            </div>
          </div>

          <h3 style={{ margin: "0 0 12px", fontSize: 15.5 }}>템플릿에서 시작</h3>
          <div className="op-tpl-grid">
            {templates.map((t) => {
              const cat = t.builtinKey ? BUILTIN_CATEGORY[t.builtinKey] : null;
              return (
                // 템플릿 관리와 같은 카드 — 클릭하면 내용을 복사한 이메일을 만들어 에디터로
                <div key={t.id} className="op-tpl-card" style={{ opacity: busy ? 0.6 : 1 }}
                     onClick={() => { if (!busy) createFromTemplate(t); }}>
                  <LiveThumb html={renderPreview(t.htmlBody)} />
                  <div className="op-tpl-body">
                    <div className="name">{t.name}</div>
                    <div className="op-tpl-meta">
                      {cat
                        ? <span className={`op-minibadge ${cat.badge}`}>기본 · {cat.label}</span>
                        : <span className="used">{t.subject}</span>}
                    </div>
                  </div>
                </div>
              );
            })}
            {loaded && templates.length === 0 && (
              <p style={{ fontSize: 13, color: "var(--op-faint)", margin: 0 }}>
                사용할 템플릿이 없어요. 템플릿 관리에서 먼저 만들어 주세요.
              </p>
            )}
          </div>
        </>
      )}

      {tab === "list" && (loaded && emails.length === 0 ? (
        <div className="op-card op-card-pad" style={{ textAlign: "center", padding: "48px 24px" }}>
          <p style={{ margin: "0 0 14px", color: "var(--op-muted)", fontSize: 14 }}>
            아직 만든 이메일이 없어요. 템플릿을 불러와 첫 이메일을 만들어보세요.
          </p>
          <button className="op-btn op-btn-sm" onClick={() => setTab("create")}>새 이메일 만들기</button>
        </div>
      ) : (
        <div className="op-tpl-grid">
          {emails.map((e) => {
            const source = e.sourceTemplateId != null
              ? templates.find((t) => t.id === e.sourceTemplateId)?.name
              : null;
            const used = campaigns
              .filter((c) => c.emailId === e.id)
              .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
            const expanded = expandedId === e.id;
            return (
              // 템플릿 카드와 같은 라이브 썸네일 카드 — 발송할 실물이 눈에 보이게
              <div key={e.id} className="op-tpl-card" style={{ cursor: "pointer" }}
                   onClick={() => nav(editorRouteFor(e, "email"))}>
                <LiveThumb html={renderPreview(e.htmlBody)} />
                <div className="op-tpl-body">
                  <div className="name">{e.name}</div>
                  <div style={{ fontSize: 12, color: "var(--op-muted)", margin: "2px 0 6px" }} className="op-ell">{e.subject}</div>
                  <div style={{ fontSize: 11.5, color: "var(--op-faint)", marginBottom: 10 }}>
                    {source ? `템플릿 '${source}' 에서 시작` : "직접 작성"} · 수정 {fmt(e.updatedAt)}
                    {used.length > 0 && (
                      <>
                        {" · "}
                        <button className="op-linkbtn" style={{ fontSize: 11.5 }}
                                onClick={(ev) => { ev.stopPropagation(); setExpandedId(expanded ? null : e.id); }}>
                          캠페인 {used.length}개 {expanded ? "접기 ▴" : "보기 ▾"}
                        </button>
                      </>
                    )}
                  </div>
                  {expanded && (
                    <div style={{ borderTop: "1px solid var(--op-border)", margin: "0 0 10px", paddingTop: 8 }}
                         onClick={(ev) => ev.stopPropagation()}>
                      {used.map((c) => (
                        <div key={c.id}
                             style={{ display: "flex", alignItems: "center", justifyContent: "space-between",
                                      gap: 8, padding: "4px 0", cursor: "pointer" }}
                             onClick={() => nav(`/campaigns/${c.id}`)}>
                          <span className="op-ell" style={{ fontSize: 12.5, fontWeight: 600 }}>{c.name ?? c.subject}</span>
                          <span className={`op-badge ${badgeClass(c.status)}`}>{statusLabel(c)}</span>
                        </div>
                      ))}
                    </div>
                  )}
                  <div style={{ display: "flex", gap: 8 }} onClick={(ev) => ev.stopPropagation()}>
                    <button className="op-btn op-btn-sm" style={{ flex: 1 }}
                            onClick={() => nav(`/campaigns/new?emailId=${e.id}`)}>
                      캠페인 만들기
                    </button>
                    <button className="op-btn op-btn-sm op-btn-ghost"
                            onClick={() => nav(editorRouteFor(e, "email"))}>편집</button>
                    <button className="op-btn op-btn-sm op-btn-ghost danger" onClick={() => remove(e)}>삭제</button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}
