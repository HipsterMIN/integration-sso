import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import { loadRedirectUri } from 'pages/Mypage/pages/useInfoStore';
import { Redirect, useLocation } from 'react-router-dom';

import { getMypageRoute } from './routes';

// PUB260513 mypage_withdraw_member_step3.html — 탈퇴 완료 화면
// (안내 사항 + .white-wrap.completed + 홈화면으로 버튼)
function WithdrawComplete(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const { search } = useLocation();

	// step2 인증 성공을 거치지 않고 직접 진입 시 차단
	if (sessionStorage.getItem('mypage_withdraw_step2_passed') !== '1') {
		return <Redirect to={getMypageRoute(memberType, 'WITHDRAW')} />;
	}

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
		<MypageContent>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>중기원패스를 이용해 주셔서 감사합니다.</p>
						</li>
						<li>
							<p>
								탈퇴 이후에 재가입은 가능하지만 기존에 사용하였던 ID는 더이상
								사용할 수 없습니다.
							</p>
						</li>
					</ul>
					<figure className="img">
						<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
			</div>
			<div className="form-container" aria-label="회원 탈퇴 완료">
				<div className="white-wrap completed">
					<figure className="img">
						<img
							src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG_2}
							alt=""
							aria-hidden="true"
						/>
					</figure>
					<h4 className="completed-tit">
						중기원패스 {isBusiness ? '기업회원' : '통합회원'} 탈퇴가
						완료되었습니다
					</h4>
					<p className="completed-txt gray">
						회원님의 계정은 정상적으로 탈퇴 처리되었습니다.
						<br />
						탈퇴 후 회원 정보 및 이용 기록은 복구할 수 없습니다.
						<br />
						<br />
						그동안 서비스를 이용해주셔서 감사합니다.
					</p>
				</div>
				<div className="btn-box" role="group" aria-label="페이지 이동">
					<button
						type="button"
						className="btn point"
						onClick={handleHomeClick}
					>
						<span>홈화면으로</span>
						<i className="icon ico-exit-to-app large" aria-hidden="true" />
					</button>
				</div>
			</div>
		</MypageContent>
	);
}

export default WithdrawComplete;
