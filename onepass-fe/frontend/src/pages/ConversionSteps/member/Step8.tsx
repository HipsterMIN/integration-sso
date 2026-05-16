import ROUTES from 'constants/routes';
import ConversionLayout from 'components/ConversionLayout';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useConversion } from 'providers/Conversion/ConversionContext';

interface Step8Props {
	memberType?: MemberType;
	currentStep?: number;
}

/**
 * Open Redirect 방어: redirect_uri가 허용된 도메인인지 검증한다.
 * OWASP A10: Unvalidated Redirects and Forwards 대응.
 *
 * 허용 도메인: *.smes.go.kr (중소벤처기업부 유관 서비스)
 * 미래 확장: 환경변수 REDIRECT_ALLOWED_ORIGINS로 설정 가능하도록 구성
 */
function isSafeRedirectUri(uri: string): boolean {
	try {
		const url = new URL(uri);
		// 허용 조건: https 프로토콜 + *.smes.go.kr 도메인
		return url.protocol === 'https:' && (
			url.hostname.endsWith('.smes.go.kr') ||
			url.hostname === 'smes.go.kr'
		);
	} catch {
		// URL 파싱 실패 → 안전하지 않음
		return false;
	}
}

function ConversionStep8({ memberType = 'member', currentStep = 6 }: Step8Props): JSX.Element {
	const { data } = useConversion();
	const isBusiness = memberType === 'business';

	const handleLogin = (): void => {
		if (data.redirectUri && isSafeRedirectUri(data.redirectUri)) {
			window.location.href = data.redirectUri;
		} else {
			// redirectUri가 없거나 허용되지 않은 도메인 → 기본 로그인 페이지
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
