import Modal from 'components/KrdsModal';
import { useConversion } from 'providers/Conversion/ConversionContext';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { BusinessType, Client, PerAgency } from 'types/api/ext/clients';

/* ── 서비스 상태 헬퍼 ── */

/** 체크(선택) 가능 여부: READY/STUB + 미가입 항목만 true */
export function isCheckable(client: Client): boolean {
	const available = ['READY', 'STUB'].includes(client.serviceStatus);
	const registered =
		'registered' in client && (client as PerAgency).registered === true;
	return available && !registered;
}

/** 상태 칩 정보. 정상(READY/STUB + 미가입 + 조회성공/미등록)이면 null 반환 */
function getStatusChip(
	client: Client,
): { label: string; className: string } | null {
	// 1단계: registered — 기등록
	if ('registered' in client && (client as PerAgency).registered) {
		return { label: '계정연결', className: 'badge linked' };
	}
	// 2단계: serviceStatus — 송신 불가
	switch (client.serviceStatus) {
		case 'DOWN':
			return { label: '서비스중단', className: 'badge down' };
		case 'CB_OPEN':
			return { label: '일시중단', className: 'badge cb-open' };
		case 'EP_NOT_CONFIGURED':
			return { label: '미연동', className: 'badge not-configured' };
		default:
			break;
	}
	// 3단계: queryStatus — "알 수 없음" 안내 (체크 가능 항목에 한해)
	if ('queryStatus' in client) {
		const qs = (client as PerAgency).queryStatus;
		switch (qs) {
			case 'ERROR':
				return { label: '조회실패', className: 'badge query-error' };
			case 'TIMEOUT':
				return { label: '응답지연', className: 'badge query-timeout' };
			case 'CB_BLOCKED':
				return { label: '일시중단', className: 'badge cb-open' };
			default:
				// FOUND, NOT_FOUND → 칩 없음
				return null;
		}
	}
	return null;
}

interface ServiceListModalProps {
	isOpen: boolean;
	onClose: () => void;
	clients: Client[];
	groupMap: Map<string, string>;
	businessTypes: BusinessType[];
}

