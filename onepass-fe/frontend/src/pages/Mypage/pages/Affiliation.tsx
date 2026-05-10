import Modal from 'components/KrdsModal';
import MypageContent from 'components/MypageContent';
import { useMypageType } from 'components/MypageLayout';
import IMAGES from 'constants/images';
import history from 'lib/history';
import { ChangeEvent, FormEvent, useMemo, useState } from 'react';

import { saveSelectedServices } from './affiliationServices';
import { getMypageRoute } from './routes';
import { useInfoStore } from './useInfoStore';

function Affiliation(): JSX.Element {
	const memberType = useMypageType();
	const isBusiness = memberType === 'business';
	const { member, business } = useInfoStore();
	const clients = isBusiness ? business.clients : member.clients;

	// API 응답의 clients[] → 화면 표시용 목록 (useYn=Y 항목만)
	const services = useMemo(
		() =>
			clients
				.filter((c) => c.useYn === 'Y')
				.map((c) => ({
					id: c.clientId ?? c.clientNm,
					tit: c.clientNm,
					text: c.description ?? `${c.clientNm} 탈퇴`,
				})),
		[clients],
	);
	const [checked, setChecked] = useState<Record<string, boolean>>({});
	const [isAlertOpen, setIsAlertOpen] = useState(false);
	const [devNoticeModal, setDevNoticeModal] = useState(false);

	const allChecked = services.length > 0 && services.every((s) => checked[s.id]);

	const handleItemChange = (id: string) => (e: ChangeEvent<HTMLInputElement>): void => {
		setChecked((prev) => ({ ...prev, [id]: e.target.checked }));
	};

	const handleAllChange = (e: ChangeEvent<HTMLInputElement>): void => {
		const val = e.target.checked;
		const next: Record<string, boolean> = {};
		services.forEach((s) => { next[s.id] = val; });
		setChecked(next);
	};

	const handleWithdraw = (): void => {
		if (!isBusiness) {
			// TODO: 개인회원 API 배포 후 복원
			setDevNoticeModal(true);
			return;
		}
		const selected = services.filter((s) => checked[s.id]).map((s) => s.id);
		if (selected.length === 0) {
			setIsAlertOpen(true);
			return;
		}
		saveSelectedServices(selected);
		history.push(getMypageRoute(memberType, 'AFFILIATION_WITHDRAW_STEP1'));
	};

	const goAdd = (): void =>
		history.push(getMypageRoute(memberType, 'AFFILIATION_ADD_STEP1'));

	return (
		<MypageContent>
			<div className="title-top-box">
				<div className="text-info-wrap point">
					<ul className="text-list-wrap check" aria-label="안내 사항">
						<li>
							<p>
								추가하기 버튼을 클릭하시면 중기원패스 통합회원을 이용하실 수 있는
								유관기관 항목을 보실 수 있습니다.
							</p>
						</li>
						<li>
							<p>
								이용중인 유관기관을 선택 후 회원탈퇴를 선택하시면 해당 유관기관을 탈퇴하실 수 있습니다.
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
				aria-label="유관기관 서비스 관리"
			>
				<div className="white-wrap">
					<div className="h3-title-box">
						<h3 className="h3-title">이용중인 유관기관</h3>
						<button
							type="button"
							className="btn add-btn purple"
							onClick={goAdd}
						>
							<span>유관기관 추가</span>
							<i className="icon ico-add" aria-hidden="true" />
						</button>
					</div>
					<div className="form-wrap">
						<div className="check-box-wrap scroll" role="group" aria-label="이용중인 유관기관 목록">
							{services.map((svc) => (
								<label key={svc.id} className="check-box style3">
									<input
										type="checkbox"
										id={svc.id}
										name="check"
										checked={!!checked[svc.id]}
										onChange={handleItemChange(svc.id)}
									/>
									<div className="text-box">
										<strong className="tit">{svc.tit}</strong>
										<p className="text">{svc.text}</p>
									</div>
								</label>
							))}
						</div>
						<label className="check-box style1 all">
							<input
								type="checkbox"
								id="all_check"
								name="all_check"
								checked={allChecked}
								onChange={handleAllChange}
							/>
							<small>이용중인 유관기관 전체 선택</small>
						</label>
					</div>
				</div>
				<div className="btn-box">
					<button type="button" className="btn point" onClick={handleWithdraw}>
						<span>유관기관 탈퇴</span>
						<i className="icon ico-exit-to-app small" aria-hidden="true" />
					</button>
				</div>
			</form>

			<Modal
				id="modal_alert_select"
				isOpen={isAlertOpen}
				onClose={(): void => setIsAlertOpen(false)}
				topText=""
				title="알림"
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setIsAlertOpen(false),
					},
				]}
			>
				<p>탈퇴할 유관기관을 선택해 주세요.</p>
			</Modal>

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

export default Affiliation;
