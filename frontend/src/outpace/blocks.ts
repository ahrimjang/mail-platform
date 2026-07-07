/* Block model for the box-based email editor (/editor).
   Blocks serialize two ways at save time:
   - renderBlocksHtml(): email-safe table HTML — what actually gets sent.
   - an <!--opblocks:base64(JSON)--> marker prepended to htmlBody — what lets
     the editor reopen a saved template with its structure intact.
   The send pipeline ignores HTML comments, so the marker rides along for free.

   v2: body-like fields hold LIMITED RICH HTML (b/i/a/br only, whitelisted by
   sanitizeRich) so the canvas can be edited inline; per-block style fields
   (padding, font size, colors, button shape) override per-type defaults.
   v1 markers (plain-text bodies, no style fields) are upgraded on load. */

export type Align = "left" | "center" | "right";

interface BaseStyle {
  bg: string;
  bgImage?: string; // background image URL (uploaded or external)
  padY?: number; // vertical padding override (px)
  padX?: number; // horizontal padding override (px)
}

export interface TextBlock extends BaseStyle {
  id: string;
  type: "text";
  heading: string; // plain text ("" = none)
  body: string; // sanitized rich HTML
  align: Align;
  fontSize?: number;
  color?: string;
}

export interface ImageBlock extends BaseStyle {
  id: string;
  type: "image";
  url: string; // "" renders a placeholder in the canvas and is skipped in HTML
  alt: string;
}

export interface ButtonBlock extends BaseStyle {
  id: string;
  type: "button";
  label: string; // plain text
  url: string;
  align: Align;
  btnColor?: string;
  btnRadius?: number;
}

export interface TwoColBlock extends BaseStyle {
  id: string;
  type: "two";
  leftTitle: string; // plain
  leftBody: string; // rich
  rightTitle: string; // plain
  rightBody: string; // rich
  fontSize?: number;
  color?: string;
}

export interface DividerBlock extends BaseStyle {
  id: string;
  type: "divider";
}

export interface FooterBlock extends BaseStyle {
  id: string;
  type: "footer";
  text: string; // rich
  color?: string;
}

export type Block = TextBlock | ImageBlock | ButtonBlock | TwoColBlock | DividerBlock | FooterBlock;
export type BlockType = Block["type"];

export const BLOCK_NAMES: Record<BlockType, string> = {
  text: "텍스트",
  image: "이미지",
  button: "버튼",
  two: "2단 콘텐츠",
  divider: "구분선",
  footer: "푸터",
};

export const BG_SWATCHES = ["#ffffff", "#f8fafc", "#eff6ff", "#fffbeb", "#fafafa"];
export const TEXT_COLORS = ["#18181b", "#3f3f46", "#71717a", "#2563eb", "#16a34a", "#dc2626"];
export const BTN_COLORS = ["#2563eb", "#16a34a", "#d97706", "#dc2626", "#18181b"];

/* Per-type defaults the style fields fall back to. */
export const DEFAULTS = {
  text: { padY: 28, padX: 34, fontSize: 14.5, color: "#3f3f46" },
  image: { padY: 0, padX: 0 },
  button: { padY: 22, padX: 34, btnColor: "#2563eb", btnRadius: 10 },
  two: { padY: 22, padX: 34, fontSize: 13, color: "#71717a" },
  divider: { padY: 8, padX: 34 },
  footer: { padY: 24, padX: 34, color: "#a1a1aa" },
} as const;

function uid(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID().slice(0, 8)
    : Math.random().toString(36).slice(2, 10);
}

/** Fresh block of the given type with sensible starter content. */
export function newBlock(type: BlockType): Block {
  const id = uid();
  switch (type) {
    case "text":
      return { id, type, heading: "", body: "여기에 내용을 작성하세요.", align: "left", bg: "#ffffff" };
    case "image":
      return { id, type, url: "", alt: "", bg: "#ffffff" };
    case "button":
      return { id, type, label: "지금 확인하기", url: "https://example.com", align: "center", bg: "#ffffff" };
    case "two":
      return {
        id, type,
        leftTitle: "왼쪽 제목", leftBody: "왼쪽 내용을 작성하세요.",
        rightTitle: "오른쪽 제목", rightBody: "오른쪽 내용을 작성하세요.",
        bg: "#ffffff",
      };
    case "divider":
      return { id, type, bg: "#ffffff" };
    case "footer":
      return { id, type, text: "Acme Inc. · 서울특별시 강남구 테헤란로 1", bg: "#fafafa" };
  }
}

