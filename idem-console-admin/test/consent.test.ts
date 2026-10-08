import { describe, expect, it } from 'vitest';
import { consentPath, emptyPublish, sortItems, toPublishBody, validatePublish } from '../src/lib/consent';
import type { ConsentItem } from '../src/lib/types';

describe('consent catalog (1.1)', () => {
  it('경로: 서비스 코드가 있으면 서비스 전용, 없으면 플랫폼 공통', () => {
    expect(consentPath('AG 1')).toBe('/services/AG%201/consents');
    expect(consentPath(null)).toBe('/consents');
    expect(consentPath(undefined)).toBe('/consents');
  });

  it('validatePublish: 유형 형식·제목·전문 URL·시행 시각을 잡고, 소문자 유형은 대문자로 봐 준다', () => {
    const bad = validatePublish({ ...emptyPublish(), consentType: '1abc', contentUrl: 'ftp://x', effectiveAt: 'not-a-date' });
    expect(bad.join('|')).toContain('유형');
    expect(bad.join('|')).toContain('버전 태그');
    expect(bad.join('|')).toContain('제목');
    expect(bad.join('|')).toContain('http(s)');
    expect(bad.join('|')).toContain('시행 시각');
    expect(validatePublish({ ...emptyPublish(), consentType: 'privacy_policy', versionTag: '2026-10', title: '개인정보', contentUrl: 'https://a.example.org/p' })).toEqual([]);
  });

  it('toPublishBody: 빈 값은 보내지 않고 유형은 대문자, 시행 시각은 ISO', () => {
    expect(toPublishBody({ ...emptyPublish(), consentType: ' marketing ', title: '마케팅', required: false })).toEqual({ consentType: 'MARKETING', required: false, title: '마케팅' });
    const b = toPublishBody({ ...emptyPublish(), consentType: 'TERMS_OF_SERVICE', title: 't', versionTag: '2026-10', contentUrl: 'https://a/t', effectiveAt: '2026-10-01T09:00' });
    expect(b.versionTag).toBe('2026-10');
    expect(b.contentUrl).toBe('https://a/t');
    expect(typeof b.effectiveAt).toBe('string');
    expect(Date.parse(String(b.effectiveAt))).toBe(new Date('2026-10-01T09:00').getTime());
  });

  it('sortItems: 유형별로 묶고 시행 중이 먼저, 같은 상태는 최근 시행 먼저', () => {
    const it = (versionId: string, consentType: string, status: string, effectiveAt: string): ConsentItem => ({ versionId, consentType, status, required: true, effectiveAt });
    const sorted = sortItems([
      it('c', 'TERMS', 'SUPERSEDED', '2026-01-01T00:00:00Z'),
      it('a', 'PRIVACY', 'ACTIVE', '2026-09-01T00:00:00Z'),
      it('b', 'TERMS', 'ACTIVE', '2026-10-01T00:00:00Z'),
      it('d', 'TERMS', 'SUPERSEDED', '2025-01-01T00:00:00Z'),
    ]);
    expect(sorted.map((x) => x.versionId)).toEqual(['a', 'b', 'c', 'd']);
  });
});
