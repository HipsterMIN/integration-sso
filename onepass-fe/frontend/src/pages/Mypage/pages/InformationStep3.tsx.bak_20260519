import { modifyEnterprise, modifyMember } from 'api/ext/members';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { ChangeEvent, FormEvent, useRef, useState } from 'react';

import { getMypageRoute } from './routes';
import { loadUserId, useInfoStore } from './useInfoStore';

const EMAIL_OPTIONS = ['direct', 'naver.com', 'gmail.com', 'hanmail.net'];

/** 숫자만 추출하여 YYYY-MM-DD 형식으로 포맷 */
function formatDateInput(value: string): string {
	const digits = value.replace(/\D/g, '').slice(0, 8);
	if (digits.length <= 4) return digits;
	if (digits.length <= 6) return `${digits.slice(0, 4)}-${digits.slice(4)}`;
	return `${digits.slice(0, 4)}-${digits.slice(4, 6)}-${digits.slice(6)}`;
}

/** YYYY-MM-DD 형식의 날짜가 유효한지 검증 */
function isValidDate(value: string): boolean {
	const digits = value.replace(/\D/g, '');
	if (digits.length !== 8) return false;
	const year = parseInt(digits.slice(0, 4), 10);
	const month = parseInt(digits.slice(4, 6), 10);
	const day = parseInt(digits.slice(6, 8), 10);
	if (year < 1900 || year > new Date().getFullYear()) return false;
	if (month < 1 || month > 12) return false;
	const date = new Date(year, month - 1, day);
	return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day;
}

/** 이메일 형식 검증 */
function isValidEmail(email1: string, email2: string): boolean {
	if (!email1 || !email2) return false;
	const full = `${email1}@${email2}`;
	return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(full);
}

interface DateInputProps {
	id: string;
	name: string;
	defaultValue: string;
	placeholder?: string;
}

function DateInput({ id, name, defaultValue, placeholder }: DateInputProps): JSX.Element {
	const [value, setValue] = useState(formatDateInput(defaultValue));

	const handleChange = (e: ChangeEvent<HTMLInputElement>): void => {
		const formatted = formatDateInput(e.target.value);
		setValue(formatted);
	};

	return (
		<input
			id={id}
			type="text"
			name={name}
			value={value}
			onChange={handleChange}
			placeholder={placeholder || 'YYYY-MM-DD'}
			maxLength={10}
		/>
	);
}

interface EmailFieldProps {
	defaultEmail1: string;
	defaultEmail2: string;
	wrapperClassName?: string;
	required?: boolean;
}

function EmailField({
	defaultEmail1,
	defaultEmail2,
	wrapperClassName = 'input-wrap style2',
	required = false,
}: EmailFieldProps): JSX.Element {
	const matched = EMAIL_OPTIONS.find((o) => o === defaultEmail2) || 'direct';
	const [selectVal, setSelectVal] = useState(matched);
	const [email2Val, setEmail2Val] = useState(defaultEmail2);

	const handleSelect = (e: ChangeEvent<HTMLSelectElement>): void => {
		const val = e.target.value;
		setSelectVal(val);
		if (val !== 'direct') {
			setEmail2Val(val);
		} else {
			setEmail2Val('');
		}
	};

	return (
		<div className={wrapperClassName}>
			<label htmlFor="email1">
				이메일{required && <span className="essential">필수</span>}
			</label>
			<div className="input-flex-box">
				<div className="input-box small">
					<input
						id="email1"
						type="text"
						name="email1"
						defaultValue={defaultEmail1}
						aria-label="이메일 아이디"
					/>
				</div>
				<span>@</span>
				<div className="input-box small">
					<input
						id="email2"
						type="text"
						name="email2"
						value={email2Val}
						onChange={(e): void => setEmail2Val(e.target.value)}
						readOnly={selectVal !== 'direct'}
						aria-label="이메일 도메인"
					/>
				</div>
				<div className="input-box small">
					<select
						id="emailSelect"
						value={selectVal}
						onChange={handleSelect}
						aria-label="이메일 도메인 선택"
					>
						<option value="direct">직접 입력</option>
						<option value="naver.com">naver.com</option>
						<option value="gmail.com">gmail.com</option>
						<option value="hanmail.net">hanmail.net</option>
					</select>
					<button type="button" className="btn large icon arrow-bottom">
						<span className="hidden">선택창 열기</span>
						<i className="icon arrow-bottom" aria-hidden="true" />
					</button>
				</div>
			</div>
		</div>
	);
}

