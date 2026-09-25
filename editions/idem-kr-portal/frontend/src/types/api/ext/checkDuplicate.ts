export interface CheckDuplicateParams {
	type: 'ENT' | 'IND';
	value: string;
}

export interface CheckDuplicateData {
	value: string;
	type: string;
	exists: boolean;
	message: string;
}

export interface CheckDuplicateResponse {
	success: boolean;
	data: CheckDuplicateData;
}
