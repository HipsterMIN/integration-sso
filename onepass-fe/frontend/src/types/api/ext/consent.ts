export interface ConsentTokenRequest {
	realm: string;
	clientId: string;
	flowContext: string;
	kcUserId?: string | null;
}

export interface ConsentTokenResponse {
	success: boolean;
	data: {
		token: string;
		exp: number;
		expiresAt: string;
	};
}

export interface ConsentLine {
	versionId: number;
	accepted: boolean;
}

export interface ConsentSubmitRequest {
	consentToken: string;
	flowContext: string;
	deviceId?: string;
	lines: ConsentLine[];
}

export interface ConsentSubmitResponse {
	success: boolean;
	data: {
		eventId: number;
		submittedAt: string;
		evidenceId: string;
		lineCount: number;
	};
}