interface FormProps {
	formRef: React.RefObject<HTMLFormElement>;
	onSubmit: () => void;
}

// PUB260507 mypage_information_step3.html — 기본 정보(편집) + 알림 수신 + 수정
function BusinessForm({ formRef, onSubmit }: FormProps): JSX.Element {
	const { business } = useInfoStore();
	const telCombined = `${business.tel2}${business.tel3}`;

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
								중기원패스 통합회원의 회원정보는 개인정보처리방침에 따라
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
				ref={formRef}
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="나의 정보 수정"
			>
				<div className="white-wrap">
					<h3 className="h3-title">기본 정보</h3>
					<div className="form-wrap">
						<div className="input-wrap width50">
							<label htmlFor="company_name">
								회사명<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									id="company_name"
									type="text"
									name="company_name"
									defaultValue={business.company_name}
									disabled
									readOnly
								/>
							</div>
						</div>
						<div className="input-wrap width50">
							<label htmlFor="name">
								대표자명<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									id="name"
									type="text"
									name="name"
									defaultValue={business.name}
									disabled
									readOnly
								/>
							</div>
						</div>
						<div className="input-wrap width50">
							<label htmlFor="founded_date">
								설립일<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<DateInput
									id="founded_date"
									name="founded_date"
									defaultValue={business.estbDt || ''}
									placeholder="YYYY-MM-DD"
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
									defaultValue={business.company_num}
									disabled
									readOnly
								/>
							</div>
						</div>
						<EmailField
							defaultEmail1={business.email1}
							defaultEmail2={business.email2}
							wrapperClassName="input-wrap width50"
							required
						/>
						<div className="input-wrap width50">
							<label htmlFor="tel2">대표 전화번호</label>
							<div className="input-flex-box">
								<div className="input-box small">
									<select
										id="tel1"
										name="tel1"
										defaultValue={business.tel1}
										aria-label="대표 전화번호 지역번호"
									>
										<option value="" hidden>선택</option>
										<option value="02">02</option>
										<option value="070">070</option>
										<option value="010">010</option>
										{!['02', '070', '010'].includes(business.tel1) && (
											<option value={business.tel1}>{business.tel1}</option>
										)}
									</select>
									<button
										type="button"
										className="btn large icon arrow-bottom"
									>
										<span className="hidden">선택창 열기</span>
										<i className="icon arrow-bottom" aria-hidden="true" />
									</button>
								</div>
								<div className="input-box small">
									<input
										id="tel2"
										type="text"
										name="tel2"
										defaultValue={telCombined}
										aria-label="대표 전화번호 뒷자리"
									/>
								</div>
							</div>
						</div>
					</div>
				</div>
				{/* <div className="white-wrap">
					<h3 className="h3-title">알림 수신</h3>
					<div className="text-info-wrap point">
						<ul className="text-list-wrap check" aria-label="안내 사항">
							<li>
								<p>
									중소벤처24의 알림은 이메일과 SNS 또는 알림톡으로 발송되며, 정책자금
									상담, Q&A, 민원 등의 처리현황 정보가 발송됩니다.
								</p>
							</li>
						</ul>
					</div>
					<div className="check-box-wrap" role="group" aria-label="알림 수신 방법">
						<label className="check-box style4 large">
							<input type="checkbox" name="push" value="문자" defaultChecked />
							<small>문자</small>
						</label>
						<label className="check-box style4 large">
							<input type="checkbox" name="push" value="알림톡(카카오톡)" />
							<small>알림톡(카카오톡)</small>
						</label>
						<label className="check-box style4 large">
							<input type="checkbox" name="push" value="이메일수신" />
							<small>이메일수신</small>
						</label>
					</div>
				</div> */}
				<div className="btn-box" role="group" aria-label="페이지 이동">
					<button type="button" className="btn point" onClick={onSubmit}>
						<span>수정</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
				</div>
			</form>
		</>
	);
}

