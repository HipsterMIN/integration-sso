import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { FormEvent } from 'react';

import { getMypageRoute } from './routes';
import { useInfoStore } from './useInfoStore';

// 통합회원 탈퇴 — step1 (URL: /withdraw): 회원 정보 + 보관 안내 + 통합회원 탈퇴 버튼
function Withdraw(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const { member, business } = useInfoStore();
	const nextRoute = getMypageRoute(memberType, 'WITHDRAW_STEP2');

	return (
		<MypageContent>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>중기원패스를 이용해 주신 회원님께 진심으로 감사드립니다.</p>
						</li>
						<li>
							<p>
								탈퇴 이후에 재가입은 가능하지만 기존에 사용하였던 ID는 더이상
								사용할 수 없습니다.
							</p>
						</li>
						{isBusiness && (
							<li>
								<p>기업회원은 해당 기업관리자만이 회원탈퇴가 가능합니다.</p>
							</li>
						)}
					</ul>
					<figure className="img">
						<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
			</div>
			<form
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="통합회원 탈퇴"
			>
				<div className="white-wrap">
					{isBusiness ? (
						<div className="box">
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
					) : (
						<div className="box">
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
					)}

					<div className="box">
						<div className="title-box">
							<h3 className="h3-title">회원 탈퇴 시 회원정보 보관 안내</h3>
							<p className="text">
								회원가입 시 입력하신 회원정보는 &quot;개인정보처리방침&quot;에
								따라 아래와 같이 일정기간 저장함을 안내합니다.
							</p>
						</div>
						<div className="table-box style1 black scroll">
							<table>
								<caption>회원 탈퇴 시 회원정보 보관 안내</caption>
								<colgroup>
									<col style={{ width: '20%' }} />
									<col style={{ width: '20%' }} />
									<col style={{ width: '20%' }} />
									<col style={{ width: '20%' }} />
									<col style={{ width: '20%' }} />
								</colgroup>
								<thead>
									<tr>
										<th><p>구분</p></th>
										<th><p>보유기간</p></th>
										<th><p>수집동의</p></th>
										<th><p>법적근거</p></th>
										<th><p>비고</p></th>
									</tr>
								</thead>
								<tbody>
									<tr>
										<th><p>회원정보</p></th>
										<td><p>즉시파기</p></td>
										<td rowSpan={4}><p>정보주체의 동의</p></td>
										<td rowSpan={4}>
											<p>
												개인정보보호법 제3장 정보통신망 이용촉진 및 정보보호
												등에 관한 법률 제27조
											</p>
										</td>
										<td rowSpan={4}><p>보유기간이 도달하면 즉시 파기</p></td>
									</tr>
									<tr>
										<th><p>지원사업신청이력</p></th>
										<td><p>5년</p></td>
									</tr>
									<tr>
										<th><p>증명서 발급 이력</p></th>
										<td><p>180일</p></td>
									</tr>
									<tr>
										<th><p>전자민원신청이력</p></th>
										<td><p>180일</p></td>
									</tr>
								</tbody>
							</table>
						</div>
					</div>
				</div>
				<div className="btn-box" role="group" aria-label="페이지 이동">
					<button
						type="button"
						className="btn point"
						onClick={(): void => {
							sessionStorage.setItem('mypage_withdraw_step1_passed', '1');
							history.push(nextRoute);
						}}
					>
						<span>탈퇴하기</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
				</div>
			</form>
		</MypageContent>
	);
}

export default Withdraw;
