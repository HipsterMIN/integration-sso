import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';

/** 유관기관 추가 (기업회원) */
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

/** 유관기관 추가 (개인회원) */
export const addMemberAffiliation = async (
	mbrUuid: string,
	clientIds: string[],
): Promise<SuccessResponse<unknown> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			`/api/ext/provision/users/${mbrUuid}/affiliations/add`,
			{ mbrUuid, addClientIds: clientIds },
		);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

/** 유관기관 탈퇴 (기업회원) */
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

/** 유관기관 탈퇴 (개인회원) */
export const withdrawMemberAffiliation = async (
	mbrUuid: string,
	clientIds: string[],
	ciToken: string,
	withdrawalReason?: string,
): Promise<SuccessResponse<unknown> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			`/api/ext/provision/users/${mbrUuid}/affiliations/withdraw`,
			{
				mbrUuid,
				ciToken,
				targetClientIds: clientIds,
				withdrawalReason: withdrawalReason || '서비스 미이용',
			},
		);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};
