import { describe, expect, it } from 'vitest';
import {
  CODE_RE, assignmentsPath, emptyAssign, emptyRole, listQuery, pageCount, reasonQuery, rolesPath, toAssignBody, toExpiresAt, toRoleBody,
  userRolesPath, validateAssign, validateCode, validateRole,
} from '../src/lib/assignments';
import { agenciesQuery, pageCount as agencyPages } from '../src/lib/agencies';

describe('할당 관리 (1.1.1 G1-3)', () => {
  it('경로: 서비스·사용자·역할 코드를 URL 인코딩한다', () => {
    expect(assignmentsPath('AG 1')).toBe('/services/AG%201/assignments');
    expect(assignmentsPath('AG1', 'u:1/x')).toBe('/services/AG1/assignments/u%3A1%2Fx');
    expect(rolesPath('AG1')).toBe('/services/AG1/roles');
    expect(userRolesPath('AG1', 'u1')).toBe('/services/AG1/assignments/u1/roles');
    expect(userRolesPath('AG1', 'u1', 'VIEWER')).toBe('/services/AG1/assignments/u1/roles/VIEWER');
    expect(listQuery(2)).toBe('?page=2&size=50');
    expect(listQuery(-1, 999)).toBe('?page=0&size=200');
    expect(reasonQuery('  ')).toBe('');
    expect(reasonQuery('정리 & 이관')).toBe('?reason=%EC%A0%95%EB%A6%AC%20%26%20%EC%9D%B4%EA%B4%80');
  });

  it('코드 검증: hub requireCode 와 같은 규칙 (영숫자·_ . : - 1~100자)', () => {
    expect(CODE_RE.test('user_1.a:b-c')).toBe(true);
    expect(validateCode('u1', '사용자 ID')).toBeNull();
    expect(validateCode('', '사용자 ID')).toContain('필수');
    expect(validateCode('u 1', '사용자 ID')).toContain('영숫자');
    expect(validateCode('x'.repeat(101), '역할 코드')).toContain('1~100자');
  });

  it('만료: 비우면 무기한, 과거·형식 오류는 잡고, 미래면 ISO', () => {
    expect(toExpiresAt('')).toEqual({});
    expect(toExpiresAt('nope').error).toContain('형식');
    expect(toExpiresAt('2000-01-01T00:00').error).toContain('미래');
    const future = new Date(Date.now() + 86_400_000);
    const local = `${future.getFullYear()}-${String(future.getMonth() + 1).padStart(2, '0')}-${String(future.getDate()).padStart(2, '0')}T09:00`;
    const r = toExpiresAt(local);
    expect(r.error).toBeUndefined();
    expect(Date.parse(String(r.value))).toBe(new Date(local).getTime());
  });

  it('할당 폼: 검증과 본문 (빈 값은 보내지 않는다)', () => {
    expect(validateAssign({ ...emptyAssign(), qimUserId: 'bad id', expiresAt: '2000-01-01T00:00', reason: 'r'.repeat(501) })).toHaveLength(3);
    expect(validateAssign({ ...emptyAssign(), qimUserId: ' u1 ' })).toEqual([]);
    expect(toAssignBody({ ...emptyAssign(), qimUserId: ' u1 ' })).toEqual({ qimUserId: 'u1' });
    expect(toAssignBody({ qimUserId: 'u1', expiresAt: '', reason: ' 이관 ' })).toEqual({ qimUserId: 'u1', reason: '이관' });
  });

  it('역할 폼: 코드·이름 필수, 설명은 선택', () => {
    expect(validateRole({ ...emptyRole(), roleCode: 'VIEWER' })).toEqual(['이름은 필수 (1~200자)']);
    expect(validateRole({ roleCode: 'bad code', name: 'n', description: 'd'.repeat(1001) })).toHaveLength(2);
    expect(toRoleBody({ roleCode: ' VIEWER ', name: ' 열람 ', description: '' })).toEqual({ roleCode: 'VIEWER', name: '열람' });
    expect(toRoleBody({ roleCode: 'VIEWER', name: '열람', description: ' 읽기 ' })).toEqual({ roleCode: 'VIEWER', name: '열람', description: '읽기' });
  });

  it('페이지 수: total 0 → 1, 올림', () => {
    expect(pageCount({ total: 0, size: 50 })).toBe(1);
    expect(pageCount({ total: 50, size: 50 })).toBe(1);
    expect(pageCount({ total: 51, size: 50 })).toBe(2);
    expect(pageCount({ total: 5, size: 0 })).toBe(1);
  });

  it('기관 목록: 서버 페이징·검색 쿼리 (빈 검색어는 붙이지 않는다)', () => {
    expect(agenciesQuery(0, '')).toBe('/agencies?page=0&size=50');
    expect(agenciesQuery(3, ' 보건 ')).toBe('/agencies?page=3&size=50&q=%EB%B3%B4%EA%B1%B4');
    expect(agenciesQuery(-2, '', 10)).toBe('/agencies?page=0&size=10');
    expect(agencyPages(0)).toBe(1);
    expect(agencyPages(101)).toBe(3);
  });
});
