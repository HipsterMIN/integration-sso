import getClients from 'api/ext/clients';
import provisionEnterprise from 'api/provision/enterprises';
import provisionUser from 'api/provision/users';
import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import Spinner from 'components/Spinner';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useRegister } from 'providers/Register/RegisterContext';
import { useCallback, useEffect, useState } from 'react';
import Slider from 'react-slick';
import type { Client } from 'types/api/ext/clients';

import { getRegisterRoute } from '../routes';

const SLIDER_SETTINGS = {
	rows: 2,
	slidesPerRow: 5,
	slidesToShow: 1,
	slidesToScroll: 1,
	dots: true,
	arrows: false,
	infinite: false,
	responsive: [
		{ breakpoint: 1024, settings: { rows: 2, slidesPerRow: 3 } },
		{ breakpoint: 768, settings: { rows: 2, slidesPerRow: 2 } },
	],
};

interface Step5Props {
	memberType?: MemberType;
	currentStep?: number;
}

/**
 * 유관기관 계정 연결 + 가입 처리 단계 (이전엔 4단계였으나 단계 swap 으로 5단계로 이동).
 * 새 4단계(정보입력)에서 입력된 폼 데이터(context)와 유관기관 선택 결과를 합쳐
 * provisionUser/provisionEnterprise 호출.
 *
 * UI: PUB260527 conversion_member_step5.html — affiliation-container + slide-wrap + slide 카드 구조.
 * 데이터: getClients() 결과를 context.availableClients 에 저장 후 사용.
 */
