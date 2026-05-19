import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { ClientsResponse } from 'types/api/ext/clients';

const getClients = async (): Promise<SuccessResponse<ClientsResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get('/api/ext/clients');
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default getClients;
