import type { CampaignView } from "../types";

/* Fallback demo data so the hifi screens still read correctly when the
   backend has no campaigns yet. Real API data takes precedence everywhere. */
export const MOCK_CAMPAIGNS: CampaignView[] = [
  { id: 9001, subject: "월간 뉴스레터 6월호", status: "SENDING", total: 18200, pending: 5708, sent: 12480, failed: 12, bounced: 0, suppressed: 0, opened: 4120, clicked: 980, createdAt: new Date("2026-07-01T12:00:00").toISOString(), senderName: "Acme 팀", senderEmail: "hello@acme.io", scheduledAt: null, templateId: null, templateName: null, listId: 9101, listName: "전체 구독자" },
  { id: 9002, subject: "주간 상품 추천", status: "SENDING", total: 5400, pending: 4350, sent: 1050, failed: 0, bounced: 0, suppressed: 0, opened: 210, clicked: 44, createdAt: new Date("2026-07-01T11:30:00").toISOString(), senderName: "Acme 팀", senderEmail: "hello@acme.io", scheduledAt: null, templateId: null, templateName: null, listId: 9101, listName: "전체 구독자" },
  { id: 9003, subject: "블랙프라이데이 사전알림", status: "QUEUED", total: 24000, pending: 24000, sent: 0, failed: 0, bounced: 0, suppressed: 0, opened: 0, clicked: 0, createdAt: new Date("2026-07-01T09:00:00").toISOString(), senderName: "Acme 팀", senderEmail: "deals@acme.io", scheduledAt: new Date("2026-07-01T21:00:00").toISOString(), templateId: null, templateName: "프로모션 · 할인 안내", listId: null, listName: null },
  { id: 9004, subject: "휴면 고객 리마인드", status: "COMPLETED", total: 9000, pending: 0, sent: 8940, failed: 60, bounced: 40, suppressed: 12, opened: 3100, clicked: 720, createdAt: new Date("2026-06-30T15:00:00").toISOString(), senderName: "Acme 팀", senderEmail: "hello@acme.io", scheduledAt: null, templateId: null, templateName: null, listId: 9101, listName: "전체 구독자" },
  { id: 9005, subject: "신규 가입 환영 메일", status: "COMPLETED", total: 3210, pending: 0, sent: 3210, failed: 0, bounced: 0, suppressed: 0, opened: 1980, clicked: 610, createdAt: new Date("2026-06-29T10:00:00").toISOString(), senderName: "Acme 팀", senderEmail: "welcome@acme.io", scheduledAt: null, templateId: null, templateName: null, listId: 9101, listName: "전체 구독자" },
];

/* (The design-template gallery moved to the database — seeded built-in
   templates, editable like any other. See BuiltinTemplates on the backend.) */
