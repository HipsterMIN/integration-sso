import { getEnterprise, getMember } from 'api/ext/members';
import cx from 'classnames';
import MypageSideNav from 'components/MypageSideNav';
import Spinner from 'components/Spinner';
import IMAGES from 'constants/images';
import {
	BusinessInfo,
	InfoStoreContext,
	loadFromStorage,
	loadUserId,
	mapEnterpriseResponse,
	mapMemberResponse,
	MemberInfo,
	saveToStorage,
	saveUserId,
} from 'pages/Mypage/pages/useInfoStore';
import {
	createContext,
	ReactNode,
	useCallback,
	useContext,
	useEffect,
	useMemo,
	useState,
} from 'react';
import { useLocation } from 'react-router-dom';

export type MypageSection = 'information' | 'affiliation' | 'password' | 'withdraw';
export type MypageMemberType = 'member' | 'business';

const MypageContext = createContext<MypageMemberType>('member');

export function useMypageType(): MypageMemberType {
	return useContext(MypageContext);
}

interface MypageLayoutProps {
	children: ReactNode;
	memberType: MypageMemberType;
}

function getSection(pathname: string): MypageSection {
	if (pathname.includes('/affiliation')) return 'affiliation';
	if (pathname.includes('/password')) return 'password';
	if (/\/withdraw(\/|$)/.test(pathname) && !pathname.includes('/affiliation/')) {
		return 'withdraw';
	}
	return 'information';
}

const SECTION_TITLE: Record<MypageSection, string> = {
	information: '나의 정보',
	affiliation: '유관기관 서비스 관리',
	password: '비밀번호 수정',
	withdraw: '회원 탈퇴',
};

function getExtraClasses(pathname: string): string {
	const parts = pathname.replace(/^\/mypage-(member|business)\//, '').split('/');
	const classes: string[] = [];

	if (parts[0]) classes.push(parts[0]);

	if (parts[0] === 'affiliation' && parts.length === 1) {
		classes.push('step1');
	}

	for (let i = 1; i < parts.length; i += 1) {
		classes.push(parts[i]);
	}

	if (parts[0] === 'affiliation' && parts[1] === 'add' && parts[2] === 'step2') {
		classes.push('withdraw');
	}

	return classes.join(' ');
}

/** API 호출 후 setState + localStorage 저장 (컴포넌트 외부 헬퍼) */
async function fetchMemberInfo(
	mbrNo: string,
	setMember: React.Dispatch<React.SetStateAction<MemberInfo>>,
	setLoading: React.Dispatch<React.SetStateAction<boolean>>,
): Promise<void> {
	setLoading(true);
	try {
		const res = await getMember(mbrNo);
		if (res.statusCode === 200 && res.payload?.data) {
			const mbrUuid = res.payload.data.mbrUuid || mbrNo;
			saveUserId('member', mbrUuid);
			setMember((prev) => {
				const next = { ...prev, ...mapMemberResponse(res.payload.data) };
				const stored = loadFromStorage();
				saveToStorage({ ...stored, member: next });
				return next;
			});
		}
	} finally {
		setLoading(false);
	}
}

async function fetchEnterpriseInfo(
	entMbrNo: string,
	setBusiness: React.Dispatch<React.SetStateAction<BusinessInfo>>,
	setLoading: React.Dispatch<React.SetStateAction<boolean>>,
): Promise<void> {
	setLoading(true);
	try {
		const res = await getEnterprise(entMbrNo);
		if (res.statusCode === 200 && res.payload?.data) {
			const mbrUuid = res.payload.data.mbrUuid || entMbrNo;
			saveUserId('business', mbrUuid);
			setBusiness((prev) => {
				const next = { ...prev, ...mapEnterpriseResponse(res.payload.data) };
				const stored = loadFromStorage();
				saveToStorage({ ...stored, business: next });
				return next;
			});
		}
	} finally {
		setLoading(false);
	}
}

function MypageLayout({
	children,
	memberType,
}: MypageLayoutProps): JSX.Element {
	const { pathname, search } = useLocation();
	const section = getSection(pathname);
	const extraClasses = getExtraClasses(pathname);
	const pageTitle = SECTION_TITLE[section];

	const stored = loadFromStorage();
	const [member, setMember] = useState<MemberInfo>(stored.member);
	const [business, setBusiness] = useState<BusinessInfo>(stored.business);
	const [loading, setLoading] = useState(false);

	const updateMember = useCallback(
		(data: Partial<MemberInfo>) =>
			setMember((prev) => {
				const next = { ...prev, ...data };
				saveToStorage({ member: next, business });
				return next;
			}),
		[business],
	);
	const updateBusiness = useCallback(
		(data: Partial<BusinessInfo>) =>
			setBusiness((prev) => {
				const next = { ...prev, ...data };
				saveToStorage({ member, business: next });
				return next;
			}),
		[member],
	);

	// URL 파라미터 → localStorage 저장 ID → stored 데이터 ID 순으로 fallback
	useEffect(() => {
		const params = new URLSearchParams(search);
		const mbrNo = params.get('mbrNo');
		const entMbrNo = params.get('entMbrNo');
		const uuid = params.get('uuid');

		if (memberType === 'member') {
			const id = mbrNo || uuid || loadUserId('member') || stored.member.mbrNo;
			if (id) fetchMemberInfo(id, setMember, setLoading);
		} else if (memberType === 'business') {
			const id = entMbrNo || uuid || loadUserId('business') || stored.business.entMbrNo;
			if (id) fetchEnterpriseInfo(id, setBusiness, setLoading);
		}
	// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [memberType, search]);

	const ctxValue = useMemo(
		() => ({ member, business, updateMember, updateBusiness }),
		[member, business, updateMember, updateBusiness],
	);

	return (
		<InfoStoreContext.Provider value={ctxValue}>
			<MypageContext.Provider value={memberType}>
				<main
					id="main-content"
					className={cx('container', 'sub', 'mypage', memberType, extraClasses)}
				>
					<div className="page-title-wrap">
						<div className="page-title-text-box">
							<h2 className="page-title">{pageTitle}</h2>
						</div>
						<figure className="img-box">
							<img src={IMAGES.RENEWAL_PAGE_TITLE_IMG} alt="" aria-hidden="true" />
						</figure>
					</div>
					<div className="mypage-row">
						<MypageSideNav section={section} memberType={memberType} />
						<div className="sub-wrap inner">
							{loading ? (
								<Spinner tip="회원 정보를 불러오고 있습니다..." height="300px" />
							) : (
								children
							)}
						</div>
					</div>
				</main>
			</MypageContext.Provider>
		</InfoStoreContext.Provider>
	);
}

export default MypageLayout;
