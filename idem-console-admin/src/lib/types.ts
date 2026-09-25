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
