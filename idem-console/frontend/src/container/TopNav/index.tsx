import './TopNav.style.scss';

import { Row, Space } from 'antd';

function TopNav(): JSX.Element {
	return (
		<Row className="top-navigation" justify="space-between">
			<Row justify="end">
				<Space align="center" size={16} direction="horizontal" />
			</Row>
		</Row>
	);
}

export default TopNav;
