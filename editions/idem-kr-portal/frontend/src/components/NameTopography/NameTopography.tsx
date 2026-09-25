import { Typography } from 'antd';

interface NameTopographyProps {
	serviceName?: string;
}

function NameTopography({ serviceName }: NameTopographyProps): JSX.Element {
	const { Title } = Typography;
	return <Title level={2}>{serviceName}</Title>;
}

NameTopography.defaultProps = {
	serviceName: '',
};

export default NameTopography;
