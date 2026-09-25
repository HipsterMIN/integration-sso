import passwordChange from 'api/account/passwordChange';
import Modal from 'components/KrdsModal';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import { ChangeEvent, FormEvent, useEffect, useState } from 'react';

import {
	clearAll,
	clearCiToken,
	isAuthed,
	loadCiToken,
	markDone,
} from './services';

const PASSWORD_HINT =
	'영문자(대·소문자), 숫자, 특수문자 중 두 가지를 조합하여 8자~20자 이내';
const FAIL_TITLE = '비밀번호 변경에 실패했습니다';
const RETRY_LATER_MSG = '잠시 후 다시 시도해 주세요.';
const TOKEN_GONE_TITLE = '본인인증 정보가 만료되었습니다';
const TOKEN_GONE_MSG = '본인인증을 다시 진행해 주세요.';

type RestartReason =
	| 'MISMATCH'
	| 'TOKEN_EXPIRED'
	| 'TOKEN_REUSED'
	| 'NOT_FOUND'
	| 'FAIL';

const RESTART_PROMPTS: Record<
	RestartReason,
	{ title: string; message: string }
> = {
	MISMATCH: {
		title: '본인인증 정보와 아이디가 일치하지 않습니다',
		message: '아이디를 확인 후 본인인증부터 다시 진행해 주세요.',
	},
	TOKEN_EXPIRED: { title: TOKEN_GONE_TITLE, message: TOKEN_GONE_MSG },
	TOKEN_REUSED: { title: TOKEN_GONE_TITLE, message: TOKEN_GONE_MSG },
	NOT_FOUND: {
		title: '해당 정보로 가입된 회원을 찾을 수 없습니다',
		message: '본인인증부터 다시 진행해 주세요.',
	},
	FAIL: { title: FAIL_TITLE, message: RETRY_LATER_MSG },
};

