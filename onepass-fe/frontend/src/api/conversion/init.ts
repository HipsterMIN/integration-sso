import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { beApiInstance } from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';

/** /api/v1/conversion/init 응답 (snake_case — BE @JsonProperty 기준) */
export interface ConversionInitResponse {
	/** 전환 세션 ID */
	conversion_session_id: string;
	/** 회원 유형: INDIVIDUAL | ENTERPRISE */
	user_type: string;
	/** 세션 만료 시각 ISO8601 */
	expires_at: string;
}

export interface ConversionInitRequest {
	/** signed_request JWT (신규 흐름) */
	signed_request?: string;
	/** 레거시: 기관 클라이언트 ID */
	client_id?: string;
	/** 레거시: 회원 ID */
	mbr_id?: string;
	/** 레거시: redirect URI */
	redirect_uri?: string;
}

/**
 * 회원전환 세션 초기화 (IdO proxy 경유)
 * POST /api/v1/conversion/init
 */
const conversionInit = async (
	params: ConversionInitRequest,
): Promise<SuccessResponse<ConversionInitResponse> | ErrorResponse> => {
	try {
		const response = await beApiInstance.post('/api/v1/conversion/init', params);
		return {
			statusCode: 200,
			error: null,
			message: 'success',
			payload: response.data,
		};
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default conversionInit;
