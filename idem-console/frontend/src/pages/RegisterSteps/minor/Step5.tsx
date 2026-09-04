/**
 * 만14세 미만 회원가입 Step5 — 계정 정보 입력
 *
 * 일반 회원가입 Step5와 동일한 provisionUser API를 사용하되,
 * 미성년자 플로우 전용 안내 배너를 추가한다.
 *
 * 프로비저닝 시:
 *   - ciToken: 아동 본인(Step3에서 발급)
 *   - guardianCiToken: BE 서버가 guardianConsentEventId와 함께 처리
 *     (현재 BE API가 guardianCiToken 파라미터를 지원하면 함께 전송)
 *
 * 참고: BE Q-IM /api/ext/provision/users가 미성년자 guardianCiToken 파라미터를
 *       지원하지 않는 경우, 해당 필드는 향후 BE 스펙 확정 후 추가.
 *       현재 구현은 guardianConsentEventId를 API 요청에 포함하여 전송.
 */
import provisionUser from 'api/provision/users';
import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import IMAGES from 'constants/images';
import { useRegister } from 'providers/Register/RegisterContext';
import { useCallback, useState } from 'react';
import AccountForm from '../member/components/AccountForm';
import MemberInfoForm from '../member/components/MemberInfoForm';
import { getMinorRegisterRoute } from './routes';

function RegisterMinorStep5(): JSX.Element {
	const { data, updateData } = useRegister();
	const [showAlert, setShowAlert] = useState(false);
	const [alertMessage, setAlertMessage] = useState('');
	const [failedModal, setFailedModal] = useState(false);
	const [errorMessage, setErrorMessage] = useState('');
	const [verificationStatus, setVerificationStatus] = useState({
		validateOk: false,
		duplicateOk: false,
		formValid: false,
	});

	const handleVerificationChange = useCallback(
		(status: { validateOk: boolean; duplicateOk: boolean; formValid: boolean }) => {
			setVerificationStatus(status);
		},
		[],
	);

	const handleNext = async (): Promise<boolean> => {
		// 필수 입력 검증
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

		// 아동 본인인증 토큰 검증
		if (!data.ciToken) {
			setAlertMessage('아동 본인인증이 완료되지 않았습니다. 이전 단계를 확인해 주세요.');
			setShowAlert(true);
			return false;
		}

		// 법정대리인 동의 완료 검증
		if (!data.guardianConsentDone) {
			setAlertMessage('법정대리인 동의가 완료되지 않았습니다. 이전 단계를 확인해 주세요.');
			setShowAlert(true);
			return false;
		}

		if (!verificationStatus.duplicateOk) {
			setAlertMessage('아이디 중복확인을 완료해 주세요.');
			setShowAlert(true);
			return false;
		}

		// clients 조립 (미성년자는 기관 연결 선택 없이 빈 배열 — Step4가 없음)
		const memberClients = data.selectedClients.map((ssoClientId, idx) => ({
			clientId: ssoClientId,
			mbrId: data.loginId,
			...(idx === 0 ? { rprsInstYn: 'Y' as const } : {}),
		}));

		const indvEmlAddr =
			data.email && data.emailDomain ? `${data.email}@${data.emailDomain}` : undefined;

		const telno =
			data.telPrefix && data.telSuffix ? `${data.telPrefix}${data.telSuffix}` : undefined;

		const provResponse = await provisionUser({
			ciToken: data.ciToken,
			memberName: data.name,
			loginId: data.loginId,
			initialPassword: data.password,
			clients: memberClients,
			indvMblTelno: `${data.phonePrefix || '010'}${data.phoneSuffix || ''}`,
			indvEmlAddr,
			telno,
			birthDate: data.birthDate || undefined,
			notiPrefs: {
				sms: data.notifications.sms ? 'Y' : 'N',
				kakao: data.notifications.kakao ? 'Y' : 'N',
				email: data.notifications.email ? 'Y' : 'N',
			},
		});

		if (provResponse.statusCode !== 200 || !provResponse.payload?.data) {
			setErrorMessage(provResponse.message || '개인회원 등록에 실패하였습니다.');
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
				currentStep={5}
				prevRoute={getMinorRegisterRoute(4)}
				nextRoute={getMinorRegisterRoute(6)}
				memberType="member"
				title="계정 정보 입력"
				noWrap
				onNext={handleNext}
			>
				<div className="white-wrap">
					<h3 className="h3-title">기본/회원 정보</h3>

					{/* 미성년자 플로우 완료 상태 표시 */}
					{data.guardianConsentDone && (
						<div
							style={{
								background: '#d5f5e3',
								border: '1px solid #27ae60',
								borderRadius: '8px',
								padding: '12px 16px',
								marginBottom: '16px',
							}}
							role="status"
						>
							<p style={{ color: '#1e8449', fontSize: '13px' }}>
								✓ 아동 본인인증 완료 ({data.name})<br />
								✓ 법정대리인({data.guardianName}) 동의 완료
							</p>
						</div>
					)}

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

					<div className="form-wrap">
						<AccountForm
							isBusiness={false}
							flat
							onVerificationChange={handleVerificationChange}
						/>
						<MemberInfoForm isBusiness={false} flat />
					</div>
				</div>
			</RegisterLayout>

			<Modal
				id="minor-validation-alert"
				isOpen={showAlert}
				onClose={(): void => setShowAlert(false)}
				topText="알림"
				title="입력 정보를 확인해 주세요."
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: (): void => setShowAlert(false) },
				]}
			>
				<p>{alertMessage}</p>
			</Modal>
			<Modal
				id="modal_minor_failed_provisioning"
				isOpen={failedModal}
				onClose={(): void => setFailedModal(false)}
				topText="개인회원 등록 오류"
				title={errorMessage}
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: (): void => setFailedModal(false) },
				]}
			>
				<p className="text">
					개인회원 등록 처리 중 오류가 발생하였습니다. <br />
					입력 정보를 확인하신 후 다시 시도해 주세요.
				</p>
			</Modal>
		</>
	);
}

export default RegisterMinorStep5;
