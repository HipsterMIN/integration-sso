import provisionEnterprise from 'api/provision/enterprises';
import provisionUser from 'api/provision/users';
import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useRegister } from 'providers/Register/RegisterContext';
import { useCallback, useState } from 'react';

import { getRegisterRoute } from '../routes';
import AccountForm from './components/AccountForm';
import MemberInfoForm from './components/MemberInfoForm';
import NotificationSettings from './components/NotificationSettings';

interface Step5Props {
	memberType?: MemberType;
	currentStep?: number;
}

function RegisterStep5({
	memberType = 'member',
	currentStep = 5,
}: Step5Props): JSX.Element {
	const { data, updateData } = useRegister();
	const isBusiness = memberType === 'business';
	const [showAlert, setShowAlert] = useState(false);
	const [alertMessage, setAlertMessage] = useState('');
	const [failedModal, setFailedModal] = useState(false);
	const [errorMessage, setErrorMessage] = useState('');
	const [verificationStatus, setVerificationStatus] = useState({
		validateOk: false,
		duplicateOk: false,
		formValid: false,
	});

	// Step3 인증 시 개인: userCheckConversion, 기업: enterpriseCheckConversion으로
	// 이미 context(availableClients, selectedClients)에 저장됨

	const handleVerificationChange = useCallback(
		(status: { validateOk: boolean; duplicateOk: boolean; formValid: boolean }) => {
			setVerificationStatus(status);
		},
		[],
	);

	const toggleNotification = (key: string): void => {
		const updated = { ...data.notifications, [key]: !data.notifications[key] };
		updateData({ notifications: updated });
	};

	const handleNext = async (): Promise<boolean> => {
		if (isBusiness) {
			if (
				!data.bzmnNm ||
				!data.rprsvNm ||
				!data.brno ||
				data.brno.length !== 10 ||
				!data.loginId ||
				!data.password ||
				!data.email ||
				!data.emailDomain
			) {
				setAlertMessage('(필수) 항목을 모두 입력한 후 다음으로 진행해 주세요.');
				setShowAlert(true);
				return false;
			}

			if (!verificationStatus.validateOk) {
				setAlertMessage('국세청 진위확인을 완료해 주세요.');
				setShowAlert(true);
				return false;
			}

			if (!verificationStatus.duplicateOk) {
				setAlertMessage('사업자등록번호 중복확인을 완료해 주세요.');
				setShowAlert(true);
				return false;
			}

			console.log('[RegisterStep5] 기업 selectedClients:', JSON.stringify(data.selectedClients));
			console.log('[RegisterStep5] 기업 availableClients:', JSON.stringify(data.availableClients.map((c) => ({ ssoClientId: c.ssoClientId, businessTypes: c.businessTypes }))));

			const clients =
				data.selectedClients.length > 0
					? data.selectedClients
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
							)
					: undefined;

			console.log('[RegisterStep5] 기업 최종 clients:', JSON.stringify(clients));

			const rprsEmlAddr = data.email && data.emailDomain
				? `${data.email}@${data.emailDomain}`
				: '';
			const rprsTelno = data.telPrefix && data.telSuffix
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
				clients,
			});

			if (
				provResponse.statusCode !== 200
				|| !provResponse.payload?.data
				|| provResponse.payload?.success === false
			) {
				setErrorMessage(
					provResponse.payload?.message
					|| provResponse.error
					|| provResponse.message
					|| '기업 등록에 실패하였습니다.',
				);
				setFailedModal(true);
				return false;
			}

			const { entMbrNo, provisioningToken } = provResponse.payload.data;
			updateData({ entMbrNo, provisioningToken });
			return true;
		}

		// --- 개인회원 ---
		console.log('[RegisterStep5] telPrefix:', JSON.stringify(data.telPrefix), 'telSuffix:', JSON.stringify(data.telSuffix));
		console.log('[RegisterStep5] phonePrefix:', JSON.stringify(data.phonePrefix), 'phoneSuffix:', JSON.stringify(data.phoneSuffix));
		console.log('[RegisterStep5] email:', JSON.stringify(data.email), 'emailDomain:', JSON.stringify(data.emailDomain));
		if (!data.loginId || !data.password) {
			setAlertMessage('(필수) 항목을 모두 입력한 후 다음으로 진행해 주세요.');
			setShowAlert(true);
			return false;
		}

		if (!verificationStatus.formValid) {
			setAlertMessage('아이디 또는 비밀번호 형식을 확인해 주세요.');
			setShowAlert(true);
			return false;
		}

		if (!data.ciToken) {
			setAlertMessage(
				'본인인증이 완료되지 않았습니다. 이전 단계를 확인해 주세요.',
			);
			setShowAlert(true);
			return false;
		}

		if (!verificationStatus.duplicateOk) {
			setAlertMessage('아이디 중복확인을 완료해 주세요.');
			setShowAlert(true);
			return false;
		}

		// clients 조립 (Step4에서 선택된 서비스) — fromClientId(initialClientId)와 일치하면 대표기관(Y)
		console.log('[RegisterStep5] 개인 selectedClients:', JSON.stringify(data.selectedClients));
		console.log('[RegisterStep5] 개인 availableClients:', JSON.stringify(data.availableClients.map((c) => ({ ssoClientId: c.ssoClientId, businessTypes: c.businessTypes }))));

		const memberClients = data.selectedClients
			.map((ssoClientId) => {
				const client = data.availableClients.find(
					(c) => c.ssoClientId === ssoClientId,
				);
				if (!client) return null;
				return {
					clientId: ssoClientId,
					mbrId: '',
					rprsInstYn: ssoClientId === data.initialClientId ? ('Y' as const) : ('N' as const),
				};
			})
			.filter(
				(c): c is { clientId: string; mbrId: string; rprsInstYn: 'Y' | 'N' } => c !== null,
			);

		console.log('[RegisterStep5] 개인 최종 memberClients:', JSON.stringify(memberClients));

		// 이메일 조합
		const indvEmlAddr =
			data.email && data.emailDomain
				? `${data.email}@${data.emailDomain}`
				: undefined;

		// 일반전화 조합
		const telno =
			data.telPrefix && data.telSuffix
				? `${data.telPrefix}-${data.telSuffix}`
				: undefined;

		// 휴대폰 포맷: 010-XXXX-XXXX (suffix 8자리일 때 4+4 분리)
		const phoneFormatted = data.phoneSuffix && data.phoneSuffix.length === 8
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

		if (
			provResponse.statusCode !== 200
			|| !provResponse.payload?.data
			|| provResponse.payload?.success === false
		) {
			setErrorMessage(
				provResponse.payload?.message
				|| provResponse.error
				|| provResponse.message
				|| '개인회원 등록에 실패하였습니다.',
			);
			setFailedModal(true);
			return false;
		}

		const { mbrNo, mbrUuid, provisioningToken } = provResponse.payload.data;
		updateData({ mbrNo, mbrUuid, provisioningToken });
		return true;
	};

	return (
		<>
			<RegisterLayout
				currentStep={currentStep}
				prevRoute={getRegisterRoute(currentStep - 1, memberType)}
				nextRoute={getRegisterRoute(currentStep + 1, memberType)}
				memberType={memberType}
				noWrap
				onNext={handleNext}
			>
				<div className="white-wrap">
					<h3 className="h3-title">{isBusiness ? '기본 정보' : '기본/회원 정보'}</h3>
					<div className="text-info-wrap point">
						<ul className="text-list-wrap check" aria-label="안내 사항">
							<li>
								<p>
									회원정보는 정책지원 및 맞춤형 서비스를 제공하는데 사용되므로 정확한
									정보를 입력해 주세요.
								</p>
							</li>
							<li>
								<p>
									<span className="essential">필수</span>항목은 반드시 기입해 주시기
									바랍니다.
								</p>
							</li>
						</ul>
						<figure className="img">
							<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
						</figure>
					</div>
					{isBusiness ? (
						<AccountForm isBusiness onVerificationChange={handleVerificationChange} />
					) : (
						<div className="form-wrap">
							<AccountForm isBusiness={false} flat onVerificationChange={handleVerificationChange} />
							<MemberInfoForm isBusiness={false} flat />
						</div>
					)}
				</div>
				{/* <div className="white-wrap">
					<h3 className="h3-title">알림 수신</h3>
					<div className="text-info-wrap point">
						<ul className="text-list-wrap check" aria-label="안내 사항">
							<li>
								<p>
									중기원패스의 알림은 이메일과 SNS 또는 알림톡으로 발송되며, 정책자금
									상담, Q&amp;A, 민원 등의 처리현황 정보가 발송됩니다.
								</p>
							</li>
						</ul>
						<figure className="img">
							<img
								src={IMAGES.RENEWAL_TEXT_LIST_IMG_RECEIVE_NOTIFICATIONS}
								alt=""
								aria-hidden="true"
							/>
						</figure>
					</div>
					<NotificationSettings
						notifications={data.notifications}
						onToggle={toggleNotification}
					/>
				</div> */}
			</RegisterLayout>
			<Modal
				id="validation-alert"
				isOpen={showAlert}
				onClose={(): void => setShowAlert(false)}
				topText="알림"
				title="입력 정보를 확인해 주세요."
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setShowAlert(false),
					},
				]}
			>
				<p>{alertMessage}</p>
			</Modal>
			<Modal
				id="modal_failed_provisioning"
				isOpen={failedModal}
				onClose={(): void => setFailedModal(false)}
				topText={isBusiness ? '기업 등록 오류' : '개인회원 등록 오류'}
				title={isBusiness ? '기업 등록 실패' : '개인회원 등록 실패'}
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: (): void => setFailedModal(false) },
				]}
			>
				<p className="text">
					{errorMessage}
				</p>
			</Modal>
		</>
	);
}

export default RegisterStep5;
