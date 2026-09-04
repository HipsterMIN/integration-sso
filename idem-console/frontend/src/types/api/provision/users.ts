export interface ProvisionUserClientLink {
	clientId: string;
	mbrId?: string;
	rprsInstYn?: string;
}

export interface ProvisionUserRequest {
	/** CI 토큰 (Q-IM JWT, sub = mbrUuid) */
	ciToken: string;
	/** 성명 */
	memberName: string;
	/** 로그인 ID (5~20자) */
	loginId: string;
	/** 초기 비밀번호 */
	initialPassword: string;
	/** 전달 대상 클라이언트 목록 (필수, NotEmpty) */
	clients: ProvisionUserClientLink[];
	/** 이메일 (계정/연락용 루트 필드) */
	email?: string;
	/** 휴대폰 (계정/연락용 루트 필드, dash 포함) */
	phone?: string;
	/** 개인 휴대폰 */
	indvMblTelno?: string;
	/** 개인 이메일 */
	indvEmlAddr?: string;
	/** 일반전화 (자택, dash 허용) */
	telno?: string;
	/** 생년월일 (YYYYMMDD) */
	birthDate?: string;
	/** 알림 채널 설정 */
	notiPrefs?: { sms: string; kakao: string; email: string };
}

export interface ProvisionUserResponseData {
	mbrNo: string;
	mbrUuid: string;
	provisioningToken: string;
	tokenExpiresAt: string;
	agencyStatus: string;
	summary: {
		total: number;
		success: number;
		failed: number;
		stubbed: number;
		externalStubbed: number;
	};
	agencyResults: {
		clientId: string;
		status: string;
		instMbrId?: string;
	}[];
	failedAgencies: string[];
}

export interface ProvisionUserPayload {
	resultCode: string;
	resultMsg: string;
	data: ProvisionUserResponseData;
	success?: boolean;
	message?: string;
	errorCode?: string;
}

/** CI 토큰 발급 응답 (Q-IM /api/ext/ci/token) — NICE 인증 결과 원본 보존 */
export interface NiceAuthResultExtra {
	/** 이름 (NFC 정규화 + trim) */
	name?: string;
	/** 생년월일 (숫자만, 예: "19900101") */
	birthDate?: string;
	/** 성별 (M | F) */
	gender?: 'M' | 'F';
	/** 휴대폰번호 (숫자만, 예: "01012345678") */
	phone?: string;
}

/**
 * 플로우 컨텍스트
 * - PROVISION_USER: 신규 회원 프로비저닝
 * - CHECK_CONVERSION: 기존 회원 → SSO 전환 검사
 * - USER_WITHDRAW: 회원 탈퇴
 * - GUARDIAN_CONSENT: 미성년자 가입 시 보호자 동의 인증
 * - FIND_LOGIN_ID: 아이디 찾기 (CI 인증)
 * - PASSWORD_CHANGE: 비밀번호 찾기/변경 (CI 인증)
 */
export type CiTokenFlowContext =
	| 'PROVISION_USER'
	| 'CHECK_CONVERSION'
	| 'USER_WITHDRAW'
	| 'GUARDIAN_CONSENT'
	| 'FIND_LOGIN_ID'
	| 'PASSWORD_CHANGE';

