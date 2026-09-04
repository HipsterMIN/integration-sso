import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import idoInstance from 'api/idoInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type {
	ProvisionUserPayload,
	ProvisionUserRequest,
	WithdrawUserPayload,
	WithdrawUserRequest,
} from 'types/api/provision/users';

/**
 * Q-IM에 직접 개인회원 프로비저닝 API를 호출한다.
 * ciToken이 consume(jti SETNX) 되며, Q-IM Redis의 sealedCi가 자동 복호화되어 처리된다.
 */
const provisionUser = async (
	props: ProvisionUserRequest,
): Promise<SuccessResponse<ProvisionUserPayload> | ErrorResponse> => {
	try {
		const response = await idoInstance.post('/api/v1/ext/provision/users', props);
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

/**
 * 개인 회원 탈퇴 (Q-IM /api/ext/provision/users/withdraw)
 * ciToken 은 jti 1회 consume — withdrawalReason 은 audit 보존 (PIPA §28).
 */
export const withdrawUser = async (
	props: WithdrawUserRequest,
): Promise<SuccessResponse<WithdrawUserPayload> | ErrorResponse> => {
	try {
		const response = await idoInstance.post(
			'/api/v1/ext/provision/users/withdraw',
			{ ...props, withdrawalReason: props.withdrawalReason || '서비스 미이용' },
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

export default provisionUser;