function ServiceListModal({
	isOpen,
	onClose,
	clients,
	groupMap,
	businessTypes,
}: ServiceListModalProps): JSX.Element {
	const { data, updateData } = useConversion();
	const [checked, setChecked] = useState<Record<string, boolean>>({});
	const [allChecked, setAllChecked] = useState(false);
	const [filterInst, setFilterInst] = useState('');
	const [filterBizType, setFilterBizType] = useState('');
	const [searchKeyword, setSearchKeyword] = useState('');

	// 방어 — API 응답 shape 가 예상과 달라 array 가 아닐 경우 빈 배열로 폴백
	const safeClients = useMemo(() => (Array.isArray(clients) ? clients : []), [
		clients,
	]);

	// 기관명 고유값 목록 (groups 배열에서 중복 기관명 제거)
	const instNames = useMemo(() => {
		const names = Array.from(
			new Set(
				safeClients
					.map((c) => (c.groups ? groupMap.get(c.groups) : undefined))
					.filter((n): n is string => !!n),
			),
		);
		names.sort();
		return names;
	}, [safeClients, groupMap]);

	// 필터링된 클라이언트 목록
	const filteredClients = useMemo(() => {
		let result = safeClients;
		if (filterInst) {
			result = result.filter(
				(c) => c.groups && groupMap.get(c.groups) === filterInst,
			);
		}
		if (filterBizType && filterBizType !== 'ALL') {
			result = result.filter(
				(c) =>
					c.businessTypes != null &&
					(c.businessTypes === filterBizType || c.businessTypes === 'ALL'),
			);
		}
		if (searchKeyword.trim()) {
			const keyword = searchKeyword.trim().toLowerCase();
			result = result.filter(
				(c) =>
					c.clientNm.toLowerCase().includes(keyword) ||
					(c.description ?? '').toLowerCase().includes(keyword),
			);
		}
		return result;
	}, [safeClients, filterInst, filterBizType, searchKeyword, groupMap]);

	// 필터 결과 중 체크 가능한 항목만
	const checkableFiltered = useMemo(
		() => filteredClients.filter(isCheckable),
		[filteredClients],
	);

	// 모달 열릴 때 Context에서 선택 상태 복원 + 필터 초기화
	useEffect(() => {
		if (!isOpen) return;
		const restored: Record<string, boolean> = {};
		data.selectedClients.forEach((id) => {
			restored[id] = true;
		});
		setChecked(restored);
		setAllChecked(
			checkableFiltered.length > 0 &&
				checkableFiltered.every((c) => restored[c.ssoClientId]),
		);
		setFilterInst('');
		setFilterBizType('');
		setSearchKeyword('');
	}, [isOpen, data.selectedClients, safeClients]);

	// 필터된 목록 기준 전체 선택 상태 갱신 (체크 가능 항목만)
	useEffect(() => {
		if (checkableFiltered.length === 0) {
			setAllChecked(false);
		} else {
			setAllChecked(checkableFiltered.every((c) => checked[c.ssoClientId]));
		}
	}, [checkableFiltered, checked]);

	const handleCheck = useCallback(
		(ssoClientId: string) => {
			const target = safeClients.find((c) => c.ssoClientId === ssoClientId);
			if (target && !isCheckable(target)) return;
			setChecked((prev) => ({ ...prev, [ssoClientId]: !prev[ssoClientId] }));
		},
		[safeClients],
	);

	const handleAllCheck = useCallback(() => {
		const next = !allChecked;
		setChecked((prev) => {
			const updated = { ...prev };
			checkableFiltered.forEach((c) => {
				updated[c.ssoClientId] = next;
			});
			return updated;
		});
	}, [allChecked, checkableFiltered]);

	const handleReset = useCallback(() => {
		setChecked({});
		setAllChecked(false);
		setFilterInst('');
		setFilterBizType('');
		setSearchKeyword('');
	}, []);

	const handleApply = useCallback(() => {
		const selectedIds = Object.entries(checked)
			.filter(([, v]) => v)
			.map(([k]) => k);
		updateData({ selectedClients: selectedIds });
		onClose();
	}, [checked, updateData, onClose]);

	return (
		<Modal
			id="modal_service_list"
			isOpen={isOpen}
			onClose={onClose}
			topText=""
			title="중소벤처기업부 유관시스템 서비스 목록"
			buttons={[
				{
					label: '초기화',
					variant: 'tertiary',
					size: 'large',
					full: true,
					onClick: handleReset,
				},
				{
					label: '적용하기',
					variant: 'primary',
					size: 'large',
					full: true,
					onClick: handleApply,
				},
			]}
			contentsClassName="form-wrap"
		>
			<div className="input-flex-box">
				<label className="input-box">
					<select
						name="select_inst"
						value={filterInst}
						onChange={(e): void => setFilterInst(e.target.value)}
						aria-label="기관 선택"
					>
						<option value="">기관 전체</option>
						{instNames.map((name) => (
							<option key={name} value={name}>
								{name}
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
						name="select_biz_type"
						value={filterBizType}
						onChange={(e): void => setFilterBizType(e.target.value)}
						aria-label="사업유형 선택"
					>
						<option value="">사업유형</option>
						{businessTypes.map((bt) => (
							<option key={bt.key} value={bt.key}>
								{bt.name}
							</option>
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
			<div className="check-box-wrap scroll" role="group" aria-label="서비스 목록">
				{filteredClients.length === 0 && (
					<p className="empty-result">검색 결과가 없습니다.</p>
				)}
				{filteredClients.map((client) => {
					const checkable = isCheckable(client);
					const chip = getStatusChip(client);

					return (
						<label
							key={client.ssoClientId}
							className={`check-box style3${checkable ? '' : ' disabled'}`}
						>
							<input
								type="checkbox"
								id={`modal_client_${client.ssoClientId}`}
								name="check"
								checked={!!checked[client.ssoClientId]}
								disabled={!checkable}
								onChange={(): void => handleCheck(client.ssoClientId)}
							/>
							<div className="text-box">
								<strong className="tit">
									{client.clientNm}
									{chip && <span className={chip.className}>{chip.label}</span>}
								</strong>
								<p className="text">{client.description}</p>
								<p
									className="text"
									style={{ fontSize: '12px', color: '#888', marginTop: '2px' }}
								>
									{client.groups ? groupMap.get(client.groups) ?? '' : ''}
								</p>
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
				<small>
					모두 선택합니다.{' '}
					<span style={{ fontSize: '12px', color: '#888' }}>
						(연동 가능 항목만 선택)
					</span>
				</small>
			</label>
		</Modal>
	);
}

export default ServiceListModal;
