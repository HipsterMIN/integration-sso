import './Login.styles.scss';

import AnyIdLoginModal from 'components/AnyIdLoginModal';
import Modal from 'components/KrdsModal';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import type { EzAuthBizResult } from 'hooks/useEzAuth';
import useEzAuth from 'hooks/useEzAuth';
import useAnyIdAuth, { AnyIdAuthResult } from 'hooks/useAnyIdAuth';
import useNicePhoneAuth, { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
import usePersonalEasyAuth, { EasysignResult } from 'hooks/usePersonalEasyAuth';
import history from 'lib/history';
import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';

import { ERROR_MESSAGES } from './constants';
import { useKeycloakParams, useSectionAnimation } from './hooks';

function Login(): JSX.Element {
	const [activeTab, setActiveTab] = useState<'member' | 'business'>('member');
	const [id, setId] = useState('');
	const [password, setPassword] = useState('');
	const [bizNo, setBizNo] = useState('');
	// const [bizPassword, setBizPassword] = useState('');
	const [saveId, setSaveId] = useState(false);
	const [isLoading, setIsLoading] = useState(false);
	const [errorModal, setErrorModal] = useState(false);
	const [errorTopText, setErrorTopText] = useState('');
	const [errorTitle, setErrorTitle] = useState('');
	const [errorMessage, setErrorMessage] = useState('');
	const [devNoticeModal, setDevNoticeModal] = useState(false);

	const [encCi, setEncCi] = useState('');

	const memberIdRef = useRef<HTMLInputElement>(null);
	const bizNoRef = useRef<HTMLInputElement>(null);
	const memberFormRef = useRef<HTMLFormElement>(null);
	const bizFormRef = useRef<HTMLFormElement>(null);
	const easyAuthFormRef = useRef<HTMLFormElement>(null);

	const { actionUrl, error, code, returnUri, returnClient } = useKeycloakParams();

	// 개인 간편인증 훅
	const { busy: easyAuthBusy, startAuth: startEasyAuth } = usePersonalEasyAuth(
		useCallback((result: EasysignResult) => {
			if (result.resultCode === '2000') {
				setEncCi(result.ci || '');
			} else {
				setErrorTopText(result.resultCode);
				setErrorTitle(result.resultMsg);
				setErrorMessage(result.resultMsg);
				setErrorModal(true);
			}
		}, []),
		useCallback((message: string) => {
			setErrorTopText('간편인증 오류');
			setErrorTitle(message);
			setErrorMessage(message);
			setErrorModal(true);
		}, []),
	);

	// 기업 간편인증 훅 (EzAuth SDK)
	const handleBizEzAuthSuccess = useCallback(
		(data?: EzAuthBizResult): void => {
			if (!data?.businessNumber) {
				showError(
					'인증 오류',
					'인증 결과를 확인할 수 없습니다',
					'사업자등록번호를 가져오지 못했습니다. 다시 시도해주세요.',
				);
				return;
			}
			if (!actionUrl) {
				showError(
					'접근 오류',
					'비정상적인 접근입니다',
					'정상적인 경로를 통해 다시 시도해주세요.',
				);
				return;
			}
			setBizNo(data.businessNumber);
			// setState 후 즉시 submit하면 값이 반영 안되므로 setTimeout 사용
			setTimeout(() => {
				bizFormRef.current?.submit();
			}, 0);
		},
		[actionUrl],
	);

	const handleBizEzAuthError = useCallback(
		(errno: number, error?: string): void => {
			if (errno === 302) return; // 사용자 취소
			showError(
				'기업 간편인증 오류',
				'기업 간편인증에 실패하였습니다',
				error || `인증 오류가 발생했습니다. (${errno})`,
			);
		},
		[],
	);

	const { loading: bizEzAuthLoading, startAuth: startBizEzAuth } = useEzAuth(
		handleBizEzAuthSuccess,
		handleBizEzAuthError,
	);

	// Any-ID 정부 통합인증 훅
	const handleAnyIdSuccess = useCallback(
		(result: AnyIdAuthResult): void => {
			if (result.resultCode === '2000' && result.ci) {
				setEncCi(result.ci);
			} else {
				setErrorTopText('Any-ID 인증 오류');
				setErrorTitle('인증에 실패하였습니다');
				setErrorMessage(result.resultMsg ?? 'Any-ID 인증 오류가 발생했습니다.');
				setErrorModal(true);
			}
		},
		[],
	);

	const handleAnyIdError = useCallback(
		(message: string): void => {
			setErrorTopText('Any-ID 인증 오류');
			setErrorTitle('인증에 실패하였습니다');
			setErrorMessage(message);
			setErrorModal(true);
		},
		[],
	);

	const {
		busy: anyIdBusy,
		showModal: anyIdModalOpen,
		startAuth: startAnyIdAuth,
		closeModal: closeAnyIdModal,
		initSdk: initAnyIdSdk,
	} = useAnyIdAuth(handleAnyIdSuccess, handleAnyIdError, {
		actionUrl,
		authLevel: 2,
		bypass: 0,
	});

	// NICE 휴대폰 인증 훅
	const { busy: phoneAuthBusy, startAuth: startPhoneAuth } = useNicePhoneAuth(
		useCallback((result: NicePhoneAuthResult) => {
			if (result.resultCode === '2000') {
				setEncCi(result.ci || '');
			} else {
				setErrorTopText(result.resultCode);
				setErrorTitle(result.resultMsg);
				setErrorMessage(result.resultMsg);
				setErrorModal(true);
			}
		}, []),
		useCallback((message: string) => {
			setErrorTopText('휴대폰 인증 오류');
			setErrorTitle(message);
			setErrorMessage(message);
			setErrorModal(true);
		}, []),
	);

	// 간편인증 결과 수신 → form POST 전송
	useEffect(() => {
		if (encCi && actionUrl && easyAuthFormRef.current) {
			easyAuthFormRef.current.submit();
		}
	}, [encCi, actionUrl]);

	useSectionAnimation();

	// action_url 이 없는 경우 모달 표시
	useEffect(() => {
		if (!actionUrl) {
			if (error && code) {
				// Q-Sign 에러로 돌아온 경우
				const message =
					ERROR_MESSAGES[code] || `로그인 오류가 발생했습니다. (${code})`;
				setErrorTopText('로그인 오류');
				setErrorTitle('인증 서비스 연동에 실패하였습니다');
				setErrorMessage(message);
			} else {
				// 비정상 접근
				setErrorTopText('접근 오류');
				setErrorTitle('비정상적인 접근입니다');
				setErrorMessage('정상적인 경로를 통해 다시 시도해주세요.');
			}
			setErrorModal(true);
		}
	}, [actionUrl, error, code]);

	// Q-Sign 에러이지만 action_url이 있는 경우 (재로그인 가능)
	useEffect(() => {
		if (actionUrl && error && code) {
			const message =
				ERROR_MESSAGES[code] || `로그인 오류가 발생했습니다. (${code})`;
			setErrorTopText('로그인 오류');
			setErrorTitle('인증 서비스 연동에 실패하였습니다');
			setErrorMessage(message);
			setErrorModal(true);
		}
	}, [actionUrl, error, code]);

	// 탭 전환 시 첫 입력 필드에 포커스
	useEffect(() => {
		if (activeTab === 'member') {
			memberIdRef.current?.focus();
		} else {
			bizNoRef.current?.focus();
		}
	}, [activeTab]);

	const handleTabKeyDown = useCallback(
		(tab: 'member' | 'business') => (e: React.KeyboardEvent) => {
			if (e.key === 'Enter' || e.key === ' ') {
				e.preventDefault();
				setActiveTab(tab);
			}
		},
		[],
	);

	const showError = (topText: string, title: string, message: string): void => {
		setErrorTopText(topText);
		setErrorTitle(title);
		setErrorMessage(message);
		setErrorModal(true);
	};

	/** 일반 회원 로그인 - form POST 전송 */
	const onMemberSubmit = (e: FormEvent): void => {
		e.preventDefault();
		if (!id || !password) {
			showError(
				'입력 오류',
				'아이디와 비밀번호를 입력하세요',
				'아이디와 비밀번호를 모두 입력한 후 로그인해 주세요.',
			);
			return;
		}
		if (!actionUrl) {
			showError(
				'접근 오류',
				'비정상적인 접근입니다',
				'정상적인 경로를 통해 다시 시도해주세요.',
			);
			return;
		}

		setIsLoading(true);
		// hidden form으로 POST 전송
		memberFormRef.current?.submit();
	};

	/** 기업 회원 로그인 - form POST 전송 */
	const onBizSubmit = (e: FormEvent): void => {
		e.preventDefault();
		if (!bizNo) {
			showError(
				'입력 오류',
				'사업자등록번호를 입력하세요',
				'사업자등록번호를 입력한 후 로그인해 주세요.',
			);
			return;
		}
		if (bizNo.length !== 10) {
			showError(
				'입력 오류',
				'사업자등록번호를 확인해주세요',
				'사업자등록번호는 10자리를 입력해주세요.',
			);
			return;
		}
		if (!actionUrl) {
			showError(
				'접근 오류',
				'비정상적인 접근입니다',
				'정상적인 경로를 통해 다시 시도해주세요.',
			);
			return;
		}

		setIsLoading(true);
		bizFormRef.current?.submit();
	};

	const handleErrorModalClose = (): void => {
		setErrorModal(false);
		// action_url 없이 에러만 온 경우 메인으로 이동
		if (!actionUrl) {
			window.location.href = '/';
		}
	};

	return (
		<>
			<Modal
				id="modal_login_error"
				isOpen={errorModal}
				onClose={handleErrorModalClose}
				topText={errorTopText}
				title={errorTitle}
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: handleErrorModalClose },
				]}
			>
				<p>{errorMessage}</p>
			</Modal>

			<Modal
				id="modal_login_dev_notice"
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
					<strong>
						{activeTab === 'business' ? '기업 간편인증서' : '개인 간편인증서'}
					</strong>
					를 이용해 주세요.
				</p>
			</Modal>

			{/* Any-ID 정부 통합로그인 모달 */}
			<AnyIdLoginModal
				isOpen={anyIdModalOpen}
				onClose={closeAnyIdModal}
				onInit={initAnyIdSdk}
			/>

			<div className="container main">
				<div className="inner">
					<div className="page-title-wrap">
						<div className="page-title-text-box">
							<h2 className="page-title">중기원패스 회원 전환</h2>
							<p className="page-text">
								하나의 아이디로 중소벤처기업부 유관기관의 서비스를 모두 이용해보세요!
							</p>
						</div>
						<figure className="img-box">
							<img
								src={IMAGES.RENEWAL_PAGE_TITLE_IMG}
								alt=""
								aria-hidden="true"
							/>
						</figure>
					</div>

					<div className="tab-wrap">
						<div
							className="tab-list style1"
							role="tablist"
							aria-label="로그인 유형 선택"
						>
							<ul>
								<li className={activeTab === 'member' ? 'active' : ''}>
									<a
										href="#member"
										role="tab"
										id="tab-member"
										aria-selected={activeTab === 'member'}
										aria-controls="panel-member"
										tabIndex={activeTab === 'member' ? 0 : -1}
										onClick={(e): void => {
											e.preventDefault();
											setActiveTab('member');
										}}
										onKeyDown={handleTabKeyDown('member')}
									>
										<p>일반 회원 로그인</p>
									</a>
								</li>
								<li className={activeTab === 'business' ? 'active' : ''}>
									<a
										href="#business"
										role="tab"
										id="tab-business"
										aria-selected={activeTab === 'business'}
										aria-controls="panel-business"
										tabIndex={activeTab === 'business' ? 0 : -1}
										onClick={(e): void => {
											e.preventDefault();
											setActiveTab('business');
										}}
										onKeyDown={handleTabKeyDown('business')}
									>
										<p>기업 회원 로그인</p>
									</a>
								</li>
							</ul>
						</div>

						<div className="tab-cont">
							{activeTab === 'member' && (
								<div
									className="cont01 active"
									id="panel-member"
									role="tabpanel"
									aria-labelledby="tab-member"
								>
									{/* POST 전송용 hidden form - 개인회원 ID/PW */}
									<form
										ref={memberFormRef}
										method="POST"
										action={actionUrl || ''}
										style={{ display: 'none' }}
									>
										<input type="hidden" name="loginType" value="IND" />
										<input type="hidden" name="loginId" value={id} />
										<input type="hidden" name="password" value={password} />
									</form>

									{/* POST 전송용 hidden form - 개인 인증 로그인 (간편인증/휴대폰인증) */}
									<form
										ref={easyAuthFormRef}
										method="POST"
										action={actionUrl || ''}
										style={{ display: 'none' }}
									>
										<input type="hidden" name="loginType" value="IND_CI" />
										<input type="hidden" name="encCi" value={encCi} />
									</form>

									<form
										className="form-wrap white-wrap"
										onSubmit={onMemberSubmit}
										aria-label="일반 회원 로그인"
									>
										<div className="input-wrap">
											<label htmlFor="loginId">아이디</label>
											<div className="input-box">
												<input
													ref={memberIdRef}
													id="loginId"
													type="text"
													name="id"
													placeholder="아이디를 입력하세요"
													required
													autoComplete="username"
													aria-required="true"
													value={id}
													onChange={(e): void => setId(e.target.value)}
													disabled={isLoading}
												/>
											</div>
										</div>
										<div className="input-wrap">
											<label htmlFor="loginPassword">비밀번호</label>
											<div className="input-box">
												<input
													id="loginPassword"
													type="password"
													name="password"
													placeholder="비밀번호를 입력하세요"
													required
													autoComplete="current-password"
													aria-required="true"
													value={password}
													onChange={(e): void => setPassword(e.target.value)}
													disabled={isLoading}
												/>
											</div>
										</div>

										<label className="check-box style1">
											<input
												type="checkbox"
												name="save_id"
												checked={saveId}
												onChange={(e): void => setSaveId(e.target.checked)}
												aria-label="아이디 저장"
											/>
											<small>아이디 저장</small>
										</label>

										<button
											type="submit"
											className="btn point login-btn"
											disabled={isLoading}
											aria-busy={isLoading}
										>
											<span>{isLoading ? '로그인 중...' : '로그인'}</span>
										</button>

										<div className="find-wrap">
											<ul className="find-btn" aria-label="계정 찾기">
												<li>
													<button
														type="button"
														className="btn text id"
														aria-label="아이디 찾기"
													>
														<span>아이디 찾기</span>
													</button>
												</li>
												<li>
													<button
														type="button"
														className="btn text password"
														aria-label="비밀번호 찾기"
													>
														<span>비밀번호 찾기</span>
													</button>
												</li>
											</ul>
											<button
												type="button"
												className="btn text homepage-btn"
												aria-label="홈페이지로 돌아가기"
												onClick={(): void => {
													if (returnUri) {
														window.location.href = returnUri;
													}
												}}
												disabled={!returnUri}
											>
												<i
													className="icon ico-reset-exposure small"
													aria-hidden="true"
												/>
												<span>홈페이지로 돌아가기</span>
											</button>
										</div>

										<div className="join-box">
											<strong>
												중기원패스 하나로 중기부 유관서비스를 편리하게 이용하세요
											</strong>
											<button
												type="button"
												className="btn text medium"
												aria-label="개인회원 회원가입 페이지로 이동"
												onClick={(): void =>
													history.push(`${ROUTES.REGISTER_STEP1}?type=member${returnClient ? `&return_client=${returnClient}` : ''}${returnUri ? `&return_uri=${encodeURIComponent(returnUri)}` : ''}`)
												}
											>
												<span>통합회원가입</span>
											</button>
										</div>
									</form>

									<div
										className="white-wrap certi-wrap"
										aria-label="인증서 로그인"
									>
										<div className="title-box">
											<h3 className="h3-title">인증서 로그인</h3>
											<p className="text">
												통합회원 가입 및 마이페이지에서 인증서 등록 후 이용 가능합니다.
											</p>
										</div>
										<ul className="list certifi-list" aria-label="인증 수단 목록">
											<li>
												<button
													type="button"
													className="btn"
													aria-label="휴대폰 인증으로 로그인"
													onClick={startPhoneAuth}
													disabled={phoneAuthBusy}
												>
													<div className="text-box">
														<figure className="img">
															<img
																src={IMAGES.RENEWAL_CERT_PHONE}
																alt=""
																aria-hidden="true"
															/>
														</figure>
														<strong>휴대폰 인증</strong>
													</div>
													<i className="icon ico-arrow-right" aria-hidden="true" />
												</button>
											</li>
											<li>
												<button
													type="button"
													className="btn"
													aria-label="개인 간편인증서로 로그인"
													onClick={startEasyAuth}
												>
													<div className="text-box">
														<figure className="img">
															<img
																src={IMAGES.RENEWAL_CERT_APP}
																alt=""
																aria-hidden="true"
															/>
														</figure>
														<strong>개인 간편인증서</strong>
													</div>
													<i className="icon ico-arrow-right" aria-hidden="true" />
												</button>
											</li>
											<li>
												<button
													type="button"
													className="btn"
													aria-label="공동인증서로 로그인"
													onClick={(): void => setDevNoticeModal(true)}
												>
													<div className="text-box">
														<figure className="img">
															<img
																src={IMAGES.RENEWAL_CERT_JOINT}
																alt=""
																aria-hidden="true"
															/>
														</figure>
														<strong>공동인증서</strong>
													</div>
													<i className="icon ico-arrow-right" aria-hidden="true" />
												</button>
											</li>
											<li>
												<button
													type="button"
													className="btn"
													aria-label="Any-ID 정부 통합로그인"
													onClick={startAnyIdAuth}
													disabled={anyIdBusy}
													aria-busy={anyIdBusy}
												>
													<div className="text-box">
														<figure className="img">
															<img
																src={IMAGES.RENEWAL_CERT_ANY}
																alt=""
																aria-hidden="true"
															/>
														</figure>
														<strong>Any-ID</strong>
													</div>
													<i className="icon ico-arrow-right" aria-hidden="true" />
												</button>
											</li>
										</ul>
									</div>
								</div>
							)}

							{activeTab === 'business' && (
								<div
									className="cont02 active"
									id="panel-business"
									role="tabpanel"
									aria-labelledby="tab-business"
								>
									{/* POST 전송용 hidden form - 기업회원 */}
									<form
										ref={bizFormRef}
										method="POST"
										action={actionUrl || ''}
										style={{ display: 'none' }}
									>
										<input type="hidden" name="loginType" value="ENT" />
										<input type="hidden" name="brno" value={bizNo} />
									</form>

									<form
										className="form-wrap white-wrap certi-wrap"
										onSubmit={onBizSubmit}
										aria-label="기업 회원 로그인"
									>
										<div className="input-wrap">
											<label htmlFor="bizNo">사업자등록번호</label>
											<div className="input-box">
												<input
													ref={bizNoRef}
													id="bizNo"
													type="text"
													name="bizNo"
													placeholder="사업자등록번호를 입력하세요"
													required
													autoComplete="username"
													aria-required="true"
													value={bizNo}
													onChange={(e): void =>
														setBizNo(
															e.target.value.replace(/\D/g, '').slice(0, 10),
														)
													}
													disabled={isLoading}
												/>
											</div>
										</div>
										{/* <div className="input-wrap">
											<label htmlFor="bizPassword">비밀번호</label>
											<div className="input-box">
												<input
													id="bizPassword"
													type="password"
													name="bizPassword"
													placeholder="비밀번호를 입력하세요"
													required
													autoComplete="current-password"
													aria-required="true"
													value={bizPassword}
													onChange={(e): void => setBizPassword(e.target.value)}
													disabled={isLoading}
												/>
											</div>
										</div> */}

										{/* <label className="check-box style1">
											<input
												type="checkbox"
												name="save_biz_id"
												checked={saveId}
												onChange={(e): void => setSaveId(e.target.checked)}
												aria-label="아이디 저장"
											/>
											<small>아이디 저장</small>
										</label> */}

										{/* 인증서 로그인 — 로그인 버튼 위로 이동 */}
										<div className="title-box">
											<h3 className="h3-title">인증서 로그인</h3>
											<p className="text">
												통합회원 가입 및 마이페이지에서 인증서 등록 후 이용 가능합니다.
											</p>
										</div>
										<ul className="list certifi-list" aria-label="인증 수단 목록">
											<li>
												<button
													type="button"
													className="btn"
													aria-label="기업 간편인증서로 로그인"
													onClick={(): void => startBizEzAuth(bizNo || undefined)}
													disabled={bizEzAuthLoading}
												>
													<div className="text-box">
														<figure className="img">
															<img
																src={IMAGES.RENEWAL_CERT_APP}
																alt=""
																aria-hidden="true"
															/>
														</figure>
														<strong>기업 간편인증서</strong>
													</div>
													<i className="icon ico-arrow-right" aria-hidden="true" />
												</button>
											</li>
											<li>
												<button
													type="button"
													className="btn"
													aria-label="기업인증서로 로그인"
													onClick={(): void => setDevNoticeModal(true)}
												>
													<div className="text-box">
														<figure className="img">
															<img
																src={IMAGES.RENEWAL_CERT_JOINT}
																alt=""
																aria-hidden="true"
															/>
														</figure>
														<strong>기업인증서</strong>
													</div>
													<i className="icon ico-arrow-right" aria-hidden="true" />
												</button>
											</li>
										</ul>

										<button
											type="submit"
											className="btn point login-btn"
											disabled={isLoading}
											aria-busy={isLoading}
										>
											<span>{isLoading ? '로그인 중...' : '로그인'}</span>
										</button>

										{/* <ul className="find-btn" aria-label="계정 찾기">
											<li>
												<button
													type="button"
													className="btn text id"
													aria-label="아이디 찾기"
												>
													<span>아이디 찾기</span>
												</button>
											</li>
										</ul> */}

										<div className="find-wrap">
											<button
												type="button"
												className="btn text homepage-btn"
												aria-label="홈페이지로 돌아가기"
												onClick={(): void => {
													if (returnUri) {
														window.location.href = returnUri;
													}
												}}
												disabled={!returnUri}
												style={{ marginLeft: 'auto' }}
											>
												<i
													className="icon ico-reset-exposure small"
													aria-hidden="true"
												/>
												<span>홈페이지로 돌아가기</span>
											</button>
										</div>

										<div className="join-box">
											<strong>
												중기원패스 하나로 중기부 유관서비스를 편리하게 이용하세요
											</strong>
											<button
												type="button"
												className="btn text medium"
												aria-label="기업회원 회원가입 페이지로 이동"
												onClick={(): void =>
													history.push(`${ROUTES.REGISTER_STEP1}?type=business${returnClient ? `&return_client=${returnClient}` : ''}${returnUri ? `&return_uri=${encodeURIComponent(returnUri)}` : ''}`)
												}
											>
												<span>통합회원가입</span>
											</button>
										</div>
									</form>
								</div>
							)}
						</div>
					</div>
				</div>
			</div>
		</>
	);
}

export default Login;
