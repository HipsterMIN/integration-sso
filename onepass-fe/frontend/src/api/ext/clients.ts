import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import idoInstance from 'api/idoInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { ClientsResponse } from 'types/api/ext/clients';

const getClients = async (): Promise<SuccessResponse<ClientsResponse> | ErrorResponse> => {
	try {
		const response = await idoInstance.get('/api/v1/ext/clients');
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default getClients;
