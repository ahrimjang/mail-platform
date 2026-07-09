/* Editor starter + preview helpers. The example-template gallery now lives in
   the database (seeded by the backend from BuiltinTemplates; editable like any
   template), so the only code-side starter left is the blank scaffold the HTML
   editor opens with. */

export interface Starter {
  name: string;
  subject: string;
  htmlBody: string;
}

export const STARTERS: Record<string, Starter> = {
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
  orderNo: "20260708-001",
  address: "서울특별시 강남구 테헤란로 123, 45동 678호",
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
