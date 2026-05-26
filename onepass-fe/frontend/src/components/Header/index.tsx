import IMAGES from 'constants/images';
import { loadRedirectUri } from 'pages/Mypage/pages/useInfoStore';
import { useLocation } from 'react-router-dom';

function Header(): JSX.Element {
	const { pathname, search } = useLocation();
	const isMypage =
		pathname.startsWith('/mypage-member') ||
		pathname.startsWith('/mypage-business');

	const handleHomeClick = (): void => {
		const redirectUri =
			new URLSearchParams(search).get('redirect_uri') || loadRedirectUri();
		if (!redirectUri) return;
		try {
			const { origin } = new URL(redirectUri);
			window.location.href = `${origin}/`;
		} catch {
			// invalid URL — ignore
		}
	};

	return (
		<header role="banner">
			<a href="#main-content" className="sr-only" id="skip">
				본문 바로가기
			</a>
			<div className="inner">
				<h1>
					<a
						href="#"
						aria-label="중기원패스"
						onClick={(e): void => e.preventDefault()}
					>
						<img src={IMAGES.RENEWAL_LOGO} alt="중기원패스 통합로그인" />
					</a>
				</h1>
				{isMypage && (
					<div className="right-box">
						<button
							type="button"
							className="homepage-btn"
							aria-label="홈페이지 돌아가기"
							onClick={handleHomeClick}
						>
							<i
								className="icon small ico-frame-reload"
								aria-hidden="true"
							/>
							<span>홈페이지돌아가기</span>
						</button>
					</div>
				)}
			</div>
		</header>
	);
}

export default Header;
