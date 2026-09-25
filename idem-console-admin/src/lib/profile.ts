// Service Profile(JSON) ↔ 온보딩 폼 모델. 폼이 다루지 않는 키(rules·maintenance·ui·attributeMapping·security 등)는 그대로 보존한다.
import type { Profile } from './types';

export const PROTOCOL_TYPES = ['OIDC_RP', 'DIRECT', 'BRIDGE', 'APACHE_GATE', 'INTERNAL_SSO'] as const;
export type ProtocolType = (typeof PROTOCOL_TYPES)[number];
export const AUTH_LEVELS = ['L1', 'L2', 'L3'] as const;
export const SUBJECT_SCHEMES = ['', 'PAIRWISE_HMAC', 'PLATFORM_ID', 'EMAIL', 'PHONE', 'EXTERNAL_SUB'] as const;
export const CLIENT_AUTH_METHODS = ['', 'CLIENT_SECRET_BASIC', 'CLIENT_SECRET_POST'] as const;

export interface ProfileForm {
  code: string;
  name: string;
  status: 'ACTIVE' | 'INACTIVE';
  tenant: string;
  type: ProtocolType;
  callbackWhitelist: string;   // 줄 단위
  bridge: string;
  apacheGate: string;
  ssoDomain: string;
  ssoEntry: string;
  redirectUris: string;        // 줄 단위
  postLogoutRedirectUris: string;
  backchannelLogoutUri: string;
  clientAuthMethod: string;
  subjectScheme: string;
  attributes: string;          // 쉼표
  minAuthLevel: 'L1' | 'L2' | 'L3';
  allowedProviders: string;    // 쉼표
  idleMinutes: string;
  absoluteMinutes: string;
  concurrent: string;
  assignmentRequired: boolean;
  selfSignup: boolean;
  tps: string;
  daily: string;
}

export function emptyForm(code = ''): ProfileForm {
  return {
    code, name: '', status: 'ACTIVE', tenant: '', type: 'OIDC_RP',
    callbackWhitelist: '', bridge: '', apacheGate: '', ssoDomain: '', ssoEntry: '',
    redirectUris: '', postLogoutRedirectUris: '', backchannelLogoutUri: '', clientAuthMethod: '',
    subjectScheme: '', attributes: '', minAuthLevel: 'L1', allowedProviders: '',
    idleMinutes: '', absoluteMinutes: '', concurrent: '', assignmentRequired: false, selfSignup: false,
    tps: '', daily: '',
  };
}

export const lines = (s: string): string[] => s.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
export const csv = (s: string): string[] => s.split(',').map((x) => x.trim()).filter(Boolean);
const num = (s: string): number | undefined => (s.trim() === '' ? undefined : Number(s));
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' && !Array.isArray(v) ? { ...(v as Record<string, unknown>) } : {});
const arr = (v: unknown): string[] => (Array.isArray(v) ? v.map(String) : []);
const str = (v: unknown): string => (v === undefined || v === null ? '' : String(v));
function prune<T extends Record<string, unknown>>(o: T): T | undefined {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(o)) {
    if (v === undefined || v === '' || (Array.isArray(v) && v.length === 0)) continue;
    if (v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v as object).length === 0) continue;
    out[k] = v;
  }
  return Object.keys(out).length ? (out as T) : undefined;
}

/** 폼 → 프로파일 JSON. base 는 저장돼 있던 원본(있으면 폼 밖 키를 유지) */
export function toProfile(f: ProfileForm, base: Profile = {}): Profile {
  const service = prune({ ...obj(base.service), code: f.code.trim(), name: f.name.trim(), status: f.status, tenant: f.tenant.trim() || undefined }) ?? {};
  const baseProtocol = obj(base.protocol);
  const endpoints = prune({
    ...obj(baseProtocol.endpoints),
    callbackWhitelist: lines(f.callbackWhitelist),
    bridge: f.bridge.trim() || undefined, apacheGate: f.apacheGate.trim() || undefined,
    ssoDomain: f.ssoDomain.trim() || undefined, ssoEntry: f.ssoEntry.trim() || undefined,
  });
  const oidc = f.type === 'OIDC_RP'
    ? prune({
      ...obj(baseProtocol.oidc),
      redirectUris: lines(f.redirectUris),
      postLogoutRedirectUris: lines(f.postLogoutRedirectUris),
      backchannelLogoutUri: f.backchannelLogoutUri.trim() || undefined,
      clientAuthMethod: f.clientAuthMethod || undefined,
    })
    : undefined;
  const protocol: Record<string, unknown> = { ...baseProtocol, type: f.type };
  if (endpoints) protocol.endpoints = endpoints; else delete protocol.endpoints;
  if (oidc) protocol.oidc = oidc; else delete protocol.oidc;

  const identity = prune({ ...obj(base.identity), subjectScheme: f.subjectScheme || undefined, attributes: csv(f.attributes) });
  const basePolicy = obj(base.policy);
  const session = prune({ ...obj(basePolicy.session), idleMinutes: num(f.idleMinutes), absoluteMinutes: num(f.absoluteMinutes), concurrent: num(f.concurrent) });
  const assignment = f.assignmentRequired || f.selfSignup
    ? { ...obj(basePolicy.assignment), required: f.assignmentRequired, selfSignup: f.selfSignup }
    : undefined;
  const policy: Record<string, unknown> = { ...basePolicy, minAuthLevel: f.minAuthLevel };
  const providers = csv(f.allowedProviders);
  if (providers.length) policy.allowedProviders = providers; else delete policy.allowedProviders;
  if (session) policy.session = session; else delete policy.session;
  if (assignment) policy.assignment = assignment; else delete policy.assignment;
  const limits = prune({ ...obj(base.limits), tps: num(f.tps), daily: num(f.daily) });

  const out: Profile = { ...base, schemaVersion: base.schemaVersion ?? 1, service, protocol, policy };
  if (identity) out.identity = identity; else delete out.identity;
  if (limits) out.limits = limits; else delete out.limits;
  return out;
}

