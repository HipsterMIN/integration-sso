import findLoginId from 'api/account/findLoginId';
import exchangeCiToken from 'api/provision/ciToken';
import Modal from 'components/KrdsModal';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import useNicePhoneAuth, { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import usePersonalEasyAuth, { EasysignResult } from 'hooks/usePersonalEasyAuth';
import history from 'lib/history';
import { useCallback, useState } from 'react';
import { encryptCi } from 'utils/crypto/aesGcm';

import { saveFoundLoginId } from './services';

/** 성별 값을 M/F로 정규화 */
const normalizeGender = (raw?: string): 'M' | 'F' | '' => {
	if (!raw) return '';
	const v = raw.trim();
	if (['남', 'M', 'm', '1'].includes(v)) return 'M';
	if (['여', 'F', 'f', '2'].includes(v)) return 'F';
	return '';
};

// PUB260513 find_id_member.html — 개인회원 아이디 찾기 step1 (인증 수단 선택)
// /mypage-member/information/step2 패턴 차용 — 4 카드: 개인인증서 / 개인 간편인증서 / 휴대폰 / Any-ID
function FindId(): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);
	const [failedMessage, setFailedMessage] = useState('');

	const lookupLoginId = useCallback(async (ci: string, authInfo?: { name?: string; birthDate?: string; gender?: 'M' | 'F'; phone?: string }): Promise<void> => {
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

		const findRes = await findLoginId({
			ciToken: tokenResponse.payload.data.ciToken,
		});
		if (findRes.statusCode !== 200 || !findRes.payload) {
			setFailedMessage(findRes.message || '');
			setFailedModal(true);
			return;
		}

		const { resultCode, data, errorMessage } = findRes.payload;
		if (resultCode === 'SUCCESS' && data?.loginId) {
			saveFoundLoginId(data.loginId);
			history.push(ROUTES.FIND_ID_RESULT);
			return;
		}
		if (resultCode === 'NOT_FOUND') {
			history.push(ROUTES.FIND_ID_NOT_FOUND);
			return;
		}
		setFailedMessage(errorMessage || '');
		setFailedModal(true);
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
			lookupLoginId(ci, {
				name: result.name?.normalize('NFC').trim(),
				birthDate: (result.birthday || '').replace(/\D/g, ''),
				gender: '',
				phone: (result.phone || '').replace(/\D/g, ''),
			}).catch((err) => {
				console.error('[FindId] 간편인증 아이디 조회 실패:', err);
				setFailedMessage('');
				setFailedModal(true);
			});
		},
		[lookupLoginId],
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
			lookupLoginId(ci, {
				name: result.name?.normalize('NFC').trim(),
				birthDate: (result.birthdate || '').replace(/\D/g, ''),
				gender: normalizeGender(result.gender),
				phone: (result.phone || '').replace(/\D/g, ''),
			}).catch((err) => {
				console.error('[FindId] 휴대폰인증 아이디 조회 실패:', err);
				setFailedMessage('');
				setFailedModal(true);
			});
		},
		[lookupLoginId],
	);

	const handleAuthError = useCallback((): void => {
		setFailedModal(true);
	}, []);

	const { startAuth: startEasyAuth } = usePersonalEasyAuth(
		handleEasyAuthSuccess,
		handleAuthError,
	);
	const { busy: phoneAuthBusy, startAuth: startPhoneAuth } = useNicePhoneAuth(
		handlePhoneAuthSuccess,
		handleAuthError,
	);

	return (
		<>
			<main id="main-content" className="container sub find_id step1 member">
				<div className="sub-body inner">
					<div className="page-title-wrap">
						<div className="page-title-text-box">
							<h2 className="page-title">중기 통합회원 전환</h2>
							<p className="page-text">
								하나의 아이디로 중소벤처기업부 유관기관의 서비스를 모두 이용해보세요!
							</p>
						</div>
						<figure className="img-box">
							<img src={IMAGES.RENEWAL_PAGE_TITLE_IMG} alt="" aria-hidden="true" />
						</figure>
					</div>
					<div className="form-container">
						<div className="form-wrap white-wrap">
							<div className="title-box">
								<h3 className="h3-title">아이디 찾기</h3>
								<p className="text">
									아래의 방식으로 개인 인증을 하시면 회원아이디를 확인하실 수 있습니다.
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
					</div>
				</div>
			</main>
			<Modal
				id="modal_find_id_dev_notice"
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
				id="modal_find_id_auth_failed"
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

export default FindId;
