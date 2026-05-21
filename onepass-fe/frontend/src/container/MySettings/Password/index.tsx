import '../MySettings.styles.scss';
import '../UserInfo/UserInfo.styles.scss';

import { Button, Card, Flex, Input, Space, Typography } from 'antd';
import changeMyPassword from 'api/user/changeMyPassword';
import { useNotifications } from 'hooks/useNotifications';
import { Save } from 'lucide-react';
import { isPasswordNotValidMessage, isPasswordValid } from 'pages/SignUp/utils';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSelector } from 'react-redux';
import { AppState } from 'store/reducers';
import AppReducer from 'types/reducer/app';


function PasswordContainer(): JSX.Element {
	const [currentPassword, setCurrentPassword] = useState<string>('');
	const [updatePassword, setUpdatePassword] = useState<string>('');
	const { t } = useTranslation(['routes', 'settings', 'common']);
	const { user } = useSelector<AppState, AppReducer>((state) => state.app);
	const [isLoading, setIsLoading] = useState<boolean>(false);
	const [isPasswordPolicyError, setIsPasswordPolicyError] = useState<boolean>(
		false,
	);

	const defaultPlaceHolder = '*************';

	const { notifications } = useNotifications();

	useEffect(() => {
		if (currentPassword && !isPasswordValid(currentPassword)) {
			setIsPasswordPolicyError(true);
		} else {
			setIsPasswordPolicyError(false);
		}
	}, [currentPassword]);

	if (!user) {
		return <div />;
	}

	const onChangePasswordClickHandler = async (): Promise<void> => {
		try {
			setIsLoading(true);

			if (!isPasswordValid(currentPassword)) {
				setIsPasswordPolicyError(true);
				setIsLoading(false);
				return;
			}

			const { statusCode, error } = await changeMyPassword({
				newPassword: updatePassword,
				oldPassword: currentPassword,
				userId: user.userId,
			});

			if (statusCode === 200) {
				notifications.success({
					message: t('success', {
						ns: 'common',
					}),
				});
			} else {
				notifications.error({
					message:
						error ||
						t('something_went_wrong', {
							ns: 'common',
						}),
				});
			}
			setIsLoading(false);
		} catch (error) {
			setIsLoading(false);

			notifications.error({
				message: t('something_went_wrong', {
					ns: 'common',
				}),
			});
		}
	};

	const isDisabled =
		isLoading ||
		currentPassword.length === 0 ||
		updatePassword.length === 0 ||
		isPasswordPolicyError ||
		currentPassword === updatePassword;

	return (
		<>
			<Typography.Title
				level={4}
				style={{ marginTop: 0, fontSize: '16px' }}
				data-testid="change-password-header"
			>
				{t('change_password', {
					ns: 'settings',
				})}
			</Typography.Title>
			<Card className="ucube-card-box" style={{ minHeight: 'auto' }}>
				<Space direction="vertical" size="middle">
					<Flex gap={16}>
						<Space>
							<Typography
								className="userInfo-label"
								data-testid="current-password-label"
							>
								{t('current_password', {
									ns: 'settings',
								})}
							</Typography>
							<Input.Password
								className="settingsInput"
								data-testid="current-password-textbox"
								disabled={isLoading}
								placeholder={defaultPlaceHolder}
								onChange={(event): void => {
									setCurrentPassword(event.target.value);
								}}
								value={currentPassword}
							/>
						</Space>
					</Flex>
					<Flex gap={16}>
						<Space>
							<Typography className="userInfo-label" data-testid="new-password-label">
								{t('new_password', {
									ns: 'settings',
								})}
							</Typography>
							<Input.Password
								className="settingsInput"
								data-testid="new-password-textbox"
								disabled={isLoading}
								placeholder={defaultPlaceHolder}
								onChange={(event): void => {
									const updatedValue = event.target.value;
									setUpdatePassword(updatedValue);
								}}
								value={updatePassword}
							/>
						</Space>
						<Button
							disabled={isDisabled}
							loading={isLoading}
							onClick={onChangePasswordClickHandler}
							type="primary"
							data-testid="update-password-button"
						>
							<Save
								size={12}
								style={{ marginRight: '8px' }}
								data-testid="update-password-icon"
							/>{' '}
							{t('change_password', {
								ns: 'settings',
							})}
						</Button>
					</Flex>
					{isPasswordPolicyError && (
						<Space>
							<Typography.Paragraph
								data-testid="validation-message"
								style={{
									color: '#D89614',
									marginTop: '0.50rem',
								}}
							>
								{isPasswordNotValidMessage}
							</Typography.Paragraph>
						</Space>
					)}
				</Space>
			</Card>
		</>
	);
}

export default PasswordContainer;
