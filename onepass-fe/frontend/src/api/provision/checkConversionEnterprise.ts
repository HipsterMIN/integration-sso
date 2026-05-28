import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import beInstance from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type {
	CheckConversionEnterprisePayload,
	CheckConversionEnterpriseRequest,
} from 'types/api/provision/enterprises';

/**
 * 기업 회원 조회 (SP cascade) — brno 로 유관기관별 가입 여부 + 메타 조회.
 * 응답의 perAgency 는 ENT/ALL 분류 SP 만 포함 (서버측 필터).
 */
const checkConversionEnterprise = async (
	params: CheckConversionEnterpriseRequest,
): Promise<SuccessResponse<CheckConversionEnterprisePayload> | ErrorResponse> => {
	try {
		const response = await beInstance.post(
			'/api/v1/ext/provision/enterprises/check-conversion',
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

export default checkConversionEnterprise;
