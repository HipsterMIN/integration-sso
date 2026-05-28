import { ChangeEvent, useEffect, useState } from 'react';
import checkDuplicate from 'api/ext/checkDuplicate';
import getBusinessStatus from 'api/ext/businessStatus';
import businessValidate from 'api/ext/businessValidate';
import { useConversion } from 'providers/Conversion/ConversionContext';

interface AccountFormProps {
	isBusiness?: boolean;
	/** true 이면 외곽 .form-wrap 을 렌더하지 않음 — 부모가 다른 컴포넌트와 함께 단일 .form-wrap 그리드에 합쳐서 배치할 때 사용 */
	flat?: boolean;
	/** 전환 플로우 여부 (기업전환 시 아이디/비밀번호 입력 활성화) */
	isConversion?: boolean;
	onVerificationChange?: (status: { validateOk: boolean; duplicateOk: boolean; loginIdDupOk: boolean; formValid: boolean }) => void;
}

const LOGIN_ID_REGEX = /^[a-zA-Z0-9_.]+$/;

function isLoginIdValid(id: string): boolean {
	return id.length >= 5 && id.length <= 20 && LOGIN_ID_REGEX.test(id);
}

function getPasswordStrength(pw: string): { valid: boolean; typeCount: number } {
	let typeCount = 0;
	if (/[A-Z]/.test(pw)) typeCount += 1;
	if (/[a-z]/.test(pw)) typeCount += 1;
	if (/[0-9]/.test(pw)) typeCount += 1;
	if (/[!@#$%^&*()\-_+]/.test(pw)) typeCount += 1;
	return { valid: pw.length >= 8 && pw.length <= 20 && typeCount >= 4, typeCount };
}

function AccountForm({ isBusiness = false, flat = false, isConversion = false, onVerificationChange }: AccountFormProps): JSX.Element {
	const { data, updateData } = useConversion();
	const [showPassword, setShowPassword] = useState(false);
	const [showPasswordConfirm, setShowPasswordConfirm] = useState(false);
	const [passwordConfirm, setPasswordConfirm] = useState('');
	const [duplicateStatus, setDuplicateStatus] = useState<'idle' | 'checking' | 'ok' | 'duplicate' | 'error'>('idle');
	const [duplicateMessage, setDuplicateMessage] = useState('');
	const [validateStatus, setValidateStatus] = useState<'idle' | 'checking' | 'ok' | 'fail' | 'error'>('idle');
	const [validateMessage, setValidateMessage] = useState('');
	const [bizStatus, setBizStatus] = useState<{ bStt: string; taxType: string; active: boolean } | null>(null);
	const [bizStatusError, setBizStatusError] = useState('');
	const [bizStatusLoading, setBizStatusLoading] = useState(false);
	// 아이디 중복확인 전용 state (사업자등록번호 중복확인과 분리)
	const [loginIdDupStatus, setLoginIdDupStatus] = useState<'idle' | 'checking' | 'ok' | 'duplicate' | 'error'>('idle');
	const [loginIdDupMessage, setLoginIdDupMessage] = useState('');
	// 기업전환: 아이디 수정 잠금 (중복 시에만 해제)
	const [loginIdLocked, setLoginIdLocked] = useState(isConversion && isBusiness);

	// 기업전환: mbrId를 loginId 초기값으로 설정
	useEffect(() => {
		if (isConversion && isBusiness && data.mbrId && !data.loginId) {
			updateData({ loginId: data.mbrId });
		}
	// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	// 검증 상태 변경 시 부모에 전달
	const formValid = !isBusiness
		? isLoginIdValid(data.loginId) && getPasswordStrength(data.password).valid && !!passwordConfirm && data.password === passwordConfirm
		: (isConversion ? isLoginIdValid(data.loginId) && getPasswordStrength(data.password).valid && !!passwordConfirm && data.password === passwordConfirm : true);

	useEffect(() => {
		onVerificationChange?.({ validateOk: validateStatus === 'ok', duplicateOk: duplicateStatus === 'ok', loginIdDupOk: loginIdDupStatus === 'ok', formValid });
	}, [validateStatus, duplicateStatus, loginIdDupStatus, onVerificationChange, formValid]);

	const handleChange = (field: string) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>): void => {
		let { value } = e.target;
		if (field === 'loginId') {
			setLoginIdDupStatus('idle');
		}
		updateData({ [field]: value });
		// 기업 정보 변경 시 검증 상태 초기화
		if (['bzmnNm', 'rprsvNm', 'startDt'].includes(field)) {
			setValidateStatus('idle');
			setDuplicateStatus('idle');
		}
	};

	// 기업: 진위확인 → 중복확인 순차 호출 / 개인: 중복확인만
	const handleCheckDuplicate = async (): Promise<void> => {
		if (isBusiness) {
			// 필수값 체크 (설립일은 기업인증 미구현으로 임시 제외)
			if (!data.bzmnNm || !data.rprsvNm || !data.brno) {
				setValidateStatus('fail');
				setValidateMessage('회사명, 대표자명을 모두 입력한 후 확인해 주세요.');
				return;
			}

			// TODO: 기업인증 구현 후 진위확인 API 호출 활성화
			// ① 진위확인 — 임시 스킵 (기업인증 미구현으로 설립일 미확보)
			setValidateStatus('ok');
			setValidateMessage('진위확인 생략 (기업인증 미구현)');
			setDuplicateStatus('idle');
			/*
			setValidateStatus('checking');
			setDuplicateStatus('idle');
			const valResponse = await businessValidate({
				bNo: data.brno,
				startDt: data.startDt,
				representativeName: data.rprsvNm,
				companyName: data.bzmnNm,
			});

			if (valResponse.statusCode === 200 && valResponse.payload) {
				const valData = (valResponse.payload as { data?: { valid?: boolean; validMsg?: string | null; bStt?: string } }).data
					?? (valResponse.payload as { valid?: boolean; validMsg?: string | null; bStt?: string });
				if (valData.valid) {
					setValidateStatus('ok');
					setValidateMessage(valData.bStt ? `진위확인 완료 (${valData.bStt})` : '진위확인 완료');
				} else {
					setValidateStatus('fail');
					setValidateMessage(valData.validMsg || '사업자 진위확인에 실패하였습니다.');
					return;
				}
			} else {
				setValidateStatus('error');
				setValidateMessage(valResponse.message || '진위확인 요청에 실패하였습니다.');
				return;
			}
			*/

			// ② 중복확인
			setDuplicateStatus('checking');
			const dupResponse = await checkDuplicate({ type: 'ENT', value: data.brno });

			if (dupResponse.statusCode === 200 && dupResponse.payload) {
				const { data: result } = dupResponse.payload;
				const isDuplicate = result.exists === true;
				setDuplicateStatus(isDuplicate ? 'duplicate' : 'ok');
				setDuplicateMessage(result.message);
			} else {
				setDuplicateStatus('error');
				setDuplicateMessage(dupResponse.message || '중복확인에 실패하였습니다.');
			}
			return;
		}

		// 개인회원: 중복확인만
		const value = data.loginId;
		if (!value) return;

		setLoginIdDupStatus('checking');
		const response = await checkDuplicate({ type: 'IND', value });

		if (response.statusCode === 200 && response.payload) {
			const { data: result } = response.payload;
			const isDuplicate = result.exists === true;
			setLoginIdDupStatus(isDuplicate ? 'duplicate' : 'ok');
			setLoginIdDupMessage(result.message);
		} else {
			setLoginIdDupStatus('error');
			setLoginIdDupMessage(response.message || '중복확인에 실패하였습니다.');
		}
	};

	// 기업전환: 아이디 중복확인 (중복이면 수정 가능하도록 잠금 해제)
	const handleLoginIdDuplicateCheck = async (): Promise<void> => {
		const value = data.loginId;
		if (!value) return;

		setLoginIdDupStatus('checking');
		const response = await checkDuplicate({ type: 'IND', value });

		if (response.statusCode === 200 && response.payload) {
			const { data: result } = response.payload;
			const isDuplicate = result.exists === true;
			setLoginIdDupStatus(isDuplicate ? 'duplicate' : 'ok');
			setLoginIdDupMessage(result.message);
			// 중복이면 아이디 수정 가능하도록 잠금 해제
			if (isDuplicate) {
				setLoginIdLocked(false);
			}
		} else {
			setLoginIdDupStatus('error');
			setLoginIdDupMessage(response.message || '중복확인에 실패하였습니다.');
		}
	};

	// 사업자 상태 확인 API 호출 (기업 회원 + 사업자번호 있을 때)
	useEffect(() => {
		if (!isBusiness || !data.brno) return;

		let cancelled = false;
		const fetchStatus = async (): Promise<void> => {
			setBizStatusLoading(true);
			setBizStatusError('');
			const response = await getBusinessStatus({ bNo: data.brno });
			if (cancelled) return;

			if (response.statusCode === 200 && response.payload) {
				const raw = (response.payload.data || response.payload) as Record<string, unknown>;
				setBizStatus({
					bStt: (raw.bStt as string) || '알 수 없음',
					taxType: (raw.taxType as string) || '',
					active: raw.active === true,
				});
			} else {
				setBizStatusError(response.message || '사업자 상태 조회 실패');
			}
			setBizStatusLoading(false);
		};

		fetchStatus();
		return (): void => { cancelled = true; };
	}, [isBusiness, data.brno]);

	// 아이디 실시간 피드백
	const loginIdFeedback = (() => {
		if (!data.loginId) return null;
		if (data.loginId.length < 5) return { type: 'invalid' as const, msg: '아이디는 5자 이상 입력해 주세요.' };
		if (!LOGIN_ID_REGEX.test(data.loginId)) return { type: 'invalid' as const, msg: '영문, 숫자, 특수기호(_, .)만 사용 가능합니다.' };
		return null;
	})();

	// 비밀번호 실시간 피드백
	const passwordFeedback = (() => {
		if (!data.password) return null;
		if (data.password.length < 8) return { type: 'invalid' as const, msg: '비밀번호는 8자 이상 입력해 주세요.' };
		const { typeCount } = getPasswordStrength(data.password);
		if (typeCount < 4) return { type: 'invalid' as const, msg: '영문 대문자, 소문자, 숫자, 특수문자를 모두 포함해 주세요.' };
		return null;
	})();

	const inner = (
		<div className="form-group">
				<div className="input-wrap width50">
					<label htmlFor="id">
						{isBusiness ? '회사명' : '아이디'}<span className="essential">필수</span>
						{!isBusiness && (
							<small className="info-text">
								5~20자의 영문 소문자, 숫자와 특수기호 (_), (.)만 사용
							</small>
						)}
					</label>
					{isBusiness ? (
						<div className="input-box">
							<input
								id="id"
								type="text"
								name="id"
								placeholder="회사명을 입력하세요"
								value={data.bzmnNm}
								onChange={handleChange('bzmnNm')}
								required
								aria-required="true"
								autoComplete="username"
							/>
						</div>
					) : (
						<>
							<div className="input-box">
								<input
									id="id"
									type="text"
									name="id"
									placeholder="아이디를 입력하세요"
									value={data.loginId}
									onChange={handleChange('loginId')}
									maxLength={20}
									required
									aria-required="true"
									autoComplete="username"
									aria-describedby="id_hint id_error"
								/>
								<button
									type="button"
									className="btn point"
									aria-label="중복확인"
									onClick={handleCheckDuplicate}
									disabled={loginIdDupStatus === 'checking'}
								>
									<span>{loginIdDupStatus === 'checking' ? '확인중...' : '중복확인'}</span>
								</button>
							</div>
							<div className="input-hint-box">
								{loginIdFeedback && loginIdDupStatus === 'idle' && (
									<p className="invalid" id="id_error">{loginIdFeedback.msg}</p>
								)}
								{loginIdDupStatus === 'checking' && (
									<p className="information" id="id_hint">중복 확인 중...</p>
								)}
								{loginIdDupStatus === 'ok' && (
									<p className="information" id="id_hint" style={{ color: 'var(--krds-light-color-text-information)' }}>
										{loginIdDupMessage}
									</p>
								)}
								{loginIdDupStatus === 'duplicate' && (
									<p className="invalid" id="id_error">{loginIdDupMessage}</p>
								)}
								{loginIdDupStatus === 'error' && (
									<p className="invalid" id="id_error">{loginIdDupMessage}</p>
								)}
							</div>
						</>
					)}
				</div>
				{isBusiness ? (
					<>
						<div className="input-wrap width50">
							<label htmlFor="rep_name">
								대표자명<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									id="rep_name"
									type="text"
									name="rep_name"
									placeholder="대표자명을 입력하세요"
									value={data.rprsvNm}
									onChange={handleChange('rprsvNm')}
									required
									aria-required="true"
								/>
							</div>
						</div>
						<div className="input-wrap width50">
							<label htmlFor="start_dt">
								설립일<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									id="start_dt"
									type="text"
									name="start_dt"
									placeholder="YYYY-MM-DD"
									maxLength={10}
									value={data.startDt}
									onChange={(e): void => {
										const raw = e.target.value.replace(/[^0-9]/g, '');
										let formatted = raw;
										if (raw.length > 4) formatted = `${raw.slice(0, 4)}-${raw.slice(4)}`;
										if (raw.length > 6) formatted = `${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6, 8)}`;
										updateData({ startDt: formatted });
										if (['bzmnNm', 'rprsvNm', 'startDt'].includes('startDt')) {
											setValidateStatus('idle');
											setDuplicateStatus('idle');
										}
									}}
									required
									aria-required="true"
								/>
							</div>
						</div>
						<div className="input-wrap width50">
							<label htmlFor="business_num">
								사업자등록번호<span className="essential">필수</span>
								{bizStatusLoading && (
									<span className="information" style={{ marginLeft: '8px', fontWeight: 'normal' }}>사업자 상태 확인 중...</span>
								)}
								{!bizStatusLoading && bizStatus && (
									<span className="information" style={{ color: bizStatus.active ? 'var(--krds-light-color-text-information)' : '#e74c3c', marginLeft: '8px', fontWeight: 'normal' }}>
										사업자 상태: {bizStatus.bStt}{bizStatus.taxType ? ` (${bizStatus.taxType})` : ''}
									</span>
								)}
							</label>
							<div className="input-box">
								<input id="business_num" type="text" name="business_num" value={data.brno} disabled />
								<button
									type="button"
									className="btn point"
									aria-label="중복확인"
									onClick={handleCheckDuplicate}
									disabled={validateStatus === 'checking' || duplicateStatus === 'checking'}
								>
									<span>{(validateStatus === 'checking' || duplicateStatus === 'checking') ? '확인중...' : '중복확인'}</span>
								</button>
							</div>
							{!data.brno && (
								<div className="input-hint-box">
									<p className="invalid">사업자등록번호가 입력되지 않았습니다. 기업인증 단계에서 입력해 주세요.</p>
								</div>
							)}
							{!bizStatusLoading && bizStatusError && (
								<div className="input-hint-box">
									<p className="invalid">{bizStatusError}</p>
								</div>
							)}
							{validateStatus === 'checking' && (
								<div className="input-hint-box">
									<p className="information">국세청 진위확인 중...</p>
								</div>
							)}
							{validateStatus === 'fail' && (
								<div className="input-hint-box">
									<p className="invalid">{validateMessage}</p>
								</div>
							)}
							{validateStatus === 'error' && (
								<div className="input-hint-box">
									<p className="invalid">{validateMessage}</p>
								</div>
							)}
							{validateStatus === 'ok' && duplicateStatus === 'checking' && (
								<div className="input-hint-box">
									<p className="information" style={{ color: 'var(--krds-light-color-text-information)' }}>{validateMessage}</p>
									<p className="information">등록 중복확인 중...</p>
								</div>
							)}
							{validateStatus === 'ok' && duplicateStatus === 'ok' && (
								<div className="input-hint-box">
									<p className="information" style={{ color: 'var(--krds-light-color-text-information)' }}>{validateMessage}</p>
									<p className="information" style={{ color: '#256EF4' }}>{duplicateMessage}</p>
								</div>
							)}
							{validateStatus === 'ok' && duplicateStatus === 'duplicate' && (
								<div className="input-hint-box">
									<p className="information" style={{ color: 'var(--krds-light-color-text-information)' }}>{validateMessage}</p>
									<p className="invalid">{duplicateMessage}</p>
								</div>
							)}
							{validateStatus === 'ok' && duplicateStatus === 'error' && (
								<div className="input-hint-box">
									<p className="information" style={{ color: 'var(--krds-light-color-text-information)' }}>{validateMessage}</p>
									<p className="invalid">{duplicateMessage}</p>
								</div>
							)}
						</div>
						{isConversion && (
							<>
								<div className="input-wrap width50">
									<label htmlFor="conv_login_id">
										아이디<span className="essential">필수</span>
										<small className="info-text">
											5~20자의 영문 소문자, 숫자와 특수기호 (_), (.)만 사용
										</small>
									</label>
									<div className="input-box">
										<input
											id="conv_login_id"
											type="text"
											name="conv_login_id"
											placeholder="아이디를 입력하세요"
											value={data.loginId}
											onChange={handleChange('loginId')}
											maxLength={20}
											disabled={loginIdLocked}
											required
											aria-required="true"
											autoComplete="username"
										/>
										<button
											type="button"
											className="btn point"
											aria-label="아이디 중복확인"
											onClick={handleLoginIdDuplicateCheck}
											disabled={loginIdDupStatus === 'checking'}
										>
											<span>{loginIdDupStatus === 'checking' ? '확인중...' : '중복확인'}</span>
										</button>
									</div>
									<div className="input-hint-box">
										{loginIdFeedback && loginIdDupStatus === 'idle' && (
											<p className="invalid">{loginIdFeedback.msg}</p>
										)}
										{loginIdDupStatus === 'checking' && (
											<p className="information">중복 확인 중...</p>
										)}
										{loginIdDupStatus === 'ok' && (
											<p className="information" style={{ color: 'var(--krds-light-color-text-information)' }}>
												{loginIdDupMessage}
											</p>
										)}
										{loginIdDupStatus === 'duplicate' && (
											<p className="invalid">{loginIdDupMessage}</p>
										)}
										{loginIdDupStatus === 'error' && (
											<p className="invalid">{loginIdDupMessage}</p>
										)}
									</div>
								</div>
								<div className="input-wrap width50">
									<label htmlFor="conv_password">
										비밀번호<span className="essential">필수</span>
										<small className="info-text">
											영문 대문자, 소문자, 숫자, 특수문자를 모두 포함하여 8자~20자 이내
										</small>
									</label>
									<div className="input-box">
										<input
											type={showPassword ? 'text' : 'password'}
											id="conv_password"
											placeholder="비밀번호를 입력하세요"
											maxLength={20}
											value={data.password}
											onChange={handleChange('password')}
											required
											aria-required="true"
											autoComplete="new-password"
										/>
										<button
											type="button"
											className="btn large icon pw-check"
											onClick={(): void => setShowPassword((p) => !p)}
											aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}
										>
											<i className={`icon pw-visible${showPassword ? ' on' : ''}`} aria-hidden="true" />
										</button>
									</div>
									{passwordFeedback && (
										<div className="input-hint-box">
											<p className="invalid">{passwordFeedback.msg}</p>
										</div>
									)}
								</div>
								<div className="input-wrap width50">
									<label htmlFor="conv_password_check">
										비밀번호 확인<span className="essential">필수</span>
									</label>
									<div className="input-box">
										<input
											type={showPasswordConfirm ? 'text' : 'password'}
											id="conv_password_check"
											placeholder="비밀번호를 다시 한번 입력해 주세요"
											maxLength={20}
											value={passwordConfirm}
											onChange={(e): void => setPasswordConfirm(e.target.value)}
											required
											aria-required="true"
											autoComplete="new-password"
										/>
										<button
											type="button"
											className="btn large icon pw-check"
											onClick={(): void => setShowPasswordConfirm((p) => !p)}
											aria-label={showPasswordConfirm ? '비밀번호 확인 숨기기' : '비밀번호 확인 보기'}
										>
											<i className={`icon pw-visible${showPasswordConfirm ? ' on' : ''}`} aria-hidden="true" />
										</button>
									</div>
									{passwordConfirm && data.password !== passwordConfirm && (
										<div className="input-hint-box">
											<p className="invalid">비밀번호가 일치하지 않습니다.</p>
										</div>
									)}
								</div>
							</>
						)}
						<div className="input-wrap width50">
							<label htmlFor="email1">
								이메일<span className="essential">필수</span>
							</label>
							<div className="input-flex-box">
								<div className="input-box small">
									<input
										id="email1"
										type="text"
										name="email1"
										placeholder="이메일을 입력하세요"
										value={data.email}
										onChange={handleChange('email')}
										required
										aria-required="true"
										aria-label="이메일 아이디"
									/>
								</div>
								<span>@</span>
								<div className="input-box small">
									<input
										id="email2"
										type="text"
										name="email2"
										placeholder="도메인"
										value={data.emailDomain}
										onChange={handleChange('emailDomain')}
										aria-label="이메일 도메인"
									/>
								</div>
								<div className="input-box small">
									<select
										name="email_domain"
										value=""
										onChange={(e): void => {
											if (e.target.value) updateData({ emailDomain: e.target.value });
										}}
										aria-label="이메일 도메인 선택"
									>
										<option value="">직접입력</option>
										<option value="gmail.com">gmail.com</option>
										<option value="naver.com">naver.com</option>
										<option value="daum.net">daum.net</option>
									</select>
									<button type="button" className="btn large icon arrow-bottom">
										<span className="hidden">선택창 열기</span>
										<i className="icon arrow-bottom" aria-hidden="true" />
									</button>
								</div>
							</div>
						</div>
						<div className="input-wrap width50">
							<label htmlFor="tel2">
								대표 전화번호
							</label>
							<div className="input-flex-box">
								<div className="input-box small">
									<select
										name="tel1"
										value={data.telPrefix}
										onChange={handleChange('telPrefix')}
										aria-label="유선전화 지역번호"
									>
										<option value="" disabled hidden>선택</option>
										<option value="02">02</option>
										<option value="031">031</option>
									</select>
									<button type="button" className="btn large icon arrow-bottom">
										<span className="hidden">선택창 열기</span>
										<i className="icon arrow-bottom" aria-hidden="true" />
									</button>
								</div>
								<div className="input-box small">
									<input
										id="tel2"
										type="number"
										name="tel2"
										value={data.telSuffix}
										onChange={handleChange('telSuffix')}
										aria-label="유선전화 뒷자리"
									/>
								</div>
							</div>
							{data.telSuffix && /\D/.test(data.telSuffix) && (
								<div className="input-hint-box">
									<p className="invalid">숫자만 입력가능합니다.</p>
								</div>
							)}
						</div>
					</>
				) : (
					<>
						<div className="input-wrap width50" aria-hidden="true" />
						<div className="input-wrap width50">
							<label htmlFor="password">
								비밀번호<span className="essential">필수</span>
								<small className="info-text">
									영문 대문자, 소문자, 숫자, 특수문자를 모두 포함하여 8자~20자 이내
								</small>
							</label>
							<div className="input-box">
								<input
									type={showPassword ? 'text' : 'password'}
									id="password"
									placeholder="비밀번호를 입력하세요"
									maxLength={20}
									value={data.password}
									onChange={handleChange('password')}
									required
									aria-required="true"
									autoComplete="new-password"
									aria-describedby="pw_hint"
								/>
								<button
									type="button"
									className="btn large icon pw-check"
									onClick={(): void => setShowPassword((p) => !p)}
									aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}
								>
									<i className={`icon pw-visible${showPassword ? ' on' : ''}`} aria-hidden="true" />
								</button>
							</div>
							{passwordFeedback && (
								<div className="input-hint-box">
									<p className="invalid" id="pw_hint">{passwordFeedback.msg}</p>
								</div>
							)}
						</div>
						<div className="input-wrap width50">
							<label htmlFor="password_check">
								비밀번호 확인<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									type={showPasswordConfirm ? 'text' : 'password'}
									id="password_check"
									placeholder="비밀번호를 다시 한번 입력해 주세요"
									maxLength={20}
									value={passwordConfirm}
									onChange={(e): void => setPasswordConfirm(e.target.value)}
									required
									aria-required="true"
									autoComplete="new-password"
									aria-describedby="pw_check_error"
								/>
								<button
									type="button"
									className="btn large icon pw-check"
									onClick={(): void => setShowPasswordConfirm((p) => !p)}
									aria-label={showPasswordConfirm ? '비밀번호 확인 숨기기' : '비밀번호 확인 보기'}
								>
									<i className={`icon pw-visible${showPasswordConfirm ? ' on' : ''}`} aria-hidden="true" />
								</button>
							</div>
							{passwordConfirm && data.password !== passwordConfirm && (
								<div className="input-hint-box">
									<p className="invalid" id="pw_check_error">비밀번호가 일치하지 않습니다.</p>
								</div>
							)}
						</div>
					</>
				)}
			</div>
	);

	return flat ? inner : <div className="form-wrap">{inner}</div>;
}

export default AccountForm;
