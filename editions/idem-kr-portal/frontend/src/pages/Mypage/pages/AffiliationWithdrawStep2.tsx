import { withdrawAffiliation, withdrawMemberAffiliation } from 'api/provision/affiliations';
import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { FormEvent, useMemo, useState } from 'react';
import { Redirect } from 'react-router-dom';

import { clearCiToken, loadCiToken, loadSelectedServices } from './affiliationServices';
import { getMypageRoute } from './routes';
import { loadUserId, useInfoStore } from './useInfoStore';

function AffiliationWithdrawStep2(): JSX.Element {
	const memberType = useMypageType();
	const [isCompletedModalOpen, setIsCompletedModalOpen] = useState(false);
	const [isErrorModalOpen, setIsErrorModalOpen] = useState(false);
	const [submitting, setSubmitting] = useState(false);
	const affiliationRoute = getMypageRoute(memberType, 'AFFILIATION');
	const step1Route = getMypageRoute(memberType, 'AFFILIATION_WITHDRAW_STEP1');
	const isBusiness = memberType === 'business';
	const { member, business } = useInfoStore();
	const clients = isBusiness ? business.clients : member.clients;

	const selectedIds = useMemo(() => loadSelectedServices(), []);
	const ciToken = useMemo(() => (isBusiness ? '' : loadCiToken()), [isBusiness]);
	const selectedServices = useMemo(
		() => clients.filter((c) => selectedIds.includes(c.clientId ?? c.clientNm)),
		[selectedIds, clients],
	);

	// step1 인증 단계를 거치지 않고 직접 진입 시 차단
	// - 선택된 서비스 없음 → 유관기관 관리 목록으로
	// - 개인회원인데 ciToken 없음 → step1(인증) 페이지로
	if (selectedIds.length === 0) return <Redirect to={affiliationRoute} />;
	if (!isBusiness && !ciToken) return <Redirect to={step1Route} />;

	const handleWithdraw = async (): Promise<void> => {
		const uuid = loadUserId(isBusiness ? 'business' : 'member');
		if (!uuid) {
			setIsErrorModalOpen(true);
			return;
		}

		setSubmitting(true);
		let res;
		if (isBusiness) {
			res = await withdrawAffiliation(uuid, selectedIds);
		} else {
			const ciToken = loadCiToken();
			res = await withdrawMemberAffiliation(uuid, selectedIds, ciToken);
			clearCiToken();
		}
		setSubmitting(false);

		if (res.statusCode === 200) {
			setIsCompletedModalOpen(true);
		} else {
			setIsErrorModalOpen(true);
		}
	};

	return (
		<MypageContent>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>
								추가하기 버튼을 클릭하시면 중기원패스 통합회원을 이용하실 수
								있는 유관기관 항목을 보실 수 있습니다.
							</p>
						</li>
						<li>
							<p>
								이용중인 유관기관을 선택 후 회원탈퇴를 선택하시면 해당 유관기관을
								탈퇴하실 수 있습니다.
							</p>
						</li>
					</ul>
					<figure className="img">
						<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
					</figure>
				</div>
			</div>
			<form
				className="form-container"
				onSubmit={(e: FormEvent): void => e.preventDefault()}
				aria-label="유관기관 탈퇴"
			>
				<div className="white-wrap">
					<div className="box">
						<h3 className="h3-title">이용중인 유관기관</h3>
						<div className="form-wrap">
							<div className="check-box-wrap" role="group" aria-label="탈퇴할 유관기관 목록">
								{selectedServices.map((svc) => (
									<label key={svc.clientId ?? svc.clientNm} className="check-box style3">
										<input
											type="checkbox"
											id={`withdraw_${svc.clientId ?? svc.clientNm}`}
											name="check"
											checked
											disabled
											readOnly
										/>
										<div className="text-box">
											<strong className="tit">{svc.clientNm}</strong>
											<p className="text">{svc.description ?? svc.clientNm}</p>
										</div>
									</label>
								))}
							</div>
						</div>
					</div>
					<div className="box">
						<h3 className="h3-title">탈퇴 시 정보 보관 안내</h3>
						<div className="text-info-wrap point">
							<ul className="text-list-wrap check" aria-label="탈퇴 시 정보 보관 안내">
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
								<caption>탈퇴 시 정보 보관 안내</caption>
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
								<caption>탈퇴 시 정보 보관 안내 — 추가</caption>
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
				<div className="btn-box">
					<button type="button" className="btn point" disabled={submitting} onClick={handleWithdraw}>
						<span>{submitting ? '처리 중...' : '유관기관 탈퇴'}</span>
						<i className="icon ico-exit-to-app small" aria-hidden="true" />
					</button>
				</div>
			</form>

			<Modal
				id="modal_completed"
				isOpen={isCompletedModalOpen}
				onClose={(): void => history.push(affiliationRoute)}
				topText=""
				title="유관기관 서비스 관리"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						size: 'large',
						half: true,
						onClick: (): void => history.push(affiliationRoute),
					},
				]}
			>
				<div className="completed-box">
					<figure className="img">
						<img
							src={IMAGES.RENEWAL_LIST_COMPLETED_IMG_MODAL}
							alt=""
							aria-hidden="true"
						/>
					</figure>
					<p className="completed-title">
						선택하신 유관기관 서비스가 정상적으로 탈퇴되었습니다
					</p>
					<ul className="text-list-wrap dots">
						{selectedServices.map((svc) => (
							<li key={svc.clientId ?? svc.clientNm}>
								<p>{svc.clientNm}</p>
							</li>
						))}
					</ul>
				</div>
			</Modal>

			<Modal
				id="modal_withdraw_error"
				isOpen={isErrorModalOpen}
				onClose={(): void => setIsErrorModalOpen(false)}
				topText="안내"
				title="유관기관 탈퇴 실패"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setIsErrorModalOpen(false),
					},
				]}
			>
				<p>유관기관 탈퇴 처리 중 오류가 발생하였습니다.</p>
				<p>잠시 후 다시 시도해 주시기 바랍니다.</p>
			</Modal>
		</MypageContent>
	);
}

export default AffiliationWithdrawStep2;
