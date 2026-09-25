import cx from 'classnames';
import type { MypageMemberType, MypageSection } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import { useState } from 'react';

interface MypageSideNavProps {
	section: MypageSection;
	memberType: MypageMemberType;
}

const SECTION_LABEL: Record<MypageSection, string> = {
	information: '나의 정보',
	affiliation: '유관기관 서비스 관리',
	password: '비밀번호 수정',
	withdraw: '통합회원 탈퇴',
};

const SECTION_ICON: Record<MypageSection, string> = {
	information: 'article',
	affiliation: 'settings',
	password: 'key',
	withdraw: 'person_check',
};

function MypageSideNav({
	section,
	memberType,
}: MypageSideNavProps): JSX.Element {
	const [isOpen, setIsOpen] = useState(false);
	const isBusiness = memberType === 'business';
	const infoRoute = isBusiness
		? ROUTES.MYPAGE_BUSINESS_INFORMATION
		: ROUTES.MYPAGE_MEMBER_INFORMATION;
	const affiliationRoute = isBusiness
		? ROUTES.MYPAGE_BUSINESS_AFFILIATION
		: ROUTES.MYPAGE_MEMBER_AFFILIATION;
	const passwordRoute = ROUTES.MYPAGE_MEMBER_PASSWORD;
	const withdrawRoute = isBusiness
		? ROUTES.MYPAGE_BUSINESS_WITHDRAW
		: ROUTES.MYPAGE_MEMBER_WITHDRAW;

	const navigate = (to: string) => (e: React.MouseEvent): void => {
		e.preventDefault();
		setIsOpen(false);
		history.push(to);
	};

	return (
		<div className="sub-nav">
			<div className="nav-title">
				<h2><p>마이페이지</p></h2>
				<figure className="img">
					<img src={IMAGES.RENEWAL_MYPAGE_NAV_BG} alt="" aria-hidden="true" />
				</figure>
			</div>
			<div className="list-wrap">
				<button
					type="button"
					className={cx('now-page', { open: isOpen })}
					onClick={(): void => setIsOpen((prev) => !prev)}
					aria-expanded={isOpen}
				>
					<i className={cx('icon', SECTION_ICON[section])} aria-hidden="true" />
					<span>{SECTION_LABEL[section]}</span>
					<i className="icon arrow-top" aria-hidden="true" />
				</button>
				<ul className="list" role="navigation" aria-label="마이페이지 메뉴">
					<li className={section === 'information' ? 'active' : ''}>
						<a href={infoRoute} onClick={navigate(infoRoute)}>
							<i className="icon article" aria-hidden="true" />
							<span>나의 정보</span>
						</a>
					</li>
					<li className={section === 'affiliation' ? 'active' : ''}>
						<a href={affiliationRoute} onClick={navigate(affiliationRoute)}>
							<i className="icon settings" aria-hidden="true" />
							<span>유관기관 서비스 관리</span>
						</a>
					</li>
					{!isBusiness && (
						<li className={section === 'password' ? 'active' : ''}>
							<a href={passwordRoute} onClick={navigate(passwordRoute)}>
								<i className="icon key" aria-hidden="true" />
								<span>비밀번호 수정</span>
							</a>
						</li>
					)}
					<li className={section === 'withdraw' ? 'active' : ''}>
						<a href={withdrawRoute} onClick={navigate(withdrawRoute)}>
							<i className="icon person_check" aria-hidden="true" />
							<span>통합회원 탈퇴</span>
						</a>
					</li>
				</ul>
			</div>
		</div>
	);
}

export default MypageSideNav;
