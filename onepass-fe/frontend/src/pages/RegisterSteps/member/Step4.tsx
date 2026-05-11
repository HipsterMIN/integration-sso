import getClients from 'api/ext/clients';
import checkConversionProxy from 'api/provision/checkConversion';
import RegisterLayout from 'components/RegisterLayout';
import Spinner from 'components/Spinner';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useRegister } from 'providers/Register/RegisterContext';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { BusinessType, Client, ClientGroup } from 'types/api/ext/clients';

import { getRegisterRoute } from '../routes';
import ServiceListModal, { isCheckable } from './components/ServiceListModal';

interface Step4Props {
	memberType?: MemberType;
	currentStep?: number;
}

interface FetchResult {
	clientList: Client[];
	groups: ClientGroup[];
	bizTypes: BusinessType[];
}

async function fetchForMember(
	ciToken?: string,
	mbrUuid?: string,
): Promise<FetchResult | null> {
	if (!ciToken) return null;
	const response = await checkConversionProxy({
		ciToken,
		mbrId: '',
		mbrUuid,
	});
	if (response.statusCode === 200 && response.payload) {
		const raw = response.payload.data;
		return {
			clientList: raw?.perAgency ?? [],
			groups: raw?.groups ?? [],
			bizTypes: raw?.businessTypes ?? [],
		};
	}
	return null;
}

async function fetchForBusiness(): Promise<FetchResult | null> {
	const response = await getClients();
	if (response.statusCode === 200 && response.payload) {
		const raw = response.payload.data;
		return {
			clientList: Array.isArray(raw) ? raw : raw?.clients ?? [],
			groups: raw?.groups ?? [],
			bizTypes: raw?.businessTypes ?? [],
		};
	}
	return null;
}

function RegisterStep4({
	memberType = 'member',
	currentStep = 4,
}: Step4Props): JSX.Element {
	const { data, updateData } = useRegister();
	const [clients, setClients] = useState<Client[]>([]);
	const [groupMap, setGroupMap] = useState<Map<string, string>>(new Map());
	const [businessTypes, setBusinessTypes] = useState<BusinessType[]>([]);
	const [serviceModal, setServiceModal] = useState(false);
	const [loading, setLoading] = useState(true);

	const isMember = memberType === 'member';

	const selectedCount = data.selectedClients.length;
	const checkableClients = useMemo(() => clients.filter(isCheckable), [clients]);
	const allSelected =
		checkableClients.length > 0 && selectedCount === checkableClients.length;

	const { initialClientId } = data;

	// 페이지 진입 시 클라이언트 목록 조회
	useEffect(() => {
		let cancelled = false;
		const fetchData = async (): Promise<void> => {
			setLoading(true);
			const result = isMember
				? await fetchForMember(
						data.ciToken || undefined,
						data.mbrUuid || undefined,
				  )
				: await fetchForBusiness();
			if (cancelled) return;
			if (result) {
				setClients(result.clientList);
				updateData({ availableClients: result.clientList });
				setGroupMap(new Map(result.groups.map((g) => [g.key, g.name])));
				setBusinessTypes(result.bizTypes);

				// initialClientId가 있으면 매칭되는 서비스를 기본 선택
				if (initialClientId) {
					const matchingClient = result.clientList
						.filter(isCheckable)
						.find((c) => c.ssoClientId === initialClientId);
					if (matchingClient) {
						updateData({ selectedClients: [matchingClient.ssoClientId] });
					}
				}
			}
			setLoading(false);
		};
		fetchData();
		return (): void => {
			cancelled = true;
		};
	}, [isMember, data.ciToken, data.mbrUuid, initialClientId]);

	// "모두 선택합니다" → 체크 가능 항목만 전체 선택/해제
	const handleSelectAll = useCallback(() => {
		if (allSelected) {
			updateData({ selectedClients: [] });
		} else {
			updateData({
				selectedClients: checkableClients.map((c) => c.ssoClientId),
			});
		}
	}, [allSelected, checkableClients, updateData]);

	return (
		<RegisterLayout
			currentStep={currentStep}
			skipRoute={getRegisterRoute(currentStep + 1, memberType)}
			nextRoute={getRegisterRoute(currentStep + 1, memberType)}
			memberType={memberType}
		>
			{loading ? (
				<Spinner tip="서비스 목록을 불러오고 있습니다..." height="300px" />
			) : (
				<>
					<div className="text-info-wrap point">
						<ul className="text-list-wrap check" aria-label="안내 사항">
							<li>
								<p>유관시스템 서비스를 하나의 통합 ID로 연결합니다</p>
							</li>
							<li>
								<p>
									등록을 원하지 않으실 경우 '건너뛰기'를 선택하여 가입을 완료하실 수 있습니다.
								</p>
							</li>
							<li>
								<p>
									추후 ( 마이페이지 &gt; 유과기관 서비스 관리 )에서 언제든지 추가 등록, 탈퇴할 수 있습니다
								</p>
							</li>
						</ul>
						<figure className="img">
							<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
						</figure>
					</div>
					<div
						className="all-agree-wrap"
						role="group"
						aria-label="유관시스템 서비스 선택"
					>
						<div className="all-box">
							<label className="check-box style1 medium">
								<input
									type="checkbox"
									id="agree_all"
									name="agree_all"
									checked={allSelected}
									onChange={handleSelectAll}
								/>
								<small>
									<strong>모두 선택합니다.</strong>
								</small>
							</label>
						</div>
						<ul className="agree-box">
							<li>
								<div className="agree-title-box">
									<div className="title">
										<strong>통합회원 유관시스템 서비스 목록</strong>
										<span className="badge point">
											+ {selectedCount > 0 ? selectedCount : checkableClients.length}
										</span>
									</div>
									<button
										type="button"
										className="arrow-btn btn large icon"
										onClick={(): void => setServiceModal(true)}
										aria-label="통합회원 유관시스템 서비스 목록 보기"
									>
										<i className="icon arrow-right" aria-hidden="true" />
										<span className="hidden">목록 보기</span>
									</button>
								</div>
							</li>
						</ul>
					</div>

					<ServiceListModal
						isOpen={serviceModal}
						onClose={(): void => setServiceModal(false)}
						clients={clients}
						groupMap={groupMap}
						businessTypes={businessTypes}
					/>
				</>
			)}
		</RegisterLayout>
	);
}

export default RegisterStep4;
