import axios from 'api';
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { PayloadProps, Props } from 'types/api/user/loginPrecheck';

const loginPrecheck = async (
	props: Props,
): Promise<SuccessResponse<PayloadProps> | ErrorResponse> => {
	try {
		const response = await axios.get(
			`/loginPrecheck?email=${encodeURIComponent(
				props.email,
			)}&ref=${encodeURIComponent(window.location.href)}`,
		);

		return {
			statusCode: 200,
			error: null,
			message: response.statusText,
			payload: response.data.data,
		};
	} catch (error) {
		const isDevelopment = process.env.NODE_ENV === 'development';
		if (isDevelopment) {
			console.warn('loginPrecheck API 호출 실패, 개발환경 기본값을 사용합니다:', error);
			// 개발환경에서만 기본값 반환
			return {
				statusCode: 200,
				error: null,
				message: 'success',
				payload: {
					sso: false,
					ssoUrl: '',
					canSelfRegister: true,
					isUser: true,
				},
			};
		}
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default loginPrecheck;
