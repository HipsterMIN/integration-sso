import exchangeCiToken from 'api/provision/ciToken';
import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import type { EzAuthBizResult } from 'hooks/useEzAuth';
import useEzAuth from 'hooks/useEzAuth';
import type { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';
import type { EasysignResult } from 'hooks/usePersonalEasyAuth';
import usePersonalEasyAuth from 'hooks/usePersonalEasyAuth';
import history from 'lib/history';
import { ChangeEvent, useCallback, useState } from 'react';
import { useHistory } from 'react-router-dom';
import { useRegister } from 'providers/Register/RegisterContext';
import { encryptCi } from 'utils/crypto/aesGcm';

import { getRegisterRoute } from '../routes';

/** 성별 값을 M/F로 정규화 */
const normalizeGender = (raw?: string): 'M' | 'F' | '' => {
	if (!raw) return '';
	const v = raw.trim();
	if (['남', 'M', 'm', '1'].includes(v)) return 'M';
	if (['여', 'F', 'f', '2'].includes(v)) return 'F';
	return '';
};

interface Step3Props {
	memberType?: MemberType;
	currentStep?: number;
}

function RegisterStep3({
	memberType = 'member',
	currentStep = 3,
}: Step3Props): JSX.Element {
	const isBusiness = memberType === 'business';
	const history = useHistory();
	const { data, updateData } = useRegister();
	const [authType, setAuthType] = useState<
		'certificate' | 'app' | 'phone' | 'anyid' | 'cert' | 'easy' | ''
	>('');
	const [noAccountModal, setNoAccountModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);
	const [failedMessage, setFailedMessage] = useState('');
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [authRequiredModal, setAuthRequiredModal] = useState(false);
	const [showAlert, setShowAlert] = useState(false);

	// 개인 간편인증 콜백
	const handleEasyAuthSuccess = useCallback(
		(result: EasysignResult): void => {
			if (result.resultCode !== '2000') {
				setFailedModal(true);
				return;
			}

			(async (): Promise<void> => {
				let ciToken = '';
				let mbrUuid = '';
				if (result.ci) {
					const encrypted = await encryptCi(result.ci);
					// eslint-disable-next-line no-param-reassign
					result.ci = undefined;
					const authData = {
						name: result.name?.normalize('NFC').trim(),
						birthDate: (result.birthday || '').replace(/\D/g, ''),
						gender: '',
						phone: (result.phone || '').replace(/\D/g, ''),
					};
					const encryptedAuthData = await encryptCi(JSON.stringify(authData));
					const tokenResponse = await exchangeCiToken({
						encryptedCi: encrypted,
						encryptedAuthData,
						realm: 'ucube-qsign',
						clientId: 'onepassCli',
						flowContext: 'PROVISION_USER',
					});
					if (tokenResponse.statusCode === 200 && tokenResponse.payload?.success !== false && tokenResponse.payload?.data) {
						ciToken = tokenResponse.payload.data.ciToken;
						mbrUuid = tokenResponse.payload.data.mbrUuid;
					} else {
						const msg = tokenResponse.payload?.message || tokenResponse.message || '';
						setFailedMessage(msg);
						setFailedModal(true);
						return;
					}
				}
				const phone = result.phone || '';
				updateData({
					name: result.name,
					phone,
					phonePrefix: phone.slice(0, 3) || '010',
					phoneSuffix: phone.slice(3),
					ciToken,
					mbrUuid,
					birthDate: result.birthday || '',
				});
				history.push(getRegisterRoute(currentStep + 1, memberType));
			})().catch(() => {
				setFailedMessage('');
				setFailedModal(true);
			});
		},
		[updateData, currentStep, memberType],
	);

	const handleEasyAuthError = useCallback((): void => {
		setFailedModal(true);
	}, []);

	const { startAuth: startEasyAuth } = usePersonalEasyAuth(
		handleEasyAuthSuccess,
		handleEasyAuthError,
	);

	// NICE 휴대폰 인증 콜백
	const handlePhoneAuthSuccess = useCallback(
		(result: NicePhoneAuthResult): void => {
			if (result.resultCode !== '2000') {
				setFailedModal(true);
				return;
			}

			(async (): Promise<void> => {
				let ciToken = '';
				let mbrUuid = '';
				if (result.ci) {
					const encrypted = await encryptCi(result.ci);
					// eslint-disable-next-line no-param-reassign
					result.ci = undefined;
					const authData = {
						name: result.name?.normalize('NFC').trim(),
						birthDate: (result.birthdate || '').replace(/\D/g, ''),
						gender: normalizeGender(result.gender),
						phone: (result.phone || '').replace(/\D/g, ''),
					};
					const encryptedAuthData = await encryptCi(JSON.stringify(authData));
					const tokenResponse = await exchangeCiToken({
						encryptedCi: encrypted,
						encryptedAuthData,
						realm: 'ucube-qsign',
						clientId: 'onepassCli',
						flowContext: 'PROVISION_USER',
					});
					if (tokenResponse.statusCode === 200 && tokenResponse.payload?.success !== false && tokenResponse.payload?.data) {
						ciToken = tokenResponse.payload.data.ciToken;
						mbrUuid = tokenResponse.payload.data.mbrUuid;
					} else {
						const msg = tokenResponse.payload?.message || tokenResponse.message || '';
						setFailedMessage(msg);
						setFailedModal(true);
						return;
					}
				}
				const phone = result.phone || '';
				updateData({
					name: result.name,
					phone,
					phonePrefix: phone.slice(0, 3) || '010',
					phoneSuffix: phone.slice(3),
					ciToken,
					mbrUuid,
					birthDate: result.birthdate || '',
				});
				history.push(getRegisterRoute(currentStep + 1, memberType));
			})().catch(() => {
				setFailedMessage('');
				setFailedModal(true);
			});
		},
		[updateData, currentStep, memberType],
	);

	const handlePhoneAuthError = useCallback((): void => {
		setFailedModal(true);
	}, []);

	const { busy: phoneAuthBusy, startAuth: startPhoneAuth } = useNicePhoneAuth(
		handlePhoneAuthSuccess,
		handlePhoneAuthError,
	);

	// 사업자 간편인증 (EzAuth SDK) 콜백
	const handleBizAuthSuccess = useCallback(
		(resultData?: EzAuthBizResult): void => {
			if (!resultData) {
				setFailedModal(true);
				return;
			}
			updateData({
				bzmnNm: resultData.name,
				rprsvNm: resultData.name,
				brno: resultData.businessNumber || data.brno,
			});
			history.push(getRegisterRoute(currentStep + 1, memberType));
		},
		[updateData, data.brno, history, currentStep, memberType],
	);

	const handleBizAuthError = useCallback((errno: number): void => {
		if (errno === 302) return; // 사용자 취소
		setFailedModal(true);
	}, []);

	const { loading: ezAuthLoading, startAuth: startEzAuth } = useEzAuth(
		handleBizAuthSuccess,
		handleBizAuthError,
	);

	const handleBrnoChange = (e: ChangeEvent<HTMLInputElement>): void => {
		const value = e.target.value.replace(/\D/g, '').slice(0, 10);
		updateData({ brno: value });
	};

	const handleNext = (): boolean => {
		if (isBusiness && data.brno.length !== 10) {
			setShowAlert(true);
			return false;
		}

		if (!isBusiness && !data.ciToken) {
			setAuthRequiredModal(true);
			return false;
		}

		return true;
	};

	return (
		<>
			<RegisterLayout
				currentStep={currentStep}
				prevRoute={getRegisterRoute(currentStep - 1, memberType)}
				nextRoute={getRegisterRoute(currentStep + 1, memberType)}
				memberType={memberType}
				title={isBusiness ? '기업인증 및 등록' : '개인인증 및 등록'}
				onNext={handleNext}
			>
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>유관시스템 서비스를 하나의 통합 ID로 연결합니다</p>
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

				{isBusiness ? (
					<>
						<div className="form-wrap">
							<div className="input-wrap style2">
								<label htmlFor="business_num">사업자등록번호</label>
								<div className="input-box">
									<input
										id="business_num"
										type="text"
										name="business_num"
										placeholder="사업자 등록번호를 입력해주세요"
										value={data.brno}
										onChange={handleBrnoChange}
										maxLength={10}
										required
										aria-required="true"
									/>
								</div>
							</div>
						</div>
						<div
							className="check-box-wrap"
							role="group"
							aria-label="기업인증 방식 선택"
						>
							<button
								type="button"
								className="check-box style2"
								aria-pressed={authType === 'cert'}
								onClick={(): void => {
									setAuthType('cert');
									setDevNoticeModal(true);
								}}
							>
								<div className="right-box">
									<figure>
										<img
											src={IMAGES.RENEWAL_CERT_JOINT_BIG}
											alt=""
											aria-hidden="true"
										/>
									</figure>
									<div className="text-box">
										<strong className="tit">기업인증서</strong>
										<p className="text">
											공동인증서(구 공인인증서) 또는 금융인증서를 활용하여 <br />
											기업 정보를 안전하고 확실하게 인증합니다
										</p>
									</div>
								</div>
							</button>
							<button
								type="button"
								className="check-box style2"
								aria-pressed={authType === 'easy'}
								disabled={ezAuthLoading}
								onClick={(): void => {
									setAuthType('easy');
									if (data.brno.length !== 10) {
										setShowAlert(true);
										return;
									}
									startEzAuth(data.brno);
								}}
							>
								<div className="right-box">
									<figure>
										<img
											src={IMAGES.RENEWAL_CERT_APP_BIG}
											alt=""
											aria-hidden="true"
										/>
									</figure>
									<div className="text-box">
										<strong className="tit">사업자 간편인증서</strong>
										<p className="text">
											별도의 보안 프로그램 설치 없이 네이버, 카카오, PASS 등 간편인증
											수단으로 사업자 여부를 빠르게 확인하여 인증합니다
										</p>
									</div>
								</div>
							</button>
						</div>
					</>
				) : (
					<div
						className="check-box-wrap"
						role="group"
						aria-label="본인인증 방식 선택"
					>
						<button
							type="button"
							className="check-box style2"
							aria-pressed={authType === 'certificate'}
							onClick={(): void => {
								setAuthType('certificate');
								setDevNoticeModal(true);
							}}
						>
							<div className="right-box">
								<figure>
									<img
										src={IMAGES.RENEWAL_CERT_JOINT_BIG}
										alt=""
										aria-hidden="true"
									/>
								</figure>
								<div className="text-box">
									<strong className="tit">공동인증서</strong>
									<p className="text">
										공동인증서(구 공인인증서) 또는 금융인증서를 활용하여 <br />
										개인 정보를 안전하고 확실하게 인증합니다
									</p>
								</div>
							</div>
						</button>
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
									<img
										src={IMAGES.RENEWAL_CERT_APP_BIG}
										alt=""
										aria-hidden="true"
									/>
								</figure>
								<div className="text-box">
									<strong className="tit">개인 간편인증서</strong>
									<p className="text">
										별도의 보안 프로그램 설치 없이 카카오, PASS 등 간편인증 수단으로
										본인 여부를 빠르게 확인합니다
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
									<img
										src={IMAGES.RENEWAL_CERT_PHONE_BIG}
										alt=""
										aria-hidden="true"
									/>
								</figure>
								<div className="text-box">
									<strong className="tit">휴대폰 인증</strong>
									<p className="text">
										본인 명의의 휴대폰으로 인증번호를 받아 빠르게 본인 여부를 확인합니다
									</p>
								</div>
							</div>
						</button>
						<button
							type="button"
							className="check-box style2"
							aria-pressed={authType === 'anyid'}
							onClick={(): void => {
								setAuthType('anyid');
								setDevNoticeModal(true);
							}}
						>
							<div className="right-box">
								<figure>
									<img
										src={IMAGES.RENEWAL_CERT_ANY}
										alt=""
										aria-hidden="true"
									/>
								</figure>
								<div className="text-box">
									<strong className="tit">Any-ID</strong>
									<p className="text">
										공공 디지털 서비스 통합 인증 (Any-ID) 으로 본인 여부를 확인합니다
									</p>
								</div>
							</div>
						</button>
					</div>
				)}

				<Modal
					id="modal_no_account"
					isOpen={noAccountModal}
					onClose={(): void => setNoAccountModal(false)}
					topText="계정 가입 안내"
					title="조회된 계정이 없습니다"
					buttons={[{ label: '확인', variant: 'primary' }]}
				>
					<p className="text">
						해당 정보로 인증 시 조회된 계정이 없습니다. <br />
						중기 통합회원을 이용하시기 위해서는 <br />
						회원가입 후 사용해 주세요.
					</p>
				</Modal>

				<Modal
					id="modal_failed_account"
					isOpen={failedModal}
					onClose={(): void => {
						if (failedMessage.includes('가입된 계정')) {
							const p = new URLSearchParams();
							if (data.returnUri) p.set('return_uri', data.returnUri);
							if (data.initialClientId) p.set('return_client', data.initialClientId);
							const qs = p.toString();
							history.push(ROUTES.LOGIN + (qs ? `?${qs}` : ''));
						} else {
							setFailedModal(false);
							setFailedMessage('');
						}
					}}
					topText="회원 가입 안내"
					title={
						failedMessage
							|| (isBusiness ? '기업 인증이 실패하였습니다' : '본인 인증이 실패하였습니다')
					}
					buttons={
						failedMessage.includes('가입된 계정')
							? [
									{
										label: '확인',
										variant: 'primary' as const,
										onClick: (): void => {
											const p = new URLSearchParams();
											if (data.returnUri) p.set('return_uri', data.returnUri);
											if (data.initialClientId) p.set('return_client', data.initialClientId);
											const qs = p.toString();
											history.push(ROUTES.LOGIN + (qs ? `?${qs}` : ''));
										},
									},
							  ]
							: [
									{ label: '닫기', variant: 'tertiary' as const },
									{ label: '확인', variant: 'primary' as const },
							  ]
					}
				>
					<p className="text">
						{failedMessage ? (
							failedMessage
						) : isBusiness ? (
							<>
								해당 정보로 기업인증이 실패하였습니다. <br />
								다른 방식으로 기업인증을 진행해 주시기 바랍니다.
							</>
						) : (
							<>
								해당 정보로 본인인증이 실패하였습니다. <br />
								다른 방식으로 본인인증을 진행해 주시기 바랍니다.
							</>
						)}
					</p>
				</Modal>
			</RegisterLayout>
			<Modal
				id="modal_auth_required"
				isOpen={authRequiredModal}
				onClose={(): void => setAuthRequiredModal(false)}
				topText="안내"
				title={isBusiness ? '기업인증이 필요합니다' : '본인인증이 필요합니다'}
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setAuthRequiredModal(false),
					},
				]}
			>
				<p>
					{isBusiness
						? '기업인증서 또는 사업자 간편인증서로 인증을 완료해주세요.'
						: '본인인증을 완료해주세요.'}
				</p>
			</Modal>
			<Modal
				id="modal_dev_notice"
				isOpen={devNoticeModal}
				onClose={(): void => setDevNoticeModal(false)}
				topText="안내"
				title="서비스 준비 중"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setDevNoticeModal(false),
					},
				]}
			>
				<p>현재 개발 중인 기능입니다.</p>
				<p>
					<strong>{isBusiness ? '사업자 간편인증서' : '개인 간편인증서'}</strong>를
					이용해 주세요.
				</p>
			</Modal>
			{isBusiness && (
				<Modal
					id="brno-validation-alert"
					isOpen={showAlert}
					onClose={(): void => setShowAlert(false)}
					topText="알림"
					title="사업자등록번호를 확인해주세요."
					size="small"
					buttons={[
						{
							label: '확인',
							variant: 'primary',
							onClick: (): void => setShowAlert(false),
						},
					]}
				>
					<p>사업자등록번호는 10자리를 입력해주세요.</p>
				</Modal>
			)}
		</>
	);
}

export default RegisterStep3;