// PUB260507 business step3 패턴 차용 — 기본 정보(편집) + 알림 수신 + 수정
function MemberForm({ formRef, onSubmit }: FormProps): JSX.Element {
	const { member } = useInfoStore();
	const phoneCombined = `${member.phone2}${member.phone3}`;

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
								중기원패스 통합회원의 회원정보는 개인정보처리방침에 따라
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
				ref={formRef}
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="나의 정보 수정"
			>
				<div className="white-wrap">
					<h3 className="h3-title">기본 정보</h3>
					<div className="form-wrap">
						<div className="input-wrap width50">
							<label htmlFor="user_id">
								아이디<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									id="user_id"
									type="text"
									name="user_id"
									defaultValue={member.id}
									disabled
									readOnly
								/>
							</div>
						</div>
						<div className="input-wrap width50">
							<label htmlFor="name">
								이름<span className="essential">필수</span>
							</label>
							<div className="input-box">
								<input
									id="name"
									type="text"
									name="name"
									defaultValue={member.name}
								/>
							</div>
						</div>
						<div className="input-wrap width50">
							<label htmlFor="phone2">휴대전화번호</label>
							<div className="input-flex-box">
								<div className="input-box small">
									<select
										id="phone1"
										name="phone1"
										defaultValue={member.phone1 || '011'}
										aria-label="휴대전화 통신사 번호"
									>
										<option value="" hidden>선택</option>
										<option value="010">010</option>
										<option value="011">011</option>
										<option value="016">016</option>
										<option value="017">017</option>
										<option value="018">018</option>
										<option value="019">019</option>
										{!['010', '011', '016', '017', '018', '019'].includes(member.phone1) && (
											<option value={member.phone1}>{member.phone1}</option>
										)}
									</select>
									<button
										type="button"
										className="btn large icon arrow-bottom"
									>
										<span className="hidden">선택창 열기</span>
										<i className="icon arrow-bottom" aria-hidden="true" />
									</button>
								</div>
								<div className="input-box small">
									<input
										id="phone2"
										type="text"
										name="phone2"
										defaultValue={phoneCombined}
										aria-label="휴대전화 뒷자리"
									/>
								</div>
							</div>
						</div>
						<EmailField
							defaultEmail1={member.email1}
							defaultEmail2={member.email2}
							wrapperClassName="input-wrap width50"
							required
						/>
					</div>
				</div>
				{/* <div className="white-wrap">
					<h3 className="h3-title">알림 수신</h3>
					<div className="text-info-wrap point">
						<ul className="text-list-wrap check" aria-label="안내 사항">
							<li>
								<p>
									중소벤처24의 알림은 이메일과 SNS 또는 알림톡으로 발송되며, 정책자금
									상담, Q&A, 민원 등의 처리현황 정보가 발송됩니다.
								</p>
							</li>
						</ul>
					</div>
					<div className="check-box-wrap" role="group" aria-label="알림 수신 방법">
						<label className="check-box style4 large">
							<input type="checkbox" name="push" value="문자" defaultChecked />
							<small>문자</small>
						</label>
						<label className="check-box style4 large">
							<input type="checkbox" name="push" value="알림톡(카카오톡)" />
							<small>알림톡(카카오톡)</small>
						</label>
						<label className="check-box style4 large">
							<input type="checkbox" name="push" value="이메일수신" />
							<small>이메일수신</small>
						</label>
					</div>
				</div> */}
				<div className="btn-box" role="group" aria-label="페이지 이동">
					<button type="button" className="btn point" onClick={onSubmit}>
						<span>수정</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
				</div>
			</form>
		</>
	);
}

