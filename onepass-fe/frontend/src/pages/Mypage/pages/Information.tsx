import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { FormEvent } from 'react';

import { getMypageRoute } from './routes';
import { useInfoStore } from './useInfoStore';

function Information(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const { member, business } = useInfoStore();

	const handleEdit = (): void => {
		history.push(getMypageRoute(memberType, 'INFORMATION_STEP2'));
	};

	if (isBusiness) {
		return (
			<MypageContent>
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
							<img
								src={IMAGES.RENEWAL_TEXT_LIST_IMG}
								alt=""
								aria-hidden="true"
							/>
						</figure>
					</div>
				</div>
				<form
					className="form-container"
					onSubmit={(e: FormEvent): void => e.preventDefault()}
					aria-label="나의 정보"
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
										value={business.company_name}
										disabled
										readOnly
									/>
								</div>
							</div>
							<div className="input-wrap width50">
								<label htmlFor="rep_name">
									대표자명<span className="essential">필수</span>
								</label>
								<div className="input-box">
									<input
										id="rep_name"
										type="text"
										name="rep_name"
										value={business.name}
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
									<input
										id="founded_date"
										type="text"
										name="founded_date"
										value={business.estbDt || ''}
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
										value={business.company_num}
										disabled
										readOnly
									/>
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
											value={business.email1}
											disabled
											readOnly
											aria-label="이메일 아이디"
										/>
									</div>
									<span>@</span>
									<div className="input-box small">
										<input
											id="email2"
											type="text"
											name="email2"
											value={business.email2}
											disabled
											readOnly
											aria-label="이메일 도메인"
										/>
									</div>
									<div className="input-box small">
										<select id="emailSelect" disabled aria-label="이메일 도메인 선택">
											<option value="direct">직접 입력</option>
											<option value="naver.com">naver.com</option>
											<option value="gmail.com">gmail.com</option>
										</select>
										<button
											type="button"
											className="btn large icon arrow-bottom"
											disabled
										>
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
											id="tel1"
											name="tel1"
											defaultValue={business.tel1}
											disabled
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
											disabled
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
											value={`${business.tel2}${business.tel3}`}
											disabled
											readOnly
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
								<input
									type="checkbox"
									name="push"
									value="문자"
									defaultChecked
									disabled
								/>
								<small>문자</small>
							</label>
							<label className="check-box style4 large">
								<input
									type="checkbox"
									name="push"
									value="알림톡(카카오톡)"
									disabled
								/>
								<small>알림톡(카카오톡)</small>
							</label>
							<label className="check-box style4 large">
								<input
									type="checkbox"
									name="push"
									value="이메일수신"
									disabled
								/>
								<small>이메일수신</small>
							</label>
						</div>
					</div> */}
					<div className="btn-box" role="group" aria-label="페이지 동작">
						<button type="button" className="btn point" onClick={handleEdit}>
							<span>정보변경</span>
						</button>
					</div>
				</form>
			</MypageContent>
		);
	}

	return (
		<MypageContent>
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
						<img
							src={IMAGES.RENEWAL_TEXT_LIST_IMG}
							alt=""
							aria-hidden="true"
						/>
					</figure>
				</div>
			</div>
			<form
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="나의 정보"
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
									value={member.id}
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
									value={member.name}
									disabled
									readOnly
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
										disabled
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
										disabled
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
										value={`${member.phone2}${member.phone3}`}
										disabled
										readOnly
										aria-label="휴대전화 뒷자리"
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
										value={member.email1}
										disabled
										readOnly
										aria-label="이메일 아이디"
									/>
								</div>
								<span>@</span>
								<div className="input-box small">
									<input
										id="email2"
										type="text"
										name="email2"
										value={member.email2}
										disabled
										readOnly
										aria-label="이메일 도메인"
									/>
								</div>
								<div className="input-box small">
									<select id="emailSelect" disabled aria-label="이메일 도메인 선택">
										<option value="direct">직접 입력</option>
										<option value="naver.com">naver.com</option>
										<option value="gmail.com">gmail.com</option>
									</select>
									<button
										type="button"
										className="btn large icon arrow-bottom"
										disabled
									>
										<span className="hidden">선택창 열기</span>
										<i className="icon arrow-bottom" aria-hidden="true" />
									</button>
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
							<input
								type="checkbox"
								name="push"
								value="문자"
								defaultChecked
								disabled
							/>
							<small>문자</small>
						</label>
						<label className="check-box style4 large">
							<input
								type="checkbox"
								name="push"
								value="알림톡(카카오톡)"
								disabled
							/>
							<small>알림톡(카카오톡)</small>
						</label>
						<label className="check-box style4 large">
							<input
								type="checkbox"
								name="push"
								value="이메일수신"
								disabled
							/>
							<small>이메일수신</small>
						</label>
					</div>
				</div> */}
				<div className="btn-box" role="group" aria-label="페이지 동작">
					<button type="button" className="btn point" onClick={handleEdit}>
						<span>정보변경</span>
					</button>
				</div>
			</form>
		</MypageContent>
	);
}

export default Information;
