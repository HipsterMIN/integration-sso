// 1.1 감사 이상 탐지(관찰 모드) — 목록 쿼리·규칙 이름·승격 판단 보조. 점수·플래그는 서버(AuditAnomalyScorer)가 낸다.
import type { AnomalyRuleStat } from './types';

export const RULES = ['ADMIN_LOGIN_FAILURE_BURST', 'ADMIN_NEW_SOURCE_IP', 'ADMIN_OFF_HOURS_WRITE', 'AGENCY_FAILURE_BURST', 'TICKET_REPLAY'] as const;
export const RULE_LABEL: Record<string, string> = {
  ADMIN_LOGIN_FAILURE_BURST: '관리자 로그인 실패 버스트',
  ADMIN_NEW_SOURCE_IP: '관리자 새 출처 IP',
  ADMIN_OFF_HOURS_WRITE: '업무 외 시간 관리자 쓰기',
  AGENCY_FAILURE_BURST: '기관 실패 버스트(기준선 대비)',
  TICKET_REPLAY: 'Handoff 티켓 재검증 반복',
};

export interface AnomalyFilters { from: string; to: string; rule: string; agencyCode: string; severity: string; review: string; page: number; size: number }

export function anomalyQuery(f: AnomalyFilters): string {
  const q = new URLSearchParams();
  if (f.from) q.set('from', new Date(f.from).toISOString());
  if (f.to) q.set('to', new Date(f.to).toISOString());
  for (const [k, v] of [['rule', f.rule], ['agencyCode', f.agencyCode.trim()], ['severity', f.severity], ['review', f.review]] as const) if (v) q.set(k, v);
  q.set('page', String(f.page)); q.set('size', String(f.size));
  return q.toString();
}

/** 3개월 기준선 뒤 경보 승격 판단(docs/audit-anomaly.md §4): 검토 20건 이상 · 정밀도 0.7 이상이면 "승격 후보", 0.3 미만이면 "규칙 조정", 그 사이·표본 부족은 "관찰 계속" */
export function promotionVerdict(s: AnomalyRuleStat): '승격 후보' | '규칙 조정' | '관찰 계속' {
  const reviewed = s.truePositive + s.falsePositive;
  if (reviewed < 20 || s.precision === null) return '관찰 계속';
  if (s.precision >= 0.7) return '승격 후보';
  if (s.precision < 0.3) return '규칙 조정';
  return '관찰 계속';
}

/** details(JSON 문자열) → "key=value" 짧은 요약 */
export function detailsSummary(details: string | null): string {
  if (!details) return '';
  try {
    const o = JSON.parse(details) as Record<string, unknown>;
    return Object.entries(o).map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`).join(' · ');
  } catch { return details; }
}
