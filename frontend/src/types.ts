export type CampaignStatus = "DRAFT" | "QUEUED" | "SENDING" | "COMPLETED";

export interface CampaignView {
  id: number;
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
}

export interface TemplateView {
  id: number;
  name: string;
  subject: string;
  htmlBody: string;
  createdAt: string;
  updatedAt: string;
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
