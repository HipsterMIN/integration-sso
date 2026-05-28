import { withdrawEnterprise } from 'api/provision/enterprises';
import exchangeCiToken from 'api/provision/ciToken';
import { withdrawUser } from 'api/provision/users';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import Spinner from 'components/Spinner';
import IMAGES from 'constants/images';
import useEzAuth from 'hooks/useEzAuth';
import type { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';
import type { EasysignResult } from 'hooks/usePersonalEasyAuth';
import usePersonalEasyAuth from 'hooks/usePersonalEasyAuth';
import history from 'lib/history';
import { FormEvent, useCallback, useState } from 'react';
import { Redirect } from 'react-router-dom';
import { encryptCi } from 'utils/crypto/aesGcm';

import { saveCiToken } from './affiliationServices';
import { getMypageRoute } from './routes';
import { loadUserId, useInfoStore } from './useInfoStore';

const normalizeGender = (raw?: string): 'M' | 'F' | '' => {
	if (!raw) return '';
	const v = raw.trim();
	if (['남', 'M', 'm', '1'].includes(v)) return 'M';
	if (['여', 'F', 'f', '2'].includes(v)) return 'F';
	return '';
};

// 통합회원 탈퇴 — step2 (URL: /withdraw/step2): 인증 카드 선택
// AffiliationWithdrawStep1 과 동일 패턴 (기업/개인 인증서 + 간편인증서 + 휴대폰/Any-ID)

// 탈퇴 실패 시 fail 페이지에서 표시할 SP 기관명 리스트 추출
// API 의 failedCount 와 일치하도록 "성공계(SUCCESS/ALREADY_WITHDRAWN/NOT_FOUND) 가 아닌 모든 코드"를 실패로 판정
// (FAIL/CB_BLOCKED/TIMEOUT/CONNECTION_REFUSED/ENCRYPT_FAILED/EMPTY_RESPONSE/INVALID_RESPONSE/UNKNOWN 포함)
const SUCCESS_RESULT_CODES = new Set([
	'SUCCESS',
	'ALREADY_WITHDRAWN',
	'NOT_FOUND',
]);

function extractFailedInstNames(
	perAgency: { instNm?: string; resultCode?: string }[] | undefined,
): string[] {
	if (!perAgency) return [];
	return perAgency
		.filter((a) => !SUCCESS_RESULT_CODES.has(a.resultCode ?? '') && a.instNm)
		.map((a) => a.instNm as string);
}

export const WITHDRAW_FAIL_LIST_KEY = 'mypage_withdraw_fail_list';

function BusinessAuth({
	onNext,
	onFail,
	brno,
}: {
	onNext: string;
	onFail: string;
	brno: string;
}): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);
	const [apiLoading, setApiLoading] = useState(false);

	const goToFailPage = useCallback(
		(failedInstNames: string[] = []): void => {
			sessionStorage.setItem('mypage_withdraw_fail_passed', '1');
			sessionStorage.setItem(
				WITHDRAW_FAIL_LIST_KEY,
				JSON.stringify(failedInstNames),
			);
			history.push(onFail);
		},
		[onFail],
	);

	/** EzAuth 성공 → 기업 회원 탈퇴 API 호출 → Complete 이동 (실패 시 fail 페이지) */
	const handleEzAuthSuccess = useCallback(async (): Promise<void> => {
		if (!brno) {
			goToFailPage();
			return;
		}
		const reqBody = { brno };
		setApiLoading(true);
		try {
			const res = await withdrawEnterprise(reqBody);
			// SP cascade 전체 성공 여부로 판정: data.failedCount === 0 일 때만 탈퇴 성공
			const ok =
				res.statusCode === 200 && res.payload?.data?.failedCount === 0;
			if (ok) {
				sessionStorage.setItem('mypage_withdraw_step2_passed', '1');
				history.push(onNext);
			} else {
				goToFailPage(extractFailedInstNames(res.payload?.data?.perAgency));
			}
		} catch {
			goToFailPage();
		} finally {
			setApiLoading(false);
		}
	}, [onNext, brno, goToFailPage]);

	// TODO(임시): EzAuth 창 닫기/실패 콜백에서도 탈퇴 API 진행. 실제 인증 연동 시 setFailedModal(true) 로 복원.
	const handleEzAuthError = useCallback((): void => {
		void handleEzAuthSuccess();
	}, [handleEzAuthSuccess]);

	const { loading, startAuth } = useEzAuth(handleEzAuthSuccess, handleEzAuthError);

	if (apiLoading) {
		return <Spinner tip="회원 탈퇴 처리 중입니다..." height="400px" />;
	}

	return (
		<>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>중기 통합회원을 이용해 주신 회원님께 진심으로 감사드립니다.</p>
						</li>
						<li>
							<p>
								탈퇴 이후에 재가입은 가능하지만 기존에 사용하였던 ID는 더이상
								사용할 수 없습니다.
							</p>
						</li>
						<li>
							<p>기업회원은 해당 기업관리자만이 회원탈퇴가 가능합니다.</p>
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
						<p className="text">회원 탈퇴 시 기업 인증 후 진행해 주시기 바랍니다</p>
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
							onClick={(): void => { startAuth(brno); }}
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
				id="modal_withdraw_business_dev_notice"
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
				id="modal_withdraw_business_auth_failed"
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

// 개인 인증 카드 4개 — 간편인증/휴대폰인증 시 CI→ciToken 발급 → 회원 탈퇴 호출
function MemberAuth({
	onNext,
	onFail,
}: {
	onNext: string;
	onFail: string;
}): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);
	const [apiLoading, setApiLoading] = useState(false);

	const goToFailPage = useCallback(
		(failedInstNames: string[] = []): void => {
			sessionStorage.setItem('mypage_withdraw_fail_passed', '1');
			sessionStorage.setItem(
				WITHDRAW_FAIL_LIST_KEY,
				JSON.stringify(failedInstNames),
			);
			history.push(onFail);
		},
		[onFail],
	);

	/** CI 암호화 → ciToken 발급 → 개인 회원 탈퇴 API 호출 → Complete 이동 (실패 시 fail 페이지) */
	const processCiToken = useCallback(
		async (ci: string, authInfo?: { name?: string; birthDate?: string; gender?: 'M' | 'F' | ''; phone?: string }): Promise<void> => {
			setApiLoading(true);
			try {
				const mbrUuid = loadUserId('member') || '';
				const encrypted = await encryptCi(ci);
				const encryptedAuthData = authInfo
					? await encryptCi(JSON.stringify(authInfo))
					: undefined;
				const tokenRes = await exchangeCiToken({
					encryptedCi: encrypted,
					encryptedAuthData,
					realm: 'ucube-qsign',
					clientId: 'onepassCli',
					flowContext: 'USER_WITHDRAW',
					mbrUuid,
				});
				if (tokenRes.statusCode !== 200 || !tokenRes.payload?.data) {
					setFailedModal(true);
					return;
				}

				const ciToken = tokenRes.payload.data.ciToken;
				saveCiToken(ciToken);

				const withdrawRes = await withdrawUser({
					ciToken,
				});
				// SP cascade 전체 성공 여부로 판정: data.failedCount === 0 일 때만 탈퇴 성공
				const ok =
					withdrawRes.statusCode === 200 &&
					withdrawRes.payload?.data?.failedCount === 0;
				if (ok) {
					sessionStorage.setItem('mypage_withdraw_step2_passed', '1');
					history.push(onNext);
				} else {
					goToFailPage(
						extractFailedInstNames(withdrawRes.payload?.data?.perAgency),
					);
				}
			} catch {
				goToFailPage();
			} finally {
				setApiLoading(false);
			}
		},
		[onNext, goToFailPage],
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
					gender: '',
					phone: (result.phone || '').replace(/\D/g, ''),
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
					gender: normalizeGender((result as unknown as { gender?: string }).gender),
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

	if (apiLoading) {
		return <Spinner tip="회원 탈퇴 처리 중입니다..." height="400px" />;
	}

	return (
		<>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>중기 통합회원을 이용해 주신 회원님께 진심으로 감사드립니다.</p>
						</li>
						<li>
							<p>
								탈퇴 이후에 재가입은 가능하지만 기존에 사용하였던 ID는 더이상
								사용할 수 없습니다.
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
						<p className="text">회원 탈퇴 시 본인 인증 후 진행해 주시기 바랍니다</p>
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
				id="modal_withdraw_member_dev_notice"
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
				id="modal_withdraw_member_auth_failed"
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

function WithdrawStep2(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const completeRoute = getMypageRoute(memberType, 'WITHDRAW_COMPLETE');
	const failRoute = getMypageRoute(memberType, 'WITHDRAW_FAIL');
	const step1Route = getMypageRoute(memberType, 'WITHDRAW');
	const { business } = useInfoStore();

	// step1(안내 페이지)에서 "탈퇴하기" 버튼을 거치지 않고 직접 진입 시 차단
	if (sessionStorage.getItem('mypage_withdraw_step1_passed') !== '1') {
		return <Redirect to={step1Route} />;
	}

	return (
		<MypageContent>
			{isBusiness ? (
				<BusinessAuth
					onNext={completeRoute}
					onFail={failRoute}
					brno={business.company_num}
				/>
			) : (
				<MemberAuth onNext={completeRoute} onFail={failRoute} />
			)}
		</MypageContent>
	);
}

export default WithdrawStep2;
