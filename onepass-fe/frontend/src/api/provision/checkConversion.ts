import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import beInstance from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type {
	CheckConversionPayload,
	CheckConversionRequest,
} from 'types/api/provision/users';

/**
 * Q-IM에 직접 회원전환 가능 여부를 조회한다.
 * ciToken은 verify only (jti consume 안 함) — 동일 ciToken으로 provision/users 재사용 가능.
 */
const checkConversionProxy = async (
	params: CheckConversionRequest,
): Promise<SuccessResponse<CheckConversionPayload> | ErrorResponse> => {
	try {
		const response = await beInstance.post(
			'/api/v1/ext/provision/users/check-conversion',
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

export default checkConversionProxy;
