// hub 관리 API(/api/v1/admin/**) 응답 모양 — docs/admin-auth.md §3, ServiceProfileAdminController, AgencyAdminController, AuditQueryController
export type Role = 'SYSTEM_ADMIN' | 'POLICY_ADMIN' | 'AUDITOR';
export type AdminStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED';

export interface AdminMe {
  adminId: string;
  username: string;
  role: Role;
  tenantCode: string | null;
  mustChangePassword: boolean;
}

export interface LoginResponse {
  status: 'OK' | 'MFA_REQUIRED' | 'MFA_ENROLL_REQUIRED';
  mfaToken?: string;
  secret?: string;
  otpauthUri?: string;
  admin?: AdminMe;
}

export interface Agency {
  agencyCode: string;
  officialName: string;
  minAuthLevel: string | null;
  policyVersion: string | null;
  integrationType: string | null;
  active: boolean | null;
  callbackWhitelist: string[] | null;
  allowedAttributes: string[] | null;
  dailyLookupLimit: number | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface OidcClientStatus {
  serviceCode: string;
  clientId: string;
  issuer: string;
  discoveryUrl: string;
  provisioned: boolean;
  enabled: boolean;
  redirectUris: string[];
}

export interface OidcClientSecret {
  serviceCode: string;
  clientId: string;
  clientSecret: string;
  issuer: string;
  discoveryUrl: string;
}

export interface PolicyDecision {
  rule: string;
  outcome: 'ALLOW' | 'DENY' | 'SKIP';
  reason?: string;
  errorCode?: string;
}

export interface PolicySimulation {
  allowed: boolean;
  decisions: PolicyDecision[];
}

export interface AuditItem {
  auditId: string;
  category: string;
  action: string;
  actorType: string;
  actorId: string;
  resourceType: string | null;
  resourceId: string | null;
  agencyCode: string | null;
  correlationId: string | null;
  sourceIp: string | null;
  outcome: string;
  outcomeDetail: string | null;
  metadata: string | null;
  occurredAt: string;
}

export interface AuditPage {
  items: AuditItem[];
  page: number;
  size: number;
  total: number;
}

export interface AdminView {
  adminId: string;
  username: string;
  displayName: string | null;
  role: Role;
  tenantCode: string | null;
  status: AdminStatus;
  totpEnrolled: boolean;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  lockedUntil: string | null;
  createdAt: string;
}

export interface TenantView {
  code: string;
  name: string;
  status: string;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 프로파일 JSON — 스키마(service-profile.v1.schema.json)를 따르는 느슨한 객체 */
export type Profile = Record<string, unknown>;

// ── 1.1 AI 운영 보조 (/api/v1/admin/ai, AiAdminController) — 선택 컨테이너, 꺼져 있으면 status.enabled=false ──
export interface AiStatus {
  enabled: boolean;
  model: string | null;
  endpointHost: string | null;
  reason: string | null;
}

export interface AiDraft {
  draft: Profile;
  violations: string[];
  model: string;
  note: string;
}

export interface AuditDigest {
  total: number;
  rows: number;
  from: string | null;
  to: string | null;
  byCategory: Record<string, number>;
  byOutcome: Record<string, number>;
  topActions: { key: string; n: number }[];
  topAgencies: { key: string; n: number }[];
  topFailures: { action: string; detail: string; n: number }[];
  sample: { occurredAt: string; category: string; action: string; actorType: string; actor: string | null; agencyCode: string | null; outcome: string; detail: string | null }[];
}

export interface AiAuditSummary {
  digest: AuditDigest;
  summary: string;
  model: string;
}

export interface OpsSnapshot {
  at: string;
  health: Record<string, string>;
  webhookOutbox: Record<string, unknown>;
  scimOutbox: Record<string, unknown>;
  sloRetry: Record<string, unknown>;
  auditFailures1h: Record<string, number>;
  auditFailures24h: Record<string, number>;
  metrics: Record<string, number>;
}

export interface AiIncidentSummary {
  snapshot: OpsSnapshot;
  summary: string;
  model: string;
}

// ── 1.1 감사 이상 탐지 (관찰 모드, /api/v1/admin/anomalies, AnomalyAdminController) ──
export type AnomalyReview = 'TRUE_POSITIVE' | 'FALSE_POSITIVE' | 'UNSURE';

export interface AnomalyFlagItem {
  flagId: string;
  auditId: string;
  rule: string;
  score: number;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  subjectType: string;
  subject: string;
  details: string | null;
  category: string;
  action: string;
  agencyCode: string | null;
  actorId: string | null;
  sourceIp: string | null;
  correlationId: string | null;
  occurredAt: string;
  review: AnomalyReview | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  createdAt: string;
}

export interface AnomalyPage {
  items: AnomalyFlagItem[];
  page: number;
  size: number;
  total: number;
}

export interface AnomalyRuleStat {
  rule: string;
  total: number;
  truePositive: number;
  falsePositive: number;
  unsure: number;
  unreviewed: number;
  precision: number | null;
}

export interface AnomalyStats {
  days: number;
  byRule: AnomalyRuleStat[];
  byDay: { day: string; rule: string; n: number }[];
  cursor: { lastOccurredAt?: string | null; scannedTotal?: number; updatedAt?: string | null };
}

// ── 1.1 동의 카탈로그 (/api/v1/admin/services/{code}/consents · /api/v1/admin/consents, ConsentAdminController) ──
/** registry 동의 버전 — serviceCode 없음 = 플랫폼 공통(모든 서비스 로그인에서 묻는다) */
export interface ConsentItem {
  versionId: string;
  consentType: string;
  serviceCode?: string | null;
  status: 'DRAFT' | 'ACTIVE' | 'SUPERSEDED' | string;
  versionTag?: string | null;
  title?: string | null;
  contentUrl?: string | null;
  required: boolean;
  effectiveAt?: string | null;
}

// ── 1.1.1 G1-4 웹훅 서명 비밀 (/api/v1/admin/agencies/{code}/webhook, AgencyAdminController) ──
export interface WebhookStatus {
  agencyCode: string;
  configured: boolean;
  endpointUrl?: string | null;
  active?: boolean | null;
  webhookEnabled?: boolean | null;
  hasSecret?: boolean;
  sealed?: boolean;
  fingerprint?: string | null;
  secretRotatedAt?: string | null;
  updatedAt?: string | null;
}

export interface WebhookSecretRotation {
  agencyCode: string;
  signingSecret: string;
  fingerprint: string;
  warning: string;
}
