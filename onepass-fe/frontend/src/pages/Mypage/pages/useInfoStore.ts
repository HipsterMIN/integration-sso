import { createContext, useContext } from 'react';
import type {
	EnterpriseData,
	MemberClient,
	MemberData,
} from 'types/api/ext/members';

export interface MemberInfo {
	id: string;
	name: string;
	phone1: string;
	phone2: string;
	phone3: string;
	email1: string;
	email2: string;
	mbrNo: string;
	mbrSttsCd: string;
	mbrSttsNm: string;
	ssoLastLoginDt: string;
	clients: MemberClient[];
}

export interface BusinessInfo {
	company_name: string;
	company_num: string;
	name: string;
	tel1: string;
	tel2: string;
	tel3: string;
	email1: string;
	email2: string;
	entMbrNo: string;
	estbDt: string;
	mbrSttsCd: string;
	mbrSttsNm: string;
	ssoLastLoginDt: string;
	clients: MemberClient[];
}

export const MEMBER_DEFAULT: MemberInfo = {
	id: '',
	name: '',
	phone1: '',
	phone2: '',
	phone3: '',
	email1: '',
	email2: '',
	mbrNo: '',
	mbrSttsCd: '',
	mbrSttsNm: '',
	ssoLastLoginDt: '',
	clients: [],
};

export const BUSINESS_DEFAULT: BusinessInfo = {
	company_name: '',
	company_num: '',
	name: '',
	tel1: '',
	tel2: '',
	tel3: '',
	email1: '',
	email2: '',
	entMbrNo: '',
	estbDt: '',
	mbrSttsCd: '',
	mbrSttsNm: '',
	ssoLastLoginDt: '',
	clients: [],
};

/** 전화번호 문자열 → phone1/phone2/phone3 파싱 ("010-1234-5678" → ["010","1234","5678"]) */
function parsePhone(phone: string): [string, string, string] {
	const parts = phone.replace(/[^0-9-]/g, '').split('-');
	return [parts[0] || '', parts[1] || '', parts[2] || ''];
}

/** 이메일 문자열 → email1/email2 분리 ("hong@example.com" → ["hong","example.com"]) */
function parseEmail(email: string): [string, string] {
	const idx = email.indexOf('@');
	if (idx < 0) return [email, ''];
	return [email.slice(0, idx), email.slice(idx + 1)];
}

/** 개인회원 조회 API 응답 → MemberInfo 매핑 */
export function mapMemberResponse(data: MemberData): Partial<MemberInfo> {
	const [phone1, phone2, phone3] = data.phone
		? parsePhone(data.phone)
		: ['', '', ''];
	const [email1, email2] = data.email ? parseEmail(data.email) : ['', ''];
	return {
		id: data.loginId,
		name: data.memberName,
		phone1,
		phone2,
		phone3,
		email1,
		email2,
		mbrNo: data.mbrNo,
		mbrSttsCd: data.mbrSttsCd,
		mbrSttsNm: data.mbrSttsNm,
		ssoLastLoginDt: data.ssoLastLoginDt,
		clients: data.clients ?? [],
	};
}

/** 기업회원 조회 API 응답 → BusinessInfo 매핑 */
export function mapEnterpriseResponse(
	data: EnterpriseData,
): Partial<BusinessInfo> {
	const [tel1, tel2, tel3] = data.rprsTelno
		? parsePhone(data.rprsTelno)
		: ['', '', ''];
	const emailRaw = data.rprsEmlAddr || data.email;
	const [email1, email2] = emailRaw ? parseEmail(emailRaw) : ['', ''];
	return {
		company_name: data.bzmnNm,
		company_num: data.brno,
		name: data.rprsvNm,
		tel1,
		tel2,
		tel3,
		email1,
		email2,
		entMbrNo: data.entMbrNo,
		estbDt: data.estbDt ?? '',
		mbrSttsCd: data.mbrSttsCd,
		mbrSttsNm: data.mbrSttsNm,
		ssoLastLoginDt: data.ssoLastLoginDt ?? '',
		clients: data.clients ?? [],
	};
}

const STORAGE_KEY = 'mypage_info';
const USER_ID_KEY = 'mypage_user_id';

interface StoredData {
	member: MemberInfo;
	business: BusinessInfo;
}

interface StoredUserId {
	member?: string;
	business?: string;
}

export function saveUserId(
	memberType: 'member' | 'business',
	id: string,
): void {
	try {
		const raw = localStorage.getItem(USER_ID_KEY);
		const parsed: StoredUserId = raw ? JSON.parse(raw) : {};
		parsed[memberType] = id;
		localStorage.setItem(USER_ID_KEY, JSON.stringify(parsed));
	} catch {
		// ignore
	}
}

export function loadUserId(
	memberType: 'member' | 'business',
): string | undefined {
	try {
		const raw = localStorage.getItem(USER_ID_KEY);
		if (raw) {
			const parsed = JSON.parse(raw) as StoredUserId;
			return parsed[memberType] || undefined;
		}
	} catch {
		// ignore
	}
	return undefined;
}

export function loadFromStorage(): StoredData {
	try {
		const raw = localStorage.getItem(STORAGE_KEY);
		if (raw) {
			const parsed = JSON.parse(raw) as Partial<StoredData>;
			return {
				member: { ...MEMBER_DEFAULT, ...parsed.member },
				business: { ...BUSINESS_DEFAULT, ...parsed.business },
			};
		}
	} catch {
		// ignore
	}
	return { member: { ...MEMBER_DEFAULT }, business: { ...BUSINESS_DEFAULT } };
}

export function saveToStorage(data: StoredData): void {
	localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
}

interface InfoStoreContextType {
	member: MemberInfo;
	business: BusinessInfo;
	updateMember: (data: Partial<MemberInfo>) => void;
	updateBusiness: (data: Partial<BusinessInfo>) => void;
}

export const InfoStoreContext = createContext<InfoStoreContextType>({
	member: MEMBER_DEFAULT,
	business: BUSINESS_DEFAULT,
	updateMember: () => {},
	updateBusiness: () => {},
});

export function useInfoStore(): InfoStoreContextType {
	return useContext(InfoStoreContext);
}
