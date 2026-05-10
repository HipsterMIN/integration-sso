import { useRegister } from 'providers/Register/RegisterContext';
import { ChangeEvent, useState } from 'react';

interface AccountFormProps {
	isBusiness?: boolean;
	/** true 이면 외곽 .form-wrap 을 렌더하지 않음 — 부모가 다른 컴포넌트와 함께 단일 .form-wrap 그리드에 합쳐서 배치할 때 사용 */
	flat?: boolean;
}

type DuplicateStatus = 'idle' | 'checking' | 'ok' | 'duplicate';

function AccountForm({
	isBusiness = false,
	flat = false,
}: AccountFormProps): JSX.Element {
	const { data, updateData } = useRegister();
	const [showPassword, setShowPassword] = useState(false);
	const [showPasswordConfirm, setShowPasswordConfirm] = useState(false);
	const [passwordConfirm, setPasswordConfirm] = useState('');
	const [duplicateStatus, setDuplicateStatus] = useState<DuplicateStatus>('idle');
	const [duplicateMessage, setDuplicateMessage] = useState('');

	const handleChange = (field: string) => (
		e: ChangeEvent<HTMLInputElement | HTMLSelectElement>,
	): void => {
		updateData({ [field]: e.target.value });
		if (field === 'loginId') {
			setDuplicateStatus('idle');
			setDuplicateMessage('');
		}
	};

	// UI 데모용 — 실제 API 미연동
	const handleCheckDuplicate = (): void => {
		if (!data.loginId) return;
		setDuplicateStatus('ok');
		setDuplicateMessage('사용 가능한 아이디입니다.');
	};

	const inner = (
		<div className="form-group">
			<div className="input-wrap width50">
				<label htmlFor="id">
					{isBusiness ? '회사명' : '아이디'}
					<span className="essential">필수</span>
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
								required
								aria-required="true"
								autoComplete="username"
								aria-describedby="id_hint"
							/>
							<button
								type="button"
								className="btn point"
								aria-label="중복확인"
								onClick={handleCheckDuplicate}
								disabled={!data.loginId || duplicateStatus === 'checking'}
							>
								<span>
									{duplicateStatus === 'checking' ? '확인중...' : '중복확인'}
								</span>
							</button>
						</div>
						{duplicateStatus !== 'idle' && (
							<div className="input-hint-box">
								{duplicateStatus === 'checking' && (
									<p className="information" id="id_hint">중복 확인 중...</p>
								)}
								{duplicateStatus === 'ok' && (
									<p className="information" id="id_hint">{duplicateMessage}</p>
								)}
								{duplicateStatus === 'duplicate' && (
									<p className="invalid" id="id_hint">{duplicateMessage}</p>
								)}
							</div>
						)}
					</>
				)}
			</div>
			{!isBusiness && (
				<div className="input-wrap width50" aria-hidden="true" />
			)}
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
						<label htmlFor="founded_date">
							설립일<span className="essential">필수</span>
						</label>
						<div className="input-box">
							<input
								id="founded_date"
								type="text"
								name="founded_date"
								placeholder="YYYY-MM-DD"
								maxLength={10}
								value={data.startDt}
								onChange={(e): void => {
									const raw = e.target.value.replace(/[^0-9]/g, '');
									let formatted = raw;
									if (raw.length > 4)
										formatted = `${raw.slice(0, 4)}-${raw.slice(4)}`;
									if (raw.length > 6)
										formatted = `${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6, 8)}`;
									updateData({ startDt: formatted });
								}}
								required
								aria-required="true"
							/>
						</div>
					</div>
					<div className="input-wrap width50">
						<label htmlFor="business_num">
							사업자등록번호<span className="essential">필수</span>
						</label>
						<div className="input-box">
							<input
								id="business_num"
								type="text"
								name="business_num"
								value={data.brno}
								disabled
							/>
							<button
								type="button"
								className="btn point"
								aria-label="중복확인"
								disabled={!data.brno}
							>
								<span>중복확인</span>
							</button>
						</div>
						{/* {!data.brno && (
							<div className="input-hint-box">
								<p className="invalid">
									사업자등록번호가 입력되지 않았습니다. 기업인증 단계에서 입력해 주세요.
								</p>
							</div>
						)} */}
					</div>
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
										if (e.target.value)
											updateData({ emailDomain: e.target.value });
									}}
									aria-label="이메일 도메인 선택"
								>
									<option value="">직접 입력</option>
									<option value="mss.go.kr">mss.go.kr</option>
									<option value="naver.com">naver.com</option>
									<option value="gmail.com">gmail.com</option>
								</select>
								<button type="button" className="btn large icon arrow-bottom">
									<span className="hidden">선택창 열기</span>
									<i className="icon arrow-bottom" aria-hidden="true" />
								</button>
							</div>
						</div>
					</div>
					<div className="input-wrap width50">
						<label htmlFor="tel2">대표 전화번호</label>
						<div className="input-flex-box">
							<div className="input-box small">
								<select
									name="tel1"
									value={data.telPrefix}
									onChange={handleChange('telPrefix')}
									aria-label="대표 전화번호 지역번호"
								>
									<option value="" disabled hidden>
										선택
									</option>
									<option value="02">02</option>
									<option value="031">031</option>
									<option value="070">070</option>
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
									placeholder="숫자만 입력해주세요"
									value={data.telSuffix}
									onChange={handleChange('telSuffix')}
									aria-label="대표 전화번호 뒷자리"
								/>
							</div>
						</div>
					</div>
				</>
			) : (
				<>
					<div className="input-wrap width50">
						<label htmlFor="password">
							비밀번호<span className="essential">필수</span>
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
								aria-describedby="password_hint password_error"
							/>
							<button
								type="button"
								className="btn large icon pw-check"
								onClick={(): void => setShowPassword((p) => !p)}
								aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}
							>
								<i
									className={`icon pw-visible${showPassword ? ' on' : ''}`}
									aria-hidden="true"
								/>
							</button>
						</div>
						<div className="input-hint-box">
							<p className="information" id="password_hint">
								5~20자의 영문 소문자, 숫자와 특수기호 (_), (.)만 사용 가능합니다.
							</p>
							{/* <p className="invalid" id="password_error">
								에러 메시지
							</p> */}
						</div>
					</div>
					<div className="input-wrap width50">
						<label htmlFor="password_check">
							비밀번호 확인<span className="essential">필수</span>
						</label>
						<div className="input-box">
							<input
								type={showPasswordConfirm ? 'text' : 'password'}
								id="password_check"
								placeholder="비밀번호를 입력하세요"
								maxLength={20}
								value={passwordConfirm}
								onChange={(e): void => setPasswordConfirm(e.target.value)}
								required
								aria-required="true"
								autoComplete="new-password"
								aria-describedby="password_check_hint password_check_error"
							/>
							<button
								type="button"
								className="btn large icon pw-check"
								onClick={(): void => setShowPasswordConfirm((p) => !p)}
								aria-label={
									showPasswordConfirm
										? '비밀번호 확인 숨기기'
										: '비밀번호 확인 보기'
								}
							>
								<i
									className={`icon pw-visible${showPasswordConfirm ? ' on' : ''}`}
									aria-hidden="true"
								/>
							</button>
						</div>
						<div className="input-hint-box">
							<p className="information" id="password_check_hint">
								5~20자의 영문 소문자, 숫자와 특수기호 (_), (.)만 사용 가능합니다.
							</p>
							{/* <p className="invalid" id="password_check_error">
								에러 메시지
							</p> */}
						</div>
					</div>
				</>
			)}
		</div>
	);

	return flat ? inner : <div className="form-wrap">{inner}</div>;
}

export default AccountForm;
