// ══════════════════════════════════════════════════════════════════════════════
// 세션 관련 API — IdO /api/v1/fe-session  (BFF 이관 후 경로)
//
// BFF 기능이 ido 모듈로 이관됨.
//   구 경로: /api/v1/session   (onepass-fe Spring Boot)
//   신 경로: /api/v1/fe-session (ido Spring Boot)
// ══════════════════════════════════════════════════════════════════════════════
import apiClient from './client';
import { SessionCheckResponse } from '@/types';

/** GET /api/v1/fe-session/check?returnUrl=... */
export const checkSession = async (returnUrl?: string): Promise<SessionCheckResponse> => {
  const params = returnUrl ? { returnUrl } : {};
  const { data } = await apiClient.get<SessionCheckResponse>('/v1/fe-session/check', { params });
  return data;
};

/**
 * POST /api/v1/slo/initiate — SLO 전체 흐름 (Keycloak + 기관 Webhook + 감사로그)
 * Sprint 2 P1-01: feSessionId 쿠키를 함께 전송하여 서버 측 세션 만료 처리.
 */
export const logout = async (): Promise<void> => {
  await apiClient.post('/v1/slo/initiate');
};

/**
 * POST /api/v1/fe-session/logout — feSession만 만료 (Keycloak 전파 없음)
 * @deprecated SLO 구현 이후 `logout()` 사용 권장.
 */
export const logoutSessionOnly = async (): Promise<void> => {
  await apiClient.post('/v1/fe-session/logout');
};
