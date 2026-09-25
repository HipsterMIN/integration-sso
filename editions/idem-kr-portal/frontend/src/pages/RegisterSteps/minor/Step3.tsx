/**
 * 만14세 미만 회원가입 Step3 — 아동 본인인증
 *
 * 아동(회원가입 당사자) 본인인증 수행.
 * 인증 성공 후:
 *   - ciToken, mbrUuid, birthDate, name, phone → RegisterContext에 저장
 *   - isMinor = true 확인 (이미 Step1에서 판정된 상태이나 이중 검증)
 *   - Step4(법정대리인 본인인증)으로 이동
 *
 * 주의: 이 페이지는 "만14세 미만 아동" 전용이므로
 *       인증 결과의 birthDate를 재검증하여 실제로 미성년자임을 확인한다.
 *       만14세 이상으로 확인되면 일반 회원가입 Step3으로 리다이렉트한다.
 */
import exchangeCiToken from 'api/provision/ciToken';
import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import type { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';
import type { EasysignResult } from 'hooks/usePersonalEasyAuth';
import usePersonalEasyAuth from 'hooks/usePersonalEasyAuth';
import { useCallback, useState } from 'react';
import { useHistory } from 'react-router-dom';
import { useRegister } from 'providers/Register/RegisterContext';
import { encryptCi } from 'utils/crypto/aesGcm';
import { isMinorByBirthDate } from './utils';
import { getMinorRegisterRoute } from './routes';

function RegisterMinorStep3(): JSX.Element {
	const history = useHistory();
	const { updateData } = useRegister();
	const [authType, setAuthType] = useState<'app' | 'phone' | ''>('');
	const [failedModal, setFailedModal] = useState(false);
	const [ageCheckFailModal, setAgeCheckFailModal] = useState(false);
	const [authRequiredModal, setAuthRequiredModal] = useState(false);
	const [devNoticeModal, setDevNoticeModal] = useState(false);

	/**
	 * 인증 결과 공통 처리:
	 *   1. CI → encryptCi → exchangeCiToken
	 *   2. birthDate로 만14세 미만 재검증
	 *   3. RegisterContext 업데이트 → Step4로 이동
	 */
	const processAuthResult = useCallback(
		async (params: {
			ci?: string;
			name?: string;
			birthDate?: string;
			phone?: string;
		}): Promise<void> => {
			const { ci, name = '', birthDate = '', phone = '' } = params;

			// 연령 재검증: 만14세 이상이면 일반 가입 플로우로 안내
			if (birthDate && !isMinorByBirthDate(birthDate)) {
				setAgeCheckFailModal(true);
				return;
			}

			let ciToken = '';
			let mbrUuid = '';

			if (ci) {
				const encrypted = await encryptCi(ci);
				const tokenResponse = await exchangeCiToken({
					encryptedCi: encrypted,
					realm: process.env.QSIGN_REALM || 'ucube-qsign',
					clientId: process.env.QSIGN_CLIENT_ID || 'onepassCli',
					flowContext: 'PROVISION_USER',
				});

				if (tokenResponse.statusCode === 200 && tokenResponse.payload?.data) {
					ciToken = tokenResponse.payload.data.ciToken;
					mbrUuid = tokenResponse.payload.data.mbrUuid;
				} else {
					setFailedModal(true);
					return;
				}
			}

			updateData({
				name,
				phone,
				phonePrefix: phone.slice(0, 3) || '010',
				phoneSuffix: phone.slice(3),
				ciToken,
				mbrUuid,
				birthDate,
				isMinor: true,
			});

			history.push(getMinorRegisterRoute(4));
		},
		[updateData, history],
	);

	// 간편인증 콜백
	const handleEasyAuthSuccess = useCallback(
		(result: EasysignResult): void => {
			if (result.resultCode !== '2000') { setFailedModal(true); return; }
			processAuthResult({
				ci: result.ci,
				name: result.name,
				birthDate: result.birthday || '',
				phone: result.phone || '',
			}).catch(() => setFailedModal(true));
		},
		[processAuthResult],
	);

	const { startAuth: startEasyAuth } = usePersonalEasyAuth(
		handleEasyAuthSuccess,
		() => setFailedModal(true),
	);

	// NICE 휴대폰 인증 콜백
	const handlePhoneAuthSuccess = useCallback(
		(result: NicePhoneAuthResult): void => {
			if (result.resultCode !== '2000') { setFailedModal(true); return; }
			processAuthResult({
				ci: result.ci,
				name: result.name,
				birthDate: result.birthdate || '',
				phone: result.phone || '',
			}).catch(() => setFailedModal(true));
		},
		[processAuthResult],
	);

	const { busy: phoneAuthBusy, startAuth: startPhoneAuth } = useNicePhoneAuth(
		handlePhoneAuthSuccess,
		() => setFailedModal(true),
	);

	const handleNext = (): boolean => {
		setAuthRequiredModal(true);
		return false;
	};

	return (
		<>
			<RegisterLayout
				currentStep={3}
				prevRoute={getMinorRegisterRoute(2)}
				nextRoute={getMinorRegisterRoute(4)}
				memberType="member"
				title="아동 본인인증"
				onNext={handleNext}
			>
				{/* 안내 배너 */}
				<div
					className="text-info-wrap"
					style={{
						background: '#e8f4fd',
						border: '1px solid #3498db',
						borderRadius: '8px',
						padding: '12px 16px',
						marginBottom: '16px',
					}}
					role="note"
					aria-label="아동 본인인증 안내"
				>
					<p style={{ color: '#1a5276', fontSize: '14px' }}>
						<strong>회원가입하는 아동 본인</strong>의 본인인증을 진행합니다.
						아동 명의의 휴대폰 또는 간편인증 수단을 사용해 주세요.
					</p>
				</div>

				<div
					className="check-box-wrap"
					role="group"
					aria-label="아동 본인인증 방식 선택"
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
									카카오, PASS 등 간편인증 수단으로 아동 본인인증을 진행합니다.
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
									아동 명의의 휴대폰으로 인증번호를 받아 본인확인을 진행합니다.
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
									공동인증서(구 공인인증서) 또는 금융인증서로 본인인증을 진행합니다.
								</p>
							</div>
						</div>
					</button>
				</div>

				{/* 인증 실패 모달 */}
				<Modal
					id="modal_minor_child_auth_failed"
					isOpen={failedModal}
					onClose={(): void => setFailedModal(false)}
					topText="본인인증 오류"
					title="본인인증이 실패하였습니다"
					size="small"
					buttons={[
						{ label: '확인', variant: 'primary', onClick: (): void => setFailedModal(false) },
					]}
				>
					<p className="text">
						다른 방식으로 본인인증을 진행해 주시기 바랍니다.
					</p>
				</Modal>
			</RegisterLayout>

			{/* 연령 불일치 모달 — 만14세 이상으로 확인된 경우 */}
			<Modal
				id="modal_age_check_fail"
				isOpen={ageCheckFailModal}
				onClose={(): void => setAgeCheckFailModal(false)}
				topText="안내"
				title="만 14세 이상으로 확인되었습니다"
				size="small"
				buttons={[
					{
						label: '일반 회원가입으로 이동',
						variant: 'primary',
						onClick: (): void => history.push(ROUTES.REGISTER_STEP1),
					},
					{
						label: '닫기',
						variant: 'tertiary',
						onClick: (): void => setAgeCheckFailModal(false),
					},
				]}
			>
				<p className="text">
					인증 결과 만 14세 이상으로 확인되었습니다. <br />
					일반 회원가입 페이지로 이동하여 가입을 진행해 주세요.
				</p>
			</Modal>

			{/* 인증 필요 모달 */}
			<Modal
				id="modal_minor_auth_required"
				isOpen={authRequiredModal}
				onClose={(): void => setAuthRequiredModal(false)}
				topText="안내"
				title="아동 본인인증이 필요합니다"
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: (): void => setAuthRequiredModal(false) },
				]}
			>
				<p>위의 인증 수단 중 하나를 선택하여 아동 본인인증을 완료해 주세요.</p>
			</Modal>

			{/* 개발 중 안내 모달 */}
			<Modal
				id="modal_minor_dev_notice"
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
				<p><strong>개인 간편인증서</strong>를 이용해 주세요.</p>
			</Modal>
		</>
	);
}

export default RegisterMinorStep3;
