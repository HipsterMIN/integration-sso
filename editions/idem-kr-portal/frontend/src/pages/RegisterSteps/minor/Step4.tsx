/**
 * 만14세 미만 회원가입 Step4 — 법정대리인 본인인증
 *
 * 정보통신망법 제31조 핵심 이행 단계:
 *   법정대리인(친권자 또는 후견인)이 직접 본인인증을 수행하여 동의 의사를 확인한다.
 *
 * 인증 성공 후:
 *   - guardianCiToken, guardianName, guardianBirthDate, guardianPhone → RegisterContext에 저장
 *   - guardianConsentDone = true 설정
 *   - Step5(계정 정보 입력)으로 이동
 *
 * 보안 고려사항:
 *   - 법정대리인 CI는 아동 CI와 별도로 보관 (guardianCiToken)
 *   - 동일인 인증 방지: 아동 ciToken과 동일한 CI로 인증 시 경고 모달 표시
 *     (실제 중복 검증은 BE /api/v1/auth/ci-token에서 수행해야 함)
 */
import exchangeCiToken from 'api/provision/ciToken';
import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import IMAGES from 'constants/images';
import type { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';
import type { EasysignResult } from 'hooks/usePersonalEasyAuth';
import usePersonalEasyAuth from 'hooks/usePersonalEasyAuth';
import { useCallback, useState } from 'react';
import { useHistory } from 'react-router-dom';
import { useRegister } from 'providers/Register/RegisterContext';
import { encryptCi } from 'utils/crypto/aesGcm';
import { getMinorRegisterRoute } from './routes';

function RegisterMinorStep4(): JSX.Element {
	const history = useHistory();
	const { data, updateData } = useRegister();
	const [authType, setAuthType] = useState<'app' | 'phone' | ''>('');
	const [failedModal, setFailedModal] = useState(false);
	const [samePersonModal, setSamePersonModal] = useState(false);
	const [authRequiredModal, setAuthRequiredModal] = useState(false);
	const [devNoticeModal, setDevNoticeModal] = useState(false);

	/**
	 * 법정대리인 인증 결과 공통 처리
	 *   1. CI → encryptCi → exchangeCiToken (guardianCiToken 발급)
	 *   2. 법정대리인 정보 RegisterContext에 저장
	 *   3. guardianConsentDone = true
	 *   4. Step5로 이동
	 */
	const processGuardianAuth = useCallback(
		async (params: {
			ci?: string;
			name?: string;
			birthDate?: string;
			phone?: string;
		}): Promise<void> => {
			const { ci, name = '', birthDate = '', phone = '' } = params;

			let guardianCiToken = '';

			if (ci) {
				const encrypted = await encryptCi(ci);
				// CI 원문 즉시 폐기 (메모리에서 참조 제거)

				const tokenResponse = await exchangeCiToken({
					encryptedCi: encrypted,
					realm: process.env.QSIGN_REALM || 'ucube-qsign',
					clientId: process.env.QSIGN_CLIENT_ID || 'onepassCli',
					// 법정대리인 전용 flowContext — BE가 CI를 법정대리인 역할로 등록
					flowContext: 'GUARDIAN_CONSENT',
				});

				if (tokenResponse.statusCode === 200 && tokenResponse.payload?.data) {
					guardianCiToken = tokenResponse.payload.data.ciToken;
				} else {
					setFailedModal(true);
					return;
				}
			}

			// 아동과 법정대리인이 동일인인지 확인 (ciToken 비교 — 완전한 검증은 BE에서)
			// guardianCiToken이 data.ciToken과 동일하면 경고 (같은 사람이 아동+보호자 역할)
			if (guardianCiToken && guardianCiToken === data.ciToken) {
				setSamePersonModal(true);
				return;
			}

			updateData({
				guardianCiToken,
				guardianName: name,
				guardianBirthDate: birthDate,
				guardianPhone: phone,
				guardianConsentDone: true,
			});

			history.push(getMinorRegisterRoute(5));
		},
		[updateData, history, data.ciToken],
	);

	// 법정대리인 간편인증 콜백
	const handleEasyAuthSuccess = useCallback(
		(result: EasysignResult): void => {
			if (result.resultCode !== '2000') { setFailedModal(true); return; }
			processGuardianAuth({
				ci: result.ci,
				name: result.name,
				birthDate: result.birthday || '',
				phone: result.phone || '',
			}).catch(() => setFailedModal(true));
		},
		[processGuardianAuth],
	);

	const { startAuth: startEasyAuth } = usePersonalEasyAuth(
		handleEasyAuthSuccess,
		() => setFailedModal(true),
	);

	// 법정대리인 NICE 휴대폰 인증 콜백
	const handlePhoneAuthSuccess = useCallback(
		(result: NicePhoneAuthResult): void => {
			if (result.resultCode !== '2000') { setFailedModal(true); return; }
			processGuardianAuth({
				ci: result.ci,
				name: result.name,
				birthDate: result.birthdate || '',
				phone: result.phone || '',
			}).catch(() => setFailedModal(true));
		},
		[processGuardianAuth],
	);

	const { busy: phoneAuthBusy, startAuth: startPhoneAuth } = useNicePhoneAuth(
		handlePhoneAuthSuccess,
		() => setFailedModal(true),
	);

	const handleNext = (): boolean => {
		if (!data.guardianConsentDone) {
			setAuthRequiredModal(true);
			return false;
		}
		return true;
	};

	return (
		<>
			<RegisterLayout
				currentStep={4}
				prevRoute={getMinorRegisterRoute(3)}
				nextRoute={getMinorRegisterRoute(5)}
				memberType="member"
				title="법정대리인 본인인증"
				onNext={handleNext}
			>
				{/* 법정대리인 동의 안내 배너 */}
				<div
					className="text-info-wrap"
					style={{
						background: '#fdf2f8',
						border: '1px solid #8e44ad',
						borderRadius: '8px',
						padding: '12px 16px',
						marginBottom: '16px',
					}}
					role="note"
					aria-label="법정대리인 본인인증 안내"
				>
					<p style={{ color: '#5b2c6f', fontSize: '14px', marginBottom: '8px' }}>
						<strong>법정대리인(친권자 또는 후견인)</strong>의 본인인증이 필요합니다.
					</p>
					<p style={{ color: '#5b2c6f', fontSize: '13px' }}>
						정보통신망법 제31조에 따라 만 14세 미만 아동의 개인정보 수집 시 법정대리인의
						동의가 반드시 필요합니다. 보호자(부모님 등) 명의의 인증 수단을 사용해 주세요.
					</p>
				</div>

				{/* 인증 성공 시 완료 표시 */}
				{data.guardianConsentDone && (
					<div
						style={{
							background: '#d5f5e3',
							border: '1px solid #27ae60',
							borderRadius: '8px',
							padding: '12px 16px',
							marginBottom: '16px',
							display: 'flex',
							alignItems: 'center',
							gap: '8px',
						}}
						role="status"
						aria-live="polite"
					>
						<i className="icon ico-check" aria-hidden="true" />
						<p style={{ color: '#1e8449', fontSize: '14px' }}>
							<strong>{data.guardianName}</strong> 법정대리인 본인인증이 완료되었습니다.
							'다음' 버튼을 눌러 계속 진행해 주세요.
						</p>
					</div>
				)}

				<div
					className="check-box-wrap"
					role="group"
					aria-label="법정대리인 본인인증 방식 선택"
				>
					<button
						type="button"
						className="check-box style2"
						aria-pressed={authType === 'app'}
						onClick={(): void => {
							setAuthType('app');
							startEasyAuth();
						}}
					>
						<div className="right-box">
							<figure>
								<img src={IMAGES.RENEWAL_CERT_APP_BIG} alt="" aria-hidden="true" />
							</figure>
							<div className="text-box">
								<strong className="tit">개인 간편인증서</strong>
								<p className="text">
									카카오, PASS 등 법정대리인 명의의 간편인증으로 동의를 확인합니다.
								</p>
							</div>
						</div>
					</button>
					<button
						type="button"
						className="check-box style2"
						aria-pressed={authType === 'phone'}
						disabled={phoneAuthBusy}
						onClick={(): void => {
							setAuthType('phone');
							startPhoneAuth();
						}}
					>
						<div className="right-box">
							<figure>
								<img src={IMAGES.RENEWAL_CERT_PHONE_BIG} alt="" aria-hidden="true" />
							</figure>
							<div className="text-box">
								<strong className="tit">휴대폰 인증</strong>
								<p className="text">
									법정대리인 명의의 휴대폰으로 인증번호를 받아 동의를 확인합니다.
								</p>
							</div>
						</div>
					</button>
					<button
						type="button"
						className="check-box style2"
						onClick={(): void => setDevNoticeModal(true)}
					>
						<div className="right-box">
							<figure>
								<img src={IMAGES.RENEWAL_CERT_JOINT_BIG} alt="" aria-hidden="true" />
							</figure>
							<div className="text-box">
								<strong className="tit">공동인증서</strong>
								<p className="text">
									공동인증서(구 공인인증서) 또는 금융인증서로 동의를 확인합니다.
								</p>
							</div>
						</div>
					</button>
				</div>

				{/* 인증 실패 모달 */}
				<Modal
					id="modal_guardian_auth_failed"
					isOpen={failedModal}
					onClose={(): void => setFailedModal(false)}
					topText="본인인증 오류"
					title="법정대리인 인증이 실패하였습니다"
					size="small"
					buttons={[
						{ label: '확인', variant: 'primary', onClick: (): void => setFailedModal(false) },
					]}
				>
					<p className="text">
						다른 방식으로 법정대리인 인증을 진행해 주시기 바랍니다.
					</p>
				</Modal>
			</RegisterLayout>

			{/* 동일인 경고 모달 */}
			<Modal
				id="modal_same_person"
				isOpen={samePersonModal}
				onClose={(): void => setSamePersonModal(false)}
				topText="안내"
				title="아동과 법정대리인이 동일인입니다"
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: (): void => setSamePersonModal(false) },
				]}
			>
				<p className="text">
					아동 본인인증과 동일한 정보로 법정대리인 인증을 진행하셨습니다. <br />
					법정대리인(친권자 또는 후견인)은 아동 본인과 다른 사람이어야 합니다.
				</p>
			</Modal>

			{/* 인증 필요 모달 */}
			<Modal
				id="modal_guardian_auth_required"
				isOpen={authRequiredModal}
				onClose={(): void => setAuthRequiredModal(false)}
				topText="안내"
				title="법정대리인 인증이 필요합니다"
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: (): void => setAuthRequiredModal(false) },
				]}
			>
				<p>법정대리인(친권자 또는 후견인)의 본인인증을 완료해 주세요.</p>
			</Modal>

			{/* 개발 중 안내 모달 */}
			<Modal
				id="modal_guardian_dev_notice"
				isOpen={devNoticeModal}
				onClose={(): void => setDevNoticeModal(false)}
				topText="안내"
				title="서비스 준비 중"
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: (): void => setDevNoticeModal(false) },
				]}
			>
				<p>현재 개발 중인 기능입니다.</p>
				<p><strong>개인 간편인증서</strong> 또는 <strong>휴대폰 인증</strong>을 이용해 주세요.</p>
			</Modal>
		</>
	);
}

export default RegisterMinorStep4;
