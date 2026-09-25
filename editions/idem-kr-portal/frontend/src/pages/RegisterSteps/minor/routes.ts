/**
 * 만14세 미만 회원가입 플로우 라우트 헬퍼
 *
 * 플로우 순서:
 *   Step1 → 안내 (법정대리인 동의 필요 안내)
 *   Step2 → 약관 동의 (미성년자 본인 + 법정대리인 대상 약관)
 *   Step3 → 아동 본인인증 (NICE 휴대폰 / 간편인증)
 *   Step4 → 법정대리인 본인인증 (보호자 NICE 휴대폰 / 간편인증)
 *   Step5 → 계정 정보 입력 (ID/PW 설정)
 *   Step6 → 가입 완료
 */
import ROUTES from 'constants/routes';

export const MINOR_TOTAL_STEPS = 6;

export function getMinorRegisterRoute(step: number): string {
	const key = `REGISTER_MINOR_STEP${step}` as keyof typeof ROUTES;
	return ROUTES[key] ?? ROUTES.REGISTER_MINOR_STEP1;
}
