import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { CheckConversionResponse } from 'types/api/ext/clients';

const checkConversion = async (params: {
	mbrId: string;
	ci?: string;
}): Promise<SuccessResponse<CheckConversionResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			'/api/ext/provision/users/check-conversion',
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

export default checkConversion;
