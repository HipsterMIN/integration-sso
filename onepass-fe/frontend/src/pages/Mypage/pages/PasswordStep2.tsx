import passwordChange from 'api/account/passwordChange';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import { ChangeEvent, FormEvent, useEffect, useState } from 'react';
import { Redirect } from 'react-router-dom';

import {
	clearAll,
	clearCiToken,
	isAuthed,
	loadCiToken,
} from './passwordServices';
import { useInfoStore } from './useInfoStore';

const PASSWORD_HINT =
	'영문 대문자, 소문자, 숫자, 특수문자를 모두 포함하여 8자~20자 이내';
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
		title: '본인인증 정보와 회원 정보가 일치하지 않습니다',
		message: '본인인증부터 다시 진행해 주세요.',
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
	if (/[A-Z]/.test(pw)) typeCount += 1;
	if (/[a-z]/.test(pw)) typeCount += 1;
	if (/[0-9]/.test(pw)) typeCount += 1;
	if (/[!@#$%^&*()\-=_+]/.test(pw)) typeCount += 1;
	return { valid: pw.length >= 8 && pw.length <= 20 && typeCount >= 4 };
}

function renderPasswordHint(
	policyMessage: string,
	newPassword: string,
	newPasswordValid: boolean,
): JSX.Element {
	if (policyMessage) {
		return (
			<p className="invalid" id="new_password_hint">
				{policyMessage}
			</p>
		);
	}
	if (newPassword.length === 0) {
		return (
			<small className="info-text" id="new_password_hint">
				{PASSWORD_HINT}
			</small>
		);
	}
	if (!newPasswordValid) {
		return (
			<p className="invalid" id="new_password_hint">
				{PASSWORD_HINT}
			</p>
		);
	}
	return (
		<p className="information" id="new_password_hint">
			사용 가능한 비밀번호입니다.
		</p>
	);
}

function PasswordStep2(): JSX.Element {
	const { member } = useInfoStore();
	const [newPassword, setNewPassword] = useState('');
	const [newPasswordCheck, setNewPasswordCheck] = useState('');
	const [showNew, setShowNew] = useState(false);
	const [showNewCheck, setShowNewCheck] = useState(false);
	const [submitting, setSubmitting] = useState(false);
	const [policyMessage, setPolicyMessage] = useState('');
	const [successModal, setSuccessModal] = useState(false);
	const [restartModal, setRestartModal] = useState<{
		open: boolean;
		title: string;
		message: string;
	}>({ open: false, title: '', message: '' });

	// step1(개인 인증)을 거치지 않고 직접 진입 시 차단
	useEffect(() => {
		if (!isAuthed() || !loadCiToken()) {
			history.replace(ROUTES.MYPAGE_MEMBER_PASSWORD);
		}
	}, []);

	// 성공 모달 표시 중에는 가드 우회 — clearAll() 이후에도 모달 노출 유지
	if (!successModal && (!isAuthed() || !loadCiToken())) {
		return <Redirect to={ROUTES.MYPAGE_MEMBER_PASSWORD} />;
	}

	const newPasswordValid = getPasswordStrength(newPassword).valid;
	const newPasswordMismatch =
		newPasswordCheck.length > 0 && newPassword !== newPasswordCheck;

	const formValid =
		newPasswordValid && newPasswordCheck.length > 0 && !newPasswordMismatch;

	const openRestartModal = (title: string, message: string): void => {
		setRestartModal({ open: true, title, message });
	};

	const handleSubmit = async (): Promise<void> => {
		const ciToken = loadCiToken();
		if (!ciToken) {
			openRestartModal(TOKEN_GONE_TITLE, TOKEN_GONE_MSG);
			return;
		}
		if (!member.id) {
			openRestartModal(
				FAIL_TITLE,
				'회원 정보를 찾을 수 없습니다. 다시 로그인해주세요.',
			);
			return;
		}
		setSubmitting(true);
		setPolicyMessage('');
		try {
			const res = await passwordChange({
				ciToken,
				loginId: member.id,
				newPassword,
			});
			if (res.statusCode !== 200 || !res.payload) {
				openRestartModal(FAIL_TITLE, res.message || RETRY_LATER_MSG);
				return;
			}
			const { resultCode, errorMessage } = res.payload;
			if (resultCode === 'SUCCESS') {
				clearAll();
				setSuccessModal(true);
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
			console.error('[MypagePassword] 비밀번호 변경 실패:', err);
			openRestartModal(FAIL_TITLE, RETRY_LATER_MSG);
		} finally {
			setSubmitting(false);
		}
	};

	const restartFlow = (): void => {
		clearCiToken();
		history.replace(ROUTES.MYPAGE_MEMBER_PASSWORD);
	};

	const handleSuccessConfirm = (): void => {
		setSuccessModal(false);
		history.push(ROUTES.MYPAGE_MEMBER_INFORMATION);
	};

	return (
		<MypageContent>
			<form
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="비밀번호 변경"
			>
				<div className="white-wrap">
					<h3 className="h3-title">새 비밀번호 설정</h3>
					<div className="text-info-wrap point">
						<ul className="text-list-wrap check" aria-label="안내 사항">
							<li>
								<p>본인 인증이 완료되었습니다. 사용하실 새 비밀번호를 입력해 주세요.</p>
							</li>
							<li>
								<p>비밀번호는 타인에게 노출되지 않도록 주의해 주세요.</p>
							</li>
						</ul>
					</div>
					<div className="form-wrap">
						<div className="input-wrap">
							<label htmlFor="new_password">
								새 비밀번호<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									type={showNew ? 'text' : 'password'}
									id="new_password"
									name="new_password"
									placeholder="새 비밀번호를 입력해주세요"
									maxLength={20}
									value={newPassword}
									onChange={(e: ChangeEvent<HTMLInputElement>): void => {
										setNewPassword(e.target.value);
										if (policyMessage) setPolicyMessage('');
									}}
									autoComplete="new-password"
									aria-required="true"
									aria-describedby="new_password_hint"
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
							<label htmlFor="new_password_check">
								새 비밀번호 확인<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									type={showNewCheck ? 'text' : 'password'}
									id="new_password_check"
									name="new_password_check"
									placeholder="새 비밀번호를 다시 한 번 입력해주세요"
									maxLength={20}
									value={newPasswordCheck}
									onChange={(e: ChangeEvent<HTMLInputElement>): void =>
										setNewPasswordCheck(e.target.value)
									}
									autoComplete="new-password"
									aria-required="true"
									aria-describedby="new_password_check_hint"
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
										<p className="invalid" id="new_password_check_hint">
											비밀번호가 일치하지 않습니다.
										</p>
									) : (
										<p className="information" id="new_password_check_hint">
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
						<span>변경하기</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
				</div>
			</form>

			<Modal
				id="modal_password_success"
				isOpen={successModal}
				onClose={handleSuccessConfirm}
				topText=""
				title="비밀번호 변경"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						half: true,
						onClick: handleSuccessConfirm,
					},
				]}
			>
				<div className="completed-box">
					<figure className="img">
						<img
							src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG_MODAL}
							alt=""
							aria-hidden="true"
						/>
					</figure>
					<p className="completed-title blue">
						비밀번호가 정상적으로 변경되었습니다
					</p>
				</div>
			</Modal>

			<Modal
				id="modal_password_restart"
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
		</MypageContent>
	);
}

export default PasswordStep2;
