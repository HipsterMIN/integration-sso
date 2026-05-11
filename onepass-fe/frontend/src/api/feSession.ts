/**
 * feSession (FE 세션) / SLO API 클라이언트
 *
 * ido 서비스의 세션·로그아웃 엔드포인트를 호출한다.
 *
 * SLO 흐름 (설계서 §13.3):
 *   FE 로그아웃 버튼 클릭
 *     → POST /api/v1/slo/initiate  (이 파일)
 *         ① ido: feSession Redis 삭제
 *         ② ido → q-sign: Keycloak 세션 종료
 *         ③ ido: 기관 로그아웃 Webhook Outbox 적재
 *         ④ ido: 감사 로그 기록
 *     → 204 No Content + feSessionId 쿠키 Clear
 */
import axios from 'api';
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';

// ── SLO (Single Logout) ────────────────────────────────────────────────────

/**
 * SLO 시작 — feSessionId 쿠키가 자동으로 포함되므로 별도 파라미터 불필요.
 *
 * 성공: 204 No Content  (feSessionId 쿠키 Max-Age=0 으로 제거됨)
 * 실패: 에러 응답 반환 (Logout 흐름은 best-effort — 실패해도 FE는 로컬 정리 수행)
 */
export const initiateSlo = async (): Promise<
	SuccessResponse<null> | ErrorResponse
> => {
	try {
		await axios.post(
			'/api/v1/slo/initiate',
			{},
			{ withCredentials: true }, // feSessionId 쿠키 전송
		);
		return {
			statusCode: 200,
			error: null,
			message: 'SLO completed',
			payload: null,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

// ── feSession 상태 확인 ────────────────────────────────────────────────────

/**
 * 현재 feSession 유효 여부 확인.
 * 200 OK → 세션 유효, 401/404 → 세션 만료
 */
export const checkFeSession = async (): Promise<
	SuccessResponse<{ sessionId: string; qimUserId: string }> | ErrorResponse
> => {
	try {
		const response = await axios.get('/api/v1/fe-session/check', {
			withCredentials: true,
		});
		return {
			statusCode: 200,
			error: null,
			message: response.statusText,
			payload: response.data,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};
