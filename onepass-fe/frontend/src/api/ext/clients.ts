import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import beInstance from 'api/beInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { ClientsResponse } from 'types/api/ext/clients';

const getClients = async (
	params?: { reverseYN?: 'Y' | 'N' },
): Promise<SuccessResponse<ClientsResponse> | ErrorResponse> => {
	try {
		const response = await beInstance.get('/api/v1/ext/clients', { params });
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default getClients;
