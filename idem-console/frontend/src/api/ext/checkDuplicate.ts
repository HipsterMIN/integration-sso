import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import idoInstance from 'api/idoInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { CheckDuplicateParams, CheckDuplicateResponse } from 'types/api/ext/checkDuplicate';

const checkDuplicate = async (
	params: CheckDuplicateParams,
): Promise<SuccessResponse<CheckDuplicateResponse> | ErrorResponse> => {
	try {
		const response = await idoInstance.get('/api/v1/ext/check-duplicate', { params });
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default checkDuplicate;
