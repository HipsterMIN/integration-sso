import { useConversion } from 'providers/Conversion/ConversionContext';
import { ChangeEvent } from 'react';

interface MemberInfoFormProps {
	isBusiness: boolean;
	/** true 이면 외곽 .form-wrap 을 렌더하지 않음 */
	flat?: boolean;
}

function MemberInfoForm({ isBusiness, flat = false }: MemberInfoFormProps): JSX.Element {
	const { data, updateData } = useConversion();

	const handleChange =
		(field: string) =>
		(e: ChangeEvent<HTMLInputElement | HTMLSelectElement>): void => {
			updateData({ [field]: e.target.value });
		};

	const inner = (
		<div className="form-group">
			{isBusiness ? (
				<>
					<div className="input-wrap">
						<label htmlFor="company_name">
							<span className="point">(필수)</span>회사명
						</label>
						<div className="input-box">
							<input id="company_name" type="text" name="company_name" value={data.bzmnNm} disabled />
						</div>
					</div>
					<div className="input-wrap">
						<label htmlFor="business_num">
							<span className="point">(필수)</span>사업자등록번호
						</label>
						<div className="input-box">
							<input id="business_num" type="number" name="business_num" value={data.brno} disabled />
						</div>
					</div>
					<div className="input-wrap">
						<label htmlFor="name">
							<span className="point">(필수)</span>대표자명
						</label>
						<div className="input-box">
							<input id="name" type="text" name="name" value={data.rprsvNm} disabled />
						</div>
					</div>
					<div className="input-wrap">
						<label htmlFor="phone2">
							<span className="point">(필수)</span>대표전화
						</label>
						<div className="input-flex-box">
							<div className="input-box">
								<select name="phone1" value={data.phonePrefix || '010'} disabled aria-label="대표전화 앞자리">
									<option value="010">010</option>
									<option value="011">011</option>
								</select>
								<button type="button" className="btn large icon arrow-bottom">
									<span className="hidden">선택창 열기</span>
									<i className="icon arrow-bottom" aria-hidden="true" />
								</button>
							</div>
							<div className="input-box">
								<input id="phone2" type="number" name="phone2" value={data.phoneSuffix} disabled aria-label="대표전화 뒷자리" />
							</div>
						</div>
					</div>
					<div className="input-wrap">
						<label htmlFor="email1">
							<span className="point">(필수)</span>이메일
						</label>
						<div className="input-flex-box">
							<div className="input-box">
								<input id="email1" type="text" name="email1" value={data.email} disabled aria-label="이메일 아이디" />
							</div>
							<span>@</span>
							<div className="input-box">
								<input id="email2" type="text" name="email2" value={data.emailDomain} disabled aria-label="이메일 도메인" />
							</div>
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
							<input id="name" type="text" name="name" value={data.name} disabled />
						</div>
					</div>
					<div className="input-wrap width50">
						<label htmlFor="phone2">
							휴대전화<span className="essential">필수</span>
						</label>
						<div className="input-flex-box">
							<div className="input-box small">
								<select name="phone1" value={data.phonePrefix || '010'} disabled aria-label="휴대전화 앞자리">
									<option value="010">010</option>
									<option value="011">011</option>
								</select>
								<button type="button" className="btn large icon arrow-bottom">
									<span className="hidden">선택창 열기</span>
									<i className="icon arrow-bottom" aria-hidden="true" />
								</button>
							</div>
							<div className="input-box small">
								<input id="phone2" type="number" name="phone2" value={data.phoneSuffix} disabled aria-label="휴대전화 뒷자리" />
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
									placeholder="이메일"
									value={data.email}
									onChange={handleChange('email')}
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
						</div>
					</div>
				</>
			)}
		</div>
	);

	return flat ? inner : <div className="form-wrap">{inner}</div>;
}

export default MemberInfoForm;