/** Starting document for a blank template (mirrors the design handoff canvas). */
export function defaultBlocks(): Block[] {
  const intro = newBlock("text") as TextBlock;
  intro.heading = "{{name}}님, 이번 달 소식을 전해요";
  intro.body = "안녕하세요, Acme 팀입니다. 이번 달 준비한 새 기능과 구독자 전용 혜택을 정리했어요. 아래에서 자세한 내용을 확인해 보세요.";
  const two = newBlock("two") as TwoColBlock;
  two.leftTitle = "새 자동화 기능";
  two.leftBody = "조건에 따라 메일이 자동으로 발송돼요.";
  two.rightTitle = "실시간 리포트";
  two.rightBody = "발송 성과를 바로 확인하세요.";
  return [newBlock("image"), intro, two, newBlock("button"), newBlock("footer")];
}

/* ------------------------------ rich-text safety ---------------------------- */

const ALLOWED_TAGS = new Set(["B", "STRONG", "I", "EM", "A", "BR"]);

/**
 * Whitelist sanitizer for inline-edited HTML: keeps b/strong/i/em/a/br,
 * unwraps everything else (contentEditable's div/p line wrappers become <br>),
 * and strips all attributes except a safe http(s)/mailto href on links.
 */
export function sanitizeRich(html: string): string {
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, "text/html");
  const root = doc.body.firstElementChild as HTMLElement;

  function clean(node: HTMLElement) {
    for (const child of [...node.children] as HTMLElement[]) {
      clean(child);
      if (ALLOWED_TAGS.has(child.tagName)) {
        for (const attr of [...child.attributes]) {
          const keep = child.tagName === "A" && attr.name === "href" && /^(https?:|mailto:)/i.test(attr.value);
          if (!keep) child.removeAttribute(attr.name);
        }
        if (child.tagName === "A") child.setAttribute("style", "color:#2563eb");
      } else {
        // block wrappers (div/p) mean a line break in contentEditable output
        if ((child.tagName === "DIV" || child.tagName === "P") && child.previousSibling) {
          child.parentNode?.insertBefore(doc.createElement("br"), child);
        }
        child.replaceWith(...child.childNodes);
      }
    }
  }
  clean(root);
  return root.innerHTML;
}

