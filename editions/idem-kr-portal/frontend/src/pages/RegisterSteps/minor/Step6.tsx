/**
 * 만14세 미만 회원가입 Step6 — 가입 완료
 *
 * 법정대리인 동의를 통한 미성년자 회원가입 완료 화면.
 * 가입자(아동)와 법정대리인 정보를 함께 표시하여 완료를 확인시킨다.
 */
import RegisterLayout from 'components/RegisterLayout';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import { useRegister } from 'providers/Register/RegisterContext';

/**
 * Open Redirect 방어 (OWASP A10)
 * 허용 도메인: https://*.smes.go.kr
 */
function isSafeReturnUri(uri: string): boolean {
	try {
		const url = new URL(uri);
		return (
			url.protocol === 'https:' &&
			(url.hostname.endsWith('.smes.go.kr') || url.hostname === 'smes.go.kr')
		);
	} catch {
		return false;
	}
}

function RegisterMinorStep6(): JSX.Element {
	const { data } = useRegister();

	const handleLogin = (): void => {
		if (data.returnUri && isSafeReturnUri(data.returnUri)) {
			window.location.href = data.returnUri;
		} else {
			window.location.href = ROUTES.LOGIN;
		}
	};

	return (
		<RegisterLayout currentStep={6} memberType="member" noWrap>
			<div className="white-wrap completed">
				<figure className="img">
					<img src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG} alt="" aria-hidden="true" />
				</figure>
				<h4 className="completed-tit">중기원패스 회원가입이 완료되었습니다</h4>
				<p className="completed-txt">
					모든 중소벤처기업부의 유관기관 서비스를 <br />
					한곳에서 편리하게 이용해 보세요!
				</p>
			</div>

			{/* 법정대리인 동의 완료 확인 안내 */}
			<div
				className="white-wrap"
				style={{ marginTop: '16px' }}
				role="note"
				aria-label="법정대리인 동의 완료 확인"
			>
				<h3 className="h3-title">법정대리인 동의 완료</h3>
				<ul className="text-list-wrap check">
					<li>
						<p>
							<strong>{data.guardianName}</strong> 법정대리인의 동의 하에 회원가입이
							완료되었습니다.
						</p>
					</li>
					<li>
						<p>
							정보통신망법 제31조에 따른 법정대리인 동의 절차가 정상적으로 이행되었습니다.
						</p>
					</li>
					<li>
						<p>
							개인정보 처리 및 법정대리인 동의 내역은 마이페이지에서 확인하실 수 있습니다.
						</p>
					</li>
				</ul>
			</div>

			<div className="btn-box" role="group" aria-label="페이지 이동">
				<button type="button" className="btn point" onClick={handleLogin}>
					<span>로그인 하기</span>
					<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
				</button>
			</div>
		</RegisterLayout>
	);
}

export default RegisterMinorStep6;
