import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';

function Footer(): JSX.Element {
	return (
		<footer role="contentinfo">
			<div className="inner">
				<div className="top">
					<ul className="list" aria-label="사이트 정책">
						<li>
							<a
								href={ROUTES.PRIVACY}
								className="btn text"
								onClick={(e): void => {
									e.preventDefault();
									history.push(ROUTES.PRIVACY);
								}}
							>
								<span>개인정보처리방침</span>
							</a>
						</li>
						<li>
							<a
								href={ROUTES.USE_TERMS}
								className="btn text"
								onClick={(e): void => {
									e.preventDefault();
									history.push(ROUTES.USE_TERMS);
								}}
							>
								<span>이용약관</span>
							</a>
						</li>
					</ul>
				</div>
				<div className="bot">
					<address>
						<ul>
							<li>
								<div className="box">
									<strong className="title">중소기업통합플랫폼 시스템 장애 문의</strong>
									<p className="detail">
										<a href="tel:(044) 300-0990">
											<span>(044) 300-0990</span>
										</a>
										,{' '}
										<a href="tel:(044) 300-0991">
											<span>(044) 300-0991</span>
										</a>
									</p>
								</div>
								<div className="box">
									<strong className="title">메일문의</strong>
									<p className="detail">
										<a href="mailto:smeshelp@tipa.or.kr">
											<span>smeshelp@tipa.or.kr</span>
										</a>
									</p>
								</div>
							</li>
							<li>
								<div className="box">
									<strong className="title">중소벤처기업부</strong>
									<p className="detail">
										30121, 세종특별자치시 가름로 180(어진동), 세종파이낸스센터3차 4층~6층
									</p>
								</div>
								<div className="box">
									<strong className="title">대표전화</strong>
									<p className="detail">
										국번없이{' '}
										<a href="tel:1357">
											<span>1357</span>
										</a>
									</p>
								</div>
							</li>
							<li>
								<div className="box">
									<strong className="title">[운영기관] 중소기업기술정보진흥원</strong>
									<p className="detail">
										(30141, 세종특별자치시 집현중앙로 79, 중소기업기술정보진흥원(TIPA)
									</p>
								</div>
							</li>
						</ul>
						<div className="copy-box">
							<p>copyright ⓒ 중소벤처기업부. All rights reserved.</p>
						</div>
					</address>
					<figure className="logo">
						<img src={IMAGES.RENEWAL_FOOTER_LOGO} alt="중소벤처24" />
					</figure>
				</div>
			</div>
		</footer>
	);
}

export default Footer;
