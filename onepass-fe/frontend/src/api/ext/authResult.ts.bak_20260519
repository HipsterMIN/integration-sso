import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type { AuthStatusResponse, AuthResultResponse } from 'types/api/ext/authResult';

export const getAuthStatus = async (): Promise<SuccessResponse<AuthStatusResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get('/api/ext/auth-status');
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export const getAuthResult = async (
	txId: string,
): Promise<SuccessResponse<AuthResultResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get(`/api/ext/auth-result/${txId}`);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};
