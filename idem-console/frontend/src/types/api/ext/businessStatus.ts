export interface BusinessStatusRequest {
	bNo: string;
}

export interface BusinessStatusResponse {
	success: boolean;
	data: Record<string, unknown>;
	message?: string;
}
