/**
 * 만14세 미만 회원가입 Step1 — 법정대리인 동의 안내
 *
 * 정보통신망법 제31조 법적 의무 이행:
 *   만14세 미만 아동의 개인정보를 수집·이용하려면 법정대리인(친권자 또는 후견인)의
 *   동의를 받아야 합니다.
 *
 * 이 페이지에서 하는 일:
 *   1. 법정대리인 동의가 필요한 이유를 안내
 *   2. 플로우 전체 단계 미리보기 제공
 *   3. "시작하기" → Step2(약관 동의)로 이동
 */
import RegisterLayout from 'components/RegisterLayout';
import { getMinorRegisterRoute, MINOR_TOTAL_STEPS } from './routes';

function RegisterMinorStep1(): JSX.Element {
	return (
		<RegisterLayout
			currentStep={1}
			nextRoute={getMinorRegisterRoute(2)}
			memberType="member"
			title="법정대리인 동의 안내"
			nextLabel="시작하기"
		>
			{/* ① 법적 근거 및 안내 */}
			<div className="text-info-wrap point">
				<ul className="text-list-wrap check" aria-label="법정대리인 동의 안내">
					<li>
						<p>
							<strong>만 14세 미만</strong> 아동은 개인정보 수집 시 법정대리인(친권자 또는
							후견인)의 동의가 필요합니다.
						</p>
					</li>
					<li>
						<p>
							이는 <strong>정보통신망법 제31조</strong>에 따른 법적 의무로, 아동의 개인정보
							보호를 위해 반드시 이행해야 합니다.
						</p>
					</li>
					<li>
						<p>
							법정대리인의 본인인증 및 동의 완료 후 회원가입을 진행할 수 있습니다.
						</p>
					</li>
				</ul>
			</div>

			{/* ② 가입 절차 안내 */}
			<div className="white-wrap" style={{ marginTop: '16px' }}>
				<h3 className="h3-title">가입 절차 안내</h3>
				<ol
					className="text-list-wrap"
					aria-label={`전체 ${MINOR_TOTAL_STEPS}단계 가입 절차`}
					style={{ paddingLeft: '20px', lineHeight: '2' }}
				>
					<li>
						<strong>약관 동의</strong> — 서비스 이용약관 및 개인정보 처리 동의
					</li>
					<li>
						<strong>아동 본인인증</strong> — 가입하는 아동의 본인인증 (휴대폰 또는 간편인증)
					</li>
					<li>
						<strong>법정대리인 본인인증</strong> — 친권자 또는 후견인의 본인인증
					</li>
					<li>
						<strong>계정 정보 입력</strong> — 아이디 및 비밀번호 설정
					</li>
					<li>
						<strong>가입 완료</strong>
					</li>
				</ol>
			</div>

			{/* ③ 준비물 안내 */}
			<div className="white-wrap" style={{ marginTop: '16px' }}>
				<h3 className="h3-title">준비물</h3>
				<ul className="text-list-wrap check" aria-label="준비물">
					<li>
						<p>아동 명의 휴대폰 또는 간편인증 수단 (카카오, PASS 등)</p>
					</li>
					<li>
						<p>법정대리인(부모님 등) 명의 휴대폰 또는 간편인증 수단</p>
					</li>
				</ul>
			</div>
		</RegisterLayout>
	);
}

export default RegisterMinorStep1;
