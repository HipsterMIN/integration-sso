// ══════════════════════════════════════════════════════════════════════════════
// Zustand 전역 인증 상태 스토어
// 설계서 §12.x / Sprint 2 P1-05
//
// 관리 항목:
//   - 로그인 여부, qimUserId, authLevel (L1/L2/L3)
//   - 세션 로딩 상태 (초기화 중 / 완료)
//   - SLO 로그아웃 액션 (POST /api/v1/slo/initiate)
// ══════════════════════════════════════════════════════════════════════════════

import { create } from 'zustand';
import { devtools } from 'zustand/middleware';
import { checkSession, logout as apiLogout } from '@/api/session';
import { AuthLevel } from '@/types';

// ── 타입 정의 ─────────────────────────────────────────────────────────────────

export interface AuthState {
  /** 인증 완료 여부 */
  isAuthenticated: boolean;
  /** Q-IM 사용자 ID (인증 후 설정) */
  qimUserId: string | null;
  /** 인증 수준 */
  authLevel: AuthLevel | null;
  /** 세션 초기화 로딩 중 여부 (앱 마운트 시 checkSession 호출 중) */
  isLoading: boolean;
  /** SLO 로그아웃 진행 중 여부 */
  isLoggingOut: boolean;
}

export interface AuthActions {
  /** 세션 상태 초기화 (앱 마운트 시 1회 호출) */
  initAuth: () => Promise<void>;
  /** 인증 성공 후 상태 업데이트 */
  setAuthenticated: (qimUserId: string, authLevel: AuthLevel) => void;
  /** SLO 로그아웃 실행 (POST /api/v1/slo/initiate → 상태 초기화 → /login 리다이렉트) */
  logout: (redirectUrl?: string) => Promise<void>;
  /** 인증 상태 초기화 (세션 만료 시 401 인터셉터에서 호출) */
  clearAuth: () => void;
}

export type AuthStore = AuthState & AuthActions;

// ── 초기 상태 ─────────────────────────────────────────────────────────────────

const initialState: AuthState = {
  isAuthenticated: false,
  qimUserId:       null,
  authLevel:       null,
  isLoading:       true,   // 앱 초기화 시 로딩 상태로 시작
  isLoggingOut:    false,
};

// ── Zustand 스토어 ─────────────────────────────────────────────────────────────

export const useAuthStore = create<AuthStore>()(
  devtools(
    (set, get) => ({
      ...initialState,

      // ────────────────────────────────────────────────────────────────────
      // initAuth — 앱 마운트 시 백엔드 세션 상태 조회 후 동기화
      // ────────────────────────────────────────────────────────────────────
      initAuth: async () => {
        set({ isLoading: true });
        try {
          const session = await checkSession();
          if (session.valid && session.qimUserId && session.authLevel) {
            set({
              isAuthenticated: true,
              qimUserId:       session.qimUserId,
              authLevel:       session.authLevel,
              isLoading:       false,
            });
          } else {
            set({ ...initialState, isLoading: false });
          }
        } catch {
          // 네트워크 오류 등: 비인증 상태로 초기화
          set({ ...initialState, isLoading: false });
        }
      },

      // ────────────────────────────────────────────────────────────────────
      // setAuthenticated — Q-Sign 인증 완료 콜백에서 호출
      // ────────────────────────────────────────────────────────────────────
      setAuthenticated: (qimUserId: string, authLevel: AuthLevel) => {
        set({
          isAuthenticated: true,
          qimUserId,
          authLevel,
          isLoading:    false,
          isLoggingOut: false,
        });
      },

      // ────────────────────────────────────────────────────────────────────
      // logout — SLO 전체 흐름 실행
      // POST /api/v1/slo/initiate → 전역 상태 초기화 → /login 리다이렉트
      // ────────────────────────────────────────────────────────────────────
      logout: async (redirectUrl = '/login') => {
        if (get().isLoggingOut) return; // 중복 호출 방지
        set({ isLoggingOut: true });
        try {
          await apiLogout();
        } catch (e) {
          // 로그아웃 API 실패해도 클라이언트 상태는 초기화 (방어적 처리)
          console.warn('[authStore] SLO API 실패 — 로컬 상태 초기화:', e);
        } finally {
          // 전역 인증 상태 초기화
          set({ ...initialState, isLoading: false });
          // 로그인 페이지로 리다이렉트
          window.location.href = redirectUrl;
        }
      },

      // ────────────────────────────────────────────────────────────────────
      // clearAuth — 401 인터셉터에서 세션 만료 시 호출
      // ────────────────────────────────────────────────────────────────────
      clearAuth: () => {
        set({ ...initialState, isLoading: false });
      },
    }),
    { name: 'AuthStore' },
  ),
);