function InformationStep3(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const [resultModal, setResultModal] = useState<{ title: string; message: string; success?: boolean } | null>(null);
	const [loading, setLoading] = useState(false);
	const formRef = useRef<HTMLFormElement>(null);
	const informationRoute = getMypageRoute(memberType, 'INFORMATION');

	const handleResultConfirm = (): void => {
		const isSuccess = resultModal?.success;
		setResultModal(null);
		if (isSuccess) history.push(informationRoute);
	};

	const handleBusinessSubmit = async (): Promise<void> => {
		if (!formRef.current) return;
		const fd = new FormData(formRef.current);

		const mbrUuid = loadUserId('business');
		if (!mbrUuid) {
			setResultModal({ title: '오류', message: '회원 정보를 찾을 수 없습니다. 다시 로그인해주세요.' });
			return;
		}

		// 필수값 검증: 설립일, 이메일
		const estbDt = (fd.get('founded_date') as string || '').trim();
		const email1 = (fd.get('email1') as string || '').trim();
		const email2 = (fd.get('email2') as string || '').trim();

		if (!estbDt) {
			setResultModal({ title: '필수 입력', message: '설립일을 입력해주세요.' });
			return;
		}
		if (!isValidDate(estbDt)) {
			setResultModal({ title: '필수 입력', message: '설립일이 유효하지 않습니다. YYYY-MM-DD 형식으로 입력해주세요.' });
			return;
		}
		if (!email1 || !email2) {
			setResultModal({ title: '필수 입력', message: '이메일을 입력해주세요.' });
			return;
		}
		if (!isValidEmail(email1, email2)) {
			setResultModal({ title: '필수 입력', message: '이메일 형식이 올바르지 않습니다.' });
			return;
		}

		// 전화번호 조합
		const tel1 = (fd.get('tel1') as string || '').trim();
		const tel2 = (fd.get('tel2') as string || '').trim();
		const rprsTelno = tel1 && tel2 ? `${tel1}-${tel2}` : '';

		setLoading(true);
		const res = await modifyEnterprise({
			mbrUuid,
			rprsTelno: rprsTelno || undefined,
			rprsEmlAddr: `${email1}@${email2}`,
			estbDt,
		});
		setLoading(false);

		if (res.statusCode === 200) {
			setResultModal({ title: '나의 정보 수정', message: '회원정보가 정상적으로 변경되었습니다', success: true });
		} else {
			setResultModal({ title: '수정 실패', message: res.message || '회원정보 수정에 실패했습니다.' });
		}
	};

	const handleMemberSubmit = async (): Promise<void> => {
		if (!formRef.current) return;
		const fd = new FormData(formRef.current);

		const mbrUuid = loadUserId('member');
		if (!mbrUuid) {
			setResultModal({ title: '오류', message: '회원 정보를 찾을 수 없습니다. 다시 로그인해주세요.' });
			return;
		}

		// 이름 검증 (필수)
		const memberName = (fd.get('name') as string || '').trim();
		if (!memberName) {
			setResultModal({ title: '필수 입력', message: '이름을 입력해주세요.' });
			return;
		}
		if (memberName.length < 2) {
			setResultModal({ title: '필수 입력', message: '이름은 2자 이상 입력해주세요.' });
			return;
		}

		// 휴대전화 (선택값) — 입력된 경우에만 조합, 검증 생략
		const phone1 = (fd.get('phone1') as string || '').trim();
		const phone2 = (fd.get('phone2') as string || '').trim();
		const indvMblTelno = phone1 && phone2 ? `${phone1}-${phone2}` : '';

		// 이메일 검증 (필수)
		const email1 = (fd.get('email1') as string || '').trim();
		const email2 = (fd.get('email2') as string || '').trim();

		if (!email1 || !email2) {
			setResultModal({ title: '필수 입력', message: '이메일을 입력해주세요.' });
			return;
		}
		if (!isValidEmail(email1, email2)) {
			setResultModal({ title: '필수 입력', message: '이메일 형식이 올바르지 않습니다.' });
			return;
		}

		setLoading(true);
		const res = await modifyMember({
			mbrUuid,
			memberName: memberName || undefined,
			indvMblTelno: indvMblTelno || undefined,
			emlAddr: `${email1}@${email2}`,
		});
		setLoading(false);

		if (res.statusCode === 200) {
			setResultModal({ title: '나의 정보 수정', message: '회원정보가 정상적으로 변경되었습니다', success: true });
		} else {
			setResultModal({ title: '수정 실패', message: res.message || '회원정보 수정에 실패했습니다.' });
		}
	};

	const handleSubmit = (): void => {
		if (isBusiness) {
			handleBusinessSubmit();
		} else {
			handleMemberSubmit();
		}
	};

	return (
		<MypageContent>
			{isBusiness ? (
				<BusinessForm formRef={formRef} onSubmit={handleSubmit} />
			) : (
				<MemberForm formRef={formRef} onSubmit={handleSubmit} />
			)}

			<Modal
				id={resultModal?.success ? 'modal_completed' : 'modal_result'}
				isOpen={!!resultModal}
				onClose={handleResultConfirm}
				topText=""
				title={resultModal?.title || ''}
				size={resultModal?.success ? undefined : 'small'}
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						half: resultModal?.success ? true : undefined,
						onClick: handleResultConfirm,
					},
				]}
			>
				{resultModal?.success ? (
					<div className="completed-box">
						<figure className="img">
							<img
								src={IMAGES.RENEWAL_WRITE_COMPLETED_IMG_MODAL}
								alt=""
								aria-hidden="true"
							/>
						</figure>
						<p className="completed-title blue">{resultModal.message}</p>
					</div>
				) : (
					<p>{resultModal?.message}</p>
				)}
			</Modal>

			{loading && <div className="loading-overlay" aria-label="처리 중" />}
		</MypageContent>
	);
}

export default InformationStep3;
