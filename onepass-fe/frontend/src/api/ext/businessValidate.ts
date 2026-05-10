import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { BusinessValidateRequest, BusinessValidateResponse } from 'types/api/ext/businessValidate';

const businessValidate = async (
	body: BusinessValidateRequest,
): Promise<SuccessResponse<BusinessValidateResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.post('/api/ext/business/validate', body);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default businessValidate;
