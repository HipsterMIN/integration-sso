import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import beInstance from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { BusinessValidateRequest, BusinessValidateResponse } from 'types/api/ext/businessValidate';

const businessValidate = async (
	body: BusinessValidateRequest,
): Promise<SuccessResponse<BusinessValidateResponse> | ErrorResponse> => {
	try {
		const response = await beInstance.post('/api/v1/ext/business/validate', body);
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default businessValidate;
