import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import idoInstance from 'api/idoInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import {
	PayloadProps,
	Props,
	WithdrawEnterprisePayload,
	WithdrawEnterpriseRequest,
} from 'types/api/provision/enterprises';

const provisionEnterprise = async (
	props: Props,
): Promise<SuccessResponse<PayloadProps> | ErrorResponse> => {
	try {
		const response = await idoInstance.post(
			'/api/v1/ext/provision/enterprises',
			props,
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

/**
 * 기업 회원 탈퇴 (Q-IM /api/v1/ext/provision/enterprises/withdraw)
 * SP(유관기관) 들로 cascade 호출되며 perAgency 별 결과를 반환.
 * withdrawalReason 미지정 시 '서비스 미이용' 기본값 적용 (audit 보존, PIPA §28).
 */
export const withdrawEnterprise = async (
	props: WithdrawEnterpriseRequest,
): Promise<SuccessResponse<WithdrawEnterprisePayload> | ErrorResponse> => {
	try {
		const response = await idoInstance.post(
			'/api/v1/ext/provision/enterprises/withdraw',
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

export default provisionEnterprise;
