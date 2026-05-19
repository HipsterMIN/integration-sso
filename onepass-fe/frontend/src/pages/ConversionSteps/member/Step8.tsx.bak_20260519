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
 * [버그 수정 2026-05-16] B-1: *.smes.go.kr 하드코딩 제거
 * - 이전: *.smes.go.kr 서브도메인만 허용 → 68개 기관 중 smes.go.kr 외 도메인 전부 차단됨
 * - 수정: REACT_APP_REDIRECT_ALLOWED_ORIGINS 환경변수 기반 허용 도메인 목록으로 변경
 *
 * 허용 조건:
 *   1. https 프로토콜 필수 (http, javascript: 등 차단)
 *   2. REACT_APP_REDIRECT_ALLOWED_ORIGINS에 등록된 origin과 일치
 *   3. 와일드카드 도메인 지원: *.domain.com
 *
 * 환경변수 설정 예시 (.env.production):
 *   REACT_APP_REDIRECT_ALLOWED_ORIGINS=https://www.bizinfo.go.kr,https://www.sbiz.or.kr,...
 *
 * [중장기] GUIDE-002 참조: signed_request(JWT) 방식 도입 후
 *   BE ConversionSession에서 검증된 redirectUri를 직접 조회하는 방식으로 전환 예정.
 */
function isSafeRedirectUri(uri: string): boolean {
	try {
		const url = new URL(uri);

		// 1. https 프로토콜 필수
		if (url.protocol !== 'https:') return false;

		// 2. 환경변수에서 허용 origin 목록 파싱
		const rawOrigins = process.env.REACT_APP_REDIRECT_ALLOWED_ORIGINS ?? '';
		const allowedOrigins = rawOrigins
			.split(',')
			.map((o) => o.trim())
			.filter(Boolean);

		// 3. 환경변수 미설정 경고 — 운영 배포 시 반드시 설정 필요
		if (allowedOrigins.length === 0) {
			// eslint-disable-next-line no-console
			console.error(
				'[Security] REACT_APP_REDIRECT_ALLOWED_ORIGINS 환경변수가 설정되지 않았습니다. ' +
				'모든 외부 redirect_uri가 차단됩니다. 운영팀에 문의하세요.',
			);
			return false;
		}

		// 4. 각 허용 origin과 매칭
		return allowedOrigins.some((allowed) => {
			try {
				// 와일드카드 패턴: *.domain.com
				if (allowed.startsWith('*.')) {
					const suffix = allowed.slice(1); // .domain.com
					return (
						url.hostname.endsWith(suffix) ||
						url.hostname === suffix.slice(1) // domain.com 자체도 허용
					);
				}
				// 정확한 origin 일치 (scheme + hostname + port)
				const allowedUrl = new URL(allowed);
				return url.origin === allowedUrl.origin;
			} catch {
				return false;
			}
		});
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
