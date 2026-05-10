import 'components/NotFound/NotFound.styles.scss';

import { Button, Typography } from 'antd';
import SomethingWentWrongAsset from 'assets/SomethingWentWrong';
import ROUTES from 'constants/routes';
import history from 'lib/history';

function SomethingWentWrong(): JSX.Element {
	return (
		<div className="notFoundContainer">
			<SomethingWentWrongAsset />
			<Typography.Title level={3}>Oops! Something went wrong</Typography.Title>
			<Button
				type="primary"
				onClick={(): void => {
					history.push(ROUTES.HOME_PAGE);
				}}
			>
				Return to Services page
			</Button>
		</div>
	);
}

export default SomethingWentWrong;
