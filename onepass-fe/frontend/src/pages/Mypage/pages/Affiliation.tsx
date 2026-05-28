import getClients from 'api/ext/clients';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import Spinner from 'components/Spinner';
import IMAGES from 'constants/images';
import history from 'lib/history';
import {
	ChangeEvent,
	FormEvent,
	useCallback,
	useEffect,
	useMemo,
	useState,
} from 'react';
import type { BusinessType, Client, ClientGroup } from 'types/api/ext/clients';
import type { MemberClient } from 'types/api/ext/members';

import { saveSelectedServices } from './affiliationServices';
import { getMypageRoute } from './routes';
import { useInfoStore } from './useInfoStore';

const PAGE_SIZE = 10;

function Affiliation(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const { member, business } = useInfoStore();

	// /api/v1/ext/clients — 전체 유관기관 + 그룹/사업유형 옵션
	const [allClients, setAllClients] = useState<Client[]>([]);
	const [groups, setGroups] = useState<ClientGroup[]>([]);
	const [bizTypes, setBizTypes] = useState<BusinessType[]>([]);
	const [loading, setLoading] = useState(true);

	useEffect(() => {
		(async (): Promise<void> => {
			setLoading(true);
			const res = await getClients();
			if (res.statusCode === 200 && res.payload) {
				const raw = res.payload.data;
				// 회원유형 필터 — business: ALL/ENT, member: ALL/IND
				const fixedBizType = isBusiness ? 'ENT' : 'IND';
				const filteredByType = (raw?.clients ?? []).filter(
					(c) =>
						c.businessTypes != null
						&& (c.businessTypes === fixedBizType || c.businessTypes === 'ALL'),
				);
				setAllClients(filteredByType);
				setGroups(raw?.groups ?? []);
				setBizTypes(raw?.businessTypes ?? []);
			}
			setLoading(false);
		})();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [isBusiness]);

	// /api/v1/ext/members/~ 응답의 data.clients[] — clientId / clientNm 인덱스
	// 매칭 + clientId 있음 → 계정 연결(plus), 그 외 → 계정 미연결(no)
	const memberClientIndex = useMemo(() => {
		const stored = isBusiness ? business.clients : member.clients;
		const byId = new Map<string, MemberClient>();
		const byName = new Map<string, MemberClient>();
		stored.forEach((c) => {
			if (c.clientId) byId.set(c.clientId, c);
			byName.set(c.clientNm, c);
		});
		return { byId, byName };
	}, [isBusiness, business.clients, member.clients]);

	type ConnState = 'plus' | 'no';
	const getConnState = useCallback(
		(c: Client): ConnState => {
			const matched =
				memberClientIndex.byId.get(c.ssoClientId) ??
				memberClientIndex.byName.get(c.clientNm);
			if (!matched) return 'no';
			// useYn === 'Y' 면 계정 연결(plus), 'N' 또는 그 외는 미연결(no)
			return matched.useYn === 'Y' ? 'plus' : 'no';
		},
		[memberClientIndex],
	);

	const [checked, setChecked] = useState<Record<string, boolean>>({});
	const [alertMsg, setAlertMsg] = useState<string | null>(null);
	const [searchText, setSearchText] = useState('');
	const [filterGroup, setFilterGroup] = useState('');
	// 계정 상태 필터: '' = 전체 / 'plus' = 계정 연결 / 'no' = 계정 미연결
	const [filterConn, setFilterConn] = useState<'' | 'plus' | 'no'>('');
	const [page, setPage] = useState(1);

	// 그룹/계정 상태/검색어 필터
	const filtered = useMemo(() => {
		const q = searchText.trim().toLowerCase();
		return allClients.filter((c) => {
			if (filterGroup && c.groups !== filterGroup) return false;
			if (filterConn && getConnState(c) !== filterConn) return false;
			if (!q) return true;
			return (
				c.clientNm.toLowerCase().includes(q) ||
				(c.description ?? '').toLowerCase().includes(q)
			);
		});
	}, [allClients, filterGroup, filterConn, searchText, getConnState]);

	const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
	const currentPage = Math.min(page, totalPages);
	const pageItems = useMemo(
		() => filtered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE),
		[filtered, currentPage],
	);

	const handleItemChange = (id: string) => (
		e: ChangeEvent<HTMLInputElement>,
	): void => {
		setChecked((prev) => ({ ...prev, [id]: e.target.checked }));
	};

	// 선택된 모든 항목이 계정 연결(account-plus) 상태일 때만 → 해제 페이지로 전달.
	// 계정 미연결(account-no) 항목이 섞여 있으면 모달 안내 후 중단.
	const handleWithdraw = (): void => {
		const selectedClients = allClients.filter((c) => checked[c.ssoClientId]);
		if (selectedClients.length === 0) {
			setAlertMsg('계정 연결 해제할 유관기관(계정 연결 상태)을 선택해 주세요.');
			return;
		}
		const hasUnconnected = selectedClients.some((c) => getConnState(c) !== 'plus');
		if (hasUnconnected) {
			setAlertMsg(
				'계정 미연결 상태의 유관기관이 포함되어 있습니다.\n계정 연결 상태의 유관기관만 선택해 주세요.',
			);
			return;
		}
		saveSelectedServices(selectedClients.map((c) => c.ssoClientId));
		history.push(getMypageRoute(memberType, 'AFFILIATION_WITHDRAW_STEP1'));
	};

	// 선택된 모든 항목이 계정 미연결(account-no) 상태일 때만 → 인증 페이지로 이동(STEP1).
	// 계정 연결(account-plus) 항목이 섞여 있으면 모달 안내 후 중단.
	const handleAdd = (): void => {
		const selectedClients = allClients.filter((c) => checked[c.ssoClientId]);
		if (selectedClients.length === 0) {
			setAlertMsg('계정 연결 추가할 유관기관(계정 미연결 상태)을 선택해 주세요.');
			return;
		}
		const hasConnected = selectedClients.some((c) => getConnState(c) !== 'no');
		if (hasConnected) {
			setAlertMsg(
				'계정 연결 상태의 유관기관이 포함되어 있습니다.\n계정 미연결 상태의 유관기관만 선택해 주세요.',
			);
			return;
		}
		saveSelectedServices(selectedClients.map((c) => c.ssoClientId));
		history.push(getMypageRoute(memberType, 'AFFILIATION_ADD_STEP1'));
	};

	const handleSearchChange = (e: ChangeEvent<HTMLInputElement>): void => {
		setSearchText(e.target.value);
		setPage(1);
	};

	const handleGroupChange = (e: ChangeEvent<HTMLSelectElement>): void => {
		setFilterGroup(e.target.value);
		setPage(1);
	};

	const handleConnChange = (e: ChangeEvent<HTMLSelectElement>): void => {
		const v = e.target.value;
		setFilterConn(v === 'plus' || v === 'no' ? v : '');
		setPage(1);
	};

	const handleSearchSubmit = (e: FormEvent): void => {
		e.preventDefault();
		setPage(1);
	};

	return (
		<MypageContent>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>통합회원으로 로그인을 한번에 진행할 수 있는 유관기관 목록입니다</p>
						</li>
						<li>
							<p>유관기관을 클릭 시 해당 사이트로 자동 이동합니다</p>
						</li>
						<li>
							<p>
								연결 계정 버튼을 클릭 시 계정 연결, 계정 연결 중단을 하실 수
								있습니다
							</p>
						</li>
					</ul>
					<figure className="img">
						<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
			</div>
			<form
				className="form-container"
				onSubmit={handleSearchSubmit}
				aria-label="유관기관 목록"
			>
				<div className="white-wrap">
					<div className="input-flex-box search-box-wrap">
						<label className="input-box">
							<select
								name="filter_group"
								value={filterGroup}
								onChange={handleGroupChange}
								aria-label="기관 그룹 필터"
							>
								<option value="">기관 전체</option>
								{groups
									.filter((g) => g.key !== 'ALL')
									.map((g) => (
										<option key={g.key} value={g.key}>
											{g.name}
										</option>
									))}
							</select>
							<button type="button" className="btn large icon">
								<span className="hidden">선택창 열기</span>
								<i className="icon arrow-bottom" aria-hidden="true" />
							</button>
						</label>
						<label className="input-box">
							<select
								name="filter_conn"
								value={filterConn}
								onChange={handleConnChange}
								aria-label="계정 연결 상태 필터"
							>
								<option value="">계정 상태 전체</option>
								<option value="plus">계정 연결</option>
								<option value="no">계정 미연결</option>
							</select>
							<button type="button" className="btn large icon">
								<span className="hidden">선택창 열기</span>
								<i className="icon arrow-bottom" aria-hidden="true" />
							</button>
						</label>
						<label className="input-box">
							<input
								type="text"
								name="search_text"
								placeholder="검색어를 입력해주세요"
								value={searchText}
								onChange={handleSearchChange}
								aria-label="유관기관 검색"
							/>
							<button type="submit" className="btn large icon" aria-label="검색">
								<span className="hidden">검색</span>
								<i className="icon ico-search" aria-hidden="true" />
							</button>
						</label>
					</div>
					{loading ? (
						<Spinner tip="유관기관 목록을 불러오고 있습니다..." height="300px" />
					) : (
						<div className="check-box-wrap" role="group" aria-label="유관기관 목록">
							{pageItems.length === 0 ? (
								<p className="empty-text">조회된 유관기관이 없습니다.</p>
							) : (
								pageItems.map((c) => {
									const state = getConnState(c);
									return (
										<div key={c.ssoClientId} className="check-box style3">
											<label className="check-box style1 small">
												<input
													type="checkbox"
													id={c.ssoClientId}
													name="check"
													checked={!!checked[c.ssoClientId]}
													onChange={handleItemChange(c.ssoClientId)}
													aria-label={`${c.clientNm} 선택`}
												/>
											</label>
											{c.logo && (
												<figure className="img">
													<img src={c.logo} alt={c.clientNm} />
												</figure>
											)}
											<div className="text-box">
												<strong className="tit">{c.clientNm}</strong>
												<p className="text">{c.description ?? ''}</p>
											</div>
											{state === 'plus' ? (
												<span className="account-status account-plus">
													<i className="icon ico-attach-file-add" aria-hidden="true" />
													<span>계정 연결</span>
												</span>
											) : (
												<span className="account-status account-no">
													<i className="icon ico-attach-file-off" aria-hidden="true" />
													<span>계정 미연결</span>
												</span>
											)}
										</div>
									);
								})
							)}
						</div>
					)}
				</div>
				<div className="btn-box">
					<button type="button" className="btn white prev" onClick={handleWithdraw}>
						<span>계정 연결 해제</span>
						<i className="icon ico-attach-file-off small" aria-hidden="true" />
					</button>
					<button type="button" className="btn point" onClick={handleAdd}>
						<span>계정 연결 추가</span>
						<i className="icon ico-attach-file-add small" aria-hidden="true" />
					</button>
				</div>
				{totalPages > 1 && (
					<nav className="page-wrap" aria-label="페이지 이동">
						<ul>
							<li className={`arrow ${currentPage === 1 ? 'disabled' : ''}`}>
								<a
									href="#prev"
									onClick={(e): void => {
										e.preventDefault();
										if (currentPage > 1) setPage(currentPage - 1);
									}}
								>
									<i className="icon ico-page-prev" aria-hidden="true" />
									<span>이전</span>
								</a>
							</li>
							{Array.from({ length: totalPages }, (_, i) => i + 1).map((n) => (
								<li key={n} className={n === currentPage ? 'now' : ''}>
									<a
										href={`#p${n}`}
										onClick={(e): void => {
											e.preventDefault();
											setPage(n);
										}}
									>
										<span>{n}</span>
									</a>
								</li>
							))}
							<li className={`arrow ${currentPage === totalPages ? 'disabled' : ''}`}>
								<a
									href="#next"
									onClick={(e): void => {
										e.preventDefault();
										if (currentPage < totalPages) setPage(currentPage + 1);
									}}
								>
									<span>다음</span>
									<i className="icon ico-page-next" aria-hidden="true" />
								</a>
							</li>
						</ul>
					</nav>
				)}
			</form>

			<Modal
				id="modal_alert_select"
				isOpen={alertMsg !== null}
				onClose={(): void => setAlertMsg(null)}
				topText=""
				title="알림"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setAlertMsg(null),
					},
				]}
			>
				<p style={{ whiteSpace: 'pre-line' }}>{alertMsg}</p>
			</Modal>
		</MypageContent>
	);
}

export default Affiliation;
