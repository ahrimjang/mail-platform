/* Starter HTML for the design-template gallery. Picking a card opens the HTML
   editor pre-filled with one of these (inline styles — email-client safe). */

export interface Starter {
  name: string;
  subject: string;
  htmlBody: string;
}

export const STARTERS: Record<string, Starter> = {
  newsletter: {
    name: "월간 뉴스레터",
    subject: "{{name}}님, 이번 달 소식을 전해요",
    htmlBody: `<table width="600" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif">
  <tr><td style="background:#2563eb;color:#fff;padding:28px 32px;border-radius:12px 12px 0 0">
    <h1 style="margin:0;font-size:22px">이번 달 소식</h1>
  </td></tr>
  <tr><td style="padding:28px 32px;background:#ffffff">
    <p style="margin:0 0 16px;font-size:15px;line-height:1.7">안녕하세요 {{name}}님,</p>
    <p style="margin:0 0 16px;font-size:15px;line-height:1.7">이번 달 준비한 새 기능과 구독자 전용 혜택을 정리했어요.</p>
    <a href="https://example.com" style="display:inline-block;background:#2563eb;color:#fff;padding:12px 28px;border-radius:8px;text-decoration:none;font-weight:bold">자세히 보기</a>
  </td></tr>
</table>`,
  },
  promo: {
    name: "프로모션 · 할인 안내",
    subject: "{{name}}님만을 위한 특별 할인",
    htmlBody: `<table width="600" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif">
  <tr><td style="background:#d97706;color:#fff;padding:32px;text-align:center;border-radius:12px 12px 0 0">
    <h1 style="margin:0;font-size:26px">최대 40% 할인</h1>
  </td></tr>
  <tr><td style="padding:28px 32px;background:#ffffff;text-align:center">
    <p style="margin:0 0 16px;font-size:15px;line-height:1.7">{{name}}님, 이번 주말까지만 드리는 혜택이에요.</p>
    <a href="https://example.com" style="display:inline-block;background:#d97706;color:#fff;padding:12px 32px;border-radius:8px;text-decoration:none;font-weight:bold">지금 쇼핑하기</a>
  </td></tr>
</table>`,
  },
  welcome: {
    name: "신규 가입 환영",
    subject: "{{name}}님, 환영합니다!",
    htmlBody: `<table width="600" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif">
  <tr><td style="padding:36px 32px;background:#ffffff;text-align:center">
    <h1 style="margin:0 0 12px;font-size:24px;color:#16a34a">환영합니다 🎉</h1>
    <p style="margin:0 0 20px;font-size:15px;line-height:1.7">{{name}}님, 가입해 주셔서 감사해요.<br>지금 바로 시작해 보세요.</p>
    <a href="https://example.com" style="display:inline-block;background:#16a34a;color:#fff;padding:12px 28px;border-radius:8px;text-decoration:none;font-weight:bold">시작하기</a>
  </td></tr>
</table>`,
  },
  repurchase: {
    name: "재구매 유도",
    subject: "{{name}}님, 다시 만나요",
    htmlBody: `<table width="600" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif">
  <tr><td style="padding:32px;background:#ffffff">
    <h1 style="margin:0 0 14px;font-size:21px;color:#2563eb">{{name}}님, 오랜만이에요</h1>
    <p style="margin:0 0 18px;font-size:15px;line-height:1.7">마지막 주문 이후 새로 들어온 상품을 골라봤어요.</p>
    <a href="https://example.com" style="display:inline-block;background:#2563eb;color:#fff;padding:12px 28px;border-radius:8px;text-decoration:none;font-weight:bold">추천 상품 보기</a>
  </td></tr>
</table>`,
  },
  receipt: {
    name: "주문 영수증",
    subject: "주문이 확인되었습니다",
    htmlBody: `<table width="600" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif">
  <tr><td style="padding:32px;background:#ffffff">
    <h1 style="margin:0 0 6px;font-size:19px">주문 확인</h1>
    <p style="margin:0 0 20px;font-size:13px;color:#71717a">{{name}}님의 주문이 정상 접수되었습니다.</p>
    <table width="100%" cellpadding="8" style="border-top:1px solid #e4e4e7;border-bottom:1px solid #e4e4e7;font-size:14px">
      <tr><td>상품명</td><td align="right">수량 1</td></tr>
    </table>
    <p style="margin:16px 0 0;font-size:12px;color:#a1a1aa">본 메일은 발신 전용입니다.</p>
  </td></tr>
</table>`,
  },
  blank: {
    name: "새 템플릿",
    subject: "",
    htmlBody: `<table width="600" align="center" cellpadding="0" cellspacing="0" style="font-family:sans-serif">
  <tr><td style="padding:32px;background:#ffffff">
    <h1 style="margin:0 0 14px;font-size:22px">{{name}}님, 안녕하세요</h1>
    <p style="margin:0;font-size:15px;line-height:1.7">여기에 내용을 작성하세요.</p>
  </td></tr>
</table>`,
  },
};

/* Sample values used for the live preview pane ({{var}} substitution). */
export const SAMPLE_VARS: Record<string, string> = {
  name: "지민",
  firstName: "지민",
  lastName: "박",
  email: "jimin@example.com",
};

/* Client-side stand-in for the server renderer: known vars get sample values,
   unknown ones stay visible as highlighted chips so authors can spot typos. */
export function renderPreview(src: string): string {
  return src.replace(/\{\{\s*(\w+)\s*\}\}/g, (_, key: string) =>
    SAMPLE_VARS[key] !== undefined
      ? SAMPLE_VARS[key]
      : `<mark style="background:#fffbeb;color:#b45309;border-radius:4px;padding:0 4px">{{${key}}}</mark>`,
  );
}
