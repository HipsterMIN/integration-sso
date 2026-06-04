import idoInstance from 'api/idoInstance';
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type {
	PasswordChangePayload,
	PasswordChangeRequest,
} from 'types/api/account/passwordChange';

/**
 * 본인확인(ciToken) + loginId 일치 검증 후 비밀번호 재설정.
 * ciToken 은 호출 즉시 1회 소비 (POLICY_VIOLATION 시 미소비 — 비밀번호만 고쳐 재시도 가능).
 */
const passwordChange = async (
	params: PasswordChangeRequest,
): Promise<SuccessResponse<PasswordChangePayload> | ErrorResponse> => {
	try {
		const response = await idoInstance.post(
			'/api/v1/ext/account/users/password-change',
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

export default passwordChange;
