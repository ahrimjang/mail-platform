import type { CampaignView } from "../types";

/* Fallback demo data so the hifi screens still read correctly when the
   backend has no campaigns yet. Real API data takes precedence everywhere. */
export const MOCK_CAMPAIGNS: CampaignView[] = [
  { id: 9001, subject: "월간 뉴스레터 6월호", status: "SENDING", total: 18200, pending: 5708, sent: 12480, failed: 12, bounced: 0, suppressed: 0, opened: 4120, clicked: 980, createdAt: new Date("2026-07-01T12:00:00").toISOString(), senderName: "Acme 팀", senderEmail: "hello@acme.io", scheduledAt: null },
  { id: 9002, subject: "주간 상품 추천", status: "SENDING", total: 5400, pending: 4350, sent: 1050, failed: 0, bounced: 0, suppressed: 0, opened: 210, clicked: 44, createdAt: new Date("2026-07-01T11:30:00").toISOString(), senderName: "Acme 팀", senderEmail: "hello@acme.io", scheduledAt: null },
  { id: 9003, subject: "블랙프라이데이 사전알림", status: "QUEUED", total: 24000, pending: 24000, sent: 0, failed: 0, bounced: 0, suppressed: 0, opened: 0, clicked: 0, createdAt: new Date("2026-07-01T09:00:00").toISOString(), senderName: "Acme 팀", senderEmail: "deals@acme.io", scheduledAt: new Date("2026-07-01T21:00:00").toISOString() },
  { id: 9004, subject: "휴면 고객 리마인드", status: "COMPLETED", total: 9000, pending: 0, sent: 8940, failed: 60, bounced: 40, suppressed: 12, opened: 3100, clicked: 720, createdAt: new Date("2026-06-30T15:00:00").toISOString(), senderName: "Acme 팀", senderEmail: "hello@acme.io", scheduledAt: null },
  { id: 9005, subject: "신규 가입 환영 메일", status: "COMPLETED", total: 3210, pending: 0, sent: 3210, failed: 0, bounced: 0, suppressed: 0, opened: 1980, clicked: 610, createdAt: new Date("2026-06-29T10:00:00").toISOString(), senderName: "Acme 팀", senderEmail: "welcome@acme.io", scheduledAt: null },
];

/* Design-template catalog (thumbnails are CSS shapes, per the handoff). */
export interface DesignTemplate {
  id: string;
  name: string;
  category: "뉴스레터" | "프로모션" | "온보딩" | "트랜잭션";
  edited: string;
  badge: "blue" | "amber" | "green" | "gray";
}

export const DESIGN_TEMPLATES: DesignTemplate[] = [
  { id: "newsletter", name: "월간 뉴스레터", category: "뉴스레터", edited: "3일 전 수정", badge: "blue" },
  { id: "promo", name: "프로모션 · 할인 안내", category: "프로모션", edited: "1주 전 수정", badge: "amber" },
  { id: "welcome", name: "신규 가입 환영", category: "온보딩", edited: "2주 전 수정", badge: "green" },
  { id: "repurchase", name: "재구매 유도", category: "프로모션", edited: "1개월 전 수정", badge: "blue" },
  { id: "receipt", name: "주문 영수증", category: "트랜잭션", edited: "2개월 전 수정", badge: "gray" },
];

export const TEMPLATE_CATEGORIES = ["전체", "뉴스레터", "프로모션", "온보딩", "트랜잭션"] as const;
