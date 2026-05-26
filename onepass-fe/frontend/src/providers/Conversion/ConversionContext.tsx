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

export interface ConversionData {
	// Step1
	memberType: MemberType;
	initialClientId: string;

	// Step3 (기업인증)
	brno: string;

	// Step3 (본인인증 결과)
	/** CI 참조 토큰 — onepass-be에서 발급 (CI 자체는 프론트에 저장하지 않음) */
	ciToken: string;
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

	// Step5 (MemberInfoForm - 개인)
	name: string;
	phonePrefix: string;
	phoneSuffix: string;
	emailId: string;

	// Step4 (서비스연결)
	selectedClients: string[];
	availableClients: Client[];

	// Step2 (약관동의)
	consentEventId?: number;

	// redirect_uri (대상시스템 콜백)
	redirectUri: string;

	// Step5 (NotificationSettings)
	notifications: Record<string, boolean>;

	// URL 파라미터 (사용자 ID)
	mbrId: string;

	// 프로비저닝 결과
	entMbrNo: string;
	mbrNo: string;
	mbrUuid: string;
	provisioningToken: string;

	// Step1 (PR #196) — signed_request JWT 처리 후 onepass-be로부터 발급되는 세션 ID.
	// 이후 단계에서 conversion-init 컨텍스트를 다시 참조할 때 사용한다.
	conversionSessionId?: string;
}

const INITIAL_DATA: ConversionData = {
	memberType: 'member',
	initialClientId: '',
	brno: '',
	ciToken: '',
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
	name: MOCK_MEMBER.name,
	phonePrefix: MOCK_MEMBER.phonePrefix,
	phoneSuffix: MOCK_MEMBER.phoneSuffix,
	emailId: MOCK_MEMBER.emailId,
	consentEventId: undefined,
	redirectUri: '',
	selectedClients: [],
	availableClients: [],
	notifications: {},
	mbrId: '',
	entMbrNo: '',
	mbrNo: '',
	mbrUuid: '',
	provisioningToken: '',
};

interface ConversionContextValue {
	data: ConversionData;
	updateData: (partial: Partial<ConversionData>) => void;
	resetData: () => void;
}

const ConversionContext = createContext<ConversionContextValue>({
	data: INITIAL_DATA,
	updateData: () => {},
	resetData: () => {},
});

export function ConversionProvider({
	children,
}: {
	children: ReactNode;
}): JSX.Element {
	const [data, setData] = useState<ConversionData>(INITIAL_DATA);

	const updateData = useCallback((partial: Partial<ConversionData>) => {
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
		<ConversionContext.Provider value={value}>
			{children}
		</ConversionContext.Provider>
	);
}

export const useConversion = (): ConversionContextValue =>
	useContext(ConversionContext);

export default ConversionContext;
