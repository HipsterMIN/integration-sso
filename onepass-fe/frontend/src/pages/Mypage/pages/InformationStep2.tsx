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
import { Redirect } from 'react-router-dom';

import { getMypageRoute } from './routes';

// PUB260507 business step2 구조 차용 — 개인 인증 카드 4개
// 아이콘은 conversion-member/step3 와 동일한 _BIG variant 사용 (style2 5rem figure 매칭)
function MemberAuth({ onNext }: { onNext: string }): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);

	const handleEasyAuthSuccess = useCallback(
		(result: EasysignResult): void => {
			if (result.resultCode !== '2000') {
				setFailedModal(true);
				return;
			}
			sessionStorage.setItem('mypage_information_step2_passed', '1');
			history.push(onNext);
		},
		[onNext],
	);

	const handlePhoneAuthSuccess = useCallback(
		(result: NicePhoneAuthResult): void => {
			if (result.resultCode !== '2000') {
				setFailedModal(true);
				return;
			}
			sessionStorage.setItem('mypage_information_step2_passed', '1');
			history.push(onNext);
		},
		[onNext],
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
								중기 통합회원의 회원정보는 개인정보처리방침에 따라
								안전하게 보호되며, 회원님의 명백한 동의 없이 제 3자에게
								제공되지 않습니다.
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
						<p className="text">나의 정보 변경 시 본인 인증 후 진행해 주시기 바랍니다</p>
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
				id="modal_mypage_dev_notice"
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
					<strong>개인 간편인증서</strong>를 이용해 주세요.
				</p>
			</Modal>
			<Modal
				id="modal_mypage_auth_failed"
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

// PUB260507 mypage_information_step2.html — 기업 인증 카드 2개 (기업인증서 / 사업자 간편인증서)
function BusinessAuth({ onNext }: { onNext: string }): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);

	const handleEzAuthSuccess = useCallback(
		(): void => {
			sessionStorage.setItem('mypage_information_step2_passed', '1');
			history.push(onNext);
		},
		[onNext],
	);

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
								중기 통합회원의 회원정보는 개인정보처리방침에 따라
								안전하게 보호되며, 회원님의 명백한 동의 없이 제 3자에게
								제공되지 않습니다.
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
				id="modal_biz_dev_notice"
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
				id="modal_biz_auth_failed"
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

function InformationStep2(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const nextRoute = getMypageRoute(memberType, 'INFORMATION_STEP3');
	const step1Route = getMypageRoute(memberType, 'INFORMATION');

	// /information 에서 "정보변경" 버튼을 거치지 않고 직접 진입 시 차단
	// 기업회원은 임시 비활성화 — 재활성화 시 !isBusiness 조건 제거
	if (!isBusiness && sessionStorage.getItem('mypage_information_step1_passed') !== '1') {
		return <Redirect to={step1Route} />;
	}

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

export default InformationStep2;
