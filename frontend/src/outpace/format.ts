import type { CampaignStatus, CampaignView } from "../types";

export const fmt = (n: number): string => (Number.isFinite(n) ? n : 0).toLocaleString();

export const pctOf = (value: number, total: number): number =>
  total > 0 ? Math.round((value / total) * 100) : 0;

/* Korean label of a campaign's lifecycle state, shared by every screen so the
   wording never drifts. A QUEUED campaign with a future send time reads as
   "예약됨" (scheduled), not "대기 중" (waiting). */
export function statusLabel(c: Pick<CampaignView, "status" | "scheduledAt">): string {
  if (c.status === "QUEUED" && c.scheduledAt && new Date(c.scheduledAt).getTime() > Date.now()) return "예약됨";
  switch (c.status) {
    case "QUEUED": return "대기 중";
    case "EXPANDING":
    case "SENDING": return "발송 중";
    case "COMPLETED": return "완료";
    case "CANCELED": return "취소됨";
    case "DRAFT": return "초안";
    default: return c.status;
  }
}

/* 억제 사유의 한국어 라벨·배지 색. 사유 문자열은 서버가 만든다 — 웹훅(hard_bounce ·
   complaint), 발송 실패(bounce), 수신거부 링크(unsubscribe), 운영자 수동(manual).
   한 곳에 두어 분석 화면과 억제 목록의 표기가 어긋나지 않게 한다. */
export function suppressionReasonLabel(reason: string | null | undefined): string {
  switch (reason) {
    case "hard_bounce": return "하드 바운스";
    case "complaint": return "스팸 신고";
    case "bounce": return "발송 실패";
    case "unsubscribe": return "수신거부";
    case "manual": return "수동";
    default: return reason || "기타";
  }
}

export function suppressionReasonBadge(reason: string | null | undefined): string {
  switch (reason) {
    case "hard_bounce":
    case "bounce": return "amber";
    case "complaint": return "red";
    case "unsubscribe": return "gray";
    default: return "blue";
  }
}

/* Maps a campaign status to the `op-badge` modifier class. */
export function badgeClass(status: CampaignStatus): string {
  switch (status) {
    case "EXPANDING":
    case "SENDING":
      return "sending";
    case "QUEUED":
      return "queued";
    case "COMPLETED":
      return "completed";
    case "CANCELED":
      return "off";
    default:
      return "draft";
  }
}
