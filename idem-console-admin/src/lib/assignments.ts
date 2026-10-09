// 1.1.1 G1-3 할당 관리 — 콘솔 쪽 순수 로직 (경로·입력 검증·만료 변환·페이지 계산). 상태는 idem-authz 가 가진다; hub 관리 API 가 범위·감사를 본다.
import type { AssignmentPage } from './types';

/** hub AssignmentAdminService.requireCode 와 같은 규칙 — 영숫자·_ . : - 1~100자 (사용자 ID·역할 코드) */
export const CODE_RE = /^[A-Za-z0-9_.:-]{1,100}$/;
export const PAGE_SIZE = 50;
export const STATUS_LABEL: Record<string, string> = { ACTIVE: '유효', REVOKED: '해제', EXPIRED: '만료' };

export const assignmentsPath = (serviceCode: string, qimUserId?: string): string => {
  const base = `/services/${encodeURIComponent(serviceCode)}/assignments`;
  return qimUserId === undefined ? base : `${base}/${encodeURIComponent(qimUserId)}`;
};
export const rolesPath = (serviceCode: string): string => `/services/${encodeURIComponent(serviceCode)}/roles`;
export const userRolesPath = (serviceCode: string, qimUserId: string, roleCode?: string): string => {
  const base = `${assignmentsPath(serviceCode, qimUserId)}/roles`;
  return roleCode === undefined ? base : `${base}/${encodeURIComponent(roleCode)}`;
};

/** 목록 쿼리 — page 는 0부터, size 는 1~200 (hub 가 200 으로 자른다) */
export const listQuery = (page: number, size = PAGE_SIZE): string => `?page=${Math.max(0, Math.trunc(page))}&size=${Math.min(200, Math.max(1, Math.trunc(size)))}`;

export function validateCode(value: string, what: string): string | null {
  const v = value.trim();
  if (!v) return `${what}는 필수`;
  if (!CODE_RE.test(v)) return `${what}는 영숫자·_ . : - 1~100자`;
  return null;
}

/** datetime-local 입력 → ISO. 비우면 undefined(무기한), 과거면 오류 */
export function toExpiresAt(local: string): { value?: string; error?: string } {
  const t = local.trim();
  if (!t) return {};
  const ms = Date.parse(t);
  if (Number.isNaN(ms)) return { error: '만료 시각 형식이 아님' };
  if (ms <= Date.now()) return { error: '만료 시각은 미래여야 한다' };
  return { value: new Date(ms).toISOString() };
}

export interface AssignForm { qimUserId: string; expiresAt: string; reason: string }
export const emptyAssign = (): AssignForm => ({ qimUserId: '', expiresAt: '', reason: '' });

export function validateAssign(f: AssignForm): string[] {
  const v: string[] = [];
  const c = validateCode(f.qimUserId, '사용자 ID');
  if (c) v.push(c);
  const e = toExpiresAt(f.expiresAt);
  if (e.error) v.push(e.error);
  if (f.reason.trim().length > 500) v.push('사유는 500자 이하');
  return v;
}

/** 폼 → POST 본문. 빈 값은 보내지 않는다 */
export function toAssignBody(f: AssignForm): Record<string, unknown> {
  const body: Record<string, unknown> = { qimUserId: f.qimUserId.trim() };
  const e = toExpiresAt(f.expiresAt);
  if (e.value) body.expiresAt = e.value;
  if (f.reason.trim()) body.reason = f.reason.trim();
  return body;
}

export interface RoleForm { roleCode: string; name: string; description: string }
export const emptyRole = (): RoleForm => ({ roleCode: '', name: '', description: '' });

export function validateRole(f: RoleForm): string[] {
  const v: string[] = [];
  const c = validateCode(f.roleCode, '역할 코드');
  if (c) v.push(c);
  if (!f.name.trim() || f.name.trim().length > 200) v.push('이름은 필수 (1~200자)');
  if (f.description.trim().length > 1000) v.push('설명은 1000자 이하');
  return v;
}

export function toRoleBody(f: RoleForm): Record<string, unknown> {
  const body: Record<string, unknown> = { roleCode: f.roleCode.trim(), name: f.name.trim() };
  if (f.description.trim()) body.description = f.description.trim();
  return body;
}

/** 전체 페이지 수 — total 이 0이면 1 (빈 페이지 하나) */
export const pageCount = (p: Pick<AssignmentPage, 'total' | 'size'> | { total: number; size: number }): number =>
  p.size > 0 ? Math.max(1, Math.ceil(p.total / p.size)) : 1;

/** 해제 사유를 쿼리로 — 비우면 붙이지 않는다 */
export const reasonQuery = (reason: string): string => (reason.trim() ? `?reason=${encodeURIComponent(reason.trim())}` : '');
