import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import beInstance from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { CheckConversionResponse } from 'types/api/ext/clients';

const userCheckConversion = async (params: {
	mbrId?: string;
	ci?: string;
}): Promise<SuccessResponse<CheckConversionResponse> | ErrorResponse> => {
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

export const enterpriseCheckConversion = async (params: {
	brno: string;
}): Promise<SuccessResponse<CheckConversionResponse> | ErrorResponse> => {
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

export default userCheckConversion;
