import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';

/** 유관기관 추가 */
export const addAffiliation = async (
	uuid: string,
	clientIds: string[],
): Promise<SuccessResponse<unknown> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			`/api/ext/provision/enterprises/${uuid}/affiliations/add`,
			{ addClientIds: clientIds },
		);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

/** 유관기관 탈퇴 */
export const withdrawAffiliation = async (
	uuid: string,
	clientIds: string[],
): Promise<SuccessResponse<unknown> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			`/api/ext/provision/enterprises/${uuid}/affiliations/withdraw`,
			{ targetClientIds: clientIds },
		);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};
