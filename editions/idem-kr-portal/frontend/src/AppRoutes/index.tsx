import { ConfigProvider, Empty, theme as antdTheme } from 'antd';
import { ThemeConfig } from 'antd/es/config-provider/context';
import Spinner from 'components/Spinner';
import ROUTES from 'constants/routes';
import AppLayout from 'container/AppLayout';
import { KeyboardHotkeysProvider } from 'hooks/hotkeys/useKeyboardHotkeys';
import { NotificationProvider } from 'hooks/useNotifications';
import history from 'lib/history';
import { AppProvider } from 'providers/App/App';
import { ConversionProvider } from 'providers/Conversion/ConversionContext';
import { RegisterProvider } from 'providers/Register/RegisterContext';
import { Suspense } from 'react';
import { Redirect, Route, Router, Switch } from 'react-router-dom';
import { CompatRouter } from 'react-router-dom-v5-compat';

import PrivateRoute from './Private';
import routes from './routes';

const themeConfig: ThemeConfig = {
	algorithm: antdTheme.defaultAlgorithm,
	token: {
		borderRadius: 2,
		borderRadiusLG: 2,
		borderRadiusSM: 2,
		borderRadiusXS: 2,
		fontFamily: "'Outfit', 'Pretendard'",
		fontSize: 14,
		colorPrimary: '#23c4f8',
		colorBgBase: '#fff',
		colorBgContainer: '#fff',
		colorText: '#3a3a3a',
		colorLink: '#23c4f8',
		colorPrimaryText: '#23c4f8',
	},
	components: {
		Dropdown: {
			colorBgElevated: '#fff',
			controlItemBgHover: '#fff',
			colorText: '#121317',
			fontSize: 12,
		},
		Select: {
			colorBgElevated: '#fff',
			controlItemBgHover: '#fff',
			boxShadowSecondary: '#fff',
			colorText: '#121317',
			fontSize: 12,
		},
		Button: {
			paddingInline: 12,
			fontSize: 12,
		},
		Input: {
			colorBorder: '#E9E9E9',
		},
	},
	hashed: false,
};

function App(): JSX.Element {
	// eslint-disable-next-line @typescript-eslint/explicit-function-return-type
	const customizeRenderEmpty = () => <Empty image="/Icons/nodata.svg" />;

	return (
		<AppProvider>
			<ConfigProvider theme={themeConfig} renderEmpty={customizeRenderEmpty}>
				<Router history={history}>
					<CompatRouter>
						<NotificationProvider>
							<PrivateRoute>
								<KeyboardHotkeysProvider>
									<Suspense fallback={<Spinner size="large" tip="Loading..." />}>
										<ConversionProvider>
											<RegisterProvider>
												<Switch>
													{routes.map(({ path, component: Component, exact, layout }) => (
														<Route
															key={`${path}`}
															exact={exact}
															path={path}
															render={(props): JSX.Element => (
																<AppLayout layout={layout}>
																	{Component && <Component {...props} />}
																</AppLayout>
															)}
														/>
													))}

													<Redirect exact from="/" to={ROUTES.LOGIN} />
													<Route
														path="*"
														render={(): JSX.Element => {
															// eslint-disable-next-line no-alert
															alert(
																'유효하지 않은 URL 입니다. 중소벤처24 메인화면을 통해 이용해주시기 바랍니다.',
															);
															history.replace(ROUTES.LOGIN);
															return <></>;
														}}
													/>
												</Switch>
											</RegisterProvider>
										</ConversionProvider>
									</Suspense>
								</KeyboardHotkeysProvider>
							</PrivateRoute>
						</NotificationProvider>
					</CompatRouter>
				</Router>
			</ConfigProvider>
		</AppProvider>
	);
}

export default App;
