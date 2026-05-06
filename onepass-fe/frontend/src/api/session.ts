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

/** POST /api/v1/fe-session/logout */
export const logout = async (): Promise<void> => {
  await apiClient.post('/v1/fe-session/logout');
};
