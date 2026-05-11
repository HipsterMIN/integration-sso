/**
 * useAuthState — 인증 상태 중앙 관리 훅
 *
 * 기존 Redux store(reducers/app.ts)의 isLoggedIn / user 상태를 단일 인터페이스로
 * 제공한다. Zustand 미도입 환경에서 Redux를 그대로 활용하면서 편의 훅을 제공.
 *
 * 사용 예:
 *   const { isLoggedIn, user, logout } = useAuthState();
 */
import { Logout } from 'api/utils';
import { useSelector } from 'react-redux';
import type { AppState } from 'store/reducers';
import type { User } from 'types/reducer/app';

export interface AuthState {
	/** 현재 로그인 여부 */
	isLoggedIn: boolean;
	/** 로그인한 사용자 정보 (미로그인 시 null) */
	user: User | null;
	/** 사용자 이메일 (편의 접근자) */
	email: string;
	/** 사용자 이름 (편의 접근자) */
	name: string;
	/** 조직 ID */
	orgId: string;
	/**
	 * 로그아웃 실행
	 *
	 * SLO 흐름: POST /api/v1/slo/initiate → 로컬 상태 초기화 → 로그인 이동
	 * (best-effort: API 실패 시에도 로컬 정리 수행)
	 */
	logout: () => void;
}

/**
 * 인증 상태 및 로그아웃 액션을 반환하는 훅.
 *
 * - isLoggedIn: Redux store 기반 로그인 상태
 * - logout: SLO 흐름 통합 로그아웃 (api/utils.ts Logout)
 */
export function useAuthState(): AuthState {
	const isLoggedIn = useSelector(
		(state: AppState) => state.app.isLoggedIn,
	);
	const user = useSelector((state: AppState) => state.app.user ?? null);

	return {
		isLoggedIn,
		user,
		email: user?.email ?? '',
		name: user?.name ?? '',
		orgId: user?.orgId ?? '',
		logout: Logout,
	};
}

export default useAuthState;
