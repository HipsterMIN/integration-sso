import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { RegisterEnterpriseRequest, RegisterResponse } from 'types/api/provision/register';

const registerEnterprise = async (
	props: RegisterEnterpriseRequest,
): Promise<SuccessResponse<RegisterResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			'/api/ext/register/enterprise',
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
