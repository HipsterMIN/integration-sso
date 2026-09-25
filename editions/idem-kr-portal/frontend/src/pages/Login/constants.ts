/** Q-Sign / IM 에러 코드별 사용자 안내 메시지 */
export const ERROR_MESSAGES: Record<string, string> = {
	AUTH_FAILED: '아이디 또는 비밀번호가 일치하지 않습니다.',
	VALIDATION_ERROR: '요청 파라미터 형식이 올바르지 않습니다.',
	ACCOUNT_WITHDRAWN: '탈퇴된 계정입니다.',
	ACCOUNT_LOCKED: '잠금된 계정입니다.',
	ACCOUNT_DORMANT: '휴면 계정입니다.',
	ACCOUNT_INACTIVE: '비활성 계정입니다.',
	IM_UNAVAILABLE: 'IM 서버 연결에 실패했습니다. 잠시 후 다시 시도해주세요.',
	INVALID_IM_RESPONSE: '서버 응답 오류가 발생했습니다. 잠시 후 다시 시도해주세요.',
	MISSING_FIELDS: '필수 입력 항목이 누락되었습니다. 다시 시도해주세요.',
};
