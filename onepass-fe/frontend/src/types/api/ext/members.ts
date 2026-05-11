/** 개인회원 조회 응답 (§5.1 GET /api/ext/members/{mbrNo}) */
export interface MemberData {
	mbrNo: string;
	mbrUuid: string;
	memberName: string;
	loginId: string;
	mbrSttsCd: string;
	mbrSttsNm: string;
	ssoLastLoginDt: string;
	phone?: string;
	email?: string;
	enabled: boolean;
	clients: MemberClient[];
}

export interface MemberResponse {
	success: boolean;
	data: MemberData;
}

/** 기업회원 조회 응답 (§5.2 GET /api/ext/enterprises/{entMbrNo}) */
export interface EnterpriseData {
	entMbrNo: string;
	mbrUuid: string;
	mbrSttsCd: string;
	mbrSttsNm: string;
	bzmnTypeCd: string;
	brno: string;
	bzmnNm: string;
	rprsvNm: string;
	ssoLastLoginDt: string | null;
	rprsTelno?: string;
	email?: string;
	pics: EnterprisePic[];
	clients: MemberClient[];
}

export interface EnterpriseResponse {
	success: boolean;
	data: EnterpriseData;
}

export interface MemberClient {
	clientId?: string;
	clientNm: string;
	description?: string;
	mbrId: string;
	rprsInstYn: string;
	useYn: string;
}

/** §5.5/§5.6 유관서비스 목록 슬림 응답 */
export interface AffiliationsData {
	mbrUuid: string;
	clients: MemberClient[];
}

export interface AffiliationsResponse {
	success: boolean;
	data: AffiliationsData;
}

export interface EnterprisePic {
	picMbrNo: string;
	entMngPicYn: string;
	picDeptNm: string;
	picJbpsNm: string;
	ssoLastLoginDt: string | null;
}

/**
 * 개인회원 정보 수정 요청 (§5.3 PATCH /api/ext/members/{mbrNo})
 * - memberName: 이름 (필수)
 * - phone:      휴대전화번호 "010-1234-5678" 형식 (선택)
 * - email:      이메일 "user@example.com" 형식 (선택)
 */
export interface UpdateMemberRequest {
	memberName: string;
	phone?: string;
	email?: string;
}

/**
 * 기업회원 정보 수정 요청 (§5.4 PATCH /api/ext/enterprises/{entMbrNo})
 * - bzmnNm:    회사명 (필수)
 * - rprsvNm:   대표자명 (필수)
 * - rprsTelno: 대표 전화번호 "02-1234-5678" 형식 (선택)
 * - email:     이메일 "user@example.com" 형식 (선택)
 */
export interface UpdateEnterpriseRequest {
	bzmnNm: string;
	rprsvNm: string;
	rprsTelno?: string;
	email?: string;
}
