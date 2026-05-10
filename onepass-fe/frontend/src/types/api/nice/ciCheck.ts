export interface CiCheckRequest {
	ci: string;
	mbrDvsnCd: 'A101' | 'A102';
	cmpMbrId?: string;
	bizno?: string;
	indvlMbrNm?: string;
	indvlMbrId?: string;
}

export interface CiCheckResponse {
	result: boolean;
	indvlMbrId?: string;
	cmpMbrId?: string;
}
