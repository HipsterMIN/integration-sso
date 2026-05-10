import cx from 'classnames';

const MEMBER_STEPS = [
	'회원유형',
	'약관 동의',
	'본인인증',
	'유관기관 서비스 연결',
	'정보입력',
	'전환완료',
] as const;

const BUSINESS_STEPS = [
	'회원유형',
	'약관 동의',
	'기업인증',
	'유관기관 서비스 연결',
	'정보입력',
	'전환완료',
] as const;

const REGISTER_MEMBER_STEPS = [
	'회원유형',
	'약관 동의',
	'본인인증',
	'유관기관 서비스 연결',
	'정보입력',
	'회원가입 완료',
] as const;

const REGISTER_BUSINESS_STEPS = [
	'회원유형',
	'약관 동의',
	'기업인증',
	'유관기관 서비스 연결',
	'정보입력',
	'회원가입 완료',
] as const;

type MemberType = 'member' | 'business';
type FlowType = 'conversion' | 'register';

interface StepIndicatorProps {
	currentStep: number;
	memberType?: MemberType;
	flowType?: FlowType;
}

function getSteps(
	memberType: MemberType,
	flowType: FlowType = 'conversion',
): readonly string[] {
	if (flowType === 'register') {
		return memberType === 'business'
			? REGISTER_BUSINESS_STEPS
			: REGISTER_MEMBER_STEPS;
	}
	return memberType === 'business' ? BUSINESS_STEPS : MEMBER_STEPS;
}

function StepIndicator({
	currentStep,
	memberType = 'member',
	flowType = 'conversion',
}: StepIndicatorProps): JSX.Element {
	const steps = getSteps(memberType, flowType);

	return (
		<ol className="step-wrap" aria-label="진행 단계">
			{steps.map((name, idx) => {
				const step = idx + 1;
				const isDone = step < currentStep;
				const isActive = step === currentStep;

				return (
					<li
						key={step}
						className={cx({ done: isDone, active: isActive })}
						aria-current={isActive ? 'step' : undefined}
					>
						<span>
							{isDone && <em className="sr-only">완료</em>}
							{isActive && <em className="sr-only">현재단계</em>}
							<span className="sr-only">{step}단계 </span>
							<i className="step" aria-hidden="true">
								{step}단계
							</i>
							<span className="step-tit">{name}</span>
						</span>
					</li>
				);
			})}
		</ol>
	);
}

const STEPS = MEMBER_STEPS;

export {
	BUSINESS_STEPS,
	getSteps,
	MEMBER_STEPS,
	REGISTER_BUSINESS_STEPS,
	REGISTER_MEMBER_STEPS,
	STEPS,
};
export type { FlowType, MemberType };
export default StepIndicator;
