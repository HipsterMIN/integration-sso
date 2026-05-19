import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { beApiInstance } from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type { CiCheckRequest, CiCheckResponse } from 'types/api/nice/ciCheck';

const ciCheck = async (
	params: CiCheckRequest,
): Promise<SuccessResponse<CiCheckResponse> | ErrorResponse> => {
	try {
		const response = await beApiInstance.post('/api/v1/auth/nice/ci-check', params);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default ciCheck;
