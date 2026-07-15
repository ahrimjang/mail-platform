export type CampaignStatus = "DRAFT" | "QUEUED" | "EXPANDING" | "SENDING" | "COMPLETED" | "CANCELED";

export type MessageStatus =
  | "PENDING"
  | "SENDING"
  | "SENT"
  | "FAILED"
  | "BOUNCED"
  | "SUPPRESSED"
  | "CANCELED";

// One per-recipient delivery row (drill-down feed).
export interface MessageView {
  id: number;
  recipient: string;
  status: MessageStatus;
  errorMessage: string | null;
  updatedAt: string;
}

// One aggregated send-log row: N deliveries that hit `status` in the same time bucket.
export interface SendLogEntry {
  time: string;
  status: MessageStatus;
  count: number;
  detail: string | null; // representative failure reason for FAILED/BOUNCED buckets
}

// Per-variant delivery/engagement stats of an A/B campaign.
export interface VariantStats {
  variant: string; // "A" | "B"
  total: number;
  sent: number;
  opened: number;
  clicked: number;
}

export interface CampaignView {
  id: number;
  name?: string | null; // console display name; null = fall back to subject
  description?: string | null; // free-form note; null = none
  subject: string;
  status: CampaignStatus;
  total: number;
  pending: number;
  sent: number;
  failed: number;
  bounced: number;
  suppressed: number;
  opened: number;
  clicked: number;
  createdAt: string;
  senderName: string | null; // From display name (null = SMTP default)
  senderEmail: string | null; // From address (null = SMTP default)
  scheduledAt: string | null; // requested send time; null = immediate
  templateId: number | null; // content source (null = authored directly)
  templateName: string | null; // resolved at read time; null if deleted since
  listId: number | null; // audience source (null = raw addresses)
  listName: string | null; // resolved at read time; null if deleted since
  // A/B winner flow (all null/absent for split-only A/B and plain campaigns):
  abTestPercent?: number | null; // share of the audience in the test; the rest gets the winner
  abEvalMetric?: string | null; // "OPEN" | "CLICK"
  abWinner?: string | null; // "A" | "B"; null until decided
  abEvaluateAt?: string | null; // when the winner gets decided; null until the test batch is out
  variants?: VariantStats[] | null; // A/B per-variant stats; null/absent = plain campaign
}

// Subject + HTML body snapshot a campaign sends (variables still unrendered).
export interface CampaignContentView {
  subject: string;
  htmlBody: string;
  // A/B variant B snapshot; null on plain campaigns (null abBodyB = body shared with A).
  abSubjectB?: string | null;
  abBodyB?: string | null;
}

// Analytics: one ranked tracked link (clicks in the selected period).
export interface LinkClicksView {
  url: string;
  clicks: number;
  uniqueMessages: number;
}

// Analytics: one (weekday, hour) bucket of the open heatmap (dayOfWeek: 1=Mon..7=Sun).
export interface OpenHeatmapCell {
  dayOfWeek: number;
  hour: number;
  opens: number;
}

// Analytics: why addresses stop receiving mail.
export interface AudienceHealthView {
  suppressionReasons: { reason: string; total: number; recent: number }[];
  listOptOuts: { listId: number; listName: string; count: number }[];
}

// One day of platform-wide activity for the dashboard chart (failed folds in bounced).
export interface DashboardDay {
  date: string; // yyyy-MM-dd
  sent: number;
  failed: number;
  opened: number;
  clicked: number;
}

export interface DashboardView {
  contacts: number;
  suppressed: number;
  daily: DashboardDay[]; // oldest first, gap-free
}

export interface TemplateView {
  id: number;
  name: string;
  subject: string;
  htmlBody: string;
  createdAt: string;
  updatedAt: string;
  // Seed key of a built-in template; null = user-authored. Built-ins are
  // editable but not deletable, and can be reset to their original content.
  builtinKey: string | null;
}

export interface RenderedTemplate {
  subject: string;
  htmlBody: string;
}

export interface ContactView {
  id: number;
  email: string;
  firstName: string | null;
  lastName: string | null;
  attributes: Record<string, string>;
  createdAt: string;
}

export interface ContactListView {
  id: number;
  name: string;
  description: string | null;
  memberCount: number;
  createdAt: string;
}

export interface ImportResult {
  imported: number;
  skipped: number;
}

export interface SubscriptionView {
  suppressed: boolean;
  reason: string | null;
  since: string | null;
}

export interface UpdateContactListsRequest {
  listIds: number[];
}

export interface UpdateSubscriptionRequest {
  suppressed: boolean;
}
