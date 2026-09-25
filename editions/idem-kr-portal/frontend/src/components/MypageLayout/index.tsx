import { getEnterprise, getMember } from 'api/ext/members';
import cx from 'classnames';
import KrdsModal from 'components/KrdsModal';
import MypageSideNav from 'components/MypageSideNav';
import Spinner from 'components/Spinner';
import IMAGES from 'constants/images';
import {
	BusinessInfo,
	InfoStoreContext,
	loadFromStorage,
	loadRedirectUri,
	loadUserId,
	mapEnterpriseResponse,
	mapMemberResponse,
	MemberInfo,
	saveRedirectUri,
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
	setNotFound: React.Dispatch<React.SetStateAction<boolean>>,
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
		} else if (res.statusCode === 404 && res.error === 'PROV_003') {
			setNotFound(true);
		}
	} finally {
		setLoading(false);
	}
}

async function fetchEnterpriseInfo(
	entMbrNo: string,
	setBusiness: React.Dispatch<React.SetStateAction<BusinessInfo>>,
	setLoading: React.Dispatch<React.SetStateAction<boolean>>,
	setNotFound: React.Dispatch<React.SetStateAction<boolean>>,
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
		} else if (res.statusCode === 404 && res.error === 'PROV_003') {
			setNotFound(true);
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
	const [notFound, setNotFound] = useState(false);
	const [missingParams, setMissingParams] = useState(false);

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
		const redirectUri = params.get('redirect_uri');
		// const clientId = params.get('client_id');

		// redirect_uri 는 초입에만 URL 에 실려오므로 진입 시 localStorage 에 저장
		// (이후 sub-route 이동 시 URL 에서 사라져도 헤더/홈 버튼이 참조 가능)
		if (redirectUri) saveRedirectUri(redirectUri);

		setNotFound(false);

		// redirect_uri, client_id, uuid 중 하나라도 없으면 잘못된 접근 — 일시 비활성화
		// if (!redirectUri || !clientId || !uuid) {
		// 	setMissingParams(true);
		// 	return;
		// }

		if (memberType === 'member') {
			const id = mbrNo || uuid || loadUserId('member') || stored.member.mbrNo;
			if (id) fetchMemberInfo(id, setMember, setLoading, setNotFound);
		} else if (memberType === 'business') {
			const id = entMbrNo || uuid || loadUserId('business') || stored.business.entMbrNo;
			if (id) fetchEnterpriseInfo(id, setBusiness, setLoading, setNotFound);
		}
	// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [memberType, search, pathname]);

	const ctxValue = useMemo(
		() => ({ member, business, updateMember, updateBusiness }),
		[member, business, updateMember, updateBusiness],
	);

	const handleMissingParamsClose = useCallback((): void => {
		setMissingParams(false);
		window.history.back();
	}, []);

	const handleNotFoundConfirm = useCallback((): void => {
		setNotFound(false);
		const redirectUri =
			new URLSearchParams(search).get('redirect_uri') || loadRedirectUri();
		if (redirectUri) {
			try {
				const { origin } = new URL(redirectUri);
				window.location.href = `${origin}/`;
			} catch {
				// invalid URL — ignore
			}
		}
	}, [search]);

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
				<KrdsModal
					id="modal_member_not_found"
					isOpen={notFound}
					onClose={handleNotFoundConfirm}
					topText=""
					title="안내"
					size="small"
					buttons={[
						{
							label: '확인',
							variant: 'primary',
							onClick: handleNotFoundConfirm,
						},
					]}
				>
					<p>
						회원 정보를 찾을 수 없습니다.
						<br />
						가입 여부를 다시 한 번 확인해 주시거나,
						<br />
						문제가 계속되면 고객센터로 문의해 주세요.
					</p>
				</KrdsModal>
			</MypageContext.Provider>
			<KrdsModal
				id="modal_missing_params"
				isOpen={missingParams}
				onClose={handleMissingParamsClose}
				topText="접근 오류"
				title="잘못된 접근입니다."
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: handleMissingParamsClose,
					},
				]}
			>
				<p>
					마이페이지는 대상 시스템을 통해 접근해주세요.
				</p>
			</KrdsModal>
		</InfoStoreContext.Provider>
	);
}

export default MypageLayout;
