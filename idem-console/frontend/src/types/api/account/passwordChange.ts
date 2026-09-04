/** 비밀번호 변경 응답 데이터 (Q-IM /api/ext/account/users/password-change) */
export interface PasswordChangeData {
	/** 마스킹된 회원 UUID */
	mbrUuid: string;
	/** 변경 시각 ISO8601 */
	changedAt: string;
}

export type PasswordChangeResultCode =
	| 'SUCCESS'
	| 'NOT_FOUND'
	| 'TOKEN_EXPIRED'
	| 'TOKEN_REUSED'
	| 'MISMATCH'
	| 'POLICY_VIOLATION'
	| 'FAIL';

/** 비밀번호 변경 응답 페이로드 */
export interface PasswordChangePayload {
	resultCode: PasswordChangeResultCode;
	data?: PasswordChangeData;
	errorCode?: string;
	errorMessage?: string;
}

/** 비밀번호 변경 요청 (ciToken 1회 소비 — POLICY_VIOLATION 시 미소비) */
export interface PasswordChangeRequest {
	/** CI 토큰 (Q-IM JWT, 최대 4096자) */
	ciToken: string;
	/** 사용자가 입력한 전체 로그인 ID (최대 50자, 마스킹 값 ❌) */
	loginId: string;
	/** 신규 비밀번호 평문 (최대 256자, 서버에서 BCrypt 저장) */
	newPassword: string;
}
