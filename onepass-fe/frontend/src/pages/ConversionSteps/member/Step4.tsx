import getClients from 'api/ext/clients';
import ConversionLayout from 'components/ConversionLayout';
import Spinner from 'components/Spinner';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useConversion } from 'providers/Conversion/ConversionContext';
import { useEffect, useState } from 'react';
import type { Client } from 'types/api/ext/clients';

import { getConversionRoute } from '../routes';

interface Step4Props {
	memberType?: MemberType;
}

/**
 * ConversionStep4 — 전환 대상 기관 안내 (기관 선택 폐기)
 *
 * [기획안 변경 — 2026-05-15 장관 지시]
 * "SSO인데 왜 기관을 선택하나?" → 기관 선택 절차 폐기
 *
 * 변경 전: 유관기관 목록을 체크박스로 표시 → 사용자가 직접 선택
 * 변경 후: 전환 후 연결될 기관 목록을 읽기 전용으로 안내
 *          실제 연결은 CI(연계정보) 기반으로 IdO/Q-IM이 자동 처리
 *
 * selectedClients는 빈 배열로 유지 → Step5 provisioning 시
 * clients 파라미터를 undefined로 전달하여 BE(IdO)가 CI 기반 자동 연결 처리
 */
function ConversionStep4({ memberType = 'member' }: Step4Props): JSX.Element {
	const { data, updateData } = useConversion();
	const [loading, setLoading] = useState(true);
	const [loadError, setLoadError] = useState(false);
	const [clients, setClients] = useState<Client[]>([]);

	useEffect(() => {
		(async (): Promise<void> => {
			try {
				const res = await getClients();
				if (res.statusCode === 200 && res.payload?.data?.clients) {
					const fetchedClients = res.payload.data.clients;
					setClients(fetchedClients);
					// 기관 선택 폐기: availableClients만 저장, selectedClients는 건드리지 않음
					// Step5 provisioning 시 clients=undefined → IdO가 CI 기반 자동 연결 처리
					updateData({ availableClients: fetchedClients });
				} else {
					setLoadError(true);
				}
			} catch {
				setLoadError(true);
			} finally {
				setLoading(false);
			}
		})();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	if (loading) {
		return <Spinner tip="기관 정보를 불러오는 중..." />;
	}

	return (
		<ConversionLayout
			currentStep={4}
			prevRoute={getConversionRoute(3, memberType)}
			nextRoute={getConversionRoute(5, memberType)}
			memberType={memberType}
		>
			{/* 안내 문구 */}
			<div className="text-info-wrap point">
				<ul className="text-list-wrap check" aria-label="안내 사항">
					<li>
						<p>
							중기원패스 전환 완료 후 아래 유관기관 서비스를 하나의 통합 ID로 이용하실 수 있습니다.
						</p>
					</li>
					<li>
						<p>
							기존 계정은 본인인증(CI) 정보를 기준으로 자동으로 연결됩니다.
						</p>
					</li>
					<li>
						<p>
							연결 현황은 전환 완료 후 ( 마이페이지 &gt; 유관기관 안내 )에서 확인하실 수 있습니다.
						</p>
					</li>
				</ul>
				<figure className="img">
					<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
				</figure>
			</div>

			{/* 전환 대상 기관 목록 (읽기 전용) */}
			{loadError ? (
				<p className="text" style={{ color: '#c00', padding: '16px' }}>
					기관 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
				</p>
			) : (
				<div
					className="agency-info-list"
					role="list"
					aria-label="전환 후 연결되는 유관기관 목록"
				>
					{clients.length === 0 ? (
						<p className="text" style={{ padding: '16px', color: '#666' }}>
							연결 가능한 유관기관 정보를 불러오고 있습니다.
						</p>
					) : (
						clients.map((client) => (
							<div
								key={client.ssoClientId}
								className="agency-info-item"
								role="listitem"
							>
								<div className="text-box">
									<strong className="tit">{client.clientNm}</strong>
									{client.description && (
										<p className="text">{client.description}</p>
									)}
								</div>
							</div>
						))
					)}
				</div>
			)}

			{/* 마이페이지 유관기관 안내 문구 */}
			<div className="text-info-wrap" style={{ marginTop: '24px' }}>
				<p className="text" style={{ color: '#666', fontSize: '13px' }}>
					<strong>※ 유관기관 안내</strong>
					{' '}마이페이지에서 전환된 유관기관 연결 현황을 확인하고 관리하실 수 있습니다.
				</p>
			</div>
		</ConversionLayout>
	);
}

export default ConversionStep4;
