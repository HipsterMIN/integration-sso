export interface RegisterClientItem {
	ssoClientId: string;
	rprsInstYn?: string;
}

export interface RegisterIndividualRequest {
	loginId: string;
	memberName: string;
	clients: RegisterClientItem[];
}

export interface RegisterEnterpriseRequest {
	brno: string;
	bzmnTypeCd: string;
	picLoginId: string;
	picMbrNm: string;
	clients: RegisterClientItem[];
}

export interface RegisterResponse {
	success: boolean;
	data: {
		status: string;
		results?: { clientId: string; result: string }[];
	};
}

/** @deprecated 기존 호환용 — RegisterResponse 로 대체 */
export type RegisterEnterpriseResponse = RegisterResponse;