/** CI 토큰 발급 요청 (Q-IM /api/ext/ci/token) */
export interface CiTokenRequest {
	/** AES-256-GCM 암호화된 CI: base64(IV(12B) || ciphertext || tag(16B)) */
	encryptedCi: string;
	/** KC realm */
	realm: string;
	/** SP Client ID */
	clientId: string;
	/** 플로우 컨텍스트 */
	flowContext: CiTokenFlowContext;
	/** 기존 Q-IM mbrUuid (CHECK_CONVERSION/USER_WITHDRAW 시 필수) */
	mbrUuid?: string;
	/** KC user UUID (선택, 감사 보조) */
	kcUserId?: string;
	/**
	 * NICE 인증 결과 부가 데이터 (선택, PROVISION_USER/GUARDIAN_CONSENT 시 권장)
	 * 서버측 감사 로그 및 사용자 표시명 캐시용으로 함께 전달.
	 * @see NiceAuthResultExtra
	 */
	/** 성명 (NFC 정규화 + trim 권장) */
	name?: string;
	/** 생년월일 (숫자만, 예: "19900101") */
	birthDate?: string;
	/** 휴대폰번호 (숫자만, 예: "01012345678") */
	phone?: string;
	/** 성별 (M | F | '') — NICE 미입력시 빈문자열 허용 */
	gender?: 'M' | 'F' | '';
}

/** CI 토큰 발급 응답 (Q-IM /api/ext/ci/token) */
export interface CiTokenResponseData {
	/** JWT (sub = mbrUuid) */
	ciToken: string;
	/** Q-IM 회원 UUID (PROVISION 시 신규 발급, CHECK/WITHDRAW 시 echo) */
	mbrUuid: string;
	/** TTL 초 (300) */
	exp: number;
	/** 만료 시각 ISO8601 */
	expiresAt: string;
}

export interface CiTokenResponse {
	success: boolean;
	data: CiTokenResponseData;
	message?: string;
	errorCode?: string;
}

/** 개인 회원 탈퇴 요청 (Q-IM /api/v1/ext/provision/users/withdraw) */
export interface WithdrawUserRequest {
	/** Q-IM 이 발급한 CI 토큰 (JWT 토큰, TTL: 10분) */
	ciToken: string;
	/** 탈퇴 사유 */
	withdrawalReason?: string;
}

/** SP(유관기관) 별 탈퇴 처리 결과 */
export interface WithdrawUserPerAgency {
	/** SP ID */
	instCd?: string;
	/** SP 명 */
	instNm?: string;
	/** Q-IM 발급 UUID (없을 수 있음) */
	mbrUuid?: string | null;
	/** SUCCESS | ALREADY_WITHDRAWN | NOT_FOUND | FAIL | CB_BLOCKED | TIMEOUT
	 *  | CONNECTION_REFUSED | ENCRYPT_FAILED | EMPTY_RESPONSE | INVALID_RESPONSE | UNKNOWN */
	resultCode?: string;
	/** 실패 시 에러 코드 */
	errorCode?: string | null;
	/** 실패 시 에러 메시지 */
	errorMessage?: string | null;
}

/** 개인 회원 탈퇴 응답 data 필드 */
export interface WithdrawUserData {
	totalAgencies?: number;
	successCount?: number;
	alreadyWithdrawnCount?: number;
	notFoundCount?: number;
	failedCount?: number;
	perAgency: WithdrawUserPerAgency[];
	mode?: string;
}

/**
 * 개인 회원 탈퇴 응답 페이로드.
 * 탈퇴 성공 여부는 `data.failedCount === 0` 으로 판정 (SP cascade 포함 전체 성공일 때만).
 * top-level `success` 는 Q-IM 자체 처리 여부만 표시하므로 사용자 성공 판정에는 부적합.
 */
export interface WithdrawUserPayload {
	success: boolean;
	data: WithdrawUserData;
	message?: string;
}

/** check-conversion 요청 (Q-IM /api/ext/provision/users/check-conversion) */
export interface CheckConversionRequest {
	/** CI 토큰 (Q-IM JWT) */
	ciToken: string;
	/** 회원 ID */
	mbrId?: string;
	/** 회원 UUID */
	mbrUuid?: string;
}

/** check-conversion 응답 페이로드 */
export interface CheckConversionPayload {
	resultCode: string;
	resultMsg: string;
	data: {
		perAgency: import('types/api/ext/clients').PerAgency[];
		groups: import('types/api/ext/clients').ClientGroup[];
		businessTypes: import('types/api/ext/clients').BusinessType[];
	};
}
