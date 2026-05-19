import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type { TermsBundleResponse } from 'types/api/ext/termsBundle';

const getTermsBundle = async (
	realm = 'qim',
	client = 'sp-smeg',
	lang = 'ko',
): Promise<SuccessResponse<TermsBundleResponse> | ErrorResponse> => {
	try {
		const response = await extInstance.get('/api/ext/terms/bundle', {
			params: { realm, client, lang },
		});
		return { statusCode: 200, error: null, message: 'success', payload: response.data };
	} catch (error) {
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default getTermsBundle;
