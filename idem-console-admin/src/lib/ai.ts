// 1.1 AI 운영 보조 — 상태 캐시·조회 쿼리·초안 적용. 서버(AiAssistantService)가 스키마 검증과 감사를 맡고, 여기서는 표시·적용만 한다.
import { useEffect, useState } from 'react';
import { get } from './api';
import type { AiStatus, Profile } from './types';

const OFF: AiStatus = { enabled: false, model: null, endpointHost: null, reason: 'status 조회 실패' };
let cached: AiStatus | null = null;
let inflight: Promise<AiStatus> | null = null;

/** /ai/status 는 세션당 한 번 — 꺼진 설치본에서 메뉴·버튼을 숨기는 데만 쓴다 */
export function fetchAiStatus(force = false): Promise<AiStatus> {
  if (cached && !force) return Promise.resolve(cached);
  if (!inflight) {
    inflight = get<AiStatus>('/ai/status')
      .then((s) => { cached = s; return s; })
      .catch(() => { cached = OFF; return OFF; })
      .finally(() => { inflight = null; });
  }
  return inflight;
}

export function resetAiStatusCache(): void { cached = null; }

export function useAiStatus(): AiStatus | null {
  const [s, setS] = useState<AiStatus | null>(cached);
  useEffect(() => {
    let on = true;
    void fetchAiStatus().then((v) => { if (on) setS(v); });
    return () => { on = false; };
  }, []);
  return s;
}

export interface AuditFilters { from: string; to: string; category: string; action: string; agencyCode: string; outcome: string }

/** 감사 화면의 필터 → /ai/audit-summary 쿼리 (감사 조회와 같은 이름, 페이지 없음) */
export function auditSummaryQuery(f: AuditFilters): string {
  const q = new URLSearchParams();
  if (f.from) q.set('from', new Date(f.from).toISOString());
  if (f.to) q.set('to', new Date(f.to).toISOString());
  for (const [k, v] of [['category', f.category], ['action', f.action.trim()], ['agencyCode', f.agencyCode.trim()], ['outcome', f.outcome]] as const) {
    if (v) q.set(k, v);
  }
  return q.toString();
}

/** 초안을 JSON 탭에 넣기 전에 — 기존 기관이면 코드를 고정하고 schemaVersion 을 채운다. 저장은 여전히 관리자의 PUT. */
export function pinDraft(draft: Profile, lockedCode: string | null): Profile {
  const out: Profile = { ...draft };
  if (out.schemaVersion === undefined) out.schemaVersion = 1;
  const service = out.service && typeof out.service === 'object' && !Array.isArray(out.service) ? { ...(out.service as Record<string, unknown>) } : {};
  if (lockedCode) service.code = lockedCode;
  out.service = service;
  return out;
}

export const draftText = (p: Profile): string => JSON.stringify(p, null, 2);

/** 운영 스냅샷의 한 항목(상태별 건수 맵)을 표로 — 값이 객체·null 이면 문자열로 */
export function kvRows(m: Record<string, unknown> | undefined | null): [string, string][] {
  if (!m) return [];
  return Object.entries(m).map(([k, v]) => [k, v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v)]);
}
