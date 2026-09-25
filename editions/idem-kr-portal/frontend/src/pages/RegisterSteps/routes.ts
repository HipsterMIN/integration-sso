import type { MemberType } from 'components/StepIndicator';
import ROUTES from 'constants/routes';

export function getRegisterRoute(step: number, memberType: MemberType): string {
	if (step === 1) return ROUTES.REGISTER_STEP1;
	const prefix =
		memberType === 'business' ? 'REGISTER_BUSINESS' : 'REGISTER_MEMBER';
	const key = `${prefix}_STEP${step}` as keyof typeof ROUTES;
	return ROUTES[key];
}
