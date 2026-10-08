import { describe, expect, it } from 'vitest';
import { auditSummaryQuery, draftText, kvRows, pinDraft } from '../src/lib/ai';

describe('ai helpers', () => {
  it('감사 요약 쿼리는 감사 조회와 같은 이름, 빈 값은 뺀다', () => {
    const q = auditSummaryQuery({ from: '2026-10-07T09:00', to: '', category: 'ADMIN', action: ' ADMIN_LOGIN_FAILED ', agencyCode: '', outcome: 'FAILURE' });
    const p = new URLSearchParams(q);
    expect(p.get('from')).toBe(new Date('2026-10-07T09:00').toISOString());
    expect(p.has('to')).toBe(false);
    expect(p.get('category')).toBe('ADMIN');
    expect(p.get('action')).toBe('ADMIN_LOGIN_FAILED');
    expect(p.has('agencyCode')).toBe(false);
    expect(p.get('outcome')).toBe('FAILURE');
    expect(p.has('page')).toBe(false);
  });

  it('초안 고정 — 기존 기관이면 코드를 덮고 schemaVersion 을 채운다, 원본은 그대로', () => {
    const draft = { service: { code: 'WRONG', name: '기관' }, protocol: { type: 'DIRECT' } };
    const pinned = pinDraft(draft, 'AG_1');
    expect(pinned.schemaVersion).toBe(1);
    expect((pinned.service as Record<string, unknown>).code).toBe('AG_1');
    expect((pinned.service as Record<string, unknown>).name).toBe('기관');
    expect((draft.service as Record<string, unknown>).code).toBe('WRONG');
    const free = pinDraft({ schemaVersion: 1, service: { code: 'NEW' } }, null);
    expect((free.service as Record<string, unknown>).code).toBe('NEW');
    expect(pinDraft({}, null).service).toEqual({});
  });

  it('draftText 는 들여쓴 JSON, kvRows 는 null·객체를 문자열로', () => {
    expect(draftText({ a: 1 })).toBe('{\n  "a": 1\n}');
    expect(kvRows({ PENDING: 3, lastError: null, nested: { x: 1 } })).toEqual([['PENDING', '3'], ['lastError', '—'], ['nested', '{"x":1}']]);
    expect(kvRows(undefined)).toEqual([]);
  });
});
