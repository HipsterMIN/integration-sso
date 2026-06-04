import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import idoInstance from 'api/idoInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type {
	CiTokenRequest,
	CiTokenResponse,
} from 'types/api/provision/users';

/**
 * CI를 AES-GCM 암호화하여 Q-IM에 직접 전송하고 ciToken(JWT)을 발급받는다.
 * 프론트엔드는 ciToken만 메모리(JS 변수)에 보관하고, CI 평문은 즉시 폐기한다.
 */
const exchangeCiToken = async (
	params: CiTokenRequest,
): Promise<SuccessResponse<CiTokenResponse> | ErrorResponse> => {
	try {
		const response = await idoInstance.post('/api/v1/ext/ci/token', params);
		return {
			statusCode: 200,
			error: null,
			message: 'success',
			payload: response.data,
		};
	} catch (error) {
		const axiosError = error as AxiosError;
		const data = axiosError.response?.data as Record<string, unknown> | undefined;
		if (data?.message) {
			return {
				statusCode: (axiosError.response?.status ?? 500) as ErrorResponse['statusCode'],
				payload: null,
				error: (data.errorCode as string) || (data.error as string) || 'error',
				message: data.message as string,
			};
		}
		return ErrorResponseHandler(axiosError);
	}
};

export default exchangeCiToken;
