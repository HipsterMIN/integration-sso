import { ChangeEvent } from 'react';
import { useRegister } from 'providers/Register/RegisterContext';

interface MemberInfoFormProps {
	isBusiness: boolean;
	/** true 이면 외곽 .form-wrap 을 렌더하지 않음 */
	flat?: boolean;
}

function MemberInfoForm({
	isBusiness,
	flat = false,
}: MemberInfoFormProps): JSX.Element {
	const { data, updateData } = useRegister();

	const handleChange = (field: string) => (
		e: ChangeEvent<HTMLInputElement | HTMLSelectElement>,
	): void => {
		updateData({ [field]: e.target.value });
	};

	const inner = (
		<div className="form-group">
			{isBusiness ? (
				<>
					<div className="input-wrap width50">
						<label htmlFor="company_name">
							회사명<span className="essential">필수</span>
						</label>
						<div className="input-box">
							<input
								id="company_name"
								type="text"
								name="company_name"
								value={data.bzmnNm}
								disabled
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
								type="number"
								name="business_num"
								value={data.brno}
								disabled
							/>
						</div>
					</div>
				</>
			) : (
				<>
					<div className="input-wrap width50">
						<label htmlFor="name">
							이름<span className="essential">필수</span>
						</label>
						<div className="input-box">
							<input
								id="name"
								type="text"
								name="name"
								value={data.name}
								disabled
							/>
						</div>
					</div>
					<div className="input-wrap width50">
						<label htmlFor="phone2">
							휴대전화<span className="essential">필수</span>
						</label>
						<div className="input-flex-box">
							<div className="input-box small">
								<select
									name="phone1"
									value={data.phonePrefix}
									disabled
									aria-label="휴대전화 앞자리"
								>
									<option value="010">010</option>
									<option value="011">011</option>
								</select>
								<button type="button" className="btn large icon arrow-bottom">
									<span className="hidden">선택창 열기</span>
									<i className="icon arrow-bottom" aria-hidden="true" />
								</button>
							</div>
							<div className="input-box small">
								<input
									id="phone2"
									type="number"
									name="phone2"
									value={data.phoneSuffix}
									disabled
									aria-label="휴대전화 뒷자리"
								/>
							</div>
						</div>
					</div>
					<div className="input-wrap width50">
						<label htmlFor="tel2">유선번호</label>
						<div className="input-flex-box">
							<div className="input-box small">
								<select
									name="tel1"
									value={data.telPrefix}
									onChange={handleChange('telPrefix')}
									aria-label="유선전화 지역번호"
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
									aria-label="유선전화 뒷자리"
								/>
							</div>
						</div>
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
									name="email_domain_select"
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
				</>
			)}
		</div>
	);

	return flat ? inner : <div className="form-wrap">{inner}</div>;
}

export default MemberInfoForm;
