import 'components/NotFound/NotFound.styles.scss';

import { Space, Typography } from 'antd';
import UnAuthorized from 'assets/UnAuthorized';
import ROUTES from 'constants/routes';
import { Link } from 'react-router-dom';

function UnAuthorizePage(): JSX.Element {
	return (
		<div className="notFoundContainer">
			<Space align="center" direction="vertical">
				<UnAuthorized />
				<Typography.Title level={3}>
					Oops.. you don&apos;t have permission to view this page
				</Typography.Title>
				<Link className="notFoundButton" to={ROUTES.HOME_PAGE} tabIndex={0}>
					Return To Services Page
				</Link>
			</Space>
		</div>
	);
}

export default UnAuthorizePage;
