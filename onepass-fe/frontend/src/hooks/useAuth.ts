// ══════════════════════════════════════════════════════════════════════════════
// useAuth — 인증 상태 접근 및 SLO 로그아웃 Hook
// 설계서 §12.x / Sprint 2 P1-05
//
// 사용 예:
//   const { isAuthenticated, qimUserId, authLevel, logout } = useAuth();
// ══════════════════════════════════════════════════════════════════════════════

import { useCallback } from 'react';
import { useAuthStore } from '@/store/authStore';
import { AuthLevel } from '@/types';

export interface UseAuthReturn {
  /** 인증 완료 여부 */
  isAuthenticated: boolean;
  /** Q-IM 사용자 ID */
  qimUserId: string | null;
  /** 인증 수준 (L1/L2/L3) */
  authLevel: AuthLevel | null;
  /** 세션 초기화 로딩 중 여부 */
  isLoading: boolean;
  /** SLO 로그아웃 진행 중 여부 */
  isLoggingOut: boolean;
  /**
   * SLO 로그아웃 실행
   * POST /api/v1/slo/initiate → 전역 상태 초기화 → /login 리다이렉트
   */
  logout: (redirectUrl?: string) => Promise<void>;
}

/**
 * 인증 상태 및 SLO 로그아웃 액션 제공 Hook
 *
 * @example
 * ```tsx
 * const { isAuthenticated, logout } = useAuth();
 * return isAuthenticated
 *   ? <button onClick={() => logout()}>로그아웃</button>
 *   : <a href="/login">로그인</a>;
 * ```
 */
export function useAuth(): UseAuthReturn {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const qimUserId       = useAuthStore((s) => s.qimUserId);
  const authLevel       = useAuthStore((s) => s.authLevel);
  const isLoading       = useAuthStore((s) => s.isLoading);
  const isLoggingOut    = useAuthStore((s) => s.isLoggingOut);
  const storeLogout     = useAuthStore((s) => s.logout);

  const logout = useCallback(
    (redirectUrl?: string) => storeLogout(redirectUrl),
    [storeLogout],
  );

  return {
    isAuthenticated,
    qimUserId,
    authLevel,
    isLoading,
    isLoggingOut,
    logout,
  };
}
