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
		// SP 에서 전달된 redirect_uri 를 그대로 이동
		window.location.href = redirectUri;
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
						aria-label="중기 통합회원"
						onClick={(e): void => e.preventDefault()}
					>
						<img src={IMAGES.RENEWAL_LOGO} alt="중기 통합회원" />
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
