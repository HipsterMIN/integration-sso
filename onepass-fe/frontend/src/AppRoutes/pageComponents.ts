import Loadable from 'components/Loadable';

export const Login = Loadable(
	() => import(/* webpackChunkName: "Login" */ 'pages/Login'),
);

export const SomethingWentWrong = Loadable(
	() =>
		import(
			/* webpackChunkName: "SomethingWentWrong" */ 'pages/SomethingWentWrong'
		),
);

export const UnAuthorized = Loadable(
	() => import(/* webpackChunkName: "UnAuthorized" */ 'pages/UnAuthorized'),
);

// Conversion step1 (shared entry)
export const ConversionStep1 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionStep1" */ 'pages/ConversionSteps/member/Step1'
		),
);

// Conversion member steps
// STEP4(/step4) → 정보입력(member/Step5.tsx), STEP5(/step5) → 완료(member/Step8.tsx)
export const ConversionMemberStep2 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionMemberStep2" */ 'pages/ConversionSteps/member/Step2'
		),
);
export const ConversionMemberStep3 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionMemberStep3" */ 'pages/ConversionSteps/member/Step3'
		),
);
export const ConversionMemberStep4 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionMemberStep4" */ 'pages/ConversionSteps/member/Step5'
		),
);
export const ConversionMemberStep5 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionMemberStep5" */ 'pages/ConversionSteps/member/Step8'
		),
);

// Mypage
export const MypageMember = Loadable(
	() =>
		import(/* webpackChunkName: "MypageMember" */ 'pages/Mypage/MypageMember'),
);
export const MypageBusiness = Loadable(
	() =>
		import(
			/* webpackChunkName: "MypageBusiness" */ 'pages/Mypage/MypageBusiness'
		),
);

// UseTerms
export const UseTerms = Loadable(
	() => import(/* webpackChunkName: "UseTerms" */ 'pages/UseTerms'),
);

// Privacy
export const Privacy = Loadable(
	() => import(/* webpackChunkName: "Privacy" */ 'pages/Privacy'),
);

// OACX 간편인증 테스트
export const OacxTest = Loadable(
	() => import(/* webpackChunkName: "OacxTest" */ 'pages/OacxTest'),
);

export const SupportMain = Loadable(
	() => import(/* webpackChunkName: "SupportMain" */ 'pages/Support/Main'),
);

export const SupportQna = Loadable(
	() => import(/* webpackChunkName: "SupportQna" */ 'pages/Support/Qna'),
);

export const SupportFaq = Loadable(
	() => import(/* webpackChunkName: "SupportFaq" */ 'pages/Support/Faq'),
);

export const SupportAdmin = Loadable(
	() => import(/* webpackChunkName: "SupportAdmin" */ 'pages/Support/Admin'),
);

// Conversion business steps
// STEP4(/step4) → 정보입력(business/Step5.tsx), STEP5(/step5) → 완료(business/Step6.tsx)
export const ConversionBusinessStep2 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionBusinessStep2" */ 'pages/ConversionSteps/business/Step2'
		),
);
export const ConversionBusinessStep3 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionBusinessStep3" */ 'pages/ConversionSteps/business/Step3'
		),
);
export const ConversionBusinessStep4 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionBusinessStep4" */ 'pages/ConversionSteps/business/Step5'
		),
);
export const ConversionBusinessStep5 = Loadable(
	() =>
		import(
			/* webpackChunkName: "ConversionBusinessStep5" */ 'pages/ConversionSteps/business/Step6'
		),
);

// Register step1 (shared entry)
export const RegisterStep1 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterStep1" */ 'pages/RegisterSteps/member/Step1'
		),
);

// Register member steps
// STEP4(/step4) → 정보입력(member/Step5.tsx), STEP5(/step5) → 완료(member/Step6.tsx)
export const RegisterMemberStep2 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterMemberStep2" */ 'pages/RegisterSteps/member/Step2'
		),
);
export const RegisterMemberStep3 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterMemberStep3" */ 'pages/RegisterSteps/member/Step3'
		),
);
export const RegisterMemberStep4 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterMemberStep4" */ 'pages/RegisterSteps/member/Step5'
		),
);
export const RegisterMemberStep5 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterMemberStep5" */ 'pages/RegisterSteps/member/Step6'
		),
);

// Register business steps
// STEP4(/step4) → 정보입력(business/Step5.tsx), STEP5(/step5) → 완료(business/Step6.tsx)
export const RegisterBusinessStep2 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterBusinessStep2" */ 'pages/RegisterSteps/business/Step2'
		),
);
export const RegisterBusinessStep3 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterBusinessStep3" */ 'pages/RegisterSteps/business/Step3'
		),
);
export const RegisterBusinessStep4 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterBusinessStep4" */ 'pages/RegisterSteps/business/Step5'
		),
);
export const RegisterBusinessStep5 = Loadable(
	() =>
		import(
			/* webpackChunkName: "RegisterBusinessStep5" */ 'pages/RegisterSteps/business/Step6'
		),
);
