import { describe, expect, it } from 'vitest';
import { emptyForm, fromProfile, toProfile, validate } from '../src/lib/profile';

describe('profile form ↔ JSON', () => {
  it('OIDC_RP 온보딩 폼이 스키마 모양의 문서를 만든다 (빈 값은 빠진다)', () => {
    const f = { ...emptyForm('AG_A'), name: '기관 A', redirectUris: 'https://a.example.org/cb\n\nhttps://a.example.org/cb2', minAuthLevel: 'L2' as const, idleMinutes: '20', attributes: 'name_masked, birth_year' };
    const p = toProfile(f);
    expect(p).toEqual({
      schemaVersion: 1,
      service: { code: 'AG_A', name: '기관 A', status: 'ACTIVE' },
      protocol: { type: 'OIDC_RP', oidc: { redirectUris: ['https://a.example.org/cb', 'https://a.example.org/cb2'] } },
      identity: { attributes: ['name_masked', 'birth_year'] },
      policy: { minAuthLevel: 'L2', session: { idleMinutes: 20 } },
    });
  });

  it('DIRECT 는 oidc 블록을 내지 않고 endpoints 만 낸다', () => {
    const f = { ...emptyForm('AG_B'), name: 'B', type: 'DIRECT' as const, callbackWhitelist: 'https://b.example.org/entry', ssoEntry: 'https://b.example.org/sso', redirectUris: 'https://ignored' };
    const p = toProfile(f) as { protocol: Record<string, unknown> };
    expect(p.protocol).toEqual({ type: 'DIRECT', endpoints: { callbackWhitelist: ['https://b.example.org/entry'], ssoEntry: 'https://b.example.org/sso' } });
  });

  it('폼 밖 키(rules·maintenance·ui·security·attributeMapping)는 원본에서 보존된다', () => {
    const base = {
      schemaVersion: 1,
      service: { code: 'AG_C', name: 'C', status: 'ACTIVE' },
      protocol: { type: 'DIRECT', endpoints: { callbackWhitelist: ['https://c/x'] }, security: { mtlsRequired: true } },
      identity: { attributes: ['x'], attributeMapping: { x: 'y' } },
      policy: { minAuthLevel: 'L1', rules: [{ type: 'R', params: {} }], maintenance: [{ dayOfWeek: 'SUN', startTime: '01:00', endTime: '02:00' }] },
      ui: { brandName: 'C' },
    };
    const f = fromProfile(base);
    expect(f.callbackWhitelist).toBe('https://c/x');
    const out = toProfile({ ...f, name: 'C2', minAuthLevel: 'L3' }, base) as Record<string, any>;
    expect(out.service.name).toBe('C2');
    expect(out.policy.minAuthLevel).toBe('L3');
    expect(out.policy.rules).toEqual(base.policy.rules);
    expect(out.policy.maintenance).toEqual(base.policy.maintenance);
    expect(out.protocol.security).toEqual({ mtlsRequired: true });
    expect(out.identity.attributeMapping).toEqual({ x: 'y' });
    expect(out.ui).toEqual({ brandName: 'C' });
  });

  it('왕복: fromProfile(toProfile(f)) 가 폼을 되살린다', () => {
    const f = { ...emptyForm('AG_D'), name: 'D', type: 'OIDC_RP' as const, redirectUris: 'https://d/cb', postLogoutRedirectUris: 'https://d/', backchannelLogoutUri: 'https://d/bcl', clientAuthMethod: 'CLIENT_SECRET_POST', subjectScheme: 'EMAIL', allowedProviders: 'MOCK, NICE', absoluteMinutes: '240', concurrent: '1', assignmentRequired: true, selfSignup: false, tps: '10', daily: '1000', tenant: 'T1' };
    expect(fromProfile(toProfile(f))).toEqual(f);
  });

  it('validate 가 코드·이름·OIDC redirect·URL 형식·정수를 잡는다', () => {
    const v = validate({ ...emptyForm('a'), name: '', redirectUris: 'ftp://x', idleMinutes: 'ten' });
    expect(v.join('|')).toContain('기관 코드');
    expect(v.join('|')).toContain('기관 이름');
    expect(v.join('|')).toContain('URL 형식이 아님: ftp://x');
    expect(v.join('|')).toContain('idleMinutes');
    expect(validate({ ...emptyForm('AG_OK'), name: 'ok', redirectUris: 'https://ok/cb' })).toEqual([]);
  });
});
