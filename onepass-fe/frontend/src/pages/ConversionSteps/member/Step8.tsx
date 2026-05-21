import ROUTES from 'constants/routes';
import ConversionLayout from 'components/ConversionLayout';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useConversion } from 'providers/Conversion/ConversionContext';

interface Step8Props {
	memberType?: MemberType;
	currentStep?: number;
}

function ConversionStep8({ memberType = 'member', currentStep = 5 }: Step8Props): JSX.Element {
	const { data } = useConversion();
	const isBusiness = memberType === 'business';

	const handleLogin = (): void => {
		if (data.redirectUri) {
			window.location.href = data.redirectUri;
		} else {
			window.location.href = ROUTES.LOGIN;
		}
	};

	return (
		<ConversionLayout
			currentStep={currentStep}
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
				<h4 className="completed-tit">
					중기원패스 회원({isBusiness ? '기업' : '개인'}) 전환을 완료하였습니다.
				</h4>
				<p className="completed-txt">
					모든 중소벤처기업부의 유관기관 서비스를 <br />
					한곳에서 편리하게 이용해 보세요!
				</p>
			</div>
			<div className="btn-box" role="group" aria-label="페이지 이동">
				<button
					type="button"
					className="btn point"
					onClick={handleLogin}
				>
					<span>유관기관 서비스로 이동하기</span>
					<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
				</button>
			</div>
		</ConversionLayout>
	);
}

export default ConversionStep8;
