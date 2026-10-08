import { describe, expect, it } from 'vitest';
import { anomalyQuery, detailsSummary, promotionVerdict } from '../src/lib/anomaly';

describe('anomaly helpers', () => {
  it('목록 쿼리 — 빈 값은 빼고 page/size 는 항상', () => {
    const p = new URLSearchParams(anomalyQuery({ from: '', to: '', rule: 'TICKET_REPLAY', agencyCode: ' AG1 ', severity: '', review: 'UNREVIEWED', page: 2, size: 50 }));
    expect(p.get('rule')).toBe('TICKET_REPLAY');
    expect(p.get('agencyCode')).toBe('AG1');
    expect(p.has('severity')).toBe(false);
    expect(p.get('review')).toBe('UNREVIEWED');
    expect(p.get('page')).toBe('2');
    expect(p.get('size')).toBe('50');
  });

  it('승격 판단 — 검토 20건 이상에서 정밀도 0.7↑ 승격 후보, 0.3↓ 규칙 조정, 표본 부족은 관찰 계속', () => {
    const base = { rule: 'X', total: 100, unsure: 0, unreviewed: 0 };
    expect(promotionVerdict({ ...base, truePositive: 18, falsePositive: 2, precision: 0.9 })).toBe('승격 후보');
    expect(promotionVerdict({ ...base, truePositive: 2, falsePositive: 18, precision: 0.1 })).toBe('규칙 조정');
    expect(promotionVerdict({ ...base, truePositive: 10, falsePositive: 10, precision: 0.5 })).toBe('관찰 계속');
    expect(promotionVerdict({ ...base, truePositive: 9, falsePositive: 1, precision: 0.9 })).toBe('관찰 계속');
    expect(promotionVerdict({ ...base, truePositive: 0, falsePositive: 0, precision: null })).toBe('관찰 계속');
  });

  it('근거 요약 — JSON 을 key=value 로, 깨진 문자열은 그대로', () => {
    expect(detailsSummary('{"count":7,"threshold":5,"windowMinutes":10}')).toBe('count=7 · threshold=5 · windowMinutes=10');
    expect(detailsSummary(null)).toBe('');
    expect(detailsSummary('not json')).toBe('not json');
  });
});
