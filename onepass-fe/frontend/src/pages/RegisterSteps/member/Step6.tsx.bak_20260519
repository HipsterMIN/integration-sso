import RegisterLayout from 'components/RegisterLayout';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import { useRegister } from 'providers/Register/RegisterContext';

interface Step6Props {
	memberType?: MemberType;
	currentStep?: number;
}

/**
 * Open Redirect 방어: returnUri가 허용된 도메인인지 검증한다.
 * OWASP A10: Unvalidated Redirects and Forwards 대응.
 * 허용 도메인: https://*.smes.go.kr
 */
function isSafeReturnUri(uri: string): boolean {
	try {
		const url = new URL(uri);
		return url.protocol === 'https:' && (
			url.hostname.endsWith('.smes.go.kr') ||
			url.hostname === 'smes.go.kr'
		);
	} catch {
		return false;
	}
}

function RegisterStep6({
	memberType = 'member',
	currentStep = 6,
}: Step6Props): JSX.Element {
	const { data } = useRegister();

	const handleLogin = (): void => {
		if (data.returnUri && isSafeReturnUri(data.returnUri)) {
			window.location.href = data.returnUri;
		} else {
			window.location.href = ROUTES.LOGIN;
		}
	};

	return (
		<RegisterLayout
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
				<h4 className="completed-tit">중기원패스 회원가입이 완료되었습니다</h4>
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
					<span>로그인 하기</span>
					<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
				</button>
			</div>
		</RegisterLayout>
	);
}

export default RegisterStep6;
