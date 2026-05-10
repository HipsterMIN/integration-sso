import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { ChangeEvent, FormEvent, useRef, useState } from 'react';

import { getMypageRoute } from './routes';
import { useInfoStore } from './useInfoStore';

const EMAIL_OPTIONS = ['direct', 'naver.com', 'gmail.com', 'hanmail.net'];

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
	onPrev: () => void;
}

// PUB260507 mypage_information_step3.html — 기본 정보(편집) + 알림 수신 + 이전/다음
function BusinessForm({ formRef, onSubmit, onPrev }: FormProps): JSX.Element {
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
									defaultValue={business.estbDt || ''}
									disabled
									readOnly
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
					<button type="button" className="btn white prev" onClick={onPrev}>
						<span>이전</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
					<button type="button" className="btn point" onClick={onSubmit}>
						<span>다음</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
				</div>
			</form>
		</>
	);
}

// PUB260507 business step3 패턴 차용 — 기본 정보(편집) + 알림 수신 + 이전/다음
function MemberForm({ formRef, onSubmit, onPrev }: FormProps): JSX.Element {
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
										defaultValue={member.phone1}
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
					<button type="button" className="btn white prev" onClick={onPrev}>
						<span>이전</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
					<button type="button" className="btn point" onClick={onSubmit}>
						<span>다음</span>
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
	const [devNoticeModal, setDevNoticeModal] = useState(false);
	const prevRoute = getMypageRoute(memberType, 'INFORMATION_STEP2');
	const formRef = useRef<HTMLFormElement>(null);

	const handleSubmit = (): void => {
		setDevNoticeModal(true);
	};

	const handlePrev = (): void => history.push(prevRoute);

	return (
		<MypageContent>
			{isBusiness ? (
				<BusinessForm formRef={formRef} onSubmit={handleSubmit} onPrev={handlePrev} />
			) : (
				<MemberForm formRef={formRef} onSubmit={handleSubmit} onPrev={handlePrev} />
			)}

			<Modal
				id="modal_dev_notice"
				isOpen={devNoticeModal}
				onClose={(): void => setDevNoticeModal(false)}
				topText="안내"
				title="API 연동 구현 중"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setDevNoticeModal(false),
					},
				]}
			>
				<p>현재 회원정보 수정 API 연동 개발 중입니다.</p>
			</Modal>
		</MypageContent>
	);
}

export default InformationStep3;
