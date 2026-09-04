import { Typography } from 'antd';
import { useEffect, useState } from 'react';

function RefreshText({
	onLastRefreshHandler,
	refreshButtonHidden,
}: RefreshTextProps): JSX.Element {
	const [refreshText, setRefreshText] = useState<string>('');

	// this is to update the refresh text
	useEffect(() => {
		const interval = setInterval(() => {
			const text = onLastRefreshHandler();
			if (refreshText !== text) {
				setRefreshText(text);
			}
		}, 2000);
		return (): void => {
			clearInterval(interval);
		};
	}, [onLastRefreshHandler, refreshText]);

	return (
		<div style={{ visibility: refreshButtonHidden ? 'hidden' : 'visible' }}>
			<Typography className="dateTimeTypography">{refreshText}</Typography>
		</div>
	);
}

interface RefreshTextProps {
	onLastRefreshHandler: () => string;
	refreshButtonHidden: boolean;
}

export default RefreshText;
