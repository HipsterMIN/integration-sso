import getLocalStorageApi from 'api/browser/localstorage/get';
import setLocalStorageApi from 'api/browser/localstorage/set';
import getUserApi from 'api/user/getUser';
import { Logout } from 'api/utils';
import { LOCALSTORAGE } from 'constants/localStorage';
import store from 'store';
import AppActions from 'types/actions';
import {
	LOGGED_IN,
	UPDATE_USER,
	UPDATE_USER_ACCESS_REFRESH_ACCESS_TOKEN,
	UPDATE_USER_IS_FETCH,
} from 'types/actions/app';
import { SuccessResponse } from 'types/api';
import { PayloadProps } from 'types/api/user/getUser';
import { USER_ROLES } from 'types/roles';

const afterLogin = async (
	userId: string,
	authToken: string,
	refreshToken: string,
): Promise<SuccessResponse<PayloadProps> | undefined> => {
	setLocalStorageApi(LOCALSTORAGE.AUTH_TOKEN, authToken);
	setLocalStorageApi(LOCALSTORAGE.REFRESH_AUTH_TOKEN, refreshToken);

	store.dispatch<AppActions>({
		type: UPDATE_USER_ACCESS_REFRESH_ACCESS_TOKEN,
		payload: {
			accessJwt: authToken,
			refreshJwt: refreshToken,
		},
	});

	const shouldSkipAuth = process.env.SKIP_AUTH === 'true';
	const hasApiEndpoint = !!process.env.FRONTEND_API_ENDPOINT;

	let getUserResponse;

	if (shouldSkipAuth && !hasApiEndpoint) {
		// 인증 스킵 설정이 활성화되고 API 엔드포인트가 없으면 mock 데이터 직접 사용
		getUserResponse = {
			statusCode: 200 as const,
			error: null,
			message: 'success',
			payload: {
				createdAt: Date.now(),
				email: 'dev@localhost.com',
				id: userId,
				name: 'Development User',
				orgId: 'dev-org-123',
				profilePictureURL: '',
				organization: 'Development Organization',
				role: USER_ROLES.ADMIN,
				flags: {},
			},
		};
	} else {
		const [response] = await Promise.all([
			getUserApi({
				userId,
				token: authToken,
			}),
		]);
		getUserResponse = response;
	}

	if (getUserResponse.statusCode === 200 && getUserResponse.payload) {
		store.dispatch<AppActions>({
			type: LOGGED_IN,
			payload: {
				isLoggedIn: true,
			},
		});

		const { payload } = getUserResponse;

		store.dispatch<AppActions>({
			type: UPDATE_USER,
			payload: {
				ROLE: payload.role,
				email: payload.email,
				name: payload.name,
				orgName: payload.organization,
				profilePictureURL: payload.profilePictureURL,
				userId: payload.id,
				orgId: payload.orgId,
				userFlags: payload.flags,
			},
		});

		const isLoggedInLocalStorage = getLocalStorageApi(LOCALSTORAGE.IS_LOGGED_IN);

		if (isLoggedInLocalStorage === null) {
			setLocalStorageApi(LOCALSTORAGE.IS_LOGGED_IN, 'true');
		}

		store.dispatch({
			type: UPDATE_USER_IS_FETCH,
			payload: {
				isUserFetching: false,
			},
		});

		return getUserResponse;
	}

	store.dispatch({
		type: UPDATE_USER_IS_FETCH,
		payload: {
			isUserFetching: false,
		},
	});

	Logout();

	return undefined;
};

export default afterLogin;
