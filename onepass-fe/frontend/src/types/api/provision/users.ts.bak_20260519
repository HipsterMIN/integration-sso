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
}

/** CI 토큰 발급 요청 (Q-IM /api/ext/ci/token) */
export interface CiTokenRequest {
	/** AES-256-GCM 암호화된 CI: base64(IV(12B) || ciphertext || tag(16B)) */
	encryptedCi: string;
	/** KC realm */
	realm: string;
	/** SP Client ID */
	clientId: string;
	/** 플로우 컨텍스트 */
	flowContext:
		| 'PROVISION_USER'
		| 'CHECK_CONVERSION'
		| 'USER_WITHDRAW'
		/** 만14세 미만 법정대리인 본인인증 (정보통신망법 제31조) */
		| 'GUARDIAN_CONSENT';
	/** 기존 Q-IM mbrUuid (CHECK_CONVERSION/USER_WITHDRAW 시 필수) */
	mbrUuid?: string;
	/** KC user UUID (선택, 감사 보조) */
	kcUserId?: string;
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
