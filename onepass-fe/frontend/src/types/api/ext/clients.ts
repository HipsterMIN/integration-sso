export interface Client {
	ssoClientId: string;
	clientNm: string;
	groups: string | null;
	businessTypes: string | null;
	description: string | null;
	serviceStatus: string;
}

export interface PerAgency extends Client {
	registered: boolean;
	queryStatus: string;
	errorCode: string | null;
	errorMessage: string | null;
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
