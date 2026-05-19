import getClients from 'api/ext/clients';
import { addAffiliation, addMemberAffiliation } from 'api/provision/affiliations';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import Spinner from 'components/Spinner';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { ChangeEvent, useCallback, useEffect, useMemo, useState } from 'react';
import type { BusinessType, Client, ClientGroup } from 'types/api/ext/clients';

import { getMypageRoute } from './routes';
import { loadUserId, useInfoStore } from './useInfoStore';

function AffiliationAddStep2(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const { member, business } = useInfoStore();
	const [isListModalOpen, setIsListModalOpen] = useState(false);
	const [isCompletedModalOpen, setIsCompletedModalOpen] = useState(false);
	const [isErrorModalOpen, setIsErrorModalOpen] = useState(false);
	const [isAlertOpen, setIsAlertOpen] = useState(false);
	const [submitting, setSubmitting] = useState(false);
	const [filterInst, setFilterInst] = useState('');
	const [filterClass, setFilterClass] = useState('');
	const [searchKeyword, setSearchKeyword] = useState('');
	const affiliationRoute = getMypageRoute(memberType, 'AFFILIATION');
	const withdrawRoute = getMypageRoute(memberType, 'AFFILIATION_WITHDRAW_STEP1');

	// API 데이터
	const [allClients, setAllClients] = useState<Client[]>([]);
	const [groups, setGroups] = useState<ClientGroup[]>([]);
	const [bizTypes, setBizTypes] = useState<BusinessType[]>([]);
	const [loading, setLoading] = useState(true);

	// §7.3 클라이언트 목록 조회
	useEffect(() => {
		(async (): Promise<void> => {
			setLoading(true);
			const res = await getClients();
			if (res.statusCode === 200 && res.payload) {
				const raw = res.payload.data;
				setAllClients(raw?.clients ?? []);
				setGroups(raw?.groups ?? []);
				setBizTypes(raw?.businessTypes ?? []);
			}
			setLoading(false);
			setIsListModalOpen(true);
		})();
	}, []);

	// 이미 등록된 서비스 ID 목록 (useInfoStore 에서 가져옴, clientId 우선 → clientNm fallback)
	const activeClientIds = useMemo(() => {
		const clients = isBusiness ? business.clients : member.clients;
		return clients
			.filter((c) => c.useYn === 'Y')
			.map((c) => c.clientId ?? c.clientNm);
	}, [isBusiness, business.clients, member.clients]);

	const [checked, setChecked] = useState<Record<string, boolean>>({});

	// 필터링: groups + businessTypes + 검색어
	const filteredClients = useMemo(() => {
		const keyword = searchKeyword.trim().toLowerCase();
		return allClients.filter((c) => {
			if (filterInst && c.groups !== filterInst) return false;
			if (filterClass && c.businessTypes !== filterClass && c.businessTypes !== 'ALL') return false;
			if (keyword) {
				return (
					c.clientNm.toLowerCase().includes(keyword)
					|| (c.description ?? '').toLowerCase().includes(keyword)
				);
			}
			return true;
		});
	}, [allClients, filterInst, filterClass, searchKeyword]);

	const isActive = useCallback(
		(c: Client): boolean =>
			activeClientIds.includes(c.ssoClientId) || activeClientIds.includes(c.clientNm),
		[activeClientIds],
	);

	// 이미 등록된 항목은 select-all 대상에서 제외
	const selectableClients = useMemo(
		() => filteredClients.filter((c) => !isActive(c)),
		[filteredClients, isActive],
	);

	const allChecked =
		selectableClients.length > 0
		&& selectableClients.every((c) => checked[c.ssoClientId]);

	const newlyAdded = useMemo(
		() => allClients.filter(
			(c) => checked[c.ssoClientId] && !isActive(c),
		),
		[allClients, checked, isActive],
	);

	const handleCheck = (id: string) => (e: ChangeEvent<HTMLInputElement>): void => {
		setChecked((prev) => ({ ...prev, [id]: e.target.checked }));
	};

	const handleAllCheck = (e: ChangeEvent<HTMLInputElement>): void => {
		const val = e.target.checked;
		setChecked((prev) => {
			const next = { ...prev };
			selectableClients.forEach((c) => { next[c.ssoClientId] = val; });
			return next;
		});
	};

	const handleAdd = useCallback(async (): Promise<void> => {
		if (newlyAdded.length === 0) {
			setIsAlertOpen(true);
			return;
		}

		const uuid = loadUserId(isBusiness ? 'business' : 'member');
		if (!uuid) {
			setIsErrorModalOpen(true);
			return;
		}

		setSubmitting(true);
		const clientIds = newlyAdded.map((c) => c.ssoClientId);
		const res = isBusiness
			? await addAffiliation(uuid, clientIds)
			: await addMemberAffiliation(uuid, clientIds);
		setSubmitting(false);
		setIsListModalOpen(false);

		if (res.statusCode === 200) {
			setIsCompletedModalOpen(true);
		} else {
			setIsErrorModalOpen(true);
		}
	}, [newlyAdded, isBusiness]);

	const handleClose = (): void => {
		setIsListModalOpen(false);
		history.push(affiliationRoute);
	};

	const handleWithdraw = (): void => {
		history.push(withdrawRoute);
	};

	const handleAddAgain = (): void => {
		setChecked({});
		setSearchKeyword('');
		setFilterInst('');
		setFilterClass('');
		setIsListModalOpen(true);
	};

	// 이용중인 유관기관 (context 에서)
	const activeServices = useMemo(() => {
		const clients = isBusiness ? business.clients : member.clients;
		return clients.filter((c) => c.useYn === 'Y');
	}, [isBusiness, business.clients, member.clients]);

	if (loading) {
		return (
			<MypageContent>
				<Spinner tip="유관기관 목록을 불러오고 있습니다..." height="300px" />
			</MypageContent>
		);
	}

	// groups 중복 제거 (name 기준)
	const uniqueGroups = groups.reduce<ClientGroup[]>((acc, g) => {
		if (!acc.find((a) => a.name === g.name)) acc.push(g);
		return acc;
	}, []);

	return (
		<MypageContent>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>
								추가하기 버튼을 클릭하시면 중기원패스 통합회원을 이용하실 수
								있는 유관기관 항목을 보실 수 있습니다.
							</p>
						</li>
						<li>
							<p>
								이용중인 유관기관을 선택 후 회원탈퇴를 선택하시면 해당 유관기관을
								탈퇴하실 수 있습니다.
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
				onSubmit={(e): void => e.preventDefault()}
				aria-label="유관기관 서비스 관리"
			>
				<div className="white-wrap">
					<div className="h3-title-box">
						<h3 className="h3-title">이용중인 유관기관</h3>
						<button
							type="button"
							className="btn add-btn purple"
							onClick={handleAddAgain}
						>
							<span>유관기관 추가</span>
							<i className="icon ico-add" aria-hidden="true" />
						</button>
					</div>
					<div className="form-wrap">
						<div className="check-box-wrap scroll" role="group" aria-label="이용중인 유관기관 목록">
							{activeServices.map((svc) => (
								<label key={svc.clientId ?? svc.clientNm} className="check-box style3">
									<input
										type="checkbox"
										id={`active_${svc.clientId ?? svc.clientNm}`}
										name="active_check"
										disabled
									/>
									<div className="text-box">
										<strong className="tit">{svc.clientNm}</strong>
										<p className="text">{svc.description ?? svc.clientNm}</p>
									</div>
								</label>
							))}
						</div>
					</div>
				</div>
				<div className="btn-box">
					<button type="button" className="btn point" onClick={handleWithdraw}>
						<span>유관기관 탈퇴</span>
						<i className="icon ico-exit-to-app small" aria-hidden="true" />
					</button>
				</div>
			</form>

			<Modal
				id="modal_service_list"
				isOpen={isListModalOpen}
				onClose={handleClose}
				topText=""
				title="유관기관 서비스 목록"
				buttons={[
					{
						label: submitting ? '처리 중...' : '유관기관 추가',
						variant: 'primary',
						size: 'large',
						half: true,
						onClick: handleAdd,
					},
				]}
				contentsClassName="form-wrap"
			>
				<div className="input-flex-box">
					<label className="input-box">
						<select
							name="select1"
							value={filterInst}
							onChange={(e): void => setFilterInst(e.target.value)}
							aria-label="기관 선택"
						>
							<option value="">기관 전체</option>
							{uniqueGroups.map((g) => (
								<option key={g.key} value={g.key}>{g.name}</option>
							))}
						</select>
						<button type="button" className="btn large icon">
							<span className="hidden">선택창 열기</span>
							<i className="icon arrow-bottom" aria-hidden="true" />
						</button>
					</label>
					<label className="input-box">
						<select
							name="select2"
							value={filterClass}
							onChange={(e): void => setFilterClass(e.target.value)}
							aria-label="사업유형 선택"
						>
							<option value="">사업유형 전체</option>
							{bizTypes.map((b) => (
								<option key={b.key} value={b.key}>{b.name}</option>
							))}
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
							value={searchKeyword}
							onChange={(e): void => setSearchKeyword(e.target.value)}
							aria-label="검색어 입력"
						/>
						<button type="button" className="btn large icon">
							<span className="hidden">검색</span>
							<i className="icon search" aria-hidden="true" />
						</button>
					</label>
				</div>
				<div className="check-box-wrap column scroll" role="group" aria-label="유관기관 서비스 목록">
					{filteredClients.length === 0 && (
						<p className="empty-result">검색 결과가 없습니다.</p>
					)}
					{filteredClients.map((c) => {
						const alreadyRegistered = isActive(c);
						return (
							<label key={c.ssoClientId} className="check-box style3">
								<input
									type="checkbox"
									id={`add_${c.ssoClientId}`}
									name="check"
									checked={alreadyRegistered || !!checked[c.ssoClientId]}
									disabled={alreadyRegistered}
									onChange={alreadyRegistered ? undefined : handleCheck(c.ssoClientId)}
								/>
								<div className="text-box">
									<strong className="tit">{c.clientNm}</strong>
									<p className="text">{c.description ?? ''}</p>
								</div>
							</label>
						);
					})}
				</div>
				<label className="check-box style1 all">
					<input
						type="checkbox"
						id="all_check"
						name="all_check"
						checked={allChecked}
						onChange={handleAllCheck}
					/>
					<small>모두 선택합니다.</small>
				</label>
			</Modal>

			<Modal
				id="modal_completed"
				isOpen={isCompletedModalOpen}
				onClose={(): void => history.push(affiliationRoute)}
				topText=""
				title="유관기관 서비스 관리"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						size: 'large',
						full: true,
						onClick: (): void => history.push(affiliationRoute),
					},
				]}
			>
				<div className="completed-box">
					<figure className="img">
						<img
							src={IMAGES.RENEWAL_LIST_COMPLETED_IMG_MODAL}
							alt=""
							aria-hidden="true"
						/>
					</figure>
					<p className="completed-title">
						선택하신 유관기관 서비스가 정상적으로 추가되었습니다
					</p>
					<ul className="text-list-wrap dots">
						{newlyAdded.map((c) => (
							<li key={c.ssoClientId}>
								<p>{c.clientNm}</p>
							</li>
						))}
					</ul>
				</div>
			</Modal>

			<Modal
				id="modal_alert_select"
				isOpen={isAlertOpen}
				onClose={(): void => setIsAlertOpen(false)}
				topText=""
				title="알림"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setIsAlertOpen(false),
					},
				]}
			>
				<p>추가할 유관기관을 선택해 주세요.</p>
			</Modal>

			<Modal
				id="modal_add_error"
				isOpen={isErrorModalOpen}
				onClose={(): void => setIsErrorModalOpen(false)}
				topText="안내"
				title="유관기관 추가 실패"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setIsErrorModalOpen(false),
					},
				]}
			>
				<p>유관기관 추가 처리 중 오류가 발생하였습니다.</p>
				<p>잠시 후 다시 시도해 주시기 바랍니다.</p>
			</Modal>
		</MypageContent>
	);
}

export default AffiliationAddStep2;
