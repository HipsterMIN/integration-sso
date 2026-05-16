const ROUTES = {
	LOGIN: '/login',
	HOME_PAGE: '/',
	SOMETHING_WENT_WRONG: '/something-went-wrong',
	UN_AUTHORIZED: '/un-authorized',
	NOT_FOUND: '/not-found',
	CONVERSION_STEP1: '/conversion/step1',
	CONVERSION_MEMBER_STEP2: '/conversion-member/step2',
	CONVERSION_MEMBER_STEP3: '/conversion-member/step3',
	CONVERSION_MEMBER_STEP4: '/conversion-member/step4',
	CONVERSION_MEMBER_STEP5: '/conversion-member/step5',
	CONVERSION_MEMBER_STEP6: '/conversion-member/step6',
	CONVERSION_BUSINESS_STEP2: '/conversion-business/step2',
	CONVERSION_BUSINESS_STEP3: '/conversion-business/step3',
	CONVERSION_BUSINESS_STEP4: '/conversion-business/step4',
	CONVERSION_BUSINESS_STEP5: '/conversion-business/step5',
	CONVERSION_BUSINESS_STEP6: '/conversion-business/step6',
	REGISTER_STEP1: '/register/step1',
	REGISTER_MEMBER_STEP2: '/register-member/step2',
	REGISTER_MEMBER_STEP3: '/register-member/step3',
	REGISTER_MEMBER_STEP4: '/register-member/step4',
	REGISTER_MEMBER_STEP5: '/register-member/step5',
	REGISTER_MEMBER_STEP6: '/register-member/step6',
	REGISTER_BUSINESS_STEP2: '/register-business/step2',
	REGISTER_BUSINESS_STEP3: '/register-business/step3',
	REGISTER_BUSINESS_STEP4: '/register-business/step4',
	REGISTER_BUSINESS_STEP5: '/register-business/step5',
	REGISTER_BUSINESS_STEP6: '/register-business/step6',
	// 만14세 미만 회원가입 — 법정대리인 동의 플로우
	// 정보통신망법 제31조: 만14세 미만 아동 개인정보 수집 시 법정대리인 동의 필수
	REGISTER_MINOR_STEP1: '/register-minor/step1', // 안내 (법정대리인 동의 필요 안내)
	REGISTER_MINOR_STEP2: '/register-minor/step2', // 약관 동의 (본인 + 보호자)
	REGISTER_MINOR_STEP3: '/register-minor/step3', // 본인(아동) 본인인증
	REGISTER_MINOR_STEP4: '/register-minor/step4', // 법정대리인 본인인증
	REGISTER_MINOR_STEP5: '/register-minor/step5', // 계정 정보 입력
	REGISTER_MINOR_STEP6: '/register-minor/step6', // 가입 완료
	MYPAGE_MEMBER: '/mypage-member',
	MYPAGE_MEMBER_INFORMATION: '/mypage-member/information',
	MYPAGE_MEMBER_INFORMATION_STEP2: '/mypage-member/information/step2',
	MYPAGE_MEMBER_INFORMATION_STEP3: '/mypage-member/information/step3',
	MYPAGE_MEMBER_AFFILIATION: '/mypage-member/affiliation',
	MYPAGE_MEMBER_AFFILIATION_ADD_STEP1: '/mypage-member/affiliation/add/step1',
	MYPAGE_MEMBER_AFFILIATION_ADD_STEP2: '/mypage-member/affiliation/add/step2',
	MYPAGE_MEMBER_AFFILIATION_WITHDRAW_STEP1:
		'/mypage-member/affiliation/withdraw/step1',
	MYPAGE_MEMBER_AFFILIATION_WITHDRAW_STEP2:
		'/mypage-member/affiliation/withdraw/step2',
	MYPAGE_MEMBER_PASSWORD: '/mypage-member/password',
	MYPAGE_MEMBER_PASSWORD_STEP2: '/mypage-member/password/step2',
	MYPAGE_MEMBER_WITHDRAW: '/mypage-member/withdraw',
	MYPAGE_MEMBER_WITHDRAW_STEP2: '/mypage-member/withdraw/step2',
	MYPAGE_MEMBER_WITHDRAW_COMPLETE: '/mypage-member/withdraw/complete',
	MYPAGE_BUSINESS: '/mypage-business',
	MYPAGE_BUSINESS_INFORMATION: '/mypage-business/information',
	MYPAGE_BUSINESS_INFORMATION_STEP2: '/mypage-business/information/step2',
	MYPAGE_BUSINESS_INFORMATION_STEP3: '/mypage-business/information/step3',
	MYPAGE_BUSINESS_AFFILIATION: '/mypage-business/affiliation',
	MYPAGE_BUSINESS_AFFILIATION_ADD_STEP1:
		'/mypage-business/affiliation/add/step1',
	MYPAGE_BUSINESS_AFFILIATION_ADD_STEP2:
		'/mypage-business/affiliation/add/step2',
	MYPAGE_BUSINESS_AFFILIATION_WITHDRAW_STEP1:
		'/mypage-business/affiliation/withdraw/step1',
	MYPAGE_BUSINESS_AFFILIATION_WITHDRAW_STEP2:
		'/mypage-business/affiliation/withdraw/step2',
	MYPAGE_BUSINESS_PASSWORD: '/mypage-business/password',
	MYPAGE_BUSINESS_PASSWORD_STEP2: '/mypage-business/password/step2',
	MYPAGE_BUSINESS_WITHDRAW: '/mypage-business/withdraw',
	MYPAGE_BUSINESS_WITHDRAW_STEP2: '/mypage-business/withdraw/step2',
	MYPAGE_BUSINESS_WITHDRAW_COMPLETE: '/mypage-business/withdraw/complete',
	USE_TERMS: '/use-terms',
	PRIVACY: '/privacy',
	OACX_TEST: '/auth-test',
} as const;

export default ROUTES;
