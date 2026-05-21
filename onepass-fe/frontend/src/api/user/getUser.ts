import axios from 'api';
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import { PayloadProps, Props } from 'types/api/user/getUser';

const getUser = async (
	props: Props,
): Promise<SuccessResponse<PayloadProps> | ErrorResponse> => {
	try {
		const response = await axios.get(`/user/${props.userId}`, {
			headers: {
				Authorization: `bearer ${props.token}`,
			},
		});

		return {
			statusCode: 200,
			error: null,
			message: 'Success',
			payload: response.data,
		};
	} catch (error) {
		const isDevelopment = process.env.NODE_ENV === 'development';
		if (isDevelopment) {
			console.warn('getUser API 호출 실패, 개발환경 기본값을 사용합니다:', error);
			// 개발환경에서만 기본값 반환
			return {
				statusCode: 200,
				error: null,
				message: 'success',
				payload: {
					createdAt: Date.now(),
					email: 'dev@localhost.com',
					id: props.userId,
					name: 'Development User',
					orgId: 'dev-org-123',
					profilePictureURL: '',
					organization: 'Development Organization',
					role: 'ADMIN' as const,
					flags: {},
				},
			};
		}
		return ErrorResponseHandler(error as AxiosError);
	}
};

export default getUser;
