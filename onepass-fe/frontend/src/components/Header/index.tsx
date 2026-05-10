import IMAGES from 'constants/images';

function Header(): JSX.Element {
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
			</div>
		</header>
	);
}

export default Header;
