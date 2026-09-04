import axios from 'api';
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { AxiosError } from 'axios';
import { getVersion } from 'constants/api';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { PayloadProps } from 'types/api/user/getVersion';

const getVersionApi = async (): Promise<
	SuccessResponse<PayloadProps> | ErrorResponse
> => {
	try {
		const response = await axios.get(`/${getVersion}`);

		return {
			statusCode: 200,
			error: null,
			message: response.data.status,
			payload: response.data,
		};
	} catch (error) {
		const isDevelopment = process.env.NODE_ENV === 'development';
		if (isDevelopment) {
			console.warn('getUserVersion API 호출 실패, 개발환경 기본값을 사용합니다:', error);
			// 개발환경에서만 기본값 반환
			return {
				statusCode: 200,
				error: null,
				message: 'success',
				payload: {
					version: 'development',
					ee: 'N' as const,
					setupCompleted: true, // 개발 환경에서는 setup이 완료된 것으로 간주
				},
			};
		}
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default getVersionApi;
