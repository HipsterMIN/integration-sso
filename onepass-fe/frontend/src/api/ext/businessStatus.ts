import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { BusinessStatusRequest, BusinessStatusResponse } from 'types/api/ext/businessStatus';

const getBusinessStatus = async (
	body: BusinessStatusRequest,
): Promise<SuccessResponse<BusinessStatusResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.post('/api/ext/business/status', body);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default getBusinessStatus;
