import type { CampaignStatus } from "../types";

export const fmt = (n: number): string => (Number.isFinite(n) ? n : 0).toLocaleString();

export const pctOf = (value: number, total: number): number =>
  total > 0 ? Math.round((value / total) * 100) : 0;

/* Maps a campaign status to the `op-badge` modifier class. */
export function badgeClass(status: CampaignStatus): string {
  switch (status) {
    case "SENDING":
      return "sending";
    case "QUEUED":
      return "queued";
    case "COMPLETED":
      return "completed";
    default:
      return "draft";
  }
}
