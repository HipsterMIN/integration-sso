import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import type { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';
import type { EasysignResult } from 'hooks/usePersonalEasyAuth';
import usePersonalEasyAuth from 'hooks/usePersonalEasyAuth';
import history from 'lib/history';
import { FormEvent, useCallback, useState } from 'react';

import { getMypageRoute } from './routes';

function MemberAuth({ onNext }: { onNext: string }): JSX.Element {
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const [failedModal, setFailedModal] = useState(false);

	// TODO: API 배포 후 복원 — 인증 성공 시 다음 단계로 이동
	const handleEasyAuthSuccess = useCallback(
		(result: EasysignResult): void => {
			if (result.resultCode !== '2000') {
				setFailedModal(true);
				return;
			}
			setDevNoticeModal(true);
			// history.push(onNext);
		},
		[],
	);

	const handlePhoneAuthSuccess = useCallback(
		(result: NicePhoneAuthResult): void => {
			if (result.resultCode !== '2000') {
				setFailedModal(true);
				return;
			}
			setDevNoticeModal(true);
			// history.push(onNext);
		},
		[],
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
				<div className="cont-title-box">
					<h3 className="tit">회원유형</h3>
				</div>
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
						<p className="text">비밀번호 변경 시 본인 인증 후 진행해 주시기 바랍니다</p>
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
				<p>빠른 시일 내에 서비스를 제공할 예정입니다.</p>
			</Modal>
			<Modal
				id="modal_password_auth_failed"
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

function BusinessAuth({ onNext }: { onNext: string }): JSX.Element {
	// ⚠️ [REQUIRES_MANUAL] 기업 인증 API 연동 완료 후 아래 버튼 onClick을 실제 인증 로직으로 교체 필요
	// 현재: 개발 중 안내 모달만 표시 (기업인증 API 미구현)
	const [devNoticeModal, setDevNoticeModal] = useState(false);

	return (
		<>
			<div className="title-top-box">
				<div className="cont-title-box">
					<h3 className="tit">회원유형</h3>
				</div>
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>
								비밀번호 변경 시 기업 인증 후 안전하게 진행됩니다. 인증 정보는
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
				aria-label="기업 인증"
			>
				<div className="white-wrap">
					<div className="title-box">
						<h3 className="h3-title">기업 인증</h3>
						<p className="text">비밀번호 변경 시 기업 인증 후 진행해 주시기 바랍니다</p>
					</div>
					<div className="check-box-wrap" role="group" aria-label="인증 수단 선택">
						<button type="button" className="check-box style2" onClick={(): void => setDevNoticeModal(true)}>
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
						<button type="button" className="check-box style2" onClick={(): void => setDevNoticeModal(true)}>
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
				id="modal_biz_password_dev_notice"
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
				<p>빠른 시일 내에 서비스를 제공할 예정입니다.</p>
			</Modal>
		</>
	);
}

function PasswordStep1(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const nextRoute = getMypageRoute(memberType, 'PASSWORD_STEP2');

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

export default PasswordStep1;
