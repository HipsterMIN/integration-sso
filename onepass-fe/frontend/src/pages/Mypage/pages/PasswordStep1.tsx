import exchangeCiToken from 'api/provision/ciToken';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import type { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';
import type { EasysignResult } from 'hooks/usePersonalEasyAuth';
import usePersonalEasyAuth from 'hooks/usePersonalEasyAuth';
import history from 'lib/history';
import { FormEvent, useCallback, useEffect, useState } from 'react';
import { encryptCi } from 'utils/crypto/aesGcm';

import { clearAll, markAuthed, saveCiToken } from './passwordServices';

/** 성별 값을 M/F로 정규화 */
const normalizeGender = (raw?: string): 'M' | 'F' | '' => {
	if (!raw) return '';
	const v = raw.trim();
	if (['남', 'M', 'm', '1'].includes(v)) return 'M';
	if (['여', 'F', 'f', '2'].includes(v)) return 'F';
	return '';
};

function MemberAuth(): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);
	const [failedMessage, setFailedMessage] = useState('');

	// 진입 시 이전 플로우 상태 초기화
	useEffect(() => {
		clearAll();
	}, []);

	const issueCiToken = useCallback(async (ci: string, authInfo?: { name?: string; birthDate?: string; gender?: 'M' | 'F'; phone?: string }): Promise<void> => {
		const encrypted = await encryptCi(ci);
		const encryptedAuthData = authInfo
			? await encryptCi(JSON.stringify(authInfo))
			: undefined;
		const tokenResponse = await exchangeCiToken({
			encryptedCi: encrypted,
			encryptedAuthData,
			realm: 'ucube-qsign',
			clientId: 'onepassCli',
			flowContext: 'CHECK_CONVERSION',
		});
		if (
			tokenResponse.statusCode !== 200 ||
			tokenResponse.payload?.success === false ||
			!tokenResponse.payload?.data?.ciToken
		) {
			setFailedMessage(
				tokenResponse.payload?.message || tokenResponse.message || '',
			);
			setFailedModal(true);
			return;
		}
		saveCiToken(tokenResponse.payload.data.ciToken);
		markAuthed();
		history.push(ROUTES.MYPAGE_MEMBER_PASSWORD_STEP2);
	}, []);

	const handleEasyAuthSuccess = useCallback(
		(result: EasysignResult): void => {
			if (result.resultCode !== '2000' || !result.ci) {
				setFailedModal(true);
				return;
			}
			const { ci } = result;
			// eslint-disable-next-line no-param-reassign
			result.ci = undefined; // CI 평문 즉시 폐기
			issueCiToken(ci, {
				name: result.name?.normalize('NFC').trim(),
				birthDate: (result.birthday || '').replace(/\D/g, ''),
				gender: '',
				phone: (result.phone || '').replace(/\D/g, ''),
			}).catch((err) => {
				console.error('[MypagePassword] 간편인증 ciToken 발급 실패:', err);
				setFailedMessage('');
				setFailedModal(true);
			});
		},
		[issueCiToken],
	);

	const handlePhoneAuthSuccess = useCallback(
		(result: NicePhoneAuthResult): void => {
			if (result.resultCode !== '2000' || !result.ci) {
				setFailedModal(true);
				return;
			}
			const { ci } = result;
			// eslint-disable-next-line no-param-reassign
			result.ci = undefined; // CI 평문 즉시 폐기
			issueCiToken(ci, {
				name: result.name?.normalize('NFC').trim(),
				birthDate: (result.birthdate || '').replace(/\D/g, ''),
				gender: normalizeGender(result.gender),
				phone: (result.phone || '').replace(/\D/g, ''),
			}).catch((err) => {
				console.error('[MypagePassword] 휴대폰인증 ciToken 발급 실패:', err);
				setFailedMessage('');
				setFailedModal(true);
			});
		},
		[issueCiToken],
	);

	const handleAuthError = useCallback((): void => {
		setFailedModal(true);
	}, []);

	const { startAuth } = usePersonalEasyAuth(
		handleEasyAuthSuccess,
		handleAuthError,
	);

	const { busy: phoneAuthBusy, startAuth: startPhoneAuth } = useNicePhoneAuth(
		handlePhoneAuthSuccess,
		handleAuthError,
	);

	return (
		<>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>
								비밀번호 변경 시 본인 인증 후 안전하게 진행됩니다. 인증 정보는
								개인정보처리방침에 따라 안전하게 보호됩니다.
							</p>
						</li>
					</ul>
					<figure className="img">
						<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
			</div>
			<form
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="개인 인증"
			>
				<div className="white-wrap">
					<div className="title-box">
						<h3 className="h3-title">개인 인증</h3>
						<p className="text">
							비밀번호 변경 시 본인 인증 후 진행해 주시기 바랍니다
						</p>
					</div>
					<div className="check-box-wrap" role="group" aria-label="인증 수단 선택">
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
									<strong className="tit">개인인증서</strong>
									<p className="text">
										공동인증서(구 공인인증서) 또는 금융인증서를 활용하여 개인 정보를
										안전하고 확실하게 인증합니다
									</p>
								</div>
							</div>
						</button>
						<button
							type="button"
							className="check-box style2"
							onClick={(): void => {
								startAuth();
							}}
						>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_APP_BIG} alt="" aria-hidden="true" />
								</figure>
								<div className="text-box">
									<strong className="tit">개인 간편인증서</strong>
									<p className="text">
										별도의 보안 프로그램 설치 없이 네이버, 카카오, PASS 등 간편인증
										수단으로 본인 여부를 빠르게 확인하여 인증합니다
									</p>
								</div>
							</div>
						</button>
						<button
							type="button"
							className="check-box style2"
							disabled={phoneAuthBusy}
							onClick={(): void => {
								startPhoneAuth();
							}}
						>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_PHONE} alt="" aria-hidden="true" />
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
							onClick={(): void => setDevNoticeModal(true)}
						>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_ANY} alt="" aria-hidden="true" />
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
				</div>
			</form>
			<Modal
				id="modal_password_dev_notice"
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
					<strong>개인 간편인증서</strong> 또는 <strong>휴대폰 인증</strong>을 이용해
					주세요.
				</p>
			</Modal>
			<Modal
				id="modal_password_auth_failed"
				isOpen={failedModal}
				onClose={(): void => {
					setFailedModal(false);
					setFailedMessage('');
				}}
				topText="안내"
				title={failedMessage || '본인 인증이 실패하였습니다'}
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => {
							setFailedModal(false);
							setFailedMessage('');
						},
					},
				]}
			>
				{failedMessage ? (
					<p>{failedMessage}</p>
				) : (
					<>
						<p>해당 정보로 본인인증이 실패하였습니다.</p>
						<p>다른 방식으로 본인인증을 진행해 주시기 바랍니다.</p>
					</>
				)}
			</Modal>
		</>
	);
}

// 비밀번호 수정은 개인회원 전용 — 라우트도 /mypage-member/password 에만 등록됨
function PasswordStep1(): JSX.Element {
	return (
		<MypageContent>
			<MemberAuth />
		</MypageContent>
	);
}

export default PasswordStep1;
