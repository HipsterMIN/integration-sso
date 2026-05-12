import getClients from 'api/ext/clients';
import ConversionLayout from 'components/ConversionLayout';
import Modal from 'components/KrdsModal';
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

function ConversionStep4({ memberType = 'member' }: Step4Props): JSX.Element {
	const { data, updateData } = useConversion();
	const [infoModal, setInfoModal] = useState(false);
	const [selectedClient, setSelectedClient] = useState<Client | null>(null);
	const [loading, setLoading] = useState(true);
	const [loadError, setLoadError] = useState(false);
	const isBusiness = memberType === 'business';

	// GAP-05: 하드코딩된 SYSTEMS 배열 제거 → getClients() API 실 연동
	useEffect(() => {
		(async (): Promise<void> => {
			try {
				const res = await getClients();
				if (res.statusCode === 200 && res.payload?.data?.clients) {
					updateData({ availableClients: res.payload.data.clients });
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

	const handleCheck = (clientId: string): void => {
		const next = data.selectedClients.includes(clientId)
			? data.selectedClients.filter((id) => id !== clientId)
			: [...data.selectedClients, clientId];
		updateData({ selectedClients: next });
	};

	const handleInfoOpen = (client: Client): void => {
		setSelectedClient(client);
		setInfoModal(true);
	};

	if (loading) {
		return <Spinner tip="유관기관 목록을 불러오는 중..." />;
	}

	return (
		<ConversionLayout
			currentStep={4}
			skipRoute={getConversionRoute(5, memberType)}
			nextRoute={getConversionRoute(5, memberType)}
			memberType={memberType}
		>
			<div className="text-info-wrap point">
				<ul className="text-list-wrap check" aria-label="안내 사항">
					<li><p>유관시스템 서비스를 하나의 통합 ID로 연결합니다</p></li>
					<li>
						<p>
							등록을 원하지 않으실 경우 '건너뛰기'를 선택하여 가입을 완료하실 수 있습니다.
						</p>
					</li>
					<li>
						<p>
							추후 ( 마이페이지 &gt; 유관기관 서비스 관리 )에서 언제든지 추가 등록, 탈퇴할 수 있습니다
						</p>
					</li>
				</ul>
				<figure className="img">
					<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
				</figure>
			</div>

			{loadError ? (
				<p className="text" style={{ color: '#c00', padding: '16px' }}>
					유관기관 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
				</p>
			) : (
				<div className="check-box-wrap" role="group" aria-label="가입현황 시스템 선택">
					{data.availableClients.map((client) => (
						<div
							key={client.ssoClientId}
							className="check-box style3"
							onClick={(): void => handleInfoOpen(client)}
							role="button"
							tabIndex={0}
							onKeyDown={(e): void => {
								if (e.key === 'Enter' || e.key === ' ') handleInfoOpen(client);
							}}
						>
							<label
								htmlFor={client.ssoClientId}
								onClick={(e): void => e.stopPropagation()}
							>
								<input
									type="checkbox"
									id={client.ssoClientId}
									name="check"
									checked={data.selectedClients.includes(client.ssoClientId)}
									onChange={(): void => handleCheck(client.ssoClientId)}
									aria-label={client.clientNm}
								/>
							</label>
							<div className="text-box">
								<strong className="tit">{client.clientNm}</strong>
								{client.description && (
									<p className="text">{client.description}</p>
								)}
							</div>
						</div>
					))}
				</div>
			)}

			<Modal
				id="modal_member_information"
				isOpen={infoModal}
				onClose={(): void => setInfoModal(false)}
				topText="계정 가입 현황"
				title={selectedClient?.clientNm ?? '유관기관 정보'}
				buttons={[{ label: '적용', variant: 'primary' }]}
				contentsClassName="form-wrap"
			>
				{isBusiness ? (
					<>
						<div className="input-wrap">
							<label htmlFor="modal_company_name">회사명</label>
							<div className="input-box">
								<input
									id="modal_company_name"
									type="text"
									defaultValue={data.bzmnNm}
									disabled
								/>
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_business_num">사업자등록번호</label>
							<div className="input-box">
								<input
									id="modal_business_num"
									type="text"
									defaultValue={data.brno}
									disabled
								/>
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_rep_name">대표자명</label>
							<div className="input-box">
								<input
									id="modal_rep_name"
									type="text"
									defaultValue={data.rprsvNm}
									disabled
								/>
							</div>
						</div>
					</>
				) : (
					<>
						<div className="input-wrap">
							<label htmlFor="modal_name">이름</label>
							<div className="input-box">
								<input
									id="modal_name"
									type="text"
									defaultValue={data.name}
									disabled
								/>
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_phone1">휴대전화</label>
							<div className="input-flex-box">
								<div className="input-box">
									<input
										id="modal_phone1"
										type="text"
										defaultValue={data.phonePrefix}
										disabled
										aria-label="휴대전화 앞자리"
									/>
								</div>
								<div className="input-box">
									<input
										id="modal_phone2"
										type="text"
										defaultValue={data.phoneSuffix}
										disabled
										aria-label="휴대전화 뒷자리"
									/>
								</div>
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_email1">이메일</label>
							<div className="input-flex-box">
								<div className="input-box">
									<input
										id="modal_email1"
										type="text"
										defaultValue={data.emailId}
										disabled
										aria-label="이메일 아이디"
									/>
								</div>
								<span>@</span>
								<div className="input-box">
									<input
										id="modal_email2"
										type="text"
										defaultValue={data.emailDomain}
										disabled
										aria-label="이메일 도메인"
									/>
								</div>
							</div>
						</div>
					</>
				)}
			</Modal>
		</ConversionLayout>
	);
}

export default ConversionStep4;
