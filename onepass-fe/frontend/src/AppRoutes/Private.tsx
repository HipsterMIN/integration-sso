/* eslint-disable react-hooks/exhaustive-deps */
import getLocalStorageApi from 'api/browser/localstorage/get';
import loginApi from 'api/user/login';
import { Logout } from 'api/utils';
import Spinner from 'components/Spinner';
import { LOCALSTORAGE } from 'constants/localStorage';
import ROUTES from 'constants/routes';
import { useNotifications } from 'hooks/useNotifications';
import history from 'lib/history';
import { ReactChild, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useDispatch, useSelector } from 'react-redux';
import { matchPath, Redirect, useLocation } from 'react-router-dom';
import { Dispatch } from 'redux';
import { AppState } from 'store/reducers';
import { getInitialUserTokenRefreshToken } from 'store/utils';
import AppActions from 'types/actions';
import { UPDATE_USER_IS_FETCH } from 'types/actions/app';
import AppReducer from 'types/reducer/app';
import { routePermission } from 'utils/permission';

import routes from './routes';
import afterLogin from './utils';

function PrivateRoute({ children }: PrivateRouteProps): JSX.Element {
	const location = useLocation();
	const { pathname } = location;

	const mapRoutes = useMemo(
		() =>
			new Map(
				routes.map((e) => {
					const currentPath = matchPath(pathname, {
						path: e.path,
					});
					return [currentPath === null ? null : 'current', e];
				}),
			),
		[pathname],
	);

	const {
		isUserFetching,
		isUserFetchingError,
		isLoggedIn: isLoggedInState,
	} = useSelector<AppState, AppReducer>((state) => state.app);

	const { t } = useTranslation(['common']);
	const localStorageUserAuthToken = getInitialUserTokenRefreshToken();

	const dispatch = useDispatch<Dispatch<AppActions>>();

	const { notifications } = useNotifications();

	const currentRoute = mapRoutes.get('current');

	const isLocalStorageLoggedIn =
		getLocalStorageApi(LOCALSTORAGE.IS_LOGGED_IN) === 'true';

	const navigateToLoginIfNotLoggedIn = (isLoggedIn = isLoggedInState): void => {
		dispatch({
			type: UPDATE_USER_IS_FETCH,
			payload: {
				isUserFetching: false,
			},
		});
		if (!isLoggedIn) {
			history.push(ROUTES.LOGIN, { from: pathname });
		}
	};

	const handleUserLoginIfTokenPresent = async (
		key: keyof typeof ROUTES,
	): Promise<void> => {
		if (localStorageUserAuthToken?.refreshJwt) {
			const response = await loginApi({
				refreshToken: localStorageUserAuthToken?.refreshJwt,
			});

			if (response.statusCode === 200) {
				const route = routePermission[key];

				const userResponse = await afterLogin(
					response.payload.userId,
					response.payload.accessJwt,
					response.payload.refreshJwt,
				);

				if (
					userResponse &&
					route &&
					route.find((e) => e === userResponse.payload.role) === undefined
				) {
					history.push(ROUTES.UN_AUTHORIZED);
				}
			} else {
				Logout();

				notifications.error({
					message: response.error || t('something_went_wrong'),
				});
			}
		}
	};

	const handlePrivateRoutes = async (
		key: keyof typeof ROUTES,
	): Promise<void> => {
		if (
			localStorageUserAuthToken &&
			localStorageUserAuthToken.refreshJwt &&
			isUserFetching
		) {
			handleUserLoginIfTokenPresent(key);
		} else {
			navigateToLoginIfNotLoggedIn(isLocalStorageLoggedIn);
		}
	};

	// eslint-disable-next-line sonarjs/cognitive-complexity
	useEffect(() => {
		(async (): Promise<void> => {
			try {
				if (currentRoute) {
					const { isPrivate, key } = currentRoute;

					if (isPrivate) {
						handlePrivateRoutes(key);
					} else {
						if (getLocalStorageApi(LOCALSTORAGE.IS_LOGGED_IN) === 'true') {
							history.push(ROUTES.HOME_PAGE);
						}
						dispatch({
							type: UPDATE_USER_IS_FETCH,
							payload: {
								isUserFetching: false,
							},
						});
					}
				} else if (pathname === ROUTES.HOME_PAGE) {
					dispatch({
						type: UPDATE_USER_IS_FETCH,
						payload: {
							isUserFetching: false,
						},
					});
				} else {
					navigateToLoginIfNotLoggedIn(isLocalStorageLoggedIn);
				}
			} catch (error) {
				history.push(ROUTES.SOMETHING_WENT_WRONG);
			}
		})();
	}, [dispatch, isLoggedInState, currentRoute]);

	if (isUserFetchingError) {
		return <Redirect to={ROUTES.SOMETHING_WENT_WRONG} />;
	}

	if (isUserFetching) {
		return <Spinner tip="Loading..." />;
	}

	// eslint-disable-next-line react/jsx-no-useless-fragment
	return <>{children}</>;
}

interface PrivateRouteProps {
	children: ReactChild;
}

export default PrivateRoute;
