// ══════════════════════════════════════════════════════════════════════════════
// LogoutButton — SLO 로그아웃 버튼 컴포넌트
// 설계서 §13.3 / Sprint 2 P1-06
//
// 동작:
//   1. 버튼 클릭 → useAuth().logout() 호출
//   2. POST /api/v1/slo/initiate → Keycloak 세션 종료 + 기관 Webhook
//   3. 전역 AuthStore 초기화 → /login 리다이렉트
// ══════════════════════════════════════════════════════════════════════════════

import React from 'react';
import { useAuth } from '@/hooks/useAuth';

interface LogoutButtonProps {
  /** 버튼 텍스트 (기본: '로그아웃') */
  label?: string;
  /** 로그아웃 후 리다이렉트 URL (기본: '/login') */
  redirectUrl?: string;
  /** 추가 CSS 클래스 */
  className?: string;
}

/**
 * SLO 로그아웃 버튼
 *
 * @example
 * ```tsx
 * // 기본 사용
 * <LogoutButton />
 *
 * // 커스터마이징
 * <LogoutButton label="Sign Out" redirectUrl="/welcome" className="btn-danger" />
 * ```
 */
export const LogoutButton: React.FC<LogoutButtonProps> = ({
  label       = '로그아웃',
  redirectUrl = '/login',
  className   = '',
}) => {
  const { isAuthenticated, isLoggingOut, logout } = useAuth();

  // 인증되지 않은 상태에서는 렌더링 안 함
  if (!isAuthenticated) return null;

  const handleLogout = async () => {
    if (isLoggingOut) return;
    await logout(redirectUrl);
  };

  return (
    <button
      type="button"
      onClick={handleLogout}
      disabled={isLoggingOut}
      aria-busy={isLoggingOut}
      aria-label={isLoggingOut ? '로그아웃 처리 중...' : label}
      className={`logout-button ${className}`.trim()}
    >
      {isLoggingOut ? '로그아웃 중...' : label}
    </button>
  );
};

export default LogoutButton;
