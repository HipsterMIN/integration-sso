import { useState } from 'react';
import ConversionLayout from 'components/ConversionLayout';
import Modal from 'components/KrdsModal';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { MOCK_MEMBER, MOCK_BUSINESS } from 'constants/mockData';
import { getConversionRoute } from '../routes';

const SYSTEMS = [
	{ id: 'check1', title: '스마트공장 사업관리시스템', desc: '스마트공장 시스템 사업 정보 제공' },
	{ id: 'check2', title: '중소기업 기술개발사업 종합관리 시스템', desc: '기술개발지원사업 정보 제공' },
	{ id: 'check3', title: '범부처통합연구지원시스템', desc: '연구관리 규정 및 지침 통합시스템' },
	{ id: 'check4', title: '벤처투자종합포털', desc: '벤처투자 관련 종합정보 서비스 제공' },
	{ id: 'check5', title: '해외규격인증획득지원센터', desc: '중소기업에 효율적 인증업무 지원' },
	{ id: 'check6', title: 'K스타트업', desc: '창업콘텐츠, 창업사업 정보 제공' },
	{ id: 'check7', title: '창업기업 확인시스템', desc: '창업기업 확인 시스템' },
] as const;

interface Step4Props {
	memberType?: MemberType;
}

function ConversionStep4({ memberType = 'member' }: Step4Props): JSX.Element {
	const [checked, setChecked] = useState<Record<string, boolean>>({});
	const [infoModal, setInfoModal] = useState(false);
	const isBusiness = memberType === 'business';

	const handleCheck = (id: string): void => {
		setChecked((prev) => ({ ...prev, [id]: !prev[id] }));
	};

	return (
		<ConversionLayout
			currentStep={4}
			skipRoute={getConversionRoute(5, memberType)}
			nextRoute={getConversionRoute(5, memberType)}
			memberType={memberType}
		>
			<div className="text-info-wrap point">
				<ul className="text-list-wrap check" aria-label="안내 사항">
					<li><p>유관시스템 서비스를 하나의 통합 ID로 연결합니다</p></li>
					<li>
						<p>
							등록을 원하지 않으실 경우 ‘건너뛰기’를 선택하여 가입을 완료하실 수 있습니다.
						</p>
					</li>
					<li>
						<p>
							추후 ( 마이페이지 &gt; 유과기관 서비스 관리 )에서 언제든지 추가 등록, 탈퇴할 수 있습니다
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
			<div className="check-box-wrap" role="group" aria-label="가입현황 시스템 선택">
				{SYSTEMS.map(({ id, title, desc }) => (
					<div
						key={id}
						className="check-box style3"
						onClick={(): void => setInfoModal(true)}
						role="button"
						tabIndex={0}
						onKeyDown={(e): void => {
							if (e.key === 'Enter' || e.key === ' ') setInfoModal(true);
						}}
					>
						<label htmlFor={id} onClick={(e): void => e.stopPropagation()}>
							<input
								type="checkbox"
								id={id}
								name="check"
								checked={!!checked[id]}
								onChange={(): void => handleCheck(id)}
								aria-label={title}
							/>
						</label>
						<div className="text-box">
							<strong className="tit">{title}</strong>
							<p className="text">{desc}</p>
						</div>
					</div>
				))}
			</div>

			<Modal
				id="modal_member_information"
				isOpen={infoModal}
				onClose={(): void => setInfoModal(false)}
				topText="계정 가입 현황"
				title="해당 유관기관의 회원 정보"
				buttons={[{ label: '적용', variant: 'primary' }]}
				contentsClassName="form-wrap"
			>
				{isBusiness ? (
					<>
						<div className="input-wrap">
							<label htmlFor="modal_company_name">회사명</label>
							<div className="input-box">
								<input id="modal_company_name" type="text" defaultValue={MOCK_BUSINESS.companyName} disabled />
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_business_num">사업자등록번호</label>
							<div className="input-box">
								<input id="modal_business_num" type="number" defaultValue={MOCK_BUSINESS.businessNum} disabled />
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_rep_name">대표자명</label>
							<div className="input-box">
								<input id="modal_rep_name" type="text" defaultValue={MOCK_BUSINESS.repName} disabled />
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_phone1">대표전화</label>
							<div className="input-flex-box">
								<div className="input-box">
									<input id="modal_phone1" type="number" defaultValue={MOCK_BUSINESS.phonePrefix} disabled aria-label="대표전화 앞자리" />
								</div>
								<div className="input-box">
									<input id="modal_phone2" type="number" defaultValue={MOCK_BUSINESS.phoneSuffix} disabled aria-label="대표전화 뒷자리" />
								</div>
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_email1">이메일</label>
							<div className="input-flex-box">
								<div className="input-box">
									<input id="modal_email1" type="text" defaultValue={MOCK_BUSINESS.emailId} disabled aria-label="이메일 아이디" />
								</div>
								<span>@</span>
								<div className="input-box">
									<input id="modal_email2" type="text" defaultValue={MOCK_BUSINESS.emailDomain} disabled aria-label="이메일 도메인" />
								</div>
							</div>
						</div>
					</>
				) : (
					<>
						<div className="input-wrap">
							<label htmlFor="modal_name">이름</label>
							<div className="input-box">
								<input id="modal_name" type="text" defaultValue={MOCK_MEMBER.name} disabled />
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_phone1">휴대전화</label>
							<div className="input-flex-box">
								<div className="input-box">
									<input id="modal_phone1" type="number" defaultValue={MOCK_MEMBER.phonePrefix} disabled aria-label="휴대전화 앞자리" />
								</div>
								<div className="input-box">
									<input id="modal_phone2" type="number" defaultValue={MOCK_MEMBER.phoneSuffix} disabled aria-label="휴대전화 뒷자리" />
								</div>
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_tel1">유선전화</label>
							<div className="input-flex-box">
								<div className="input-box">
									<input id="modal_tel1" type="number" defaultValue={MOCK_MEMBER.telPrefix} disabled aria-label="유선전화 지역번호" />
								</div>
								<div className="input-box">
									<input id="modal_tel2" type="number" defaultValue={MOCK_MEMBER.telSuffix} disabled aria-label="유선전화 뒷자리" />
								</div>
							</div>
						</div>
						<div className="input-wrap">
							<label htmlFor="modal_email1">이메일</label>
							<div className="input-flex-box">
								<div className="input-box">
									<input id="modal_email1" type="text" defaultValue={MOCK_MEMBER.emailId} disabled aria-label="이메일 아이디" />
								</div>
								<span>@</span>
								<div className="input-box">
									<input id="modal_email2" type="text" defaultValue={MOCK_MEMBER.emailDomain} disabled aria-label="이메일 도메인" />
								</div>
							</div>
						</div>
					</>
				)}
			</Modal>
		</ConversionLayout>
	);
}

export default ConversionStep4;
