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
