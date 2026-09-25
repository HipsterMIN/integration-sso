import { ReactNode } from 'react';

function WelcomeLeftContainer({
	children,
}: WelcomeLeftContainerProps): JSX.Element {
	return <div className="welcome-container">{children}</div>;
}

interface WelcomeLeftContainerProps {
	version?: string;
	children: ReactNode;
}

WelcomeLeftContainer.defaultProps = {
	version: '',
};

export default WelcomeLeftContainer;