function RegisterStep5({
	memberType = 'member',
	currentStep = 5,
}: Step5Props): JSX.Element {
	const { data, updateData } = useRegister();
	const isBusiness = memberType === 'business';
	const [loading, setLoading] = useState(true);
	const [failedModal, setFailedModal] = useState(false);
	const [errorMessage, setErrorMessage] = useState('');
	const [preparingClients, setPreparingClients] = useState<Client[]>([]);

	const { initialClientId } = data;

	useEffect(() => {
		let cancelled = false;
		(async (): Promise<void> => {
			setLoading(true);
			const [res, reverseRes] = await Promise.all([
				getClients(),
				getClients({ reverseYN: 'Y' }),
			]);
			if (cancelled) return;

			// memberType 에 따른 사업유형 필터 — business: ALL/ENT, member: ALL/IND
			const fixedBizType = isBusiness ? 'ENT' : 'IND';
			const filterByMemberType = (list: Client[]): Client[] =>
				list.filter(
					(c) =>
						c.businessTypes != null
						&& (c.businessTypes === fixedBizType || c.businessTypes === 'ALL'),
				);

			if (res.statusCode === 200 && res.payload) {
				const raw = res.payload.data;
				const list: Client[] = Array.isArray(raw) ? raw : raw?.clients ?? [];
				const filtered = filterByMemberType(list);
				// 디폴트로 전체 선택
				updateData({
					availableClients: filtered,
					selectedClients: filtered.map((c) => c.ssoClientId),
				});
			}
			if (reverseRes.statusCode === 200 && reverseRes.payload) {
				const raw = reverseRes.payload.data;
				const list: Client[] = Array.isArray(raw) ? raw : raw?.clients ?? [];
				setPreparingClients(filterByMemberType(list));
			}
			setLoading(false);
		})();
		return (): void => {
			cancelled = true;
		};
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [initialClientId]);

	const toggleClient = (ssoClientId: string): void => {
		const set = new Set(data.selectedClients);
		if (set.has(ssoClientId)) set.delete(ssoClientId);
		else set.add(ssoClientId);
		updateData({ selectedClients: Array.from(set) });
	};

	const buildClients = ():
		| Array<{
				clientId: string;
				mbrId: string;
				rprsInstYn: 'Y' | 'N';
		  }>
		| undefined => {
		if (data.selectedClients.length === 0) return undefined;
		return data.selectedClients
			.map((ssoClientId) => {
				const client = data.availableClients.find(
					(c) => c.ssoClientId === ssoClientId,
				);
				if (!client) return null;
				return {
					clientId: client.ssoClientId,
					mbrId: '',
					rprsInstYn:
						client.ssoClientId === data.initialClientId
							? ('Y' as const)
							: ('N' as const),
				};
			})
			.filter(
				(c): c is { clientId: string; mbrId: string; rprsInstYn: 'Y' | 'N' } =>
					c !== null,
			);
	};

	// eslint-disable-next-line sonarjs/cognitive-complexity
	const handleNext = useCallback(async (): Promise<boolean> => {
		if (isBusiness) {
			const rprsEmlAddr =
				data.email && data.emailDomain ? `${data.email}@${data.emailDomain}` : '';
			const rprsTelno =
				data.telPrefix && data.telSuffix
					? `${data.telPrefix}-${data.telSuffix}`
					: undefined;

			const provResponse = await provisionEnterprise({
				bzmnTypeCd: 'C',
				brno: data.brno,
				bzmnNm: data.bzmnNm,
				rprsvNm: data.rprsvNm,
				estbDt: data.startDt,
				rprsTelno,
				rprsEmlAddr,
				newPic: {
					memberName: data.rprsvNm,
					loginId: data.loginId,
					initialPassword: data.password,
					email: rprsEmlAddr,
					phone: rprsTelno || '',
				},
				clients: buildClients(),
			});

			// TODO: API 실패 시 에러 모달 처리 임시 주석 — 실패해도 다음 단계로 진행
			// if (
			// 	provResponse.statusCode !== 200
			// 	|| !provResponse.payload?.data
			// 	|| provResponse.payload?.success === false
			// ) {
			// 	setErrorMessage(
			// 		provResponse.payload?.message
			// 			|| provResponse.error
			// 			|| provResponse.message
			// 			|| '기업 등록에 실패하였습니다.',
			// 	);
			// 	setFailedModal(true);
			// 	return false;
			// }

			const { entMbrNo, provisioningToken } = provResponse.payload?.data ?? {};
			updateData({ entMbrNo, provisioningToken });
			return true;
		}

		// --- 개인회원 ---
		const memberClients = buildClients() ?? [];
		const indvEmlAddr =
			data.email && data.emailDomain
				? `${data.email}@${data.emailDomain}`
				: undefined;
		const telno =
			data.telPrefix && data.telSuffix
				? `${data.telPrefix}-${data.telSuffix}`
				: undefined;
		const phoneFormatted =
			data.phoneSuffix && data.phoneSuffix.length === 8
				? `${data.phonePrefix || '010'}-${data.phoneSuffix.slice(0, 4)}-${data.phoneSuffix.slice(4)}`
				: `${data.phonePrefix || '010'}${data.phoneSuffix || ''}`;

		const provResponse = await provisionUser({
			ciToken: data.ciToken,
			memberName: data.name,
			loginId: data.loginId,
			initialPassword: data.password,
			clients: memberClients,
			email: indvEmlAddr,
			phone: phoneFormatted,
			indvMblTelno: phoneFormatted,
			indvEmlAddr,
			telno,
			birthDate: data.birthDate || undefined,
			notiPrefs: {
				sms: data.notifications.sms ? 'Y' : 'N',
				kakao: data.notifications.kakao ? 'Y' : 'N',
				email: data.notifications.email ? 'Y' : 'N',
			},
		});

		// TODO: API 실패 시 에러 모달 처리 임시 주석 — 실패해도 다음 단계로 진행
		// if (
		// 	provResponse.statusCode !== 200
		// 	|| !provResponse.payload?.data
		// 	|| provResponse.payload?.success === false
		// ) {
		// 	setErrorMessage(
		// 		provResponse.payload?.message
		// 			|| provResponse.error
		// 			|| provResponse.message
		// 			|| '개인회원 등록에 실패하였습니다.',
		// 	);
		// 	setFailedModal(true);
		// 	return false;
		// }

		const { mbrNo, mbrUuid, provisioningToken } = provResponse.payload?.data ?? {};
		updateData({ mbrNo, mbrUuid, provisioningToken });
		return true;
	// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [memberType, isBusiness, data, updateData]);

	const checkedSet = new Set(data.selectedClients);

	return (
		<>
			<RegisterLayout
				currentStep={currentStep}
				prevRoute={getRegisterRoute(currentStep - 1, memberType)}
				nextRoute={getRegisterRoute(currentStep + 1, memberType)}
				memberType={memberType}
				onNext={handleNext}
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
										추후 ( 마이페이지 &gt; 유관기관 목록 )에서 언제든지 계정 연결 해제를
										할 수 있습니다
									</p>
								</li>
							</ul>
							<figure className="img">
								<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
							</figure>
						</div>
						<div className="affiliation-container">
							<h3 className="h3-title">
								<i className="icon ico-new-window" aria-hidden="true" />
								<p>계정 연결 된 유관기관 서비스</p>
							</h3>
							<div className="affiliation-slide-wrap">
								<Slider {...SLIDER_SETTINGS} className="slide-wrap">
									{data.availableClients.map((client) => {
										const isChecked = checkedSet.has(client.ssoClientId);
										const inputId = `affiliation_${client.ssoClientId}`;
										return (
											<div key={client.ssoClientId} className="slide">
												<label htmlFor={inputId}>
													<div className="top-box">
														{client.logo && (
															<figure className="img">
																<img src={client.logo} alt={client.clientNm} />
															</figure>
														)}
														<div className="text-box">
															<p className="title">{client.clientNm}</p>
															<p className="detail">{client.description ?? ''}</p>
														</div>
													</div>
													<div className="bot-box">
														<span className="check-box style1">
															<input
																type="checkbox"
																id={inputId}
																name="affiliation_check"
																checked={isChecked}
																onChange={(): void => toggleClient(client.ssoClientId)}
																aria-label={`${client.clientNm} 선택`}
															/>
														</span>
													</div>
												</label>
											</div>
										);
									})}
								</Slider>
							</div>
						</div>
						<div className="affiliation-container waiting">
							<h3 className="h3-title">
								<i className="icon ico-new-window" aria-hidden="true" />
								<p>준비중인 유관기관 서비스</p>
							</h3>
							<div className="affiliation-slide-wrap">
								<Slider {...SLIDER_SETTINGS} className="slide-wrap">
									{preparingClients.map((client) => {
										const inputId = `affiliation_preparing_${client.ssoClientId}`;
										return (
											<div key={client.ssoClientId} className="slide">
												<label htmlFor={inputId}>
													<div className="top-box">
														{client.logo && (
															<figure className="img">
																<img src={client.logo} alt={client.clientNm} />
															</figure>
														)}
														<div className="text-box">
															<p className="title">{client.clientNm}</p>
															<p className="detail">{client.description ?? ''}</p>
														</div>
													</div>
													<div className="bot-box">
														<span className="check-box style1">
															<input
																type="checkbox"
																id={inputId}
																name="preparing_check"
																checked
																disabled
																readOnly
																aria-label={`${client.clientNm} (준비중)`}
															/>
														</span>
													</div>
												</label>
											</div>
										);
									})}
								</Slider>
							</div>
						</div>
					</>
				)}
			</RegisterLayout>
			<Modal
				id="modal_failed_provisioning"
				isOpen={failedModal}
				onClose={(): void => setFailedModal(false)}
				topText={isBusiness ? '기업 등록 오류' : '개인회원 등록 오류'}
				title={isBusiness ? '기업 등록 실패' : '개인회원 등록 실패'}
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setFailedModal(false),
					},
				]}
			>
				<p className="text">{errorMessage}</p>
			</Modal>
		</>
	);
}

export default RegisterStep5;
