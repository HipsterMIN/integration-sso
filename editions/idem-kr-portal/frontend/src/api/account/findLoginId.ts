import idoInstance from 'api/idoInstance';
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type {
	FindLoginIdPayload,
	FindLoginIdRequest,
} from 'types/api/account/findLoginId';

/**
 * 본인확인(ciToken) 후 가입된 loginId 를 마스킹하여 회신.
 * ciToken 은 verify only (jti 미소비) — 동일 토큰으로 비밀번호 변경 연속 호출 가능 (10분 내).
 */
const findLoginId = async (
	params: FindLoginIdRequest,
): Promise<SuccessResponse<FindLoginIdPayload> | ErrorResponse> => {
	try {
		const response = await idoInstance.post(
			'/api/v1/ext/account/users/find-login-id',
			params,
		);
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

export default findLoginId;
