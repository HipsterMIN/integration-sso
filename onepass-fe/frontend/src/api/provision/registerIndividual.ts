import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { RegisterIndividualRequest, RegisterResponse } from 'types/api/provision/register';

const registerIndividual = async (
	props: RegisterIndividualRequest,
): Promise<SuccessResponse<RegisterResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.post(
			'/api/ext/register/individual',
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

export default registerIndividual;