function esc(s: string): string {
  return s.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function escAttr(s: string): string {
  return esc(s).replaceAll('"', "&quot;");
}

/** v1 bodies were plain text with newlines — lift them into rich HTML once. */
function plainToRich(s: string): string {
  return esc(s).replaceAll("\n", "<br>");
}

/* ------------------------------ HTML rendering ----------------------------- */

function padOf(b: Block): string {
  const d = DEFAULTS[b.type];
  return `${b.padY ?? d.padY}px ${b.padX ?? d.padX}px`;
}

/** Only http(s) or app-relative URLs may become background images. */
export function safeImageUrl(url: string | undefined): string | null {
  if (!url) return null;
  const trimmed = url.trim();
  return /^(https?:\/\/|\/)/i.test(trimmed) ? trimmed : null;
}

/** Inline background declaration for a block (color + optional cover image). */
function bgOf(b: Block): string {
  const img = safeImageUrl(b.bgImage);
  return `background-color:${b.bg}`
    + (img ? `;background-image:url('${escAttr(img)}');background-size:cover;background-position:center` : "");
}

function blockHtml(b: Block): string {
  switch (b.type) {
    case "text": {
      const d = DEFAULTS.text;
      const heading = b.heading.trim()
        ? `<h2 style="margin:0 0 12px;font-size:22px;letter-spacing:-0.02em;color:#18181b">${esc(b.heading)}</h2>`
        : "";
      return `<td style="padding:${padOf(b)};${bgOf(b)};text-align:${b.align}">${heading}<p style="margin:0;font-size:${b.fontSize ?? d.fontSize}px;color:${b.color ?? d.color};line-height:1.75">${b.body}</p></td>`;
    }
    case "image":
      if (!b.url.trim()) {
        return `<td style="padding:0;${bgOf(b)}"></td>`;
      }
      return `<td style="padding:${padOf(b)};${bgOf(b)}"><img src="${escAttr(b.url)}" alt="${escAttr(b.alt)}" width="600" style="display:block;width:100%;height:auto"></td>`;
    case "button": {
      const d = DEFAULTS.button;
      return `<td style="padding:${padOf(b)};${bgOf(b)};text-align:${b.align}"><a href="${escAttr(b.url)}" style="display:inline-block;background:${b.btnColor ?? d.btnColor};color:#ffffff;font-size:14.5px;font-weight:bold;padding:13px 32px;border-radius:${b.btnRadius ?? d.btnRadius}px;text-decoration:none">${esc(b.label)}</a></td>`;
    }
    case "two": {
      const d = DEFAULTS.two;
      const fs = b.fontSize ?? d.fontSize;
      const col = (title: string, body: string) =>
        `<td width="48%" valign="top"><h3 style="margin:0 0 6px;font-size:14px;color:#18181b">${esc(title)}</h3><p style="margin:0;font-size:${fs}px;color:${b.color ?? d.color};line-height:1.6">${body}</p></td>`;
      return `<td style="padding:${padOf(b)};${bgOf(b)}"><table width="100%" cellpadding="0" cellspacing="0"><tr>`
        + col(b.leftTitle, b.leftBody)
        + `<td width="4%"></td>`
        + col(b.rightTitle, b.rightBody)
        + `</tr></table></td>`;
    }
    case "divider":
      return `<td style="padding:${padOf(b)};${bgOf(b)}"><hr style="border:none;border-top:1px solid #e4e4e7;margin:0"></td>`;
    case "footer": {
      const d = DEFAULTS.footer;
      return `<td style="padding:${padOf(b)};${bgOf(b)};text-align:center"><p style="margin:0;font-size:12px;color:${b.color ?? d.color};line-height:1.75">${b.text}</p></td>`;
    }
  }
}

/** Email-safe HTML for the whole document (600px centered table). */
export function renderBlocksHtml(blocks: Block[]): string {
  const rows = blocks.map((b) => `  <tr>${blockHtml(b)}</tr>`).join("\n");
  return `<table width="600" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif;background:#ffffff">\n${rows}\n</table>`;
}

/* ----------------------------- edit-state marker ---------------------------- */

const BLOCKS_PREFIX = "<!--opblocks:";
const TEXT_PREFIX = "<!--optext:";
const MARKER_VERSION = 2;

function b64encode(s: string): string {
  return btoa(unescape(encodeURIComponent(s)));
}

function b64decode(s: string): string {
  return decodeURIComponent(escape(atob(s)));
}

/** Upgrade a v1 block (plain-text bodies) to the v2 rich-HTML shape in place. */
function upgradeV1(b: Block): Block {
  switch (b.type) {
    case "text":
      return { ...b, body: plainToRich(b.body) };
    case "two":
      return { ...b, leftBody: plainToRich(b.leftBody), rightBody: plainToRich(b.rightBody) };
    case "footer":
      return { ...b, text: plainToRich(b.text) };
    default:
      return b;
  }
}

/** htmlBody for a block template: marker (edit state) + rendered HTML (send content). */
export function blocksToHtmlBody(blocks: Block[]): string {
  const payload = JSON.stringify({ v: MARKER_VERSION, blocks });
  return `${BLOCKS_PREFIX}${b64encode(payload)}-->\n${renderBlocksHtml(blocks)}`;
}

/** Recover blocks from a saved htmlBody; null when it wasn't made by this editor. */
export function parseBlocksMarker(htmlBody: string): Block[] | null {
  if (!htmlBody.startsWith(BLOCKS_PREFIX)) return null;
  const end = htmlBody.indexOf("-->");
  if (end < 0) return null;
  try {
    const parsed = JSON.parse(b64decode(htmlBody.slice(BLOCKS_PREFIX.length, end)));
    if (Array.isArray(parsed)) return (parsed as Block[]).map(upgradeV1); // v1 marker
    if (parsed && parsed.v === MARKER_VERSION && Array.isArray(parsed.blocks)) return parsed.blocks as Block[];
    return null;
  } catch {
    return null;
  }
}

/** htmlBody for a text-editor template: marker (source text) + rendered HTML. */
export function textToHtmlBody(sourceText: string, renderedHtml: string): string {
  return `${TEXT_PREFIX}${b64encode(sourceText)}-->\n${renderedHtml}`;
}

/** Recover the plain-text source from a saved htmlBody; null if not a text template. */
export function parseTextMarker(htmlBody: string): string | null {
  if (!htmlBody.startsWith(TEXT_PREFIX)) return null;
  const end = htmlBody.indexOf("-->");
  if (end < 0) return null;
  try {
    return b64decode(htmlBody.slice(TEXT_PREFIX.length, end));
  } catch {
    return null;
  }
}

/** Which editor should open a saved template. */
export function editorRouteFor(t: { id: number; htmlBody: string }): string {
  if (t.htmlBody.startsWith(BLOCKS_PREFIX)) return `/editor/${t.id}`;
  if (t.htmlBody.startsWith(TEXT_PREFIX)) return `/editor/text/${t.id}`;
  return `/editor/html/${t.id}`;
}
