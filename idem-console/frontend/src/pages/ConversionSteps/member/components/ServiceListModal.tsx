import Modal from 'components/KrdsModal';
import { useConversion } from 'providers/Conversion/ConversionContext';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { BusinessType, Client } from 'types/api/ext/clients';

function getBizTypeLabel(clientBizType: string | null, bizTypes: BusinessType[]): string {
	if (!clientBizType) return '';
	if (clientBizType === 'ALL') {
		return bizTypes.filter((bt) => bt.key !== 'ALL').map((bt) => bt.name).join(', ');
	}
	return bizTypes.find((bt) => bt.key === clientBizType)?.name ?? '';
}

interface ServiceListModalProps {
	isOpen: boolean;
	onClose: () => void;
	clients: Client[];
	groupMap: Map<string, string>;
	businessTypes: BusinessType[];
	memberType: 'member' | 'business';
}

function ServiceListModal({
	isOpen,
	onClose,
	clients,
	groupMap,
	businessTypes,
	memberType,
}: ServiceListModalProps): JSX.Element {
	const { data, updateData } = useConversion();
	const [checked, setChecked] = useState<Record<string, boolean>>({});
	const [allChecked, setAllChecked] = useState(false);
	const [filterInst, setFilterInst] = useState('');
	const [searchKeyword, setSearchKeyword] = useState('');

	// memberType에 따라 사업유형 필터 고정
	const fixedBizType = useMemo(() => {
		if (memberType === 'member') {
			const found = businessTypes.find((bt) => bt.key !== 'ALL' && bt.name.includes('개인'));
			return found?.key || businessTypes.find((bt) => bt.key === 'INDIVIDUAL')?.key || '';
		}
		const found = businessTypes.find((bt) => bt.key !== 'ALL' && bt.name.includes('기업'));
		return found?.key || businessTypes.find((bt) => bt.key === 'CORPORATE')?.key || '';
	}, [memberType, businessTypes]);
	const [filterBizType, setFilterBizType] = useState('');

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
		if (fixedBizType && fixedBizType !== 'ALL') {
			result = result.filter(
				(c) =>
					c.businessTypes != null &&
					(c.businessTypes === fixedBizType || c.businessTypes === 'ALL'),
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
	}, [safeClients, filterInst, fixedBizType, searchKeyword, groupMap]);

	// 사업유형 필터 기준 클라이언트 목록 (전체선택 기준 — 검색어 무관)
	const bizTypeClients = useMemo(() => {
		if (!fixedBizType || fixedBizType === 'ALL') return safeClients;
		return safeClients.filter(
			(c) =>
				c.businessTypes != null &&
				(c.businessTypes === fixedBizType || c.businessTypes === 'ALL'),
		);
	}, [safeClients, fixedBizType]);

	// 모달 열릴 때 Context에서 선택 상태 복원 + 필터 초기화
	useEffect(() => {
		if (!isOpen) return;
		const restored: Record<string, boolean> = {};
		data.selectedClients.forEach((id) => {
			restored[id] = true;
		});
		setChecked(restored);
		setAllChecked(
			bizTypeClients.length > 0 &&
				bizTypeClients.every((c) => restored[c.ssoClientId]),
		);
		setFilterInst('');
		setFilterBizType(fixedBizType);
		setSearchKeyword('');
	}, [isOpen, data.selectedClients, safeClients, fixedBizType, bizTypeClients]);

	// 사업유형 필터 기준 전체 선택 상태 갱신 (검색어 무관)
	useEffect(() => {
		if (bizTypeClients.length === 0) {
			setAllChecked(false);
		} else {
			setAllChecked(bizTypeClients.every((c) => checked[c.ssoClientId]));
		}
	}, [bizTypeClients, checked]);

	const handleCheck = useCallback(
		(ssoClientId: string) => {
			setChecked((prev) => ({ ...prev, [ssoClientId]: !prev[ssoClientId] }));
		},
		[],
	);

	const handleAllCheck = useCallback(() => {
		const next = !allChecked;
		setChecked((prev) => {
			const updated = { ...prev };
			bizTypeClients.forEach((c) => {
				updated[c.ssoClientId] = next;
			});
			return updated;
		});
	}, [allChecked, bizTypeClients]);

	const handleReset = useCallback(() => {
		setChecked({});
		setAllChecked(false);
		setFilterInst('');
		setFilterBizType(fixedBizType);
		setSearchKeyword('');
	}, [fixedBizType]);

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
						disabled
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
					return (
						<label
							key={client.ssoClientId}
							className="check-box style3"
						>
							<input
								type="checkbox"
								id={`modal_client_${client.ssoClientId}`}
								name="check"
								checked={!!checked[client.ssoClientId]}
								onChange={(): void => handleCheck(client.ssoClientId)}
							/>
							<div className="text-box">
								<strong className="tit">
									{client.clientNm}
								</strong>
								<p className="text">{getBizTypeLabel(client.businessTypes, businessTypes)}</p>
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
				<small>모두 선택합니다.</small>
			</label>
		</Modal>
	);
}

export default ServiceListModal;
