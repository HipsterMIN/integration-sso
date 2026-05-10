import ROUTES from 'constants/routes';
import type { MemberType } from 'components/StepIndicator';

export function getConversionRoute(step: number, memberType: MemberType): string {
	// step 1 은 회원유형 선택 화면 — member/business 분기 없이 공통 경로 사용
	if (step === 1) return ROUTES.CONVERSION_STEP1;

	const prefix = memberType === 'business' ? 'CONVERSION_BUSINESS' : 'CONVERSION_MEMBER';
	const key = `${prefix}_STEP${step}` as keyof typeof ROUTES;
	return ROUTES[key];
}
