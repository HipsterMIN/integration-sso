import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type {
	ProvisionUserPayload,
	ProvisionUserRequest,
} from 'types/api/provision/users';

/**
 * Q-IM에 직접 개인회원 프로비저닝 API를 호출한다.
 * ciToken이 consume(jti SETNX) 되며, Q-IM Redis의 sealedCi가 자동 복호화되어 처리된다.
 */
const provisionUser = async (
	props: ProvisionUserRequest,
): Promise<SuccessResponse<ProvisionUserPayload> | ErrorResponse> => {
	try {
		const response = await extInstance.post('/api/ext/provision/users', props);
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

export default provisionUser;
