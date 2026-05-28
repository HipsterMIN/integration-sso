export interface Client {
	ssoClientId: string;
	clientNm: string;
	groups: string | null;
	businessTypes: string | null;
	description: string | null;
	serviceStatus: string;
	/** SP 측 회원 식별자 — check-conversion(PerAgency) 출처일 때만 채워짐 */
	instMbrId?: string;
	logo?: string | null;
}

export interface PerAgency extends Client {
	registered: boolean;
	queryStatus: string;
	errorCode: string | null;
	errorMessage: string | null;
	instMbrId: string;
	/** SP(유관기관 시스템) 서비스 URL */
	svcUrlAddr?: string;
}

export interface ClientGroup {
	key: string;
	name: string;
}

export interface BusinessType {
	key: string;
	name: string;
}

export interface ClientsResponse {
	success: boolean;
	data: {
		clients: Client[];
		groups: ClientGroup[];
		businessTypes: BusinessType[];
	};
}

export interface CheckConversionResponse {
	success: boolean;
	data: {
		perAgency: PerAgency[];
		overlapCount: number;
		convertible: boolean;
		groups: ClientGroup[];
		businessTypes: BusinessType[];
	};
}
