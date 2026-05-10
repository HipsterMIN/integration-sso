import MypageContent from 'components/MypageContent';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import { useMypageType } from 'components/MypageLayout';

// PUB260507 conversion_business_step6.html 패턴 — write_completed_img + completed-tit/txt + 단일 point 버튼
function WithdrawComplete(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';

	return (
		<MypageContent>
			<form className="form-container" aria-label="회원 탈퇴 완료">
				<div className="white-wrap completed">
					<figure className="img">
						<img
							src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG}
							alt=""
							aria-hidden="true"
						/>
					</figure>
					<h4 className="completed-tit">
						{isBusiness ? '기업회원 탈퇴' : '통합회원 탈퇴'}가 완료되었습니다
					</h4>
					<p className="completed-txt">
						그동안 중기원패스를 이용해 주셔서 감사합니다.
						<br />
						탈퇴 이후 회원 정보 및 이용 기록은 복구할 수 없습니다.
					</p>
				</div>
				<div className="btn-box" role="group" aria-label="페이지 이동">
					<button
						type="button"
						className="btn point"
						onClick={(): void => history.push(ROUTES.HOME_PAGE)}
					>
						<span>메인 페이지로 이동</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
				</div>
			</form>
		</MypageContent>
	);
}

export default WithdrawComplete;
