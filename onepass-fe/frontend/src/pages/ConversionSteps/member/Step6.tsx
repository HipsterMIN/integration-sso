import checkConversionEnterprise from 'api/provision/checkConversionEnterprise';
import checkConversionProxy from 'api/provision/checkConversion';
import ConversionLayout from 'components/ConversionLayout';
import Spinner from 'components/Spinner';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useConversion } from 'providers/Conversion/ConversionContext';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { BusinessType, Client, ClientGroup } from 'types/api/ext/clients';

import { getConversionRoute } from '../routes';
import ServiceListModal from './components/ServiceListModal';

interface Step6Props {
	memberType?: MemberType;
	currentStep?: number;
}

interface FetchResult {
	clientList: Client[];
	groups: ClientGroup[];
	bizTypes: BusinessType[];
}

async function fetchForMember(
	mbrId: string,
	ciToken?: string,
	mbrUuid?: string,
): Promise<FetchResult | null> {
	if (!ciToken) return null;
	const response = await checkConversionProxy({
		ciToken,
		mbrId,
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

async function fetchForBusiness(brno?: string): Promise<FetchResult | null> {
	if (!brno) return null;
	const response = await checkConversionEnterprise({ brno });
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

function ConversionStep6({
	memberType = 'member',
	currentStep = 4,
}: Step6Props): JSX.Element {
	const { data, updateData } = useConversion();
	const [clients, setClients] = useState<Client[]>([]);
	const [groupMap, setGroupMap] = useState<Map<string, string>>(new Map());
	const [businessTypes, setBusinessTypes] = useState<BusinessType[]>([]);
	const [serviceModal, setServiceModal] = useState(false);
	const [loading, setLoading] = useState(true);

	const isMember = memberType === 'member';

	// memberType에 따라 사업유형 필터링된 클라이언트 목록
	const bizTypeClients = useMemo(() => {
		const fixedBizType = memberType === 'member'
			? businessTypes.find((bt) => bt.key !== 'ALL' && bt.name.includes('개인'))?.key
				|| businessTypes.find((bt) => bt.key === 'INDIVIDUAL')?.key || ''
			: businessTypes.find((bt) => bt.key !== 'ALL' && bt.name.includes('기업'))?.key
				|| businessTypes.find((bt) => bt.key === 'CORPORATE')?.key || '';
		if (!fixedBizType || fixedBizType === 'ALL') return clients;
		return clients.filter(
			(c) => c.businessTypes != null && (c.businessTypes === fixedBizType || c.businessTypes === 'ALL'),
		);
	}, [clients, memberType, businessTypes]);

	const selectedCount = data.selectedClients.length;
	const allSelected =
		bizTypeClients.length > 0 && selectedCount === bizTypeClients.length;
	const { initialClientId } = data;

	useEffect(() => {
		const selected = data.selectedClients
			.map((id) => clients.find((c) => c.ssoClientId === id))
			.filter((c): c is Client => c != null);
		// eslint-disable-next-line no-console
		console.log('[Conversion Step4] selected clients:', selected);
	}, [data.selectedClients, clients]);

	// 페이지 진입 시 클라이언트 목록 조회
	useEffect(() => {
		let cancelled = false;
		const fetchData = async (): Promise<void> => {
			setLoading(true);
			const result =
				isMember && data.mbrId
					? await fetchForMember(
							data.mbrId,
							data.ciToken || undefined,
							data.mbrUuid || undefined,
					  )
					: await fetchForBusiness(data.brno || undefined);
			if (cancelled) return;
			if (result) {
				setClients(result.clientList);
				updateData({ availableClients: result.clientList });
				setGroupMap(new Map(result.groups.map((g) => [g.key, g.name])));
				setBusinessTypes(result.bizTypes);

				// initialClientId가 있으면 매칭되는 서비스를 기본 선택
				if (initialClientId) {
					const matchingClient = result.clientList
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
	}, [isMember, data.mbrId, data.ciToken, data.mbrUuid, data.brno, initialClientId]);

	// "모두 선택합니다" → 체크 가능 항목만 전체 선택/해제
	const handleSelectAll = useCallback(() => {
		if (allSelected) {
			updateData({ selectedClients: [] });
		} else {
			updateData({
				selectedClients: bizTypeClients.map((c) => c.ssoClientId),
			});
		}
	}, [allSelected, bizTypeClients, updateData]);

	return (
		<ConversionLayout
			currentStep={currentStep}
			skipRoute={getConversionRoute(currentStep + 1, memberType)}
			nextRoute={getConversionRoute(currentStep + 1, memberType)}
			nextLabel="연결하기"
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
									등록을 원하지 않으실 경우 &apos;건너뛰기&apos;를 선택하여 가입을
									완료하실 수 있습니다.
								</p>
							</li>
							<li>
								<p>
									추후 ( 마이페이지 &gt; 유과기관 서비스 관리 )에서 언제든지 추가 등록,
									탈퇴할 수 있습니다
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
											{selectedCount}/{bizTypeClients.length}
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
						memberType={memberType}
					/>
				</>
			)}
		</ConversionLayout>
	);
}

export default ConversionStep6;
