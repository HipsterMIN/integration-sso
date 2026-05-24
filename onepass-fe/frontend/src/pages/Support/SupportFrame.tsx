import ROUTES from 'constants/routes';
import history from 'lib/history';
import { ReactNode } from 'react';

import './SupportPages.styles.scss';

interface SupportFrameProps {
	title: string;
	description: string;
	children: ReactNode;
}

function SupportFrame({ title, description, children }: SupportFrameProps): JSX.Element {
	return (
		<div className="container support-main">
			<div className="inner">
				<div className="support-page-title-wrap">
					<div className="page-title-text-box">
						<h2 className="page-title">{title}</h2>
						<p className="page-text">{description}</p>
					</div>
					<div className="support-nav">
						<button
							type="button"
							className="btn text"
							onClick={(): void => history.push(ROUTES.SUPPORT_MAIN)}
						>
							<span>메인</span>
						</button>
						<button
							type="button"
							className="btn text"
							onClick={(): void => history.push(ROUTES.SUPPORT_QNA)}
						>
							<span>Q&A</span>
						</button>
						<button
							type="button"
							className="btn text"
							onClick={(): void => history.push(ROUTES.SUPPORT_FAQ)}
						>
							<span>FAQ</span>
						</button>
						<button
							type="button"
							className="btn text"
							onClick={(): void => history.push(ROUTES.SUPPORT_ADMIN)}
						>
							<span>관리자 콘솔</span>
						</button>
					</div>
				</div>

				{children}
			</div>
		</div>
	);
}

export default SupportFrame;
