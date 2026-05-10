import RegisterLayout from 'components/RegisterLayout';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';

interface Step6Props {
	memberType?: MemberType;
	currentStep?: number;
}

function RegisterStep6({
	memberType = 'member',
	currentStep = 6,
}: Step6Props): JSX.Element {
	return (
		<RegisterLayout
			currentStep={currentStep}
			nextRoute={ROUTES.LOGIN}
			nextLabel="로그인 하기"
			memberType={memberType}
			noWrap
		>
			<div className="white-wrap completed">
				<figure className="img">
					<img
						src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG}
						alt=""
						aria-hidden="true"
					/>
				</figure>
				<h4 className="completed-tit">중기원패스 회원가입이 완료되었습니다</h4>
				<p className="completed-txt">
					모든 중소벤처기업부의 유관기관 서비스를 <br />
					한곳에서 편리하게 이용해 보세요!
				</p>
			</div>
		</RegisterLayout>
	);
}

export default RegisterStep6;
