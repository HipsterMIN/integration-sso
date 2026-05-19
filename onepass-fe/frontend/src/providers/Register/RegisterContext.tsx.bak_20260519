import type { MemberType } from 'components/StepIndicator';
import {
	createContext,
	ReactNode,
	useCallback,
	useContext,
	useMemo,
	useState,
} from 'react';
import type { Client } from 'types/api/ext/clients';

export interface RegisterData {
	// Step1
	memberType: MemberType;
	initialClientId: string;
	returnUri: string;

	// Step3 (기업인증)
	brno: string;

	// Step3 (개인인증)
	ciToken: string;
	mbrUuid: string;
	birthDate: string;

	// Step5 (AccountForm - 공통)
	loginId: string;
	email: string;
	emailDomain: string;
	phone: string;
	telPrefix: string;
	telSuffix: string;

	// Step5 (AccountForm - 기업)
	bzmnNm: string;
	rprsvNm: string;
	startDt: string;
	password: string;
	mbrId: string;

	// 프로비저닝 결과
	entMbrNo: string;
	mbrNo: string;
	provisioningToken: string;

	// Step5 (MemberInfoForm - 개인)
	name: string;
	phonePrefix: string;
	phoneSuffix: string;
	emailId: string;

	// Step2 (약관동의)
	consentEventId: number;

	// Step4 (서비스연결)
	selectedClients: string[];
	availableClients: Client[];

	// Step5 (NotificationSettings)
	notifications: Record<string, boolean>;

	// 만14세 미만 회원가입 — 법정대리인 동의 플로우 (정보통신망법 제31조)
	isMinor: boolean;                // 만14세 미만 여부 (Step3 본인인증 birthDate 기반 판정)
	guardianConsentDone: boolean;    // 법정대리인 동의 완료 여부
	guardianCiToken: string;         // 법정대리인 CI 기반 JWT 토큰 (Step4 보호자 인증 후 발급)
	guardianName: string;            // 법정대리인 성명
	guardianBirthDate: string;       // 법정대리인 생년월일 (YYYYMMDD)
	guardianPhone: string;           // 법정대리인 휴대폰번호
	guardianConsentEventId: number;  // 법정대리인 동의 이벤트 ID (Q-IM 약관 서버 발급)
}

const INITIAL_DATA: RegisterData = {
	memberType: 'member',
	initialClientId: '',
	returnUri: '',
	brno: '',
	ciToken: '',
	mbrUuid: '',
	birthDate: '',
	loginId: '',
	email: '',
	emailDomain: '',
	phone: '',
	telPrefix: '',
	telSuffix: '',
	bzmnNm: '',
	rprsvNm: '',
	startDt: '',
	password: '',
	mbrId: '',
	entMbrNo: '',
	mbrNo: '',
	provisioningToken: '',
	name: '',
	phonePrefix: '010',
	phoneSuffix: '',
	emailId: '',
	consentEventId: 0,
	selectedClients: [],
	availableClients: [],
	notifications: {},
	// 만14세 미만 법정대리인 동의 필드 초기값
	isMinor: false,
	guardianConsentDone: false,
	guardianCiToken: '',
	guardianName: '',
	guardianBirthDate: '',
	guardianPhone: '',
	guardianConsentEventId: 0,
};

interface RegisterContextValue {
	data: RegisterData;
	updateData: (partial: Partial<RegisterData>) => void;
	resetData: () => void;
}

const RegisterContext = createContext<RegisterContextValue>({
	data: INITIAL_DATA,
	updateData: () => {},
	resetData: () => {},
});

export function RegisterProvider({
	children,
}: {
	children: ReactNode;
}): JSX.Element {
	const [data, setData] = useState<RegisterData>(INITIAL_DATA);

	const updateData = useCallback((partial: Partial<RegisterData>) => {
		setData((prev) => ({ ...prev, ...partial }));
	}, []);

	const resetData = useCallback(() => {
		setData(INITIAL_DATA);
	}, []);

	const value = useMemo(() => ({ data, updateData, resetData }), [
		data,
		updateData,
		resetData,
	]);

	return (
		<RegisterContext.Provider value={value}>{children}</RegisterContext.Provider>
	);
}

export const useRegister = (): RegisterContextValue =>
	useContext(RegisterContext);

export default RegisterContext;
