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
