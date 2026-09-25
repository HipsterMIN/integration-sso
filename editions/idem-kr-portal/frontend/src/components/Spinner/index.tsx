import { CSSProperties } from 'react';
import cx from 'classnames';

import './Spinner.styles.scss';

type SpinnerSize = 'small' | 'default' | 'large';

interface SpinnerProps {
	size?: SpinnerSize;
	tip?: string;
	height?: CSSProperties['height'];
	style?: CSSProperties;
}

function Spinner({
	size = 'default',
	tip,
	height,
	style,
}: SpinnerProps): JSX.Element {
	return (
		<div
			className="spinnerContainer"
			style={{ height, ...style }}
			role="status"
			aria-live="polite"
		>
			<span
				className={cx('spinner', `spinner--${size}`)}
				aria-hidden="true"
			/>
			{tip ? <span className="spinner-tip">{tip}</span> : <span className="hidden">로딩 중</span>}
		</div>
	);
}

Spinner.defaultProps = {
	size: 'default',
	tip: undefined,
	height: undefined,
	style: {},
};

export default Spinner;