/** 프로파일 JSON → 폼 */
export function fromProfile(p: Profile): ProfileForm {
  const service = obj(p.service);
  const protocol = obj(p.protocol);
  const endpoints = obj(protocol.endpoints);
  const oidc = obj(protocol.oidc);
  const identity = obj(p.identity);
  const policy = obj(p.policy);
  const session = obj(policy.session);
  const assignment = obj(policy.assignment);
  const limits = obj(p.limits);
  const type = PROTOCOL_TYPES.includes(protocol.type as ProtocolType) ? (protocol.type as ProtocolType) : 'DIRECT';
  const level = AUTH_LEVELS.includes(policy.minAuthLevel as 'L1') ? (policy.minAuthLevel as 'L1' | 'L2' | 'L3') : 'L1';
  return {
    code: str(service.code), name: str(service.name), status: service.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE', tenant: str(service.tenant),
    type,
    callbackWhitelist: arr(endpoints.callbackWhitelist).join('\n'),
    bridge: str(endpoints.bridge), apacheGate: str(endpoints.apacheGate), ssoDomain: str(endpoints.ssoDomain), ssoEntry: str(endpoints.ssoEntry),
    redirectUris: arr(oidc.redirectUris).join('\n'), postLogoutRedirectUris: arr(oidc.postLogoutRedirectUris).join('\n'),
    backchannelLogoutUri: str(oidc.backchannelLogoutUri), clientAuthMethod: str(oidc.clientAuthMethod),
    subjectScheme: str(identity.subjectScheme), attributes: arr(identity.attributes).join(', '),
    minAuthLevel: level, allowedProviders: arr(policy.allowedProviders).join(', '),
    idleMinutes: str(session.idleMinutes), absoluteMinutes: str(session.absoluteMinutes), concurrent: str(session.concurrent),
    assignmentRequired: assignment.required === true, selfSignup: assignment.selfSignup === true,
    tps: str(limits.tps), daily: str(limits.daily),
  };
}

/** 폼 자체 검사 — 서버 스키마 검증 전에 잡을 수 있는 것만 */
export function validate(f: ProfileForm): string[] {
  const v: string[] = [];
  if (!/^[A-Za-z0-9_-]{2,64}$/.test(f.code.trim())) v.push('기관 코드는 영문·숫자·_·- 2~64자');
  if (!f.name.trim()) v.push('기관 이름은 필수');
  if (f.type === 'OIDC_RP' && lines(f.redirectUris).length === 0) v.push('OIDC_RP 는 redirect URI 가 하나 이상 필요');
  if (f.type === 'BRIDGE' && !f.bridge.trim()) v.push('BRIDGE 는 bridge 엔드포인트가 필요');
  if (f.type === 'APACHE_GATE' && !f.apacheGate.trim()) v.push('APACHE_GATE 는 apacheGate 엔드포인트가 필요');
  for (const u of [...lines(f.redirectUris), ...lines(f.postLogoutRedirectUris), ...lines(f.callbackWhitelist)]) {
    if (!/^https?:\/\//.test(u)) v.push(`URL 형식이 아님: ${u}`);
  }
  for (const [k, s] of [['idleMinutes', f.idleMinutes], ['absoluteMinutes', f.absoluteMinutes], ['concurrent', f.concurrent], ['tps', f.tps], ['daily', f.daily]] as const) {
    if (s.trim() !== '' && !/^\d+$/.test(s.trim())) v.push(`${k} 는 정수`);
  }
  return v;
}
