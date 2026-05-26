import exchangeCiToken from 'api/provision/ciToken';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import useEzAuth from 'hooks/useEzAuth';
import type { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';
import type { EasysignResult } from 'hooks/usePersonalEasyAuth';
import usePersonalEasyAuth from 'hooks/usePersonalEasyAuth';
import history from 'lib/history';
import { FormEvent, useCallback, useState } from 'react';
import { encryptCi } from 'utils/crypto/aesGcm';

import { saveCiToken } from './affiliationServices';
import { getMypageRoute } from './routes';
import { loadUserId } from './useInfoStore';

/** 성별 값을 M/F로 정규화 */
const normalizeGender = (raw?: string): 'M' | 'F' | '' => {
	if (!raw) return '';
	const v = raw.trim();
	if (['남', 'M', 'm', '1'].includes(v)) return 'M';
	if (['여', 'F', 'f', '2'].includes(v)) return 'F';
	return '';
};

// PUB260507 mypage_affiliation_withdraw_step1.html — 기업 인증 카드 2개
function BusinessAuth({ onNext }: { onNext: string }): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);

	const handleEzAuthSuccess = useCallback((): void => {
		history.push(onNext);
	}, [onNext]);

	const handleEzAuthError = useCallback((): void => {
		history.push(onNext);
	}, [onNext]);

	const { loading, startAuth } = useEzAuth(handleEzAuthSuccess, handleEzAuthError);

	return (
		<>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>
								추가하기 버튼을 클릭하시면 중기원패스 통합회원을 이용하실 수
								있는 유관기관 항목을 보실 수 있습니다.
							</p>
						</li>
						<li>
							<p>
								이용중인 유관기관을 선택 후 회원탈퇴를 선택하시면 해당 유관기관을
								탈퇴하실 수 있습니다.
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
				aria-label="기업 인증"
			>
				<div className="white-wrap">
					<div className="title-box">
						<h3 className="h3-title">기업 인증</h3>
						<p className="text">나의 정보 변경 시 기업 인증 후 진행해 주시기 바랍니다</p>
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
									<strong className="tit">기업인증서</strong>
									<p className="text">
										공동인증서(구 공인인증서) 또는 금융인증서를 활용하여 기업 정보를
										안전하고 확실하게 인증합니다
									</p>
								</div>
							</div>
						</button>
						<button
							type="button"
							className="check-box style2"
							disabled={loading}
							onClick={(): void => startAuth()}
						>
							<div className="right-box">
								<figure>
									<img src={IMAGES.RENEWAL_CERT_APP_BIG} alt="" aria-hidden="true" />
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
				</div>
			</form>
			<Modal
				id="modal_affiliation_withdraw_dev_notice"
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
					<strong>사업자 간편인증서</strong>를 이용해 주세요.
				</p>
			</Modal>
			<Modal
				id="modal_affiliation_withdraw_auth_failed"
				isOpen={failedModal}
				onClose={(): void => setFailedModal(false)}
				topText="안내"
				title="기업 인증이 실패하였습니다"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setFailedModal(false),
					},
				]}
			>
				<p>해당 정보로 기업인증이 실패하였습니다.</p>
				<p>다른 방식으로 인증을 진행해 주시기 바랍니다.</p>
			</Modal>
		</>
	);
}

// 개인 인증 카드 4개 — 간편인증/휴대폰인증 시 CI→ciToken 발급
function MemberAuth({ onNext }: { onNext: string }): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);

	/** CI 암호화 → ciToken 발급 → sessionStorage 저장 → Step2 이동 */
	const processCiToken = useCallback(
		async (ci: string, authInfo?: { name?: string; birthDate?: string; gender?: 'M' | 'F' | ''; phone?: string }): Promise<void> => {
			const mbrUuid = loadUserId('member') || '';
			const encrypted = await encryptCi(ci);
			const tokenRes = await exchangeCiToken({
				encryptedCi: encrypted,
				realm: 'ucube-qsign',
				clientId: 'onepassCli',
				flowContext: 'USER_WITHDRAW',
				mbrUuid,
				...authInfo,
			});
			if (tokenRes.statusCode === 200 && tokenRes.payload?.data) {
				saveCiToken(tokenRes.payload.data.ciToken);
				history.push(onNext);
			} else {
				setFailedModal(true);
			}
		},
		[onNext],
	);

	const handleEasyAuthSuccess = useCallback(
		(result: EasysignResult): void => {
			if (result.resultCode !== '2000') {
				setFailedModal(true);
				return;
			}
			if (result.ci) {
				const ci = result.ci;
				// eslint-disable-next-line no-param-reassign
				result.ci = undefined; // CI 평문 즉시 폐기
				processCiToken(ci, {
					name: result.name?.normalize('NFC').trim(),
					birthDate: (result.birthday || '').replace(/\D/g, ''),
					phone: (result.phone || '').replace(/\D/g, ''),
					gender: normalizeGender(undefined),
				}).catch(() => setFailedModal(true));
			} else {
				setFailedModal(true);
			}
		},
		[processCiToken],
	);

	const handlePhoneAuthSuccess = useCallback(
		(result: NicePhoneAuthResult): void => {
			if (result.resultCode !== '2000') {
				setFailedModal(true);
				return;
			}
			if (result.ci) {
				const ci = result.ci;
				// eslint-disable-next-line no-param-reassign
				result.ci = undefined; // CI 평문 즉시 폐기
				processCiToken(ci, {
					name: result.name?.normalize('NFC').trim(),
					birthDate: (result.birthdate || '').replace(/\D/g, ''),
					gender: normalizeGender(result.gender),
					phone: (result.phone || '').replace(/\D/g, ''),
				}).catch(() => setFailedModal(true));
			} else {
				setFailedModal(true);
			}
		},
		[processCiToken],
	);

	const handleAuthError = useCallback((): void => {
		setFailedModal(true);
	}, []);

	const { startAuth } = usePersonalEasyAuth(handleEasyAuthSuccess, handleAuthError);
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
								추가하기 버튼을 클릭하시면 중기원패스 통합회원을 이용하실 수
								있는 유관기관 항목을 보실 수 있습니다.
							</p>
						</li>
						<li>
							<p>
								이용중인 유관기관을 선택 후 회원탈퇴를 선택하시면 해당 유관기관을
								탈퇴하실 수 있습니다.
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
						<p className="text">유관기관 탈퇴 시 본인 인증 후 진행해 주시기 바랍니다</p>
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
							onClick={(): void => { startAuth(); }}
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
							onClick={(): void => { startPhoneAuth(); }}
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
				id="modal_affiliation_withdraw_member_dev_notice"
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
					<strong>개인 간편인증서</strong> 또는 <strong>휴대폰 인증</strong>을 이용해 주세요.
				</p>
			</Modal>
			<Modal
				id="modal_affiliation_withdraw_member_auth_failed"
				isOpen={failedModal}
				onClose={(): void => setFailedModal(false)}
				topText="안내"
				title="본인 인증이 실패하였습니다"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setFailedModal(false),
					},
				]}
			>
				<p>해당 정보로 본인인증이 실패하였습니다.</p>
				<p>다른 방식으로 본인인증을 진행해 주시기 바랍니다.</p>
			</Modal>
		</>
	);
}

function AffiliationWithdrawStep1(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const nextRoute = getMypageRoute(memberType, 'AFFILIATION_WITHDRAW_STEP2');

	return (
		<MypageContent>
			{isBusiness ? (
				<BusinessAuth onNext={nextRoute} />
			) : (
				<MemberAuth onNext={nextRoute} />
			)}
		</MypageContent>
	);
}

export default AffiliationWithdrawStep1;
