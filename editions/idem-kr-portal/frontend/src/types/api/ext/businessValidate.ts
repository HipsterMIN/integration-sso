export interface BusinessValidateRequest {
	bNo: string;
	startDt: string;
	representativeName: string;
	companyName: string;
}

export interface BusinessValidateResponse {
	success: boolean;
	data: {
		valid: boolean;
		validMsg: string | null;
		bStt: string;
		bSttCd: string;
	};
}
