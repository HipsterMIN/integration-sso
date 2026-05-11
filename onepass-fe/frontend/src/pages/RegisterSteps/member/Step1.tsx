import RegisterLayout from 'components/RegisterLayout';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useRegister } from 'providers/Register/RegisterContext';
import { useEffect, useState } from 'react';

import { getRegisterRoute } from '../routes';

function RegisterStep1(): JSX.Element {
	const { updateData } = useRegister();

	// 로그인 페이지의 통합회원가입 버튼 진입 시 `?type=member|business` 로 사전 선택
	const params = new URLSearchParams(window.location.search);
	const initialType: MemberType =
		params.get('type') === 'business' ? 'business' : 'member';
	const [selected, setSelected] = useState<MemberType>(initialType);

	// 사전 선택된 타입 및 return_client를 RegisterContext 에 즉시 반영 (마운트 시 1회)
	useEffect(() => {
		const returnClient = params.get('return_client') || '';
		const returnUri = params.get('return_uri') || '';
		updateData({ memberType: initialType, initialClientId: returnClient, returnUri });
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	const layoutType: MemberType = selected === 'business' ? 'business' : 'member';
	const nextRoute = selected ? getRegisterRoute(2, selected) : undefined;

	return (
		<RegisterLayout
			currentStep={1}
			nextRoute={nextRoute}
			memberType={layoutType}
			sectionTitle="기본 정보"
		>
			<div className="check-box-wrap" role="radiogroup" aria-label="회원유형 선택">
				<label className="check-box style2">
					<input
						type="radio"
						id="type_member"
						name="type"
						checked={selected === 'member'}
						onChange={(): void => {
							setSelected('member');
							updateData({ memberType: 'member' });
						}}
					/>
					<div className="right-box">
						<figure>
							<img src={IMAGES.MEMBER} alt="개인 회원" />
						</figure>
						<div className="text-box">
							<strong className="tit">개인회원</strong>
							<p className="text">
								개인회원 전환 후 정책 정보를 자유롭게 탐색하고 <br />
								예비창업 지원을 준비해보세요.
							</p>
						</div>
					</div>
				</label>
				<label className="check-box style2">
					<input
						type="radio"
						id="type_business"
						name="type"
						checked={selected === 'business'}
						onChange={(): void => {
							setSelected('business');
							updateData({ memberType: 'business' });
						}}
					/>
					<div className="right-box">
						<figure>
							<img src={IMAGES.BUSINESS} alt="기업 회원" />
						</figure>
						<div className="text-box">
							<strong className="tit">기업회원</strong>
							<p className="text">
								기업회원 전환 후 사업자 정보로 지원사업 신청과 <br />
								관리를 한 번에 해결하세요.
							</p>
						</div>
					</div>
				</label>
			</div>
			<div className="text-align-right">
				<button type="button" className="btn text medium age-14-btn">
					<i className="icon ico-arrow-forward small" aria-hidden="true" />
					<p>14세 미만만 회원가입</p>
				</button>
			</div>
		</RegisterLayout>
	);
}

export default RegisterStep1;
