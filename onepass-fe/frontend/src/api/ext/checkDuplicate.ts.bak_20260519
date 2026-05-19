import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { CheckDuplicateParams, CheckDuplicateResponse } from 'types/api/ext/checkDuplicate';

const checkDuplicate = async (
	params: CheckDuplicateParams,
): Promise<SuccessResponse<CheckDuplicateResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get('/api/ext/check-duplicate', { params });
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default checkDuplicate;
