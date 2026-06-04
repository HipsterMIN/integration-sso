import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import idoInstance from 'api/idoInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { RegisterEnterpriseRequest, RegisterResponse } from 'types/api/provision/register';

const registerEnterprise = async (
	props: RegisterEnterpriseRequest,
): Promise<SuccessResponse<RegisterResponse> | ErrorResponse> => {
	try {
		const response = await idoInstance.post(
			'/api/v1/ext/register/enterprise',
			props,
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

export default registerEnterprise;
