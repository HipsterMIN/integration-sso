import type { MemberType } from 'components/StepIndicator';
import { MOCK_BUSINESS, MOCK_MEMBER } from 'constants/mockData';
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

	// ────────────────────────────────────────────────────────────────────
	// 미성년자 가입 플로우 (RegisterSteps/minor/Step2~6)
	// 일반 회원가입에서는 모두 미사용 — INITIAL_DATA 에서 빈 값/false 로 초기화.
	// 추가 배경: 2026-05-10 onepass-develop.zip 통합으로 페이지 컴포넌트는 들어왔으나
	// 본 타입 정의가 동반 갱신되지 않아 tsc 14건 에러로 표면화됨 (PR #195 참조).
	// ────────────────────────────────────────────────────────────────────

	/** 미성년자 여부 (Step3 에서 본인인증 후 만 14세 미만이면 true) */
	isMinor: boolean;

	/** 미성년자 약관 동의 이벤트 ID (Step2 미성년자 분기에서 별도 저장) */
	guardianConsentEventId: number;

	/** 보호자 본인인증 CI 토큰 (Step4) */
	guardianCiToken: string;

	/** 보호자 성명 */
	guardianName: string;

	/** 보호자 생년월일 (YYYYMMDD) */
	guardianBirthDate: string;

	/** 보호자 휴대폰번호 */
	guardianPhone: string;

	/** 보호자 동의 완료 플래그 (Step4 완료 후 true) */
	guardianConsentDone: boolean;
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
	email: MOCK_MEMBER.emailId,
	emailDomain: MOCK_MEMBER.emailDomain,
	phone: '',
	telPrefix: '',
	telSuffix: '',
	bzmnNm: MOCK_BUSINESS.companyName,
	rprsvNm: MOCK_BUSINESS.repName,
	startDt: '',
	password: '',
	mbrId: '',
	entMbrNo: '',
	mbrNo: '',
	provisioningToken: '',
	name: MOCK_MEMBER.name,
	phonePrefix: MOCK_MEMBER.phonePrefix,
	phoneSuffix: MOCK_MEMBER.phoneSuffix,
	emailId: MOCK_MEMBER.emailId,
	consentEventId: 0,
	selectedClients: [],
	availableClients: [],
	notifications: {},
	// 미성년자 가입 플로우 초기값
	isMinor: false,
	guardianConsentEventId: 0,
	guardianCiToken: '',
	guardianName: '',
	guardianBirthDate: '',
	guardianPhone: '',
	guardianConsentDone: false,
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
