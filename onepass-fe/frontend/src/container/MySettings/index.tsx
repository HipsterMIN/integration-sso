import './MySettings.styles.scss';

import { Button, Space } from 'antd';
import { Logout } from 'api/utils';
import { LogOut } from 'lucide-react';

import Password from './Password';
import Translation from './Translation';
import UserInfo from './UserInfo';

function MySettings(): JSX.Element {
	// eslint-disable-next-line @typescript-eslint/explicit-function-return-type
	return (
		<Space
			direction="vertical"
			style={{
				margin: '16px 0',
				gap: '40px',
			}}
		>
			<Translation />

			<div className="user-info-container">
				<UserInfo />
			</div>

			<div className="password-reset-container">
				<Password />
			</div>

			<Button
				className="flexBtn"
				onClick={(): void => Logout()}
				type="primary"
				data-testid="logout-button"
			>
				<LogOut size={12} /> Logout
			</Button>
		</Space>
	);
}

export default MySettings;
