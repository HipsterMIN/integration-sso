import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import idoInstance from 'api/idoInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type { ConsentTokenRequest, ConsentTokenResponse, ConsentSubmitRequest, ConsentSubmitResponse } from 'types/api/ext/consent';

export const getConsentToken = async (
	body: ConsentTokenRequest,
): Promise<SuccessResponse<ConsentTokenResponse> | ErrorResponse> => {
	try {
		const response = await idoInstance.post('/api/v1/ext/consent/token', body);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export const submitConsent = async (
	body: ConsentSubmitRequest,
): Promise<SuccessResponse<ConsentSubmitResponse> | ErrorResponse> => {
	try {
		const response = await idoInstance.post('/api/v1/ext/consent', body);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};
