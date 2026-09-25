export interface NewPic {
	memberName: string;
	loginId: string;
	initialPassword: string;
	email: string;
	phone: string;
}

export interface ClientLink {
	clientId: string;
	mbrId?: string;
	rprsInstYn?: string;
}

export interface Props {
	bzmnTypeCd: string;
	brno: string;
	bzmnNm: string;
	rprsvNm: string;
	mbrId?: string;
	/** 설립일 (yyyy-MM-dd) */
	estbDt: string;
	/** 대표전화 */
	rprsTelno?: string;
	/** 대표 이메일 */
	rprsEmlAddr: string;
	newPic: NewPic;
	clients?: ClientLink[];
}

export interface ProvisionResponseData {
	entMbrNo: string;
	entMbrUuid: string;
	brno: string;
	bzmnNm: string;
	picMbrNo: string;
	picMbrUuid: string;
	clients: { clientId: string; mbrId?: string }[];
	provisioningToken: string;
	status: string;
	tokenExpiresAt: string;
}

export interface PayloadProps {
	success: boolean;
	data: ProvisionResponseData;
	message?: string;
	errorCode?: string;
}

/** 기업 회원 탈퇴 요청 (Q-IM /api/v1/ext/provision/enterprises/withdraw) */
export interface WithdrawEnterpriseRequest {
	/** 사업자등록번호 (10자리) */
	brno: string;
	/** 탈퇴 사유 */
	withdrawalReason?: string;
}

/** SP(유관기관) 별 탈퇴 처리 결과 */
export interface WithdrawEnterprisePerAgency {
	/** SP ID */
	instCd?: string;
	/** SP 명 */
	instNm?: string;
	/** Q-IM 발급 UUID */
	mbrUuid?: string | null;
	/** SUCCESS | ALREADY_WITHDRAWN | NOT_FOUND | FAIL | CB_BLOCKED | TIMEOUT */
	resultCode?: string;
	/** 실패 시 에러 코드 */
	errorCode?: string | null;
	/** 실패 시 에러 메시지 */
	errorMessage?: string | null;
}

/** 기업 회원 탈퇴 응답 data 필드 */
export interface WithdrawEnterpriseData {
	/** 전체 호출 SP 수 */
	totalAgencies?: number;
	/** 성공 건수 */
	successCount?: number;
	/** 멱등 hit 건수 */
	alreadyWithdrawnCount?: number;
	/** SP 측 회원 미존재 건수 */
	notFoundCount?: number;
	/** 실패 건수 */
	failedCount?: number;
	/** 탈퇴 대상 SP 별 처리 정보 */
	perAgency: WithdrawEnterprisePerAgency[];
	mode?: string;
}

/**
 * 기업 회원 탈퇴 응답 페이로드.
 * 탈퇴 성공 여부는 `data.failedCount === 0` 으로 판정 (SP cascade 포함 전체 성공일 때만).
 * top-level `success` 는 Q-IM 자체 처리 여부만 표시하므로 사용자 성공 판정에는 부적합.
 */
export interface WithdrawEnterprisePayload {
	success: boolean;
	data: WithdrawEnterpriseData;
	message?: string;
}
