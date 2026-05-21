export interface AuthStatusResponse {
	success: boolean;
	data: {
		authenticated: boolean;
		txId?: string;
		authType?: 'business' | 'personal';
		authenticatedAt?: string;
	};
}

export interface BusinessAuthData {
	name: string;
	businessNumber: string;
	bizOpendt: string;
	birth: string;
	phone: string;
	bizSts: string;
	authType: 'business';
	authenticatedAt: string;
}

export interface PersonalAuthData {
	name: string;
	ci: string;
	di: string;
	authType: 'personal';
	authenticatedAt: string;
}

export interface AuthResultResponse {
	success: boolean;
	data: BusinessAuthData | PersonalAuthData;
	message?: string;
}
