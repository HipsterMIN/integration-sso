// 1.1 동의 카탈로그 — 콘솔 쪽 순수 로직 (발행 폼 검증·본문·정렬). 항목은 registry 가 가진다; hub 관리 API 가 범위·감사를 본다.
import type { ConsentItem } from './types';

/** registry 와 같은 규칙 — 영대문자로 시작, 영대문자·숫자·_ 2~50자 */
export const CONSENT_TYPE_RE = /^[A-Z][A-Z0-9_]{1,49}$/;
export const COMMON_TYPES = ['TERMS_OF_SERVICE', 'PRIVACY_POLICY', 'MARKETING', 'THIRD_PARTY_SHARING'] as const;
export const STATUS_LABEL: Record<string, string> = { ACTIVE: '시행 중', SUPERSEDED: '종료', DRAFT: '초안' };

export interface PublishForm {
  consentType: string;
  versionTag: string;
  title: string;
  contentUrl: string;
  required: boolean;
  effectiveAt: string;   // datetime-local, 비우면 즉시
}

export const emptyPublish = (): PublishForm => ({ consentType: '', versionTag: '', title: '', contentUrl: '', required: true, effectiveAt: '' });

/** 서비스 코드가 있으면 서비스 전용, 없으면 플랫폼 공통(전역 관리자) 경로 */
export const consentPath = (serviceCode?: string | null): string =>
  serviceCode ? `/services/${encodeURIComponent(serviceCode)}/consents` : '/consents';

export function validatePublish(f: PublishForm): string[] {
  const v: string[] = [];
  if (!CONSENT_TYPE_RE.test(f.consentType.trim().toUpperCase())) v.push('유형은 영대문자로 시작하는 영대문자·숫자·_ 2~50자 (예: PRIVACY_POLICY)');
  if (!f.versionTag.trim() || f.versionTag.trim().length > 50) v.push('버전 태그는 필수 (1~50자, 예: 2026-10)');
  if (!f.title.trim() || f.title.trim().length > 200) v.push('제목은 필수 (1~200자)');
  if (f.contentUrl.trim() && !/^https?:\/\//i.test(f.contentUrl.trim())) v.push('전문 URL 은 http(s) 여야 한다');
  if (f.effectiveAt.trim() && Number.isNaN(Date.parse(f.effectiveAt))) v.push('시행 시각 형식이 아님');
  return v;
}

/** 폼 → POST 본문. 빈 값은 보내지 않는다(registry 기본값). 유형은 대문자 정규화 */
export function toPublishBody(f: PublishForm): Record<string, unknown> {
  const body: Record<string, unknown> = { consentType: f.consentType.trim().toUpperCase(), required: f.required };
  if (f.versionTag.trim()) body.versionTag = f.versionTag.trim();
  if (f.title.trim()) body.title = f.title.trim();
  if (f.contentUrl.trim()) body.contentUrl = f.contentUrl.trim();
  if (f.effectiveAt.trim()) body.effectiveAt = new Date(f.effectiveAt).toISOString();
  return body;
}

/** 유형별로 묶고 시행 중 → 초안 → 종료, 같은 상태는 최근 시행 먼저 */
export function sortItems(items: ConsentItem[]): ConsentItem[] {
  const rank = (s: string) => (s === 'ACTIVE' ? 0 : s === 'DRAFT' ? 1 : 2);
  return [...items].sort((a, b) =>
    a.consentType.localeCompare(b.consentType)
    || rank(a.status) - rank(b.status)
    || String(b.effectiveAt ?? '').localeCompare(String(a.effectiveAt ?? '')));
}
