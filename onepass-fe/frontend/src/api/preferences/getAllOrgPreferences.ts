import axios from 'api';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { GetAllOrgPreferencesResponseProps } from 'types/api/preferences/userOrgPreferences';

const getAllOrgPreferences = async (): Promise<
	SuccessResponse<GetAllOrgPreferencesResponseProps> | ErrorResponse
> => {
	try {
		const response = await axios.get(`/org/preferences`);

		return {
			statusCode: 200,
			error: null,
			message: response.data.status,
			payload: response.data,
		};
	} catch (error) {
		const isDevelopment = process.env.NODE_ENV === 'development';
		if (isDevelopment) {
			console.warn('Organization preferences API 호출 실패, 개발환경 기본값을 사용합니다:', error);
			return {
				statusCode: 200,
				error: null,
				message: 'success',
				payload: {
					data: [],
					status: 'success',
				},
			};
		}
		throw error;
	}
};

export default getAllOrgPreferences;
