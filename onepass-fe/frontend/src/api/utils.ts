import deleteLocalStorageKey from 'api/browser/localstorage/remove';
import { initiateSlo } from 'api/feSession';
import { LOCALSTORAGE } from 'constants/localStorage';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import store from 'store';
import {
	LOGGED_IN,
	UPDATE_ORG,
	UPDATE_USER,
	UPDATE_USER_ACCESS_REFRESH_ACCESS_TOKEN,
	UPDATE_USER_ORG_ROLE,
} from 'types/actions/app';

/**
 * 로컬 상태 초기화 — Redux 스토어 + localStorage 클리어
 * SLO 성공·실패 무관하게 항상 실행 (best-effort 정책)
 */
const clearLocalAuthState = (): void => {
	deleteLocalStorageKey(LOCALSTORAGE.AUTH_TOKEN);
	deleteLocalStorageKey(LOCALSTORAGE.IS_LOGGED_IN);
	deleteLocalStorageKey(LOCALSTORAGE.IS_IDENTIFIED_USER);
	deleteLocalStorageKey(LOCALSTORAGE.REFRESH_AUTH_TOKEN);
	deleteLocalStorageKey(LOCALSTORAGE.LOGGED_IN_USER_EMAIL);
	deleteLocalStorageKey(LOCALSTORAGE.LOGGED_IN_USER_NAME);
	deleteLocalStorageKey(LOCALSTORAGE.CHAT_SUPPORT);

	store.dispatch({
		type: LOGGED_IN,
		payload: { isLoggedIn: false },
	});

	store.dispatch({
		type: UPDATE_USER_ORG_ROLE,
		payload: { org: null, role: null },
	});

	store.dispatch({
		type: UPDATE_USER,
		payload: {
			ROLE: 'VIEWER',
			email: '',
			name: '',
			orgId: '',
			orgName: '',
			profilePictureURL: '',
			userId: '',
			userFlags: {},
		},
	});

	store.dispatch({
		type: UPDATE_USER_ACCESS_REFRESH_ACCESS_TOKEN,
		payload: { accessJwt: '', refreshJwt: '' },
	});

	store.dispatch({
		type: UPDATE_ORG,
		payload: { org: [] },
	});

	// eslint-disable-next-line @typescript-eslint/ban-ts-comment
	// @ts-ignore
	if (window && window.Intercom) {
		// eslint-disable-next-line @typescript-eslint/ban-ts-comment
		// @ts-ignore
		window.Intercom('shutdown');
	}
};

/**
 * 전체 로그아웃 (SLO — Single Logout)
 *
 * 흐름:
 *   1. POST /api/v1/slo/initiate → ido: feSession 삭제 + Keycloak 세션 종료
 *                                       + 기관 로그아웃 Webhook 적재 + 감사 로그
 *   2. 로컬 상태 초기화 (SLO 성공·실패 무관 — best-effort)
 *   3. 로그인 페이지 이동
 *
 * SLO API 실패 시에도 로컬 정리는 반드시 수행하여 FE 세션이 남지 않도록 한다.
 */
export const Logout = (): void => {
	// best-effort: API 결과를 기다리지 않고 로컬 정리 후 이동
	// (네트워크 장애·만료 세션에서도 로그아웃 UX 보장)
	initiateSlo().catch(() => {
		// SLO 실패는 치명적이지 않음 — 로컬 정리는 아래에서 항상 수행
	});

	clearLocalAuthState();
	history.push(ROUTES.LOGIN);
};

/**
 * SLO 완료를 기다리는 비동기 버전 (중요한 감사 추적이 필요한 경우 사용)
 *
 * 일반 로그아웃 버튼에서는 `Logout()` 사용 권장.
 * 보안 이벤트 감사가 중요한 경우 이 함수로 대체한다.
 */
export const LogoutAsync = async (): Promise<void> => {
	try {
		await initiateSlo();
	} catch {
		// SLO 실패는 치명적이지 않음
	} finally {
		clearLocalAuthState();
		history.push(ROUTES.LOGIN);
	}
};