function getPasswordStrength(pw: string): { valid: boolean } {
	let typeCount = 0;
	if (/[A-Za-z]/.test(pw)) typeCount += 1;
	if (/[0-9]/.test(pw)) typeCount += 1;
	if (/[!@#$%^&*()\-=_+]/.test(pw)) typeCount += 1;
	return { valid: pw.length >= 8 && pw.length <= 20 && typeCount >= 2 };
}

function renderPasswordHint(
	policyMessage: string,
	newPassword: string,
	newPasswordValid: boolean,
): JSX.Element {
	if (policyMessage) {
		return (
			<p className="invalid" id="password_new_hint">
				{policyMessage}
			</p>
		);
	}
	if (newPassword.length === 0) {
		return (
			<small className="info-text" id="password_new_hint">
				{PASSWORD_HINT}
			</small>
		);
	}
	if (!newPasswordValid) {
		return (
			<p className="invalid" id="password_new_hint">
				{PASSWORD_HINT}
			</p>
		);
	}
	return (
		<p className="information" id="password_new_hint">
			사용 가능한 비밀번호입니다.
		</p>
	);
}

// PUB260513 find_password_step2.html — 새 비밀번호 입력 폼
function FindPasswordNew(): JSX.Element {
	const [loginId, setLoginId] = useState('');
	const [newPassword, setNewPassword] = useState('');
	const [newPasswordCheck, setNewPasswordCheck] = useState('');
	const [showNew, setShowNew] = useState(false);
	const [showNewCheck, setShowNewCheck] = useState(false);
	const [submitting, setSubmitting] = useState(false);
	const [policyMessage, setPolicyMessage] = useState('');
	const [restartModal, setRestartModal] = useState<{
		open: boolean;
		title: string;
		message: string;
	}>({ open: false, title: '', message: '' });

	// 인증 없이 직접 진입 시 step1 으로 복귀
	useEffect(() => {
		if (!isAuthed() || !loadCiToken()) {
			history.replace(ROUTES.FIND_PASSWORD);
		}
	}, []);

	const newPasswordValid = getPasswordStrength(newPassword).valid;
	const newPasswordMismatch =
		newPasswordCheck.length > 0 && newPassword !== newPasswordCheck;

	const formValid =
		loginId.trim().length > 0 &&
		newPasswordValid &&
		newPasswordCheck.length > 0 &&
		!newPasswordMismatch;

	const openRestartModal = (title: string, message: string): void => {
		setRestartModal({ open: true, title, message });
	};

	const handleSubmit = async (): Promise<void> => {
		const ciToken = loadCiToken();
		if (!ciToken) {
			openRestartModal(TOKEN_GONE_TITLE, TOKEN_GONE_MSG);
			return;
		}
		setSubmitting(true);
		setPolicyMessage('');
		try {
			const res = await passwordChange({
				ciToken,
				loginId: loginId.trim(),
				newPassword,
			});
			if (res.statusCode !== 200 || !res.payload) {
				openRestartModal(FAIL_TITLE, res.message || RETRY_LATER_MSG);
				return;
			}
			const { resultCode, errorMessage } = res.payload;
			if (resultCode === 'SUCCESS') {
				clearCiToken();
				markDone();
				history.push(ROUTES.FIND_PASSWORD_RESULT);
				return;
			}
			if (resultCode === 'POLICY_VIOLATION') {
				// 토큰 미소비 — 비밀번호만 고쳐 재시도 가능
				setPolicyMessage(errorMessage || PASSWORD_HINT);
				return;
			}
			const prompt = RESTART_PROMPTS[resultCode as RestartReason];
			if (prompt) {
				openRestartModal(prompt.title, prompt.message);
				return;
			}
			openRestartModal(FAIL_TITLE, errorMessage || RETRY_LATER_MSG);
		} catch (err) {
			console.error('[FindPasswordNew] 비밀번호 변경 실패:', err);
			openRestartModal(FAIL_TITLE, RETRY_LATER_MSG);
		} finally {
			setSubmitting(false);
		}
	};

	const restartFlow = (): void => {
		clearAll();
		history.replace(ROUTES.FIND_PASSWORD);
	};

	return (
		<>
			<main id="main-content" className="container sub find_password step2">
				<div className="sub-body inner">
					<div className="page-title-wrap">
						<div className="page-title-text-box">
							<h2 className="page-title">중기원패스 회원 전환</h2>
							<p className="page-text">
								하나의 아이디로 중소벤처기업부 유관기관의 서비스를 모두 이용해보세요!
							</p>
						</div>
						<figure className="img-box">
							<img src={IMAGES.RENEWAL_PAGE_TITLE_IMG} alt="" aria-hidden="true" />
						</figure>
					</div>
					<form
						className="form-container"
						onSubmit={(e: FormEvent): void => e.preventDefault()}
						aria-label="비밀번호 재설정"
					>
						<div className="white-wrap">
							<h3 className="h3-title">비밀번호 재설정</h3>
							<div className="form-wrap">
								<div className="input-wrap">
									<label htmlFor="login_id">
										아이디<span className="essential">필수</span>
									</label>
									<div className="input-box">
										<input
											id="login_id"
											type="text"
											name="login_id"
											placeholder="아이디를 입력해주세요"
											maxLength={50}
											value={loginId}
											onChange={(e: ChangeEvent<HTMLInputElement>): void =>
												setLoginId(e.target.value)
											}
											autoComplete="username"
											aria-required="true"
											required
										/>
									</div>
								</div>
								<div className="input-wrap">
									<label htmlFor="password_new">
										새 비밀번호<span className="essential">필수</span>
									</label>
									<div className="input-box">
										<input
											id="password_new"
											type={showNew ? 'text' : 'password'}
											name="password_new"
											placeholder="새 비밀번호를 입력해주세요"
											maxLength={20}
											value={newPassword}
											onChange={(e: ChangeEvent<HTMLInputElement>): void => {
												setNewPassword(e.target.value);
												if (policyMessage) setPolicyMessage('');
											}}
											autoComplete="new-password"
											aria-required="true"
											aria-describedby="password_new_hint"
											required
										/>
										<button
											type="button"
											className="btn large icon pw-check"
											onClick={(): void => setShowNew((p) => !p)}
											aria-label={showNew ? '비밀번호 숨기기' : '비밀번호 보기'}
										>
											<i
												className={`icon pw-visible${showNew ? ' on' : ''}`}
												aria-hidden="true"
											/>
										</button>
									</div>
									<div className="input-hint-box">
										{renderPasswordHint(policyMessage, newPassword, newPasswordValid)}
									</div>
								</div>
								<div className="input-wrap">
									<label htmlFor="password_new_check">
										새 비밀번호 확인<span className="essential">필수</span>
									</label>
									<div className="input-box">
										<input
											id="password_new_check"
											type={showNewCheck ? 'text' : 'password'}
											name="password_new_check"
											placeholder="새 비밀번호를 다시 한 번 입력해주세요"
											maxLength={20}
											value={newPasswordCheck}
											onChange={(e: ChangeEvent<HTMLInputElement>): void =>
												setNewPasswordCheck(e.target.value)
											}
											autoComplete="new-password"
											aria-required="true"
											aria-describedby="password_new_check_hint"
											required
										/>
										<button
											type="button"
											className="btn large icon pw-check"
											onClick={(): void => setShowNewCheck((p) => !p)}
											aria-label={
												showNewCheck ? '비밀번호 확인 숨기기' : '비밀번호 확인 보기'
											}
										>
											<i
												className={`icon pw-visible${showNewCheck ? ' on' : ''}`}
												aria-hidden="true"
											/>
										</button>
									</div>
									{newPasswordCheck.length > 0 && (
										<div className="input-hint-box">
											{newPasswordMismatch ? (
												<p className="invalid" id="password_new_check_hint">
													비밀번호가 일치하지 않습니다.
												</p>
											) : (
												<p className="information" id="password_new_check_hint">
													비밀번호가 일치합니다.
												</p>
											)}
										</div>
									)}
								</div>
							</div>
						</div>
						<div className="btn-box" role="group" aria-label="페이지 이동">
							<button
								type="button"
								className="btn point"
								onClick={(): void => {
									handleSubmit().catch(() => {
										// handleSubmit 내부에서 모달로 안내 처리됨
									});
								}}
								disabled={!formValid || submitting}
							>
								<span>비밀번호 저장</span>
							</button>
						</div>
					</form>
				</div>
			</main>
			<Modal
				id="modal_find_password_restart"
				isOpen={restartModal.open}
				onClose={(): void => {
					setRestartModal((p) => ({ ...p, open: false }));
				}}
				topText="안내"
				title={restartModal.title}
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => {
							setRestartModal((p) => ({ ...p, open: false }));
							restartFlow();
						},
					},
				]}
			>
				<p>{restartModal.message}</p>
			</Modal>
		</>
	);
}

export default FindPasswordNew;
