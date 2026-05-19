import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import { FormEvent, useState } from 'react';

import { useInfoStore } from './useInfoStore';

// 통합회원 탈퇴 — step1 (URL: /withdraw): 회원 정보 + 보관 안내 + 통합회원 탈퇴 버튼
function Withdraw(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const { business } = useInfoStore();
	const [devNoticeModal, setDevNoticeModal] = useState(false);

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
					{isBusiness && (
						<div className="box">
							<h3 className="h3-title">회원 정보</h3>
							<div className="table-box style1 scroll">
								<table>
									<caption>기업 정보</caption>
									<colgroup>
										<col style={{ width: '16.9%' }} />
										<col />
									</colgroup>
									<tbody>
										<tr>
											<th><p>기업명</p></th>
											<td><p>{business.company_name}</p></td>
										</tr>
										<tr>
											<th><p>기업관리자</p></th>
											<td><p>{business.name}</p></td>
										</tr>
									</tbody>
								</table>
							</div>
						</div>
					)}

					<div className="box">
						<h3 className="h3-title">탈퇴 시 회원 정보 보관 안내</h3>
						<div className="text-info-wrap point">
							<ul className="text-list-wrap check" aria-label="회원 정보 보관 안내">
								<li>
									<p>
										회원가입 시 입력하신 회원정보는 &quot;개인정보처리방침&quot;에
										따라 아래와 같이 일정기간 저장함을 안내합니다.
									</p>
								</li>
							</ul>
						</div>
						<div className="table-box style1 scroll">
							<table>
								<caption>탈퇴 시 회원 정보 보관 안내</caption>
								<colgroup>
									<col style={{ width: '16.9%' }} />
									<col />
								</colgroup>
								<thead>
									<tr>
										<th><p>구분</p></th>
										<th><p>보유기간</p></th>
									</tr>
								</thead>
								<tbody>
									<tr><th><p>회원정보</p></th><td><p>즉시 파기</p></td></tr>
									<tr><th><p>지원사업신청이력</p></th><td><p>5년</p></td></tr>
									<tr><th><p>증명서 발급 이력</p></th><td><p>180일</p></td></tr>
									<tr><th><p>전자민원신청이력</p></th><td><p>180일</p></td></tr>
								</tbody>
							</table>
						</div>
						<div className="table-box style1 scroll">
							<table>
								<caption>탈퇴 시 회원 정보 보관 안내 — 추가</caption>
								<colgroup>
									<col style={{ width: '16.9%' }} />
									<col />
								</colgroup>
								<tbody>
									<tr><th><p>수집동의</p></th><td><p>정보주체의 동의</p></td></tr>
									<tr>
										<th><p>법적근거</p></th>
										<td>
											<p>
												개인정보보호법 제3장 정보통신망 이용촉진 및 정보보호 등에 관한 법률 제27조
											</p>
										</td>
									</tr>
									<tr><th><p>비고</p></th><td><p>보유기간이 도달하면 즉시 파기</p></td></tr>
								</tbody>
							</table>
						</div>
					</div>
				</div>
				<div className="btn-box" role="group" aria-label="페이지 이동">
					<button type="button" className="btn point" onClick={(): void => setDevNoticeModal(true)}>
						<span>통합회원 탈퇴</span>
						<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
					</button>
				</div>
			</form>
			<Modal
				id="modal_withdraw_dev_notice"
				isOpen={devNoticeModal}
				onClose={(): void => setDevNoticeModal(false)}
				topText="안내"
				title="서비스 준비 중"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setDevNoticeModal(false),
					},
				]}
			>
				<p>현재 개발 중인 기능입니다.</p>
				<p>빠른 시일 내에 서비스를 제공할 예정입니다.</p>
			</Modal>
		</MypageContent>
	);
}

export default Withdraw;
