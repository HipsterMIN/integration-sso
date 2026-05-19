import { beApiInstance } from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type {
	CiTokenRequest,
	CiTokenResponse,
} from 'types/api/provision/users';

/**
 * CI를 AES-GCM 암호화하여 ido(8083)의 POST /api/v1/auth/ci-token 으로 전송하고
 * ciToken(qimUserId 기반 JWT)을 발급받는다.
 *
 * B-3/Q3=B 보안 패치:
 *   - 기존: extInstance → Q-IM(8082) /api/ext/ci/token 직접 호출 (CI FE 경유)
 *   - 변경: beApiInstance → ido(8083) /api/v1/auth/ci-token (서버사이드 CI 처리)
 *           CI 원문은 ido BE에서 복호화·처리하며 FE에 노출되지 않음
 *
 * 프론트엔드는 ciToken만 메모리(JS 변수)에 보관하고, encryptedCi는 즉시 폐기한다.
 */
const exchangeCiToken = async (
	params: CiTokenRequest,
): Promise<SuccessResponse<CiTokenResponse> | ErrorResponse> => {
	try {
		const response = await beApiInstance.post('/api/v1/auth/ci-token', params);
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
