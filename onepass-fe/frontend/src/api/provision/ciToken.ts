import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
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
		const response = await extInstance.post('/api/ext/ci/token', params);
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

export default exchangeCiToken;
