import { Button, Card, Form, Input, Typography } from 'antd';
import editOrg from 'api/user/editOrg';
import getInviteDetails from 'api/user/getInviteDetails';
import loginApi from 'api/user/login';
import signUpApi from 'api/user/signup';
import afterLogin from 'AppRoutes/utils';
import WelcomeLeftContainer from 'components/WelcomeLeftContainer';
import ROUTES from 'constants/routes';
import { useNotifications } from 'hooks/useNotifications';
import history from 'lib/history';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery } from 'react-query';
import { useLocation } from 'react-router-dom';
import { SuccessResponse } from 'types/api';
import { PayloadProps } from 'types/api/user/getUser';
import { PayloadProps as LoginPrecheckPayloadProps } from 'types/api/user/loginPrecheck';
import { isPasswordNotValidMessage, isPasswordValid } from './utils';
import './SignUp.styles.scss';

const { Title } = Typography;

type FormValues = {
	firstName: string;
	email: string;
	organizationName: string;
	password: string;
	confirmPassword: string;
	hasOptedUpdates: boolean;
	isAnonymous: boolean;
};

function SignUp({ version }: SignUpProps): JSX.Element {
	const { t } = useTranslation(['signup']);
	const [loading, setLoading] = useState(false);

	const [precheck, setPrecheck] = useState<LoginPrecheckPayloadProps>({
		sso: false,
		isUser: false,
	});

	const [confirmPasswordError, setConfirmPasswordError] = useState<boolean>(
		false,
	);
	const [isPasswordPolicyError, setIsPasswordPolicyError] = useState<boolean>(
		false,
	);
	const { search } = useLocation();
	const params = new URLSearchParams(search);
	const token = params.get('token');
	const [isDetailsDisable, setIsDetailsDisable] = useState<boolean>(false);

	const getInviteDetailsResponse = useQuery({
		queryFn: () =>
			getInviteDetails({
				inviteId: token || '',
			}),
		queryKey: ['getInviteDetails', token],
		enabled: token !== null,
	});

	const { notifications } = useNotifications();
	const [form] = Form.useForm<FormValues>();

	useEffect(() => {
		if (
			getInviteDetailsResponse.status === 'success' &&
			getInviteDetailsResponse.data.payload
		) {
			const responseDetails = getInviteDetailsResponse.data.payload;
			if (responseDetails.precheck) setPrecheck(responseDetails.precheck);
			form.setFieldValue('firstName', responseDetails.name);
			form.setFieldValue('email', responseDetails.email);
			form.setFieldValue('organizationName', responseDetails.organization);
			setIsDetailsDisable(true);

		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [
		getInviteDetailsResponse.data?.payload,
		form,
		getInviteDetailsResponse.status,
	]);

	useEffect(() => {
		if (
			getInviteDetailsResponse.status === 'success' &&
			getInviteDetailsResponse.data?.error
		) {
			const { error } = getInviteDetailsResponse.data;
			notifications.error({
				message: error,
			});
		}
	}, [
		getInviteDetailsResponse.data,
		getInviteDetailsResponse.status,
		notifications,
	]);

	const isPreferenceVisible = token === null;

	const commonHandler = async (
		values: FormValues,
		callback: (
			e: SuccessResponse<PayloadProps>,
			values: FormValues,
		) => Promise<void> | VoidFunction,
	): Promise<void> => {
		try {
			const { organizationName, password, firstName, email } = values;
			const response = await signUpApi({
				email,
				name: firstName,
				orgName: organizationName,
				password,
				token: params.get('token') || undefined,
			});

			if (response.statusCode === 200) {
				const loginResponse = await loginApi({
					email,
					password,
				});

				if (loginResponse.statusCode === 200) {
					const { payload } = loginResponse;
					const userResponse = await afterLogin(
						payload.userId,
						payload.accessJwt,
						payload.refreshJwt,
					);
					if (userResponse) {
						callback(userResponse, values);
					}
				} else {
					notifications.error({
						message: loginResponse.error || t('unexpected_error'),
					});
				}
			} else {
				notifications.error({
					message: response.error || t('unexpected_error'),
				});
			}
		} catch (error) {
			notifications.error({
				message: t('unexpected_error'),
			});
		}
	};

	const onAdminAfterLogin = async (
		userResponse: SuccessResponse<PayloadProps>,
		values: FormValues,
	): Promise<void> => {
		const editResponse = await editOrg({
			isAnonymous: values.isAnonymous,
			name: values.organizationName,
			hasOptedUpdates: values.hasOptedUpdates,
			orgId: userResponse.payload.orgId,
		});
		if (editResponse.statusCode === 200) {
			history.push(ROUTES.HOME_PAGE);
		} else {
			notifications.error({
				message: editResponse.error || t('unexpected_error'),
			});
		}
	};
	const handleSubmitSSO = async (): Promise<void> => {
		if (!params.get('token')) {
			notifications.error({
				message: t('token_required'),
			});
			return;
		}
		setLoading(true);

		try {
			const values = form.getFieldsValue();
			const response = await signUpApi({
				email: values.email,
				name: values.firstName,
				orgName: values.organizationName,
				password: values.password,
				token: params.get('token') || undefined,
				sourceUrl: encodeURIComponent(window.location.href),
			});

			if (response.statusCode === 200) {
				if (response.payload?.sso) {
					if (response.payload?.ssoUrl) {
						window.location.href = response.payload?.ssoUrl;
					} else {
						notifications.error({
							message: t('failed_to_initiate_login'),
						});
						// take user to login page as there is nothing to do here
						history.push(ROUTES.LOGIN);
					}
				}
			} else {
				notifications.error({
					message: response.error || t('unexpected_error'),
				});
			}
		} catch (error) {
			notifications.error({
				message: t('unexpected_error'),
			});
		}

		setLoading(false);
	};

	const handleSubmit = (): void => {
		(async (): Promise<void> => {
			try {
				const values = form.getFieldsValue();
				setLoading(true);

				if (!isPasswordValid(values.password)) {
					setIsPasswordPolicyError(true);
					setLoading(false);
					return;
				}

				if (isPreferenceVisible) {
					await commonHandler(values, onAdminAfterLogin);
				} else {
					await commonHandler(
						values,
						async (): Promise<void> => {
							history.push(ROUTES.HOME_PAGE);
						},
					);
				}

				setLoading(false);
			} catch (error) {
				notifications.error({
					message: t('unexpected_error'),
				});
				setLoading(false);
			}
		})();
	};

	const getIsNameVisible = (): boolean =>
		!(form.getFieldValue('firstName') === 0 && !isPreferenceVisible);

	const isNameVisible = getIsNameVisible();

	const handleValuesChange: (changedValues: Partial<FormValues>) => void = (
		changedValues,
	) => {
		if ('password' in changedValues || 'confirmPassword' in changedValues) {
			const { password, confirmPassword } = form.getFieldsValue();

			const isInvalidPassword = !isPasswordValid(password) && password.length > 0;
			setIsPasswordPolicyError(isInvalidPassword);

			const isSamePassword = password === confirmPassword;
			setConfirmPasswordError(!isSamePassword);
		}
	};

	const isValidForm: () => boolean = () => {
		const values = form.getFieldsValue();
		return (
			loading ||
			!values.email ||
			!values.organizationName ||
			(!precheck.sso && (!values.password || !values.confirmPassword)) ||
			(!isDetailsDisable && !values.firstName) ||
			confirmPasswordError ||
			isPasswordPolicyError
		);
	};

	return (
		<WelcomeLeftContainer version={version}>
			<Card className="signupFormWrapper">
				<Form
					className="signupFormContainer"
					onFinish={!precheck.sso ? handleSubmit : handleSubmitSSO}
					onValuesChange={handleValuesChange}
					initialValues={{ hasOptedUpdates: false, isAnonymous: false }}
					form={form}
				>
					<Title level={4}>Create your account</Title>
					<div>
						<label className="signupLabel" htmlFor="signupEmail">{t('label_email')}</label>
						<Form.Item noStyle name="email">
							<Input
								placeholder={t('placeholder_email')}
								type="email"
								autoFocus
								required
								id="signupEmail"
								disabled={isDetailsDisable}
							/>
						</Form.Item>
					</div>

					{isNameVisible && (
						<div>
							<label className="signupLabel" htmlFor="signupFirstName">{t('label_firstname')}</label>{' '}
							<Form.Item noStyle name="firstName">
								<Input
									placeholder={t('placeholder_firstname')}
									required
									id="signupFirstName"
									disabled={isDetailsDisable && form.getFieldValue('firstName')}
								/>
							</Form.Item>
						</div>
					)}

					<div>
						<label className="signupLabel" htmlFor="organizationName">{t('label_orgname')}</label>{' '}
						<Form.Item noStyle name="organizationName">
							<Input
								placeholder={t('placeholder_orgname')}
								id="organizationName"
								disabled={isDetailsDisable}
							/>
						</Form.Item>
					</div>
					{!precheck.sso && (
						<div>
							<label className="signupLabel" htmlFor="Password">{t('label_password')}</label>{' '}
							<Form.Item noStyle name="password">
								<Input.Password required id="currentPassword" />
							</Form.Item>
						</div>
					)}
					{!precheck.sso && (
						<div>
							<label className="signupLabel" htmlFor="ConfirmPassword">{t('label_confirm_password')}</label>{' '}
							<Form.Item noStyle name="confirmPassword">
								<Input.Password required id="confirmPassword" />
							</Form.Item>
							{confirmPasswordError && (
								<Typography.Paragraph
									italic
									id="password-confirm-error"
									style={{
										color: '#D89614',
										marginTop: '0.50rem',
									}}
								>
									{t('failed_confirm_password')}
								</Typography.Paragraph>
							)}
							{isPasswordPolicyError && (
								<Typography.Paragraph
									italic
									style={{
										color: '#D89614',
										marginTop: '0.50rem',
									}}
								>
									{isPasswordNotValidMessage}
								</Typography.Paragraph>
							)}
						</div>
					)}
					{isPreferenceVisible && (
						<Typography.Paragraph
							italic
							style={{
								color: '#D89614',
								marginTop: '0.50rem',
							}}
						>
							This will create an admin account. If you are not an admin, please ask
							your admin for an invite link
						</Typography.Paragraph>
					)}

					<div className="signupButtonContainer">
						<Button
							type="primary"
							htmlType="submit"
							data-attr="signup"
							loading={loading}
							disabled={isValidForm()}
						>
							{t('button_get_started')}
						</Button>
					</div>
				</Form>
			</Card>
		</WelcomeLeftContainer>
	);
}

interface SignUpProps {
	version: string;
}

export default SignUp;
