/** 아이디 찾기 응답 데이터 (Q-IM /api/ext/account/users/find-login-id) */
export interface FindLoginIdData {
	/** 마스킹된 로그인 ID (앞 3자 + ***) */
	loginId: string;
	/** 마스킹된 회원 UUID (앞 6자 + ***) */
	mbrUuid: string;
	/** 가입일시 ISO8601 */
	createdAt: string;
	/** 회원 상태 코드 (예: ACTIVE) */
	status: string;
}

export type FindLoginIdResultCode =
	| 'SUCCESS'
	| 'NOT_FOUND'
	| 'TOKEN_EXPIRED'
	| 'TOKEN_REUSED'
	| 'FAIL';

/** 아이디 찾기 응답 페이로드 */
export interface FindLoginIdPayload {
	resultCode: FindLoginIdResultCode;
	data?: FindLoginIdData;
	errorCode?: string;
	errorMessage?: string;
}

/** 아이디 찾기 요청 (ciToken 미소비 — verify only) */
export interface FindLoginIdRequest {
	/** CI 토큰 (Q-IM JWT, 최대 4096자) */
	ciToken: string;
}
