/* 평문 본문을 발송용 HTML 로 바꾸는 규칙.

   메일은 항상 HTML 로 나간다(SmtpMailSender 가 setText(body, true)). 그래서 캠페인
   작성 화면의 본문 칸에 평문을 여러 줄 쓰면 수신자는 줄바꿈이 사라진 한 덩어리를
   받는다 — 가장 쉬운 기본 경로가 가장 조용히 망가지던 자리다. 텍스트 에디터도 같은
   변환을 하므로 규칙을 여기 한 곳에 둔다. */

export function escapeHtml(s: string): string {
  return s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

/** 본문 속 URL 을 클릭 가능한 링크로 — 링크여야 발송 파이프라인이 클릭을 추적한다.
    이스케이프된 텍스트를 받으므로 그대로 감싸도 안전하고, 문장부호 꼬리는 링크 밖으로. */
export function autoLink(escaped: string): string {
  return escaped.replace(/https?:\/\/[^\s<]+/g, (m) => {
    const trimmed = m.replace(/[.,;)]+$/, "");
    const rest = m.slice(trimmed.length);
    return `<a href="${trimmed}" style="color:#2563eb">${trimmed}</a>${rest}`;
  });
}

/** 이스케이프된 텍스트를 문단으로 — 빈 줄이 문단 경계, 한 줄 바꿈은 {@code <br>}. */
export function paragraphize(escaped: string, style: string): string {
  return escaped
    .split(/\n{2,}/)
    .map((p) => `<p style="${style}">${p.replaceAll("\n", "<br>")}</p>`)
    .join("\n");
}

/** 사용자가 HTML 을 붙여넣었는가 — 그렇다면 손대지 않는다. */
export function looksLikeHtml(text: string): boolean {
  return /<([a-z][a-z0-9]*)(\s[^>]*)?>/i.test(text);
}

/**
 * 캠페인 작성 화면의 직접 입력 본문 → 발송용 HTML.
 *
 * <p>HTML 을 붙여넣은 경우는 그대로 둔다(에디터로 만든 내용을 복사해 온 경우 포함).
 * 평문이면 줄바꿈·문단을 살려 감싼다. 여기서 감싸는 스타일은 최소한이다 — 디자인
 * 의도가 있는 메일은 이메일 에디터로 만든다.
 */
export function plainTextToHtml(text: string): string {
  const trimmed = text.trim();
  if (trimmed === "" || looksLikeHtml(trimmed)) {
    return text;
  }
  return paragraphize(autoLink(escapeHtml(trimmed)), "margin:0 0 16px;font-size:15px;line-height:1.75");
}
