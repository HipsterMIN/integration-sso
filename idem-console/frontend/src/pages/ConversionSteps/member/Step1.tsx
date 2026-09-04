import conversionInit from 'api/conversion/init';
import ConversionLayout from 'components/ConversionLayout';
import Modal from 'components/KrdsModal';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import ROUTES from 'constants/routes';
import history from 'lib/history';
import { useConversion } from 'providers/Conversion/ConversionContext';
import { useEffect, useState } from 'react';
import { getConversionRoute } from '../routes';

function ConversionStep1(): JSX.Element {
	const { updateData } = useConversion();
	const [selected, setSelected] = useState<MemberType>('member');
	const [missingParams, setMissingParams] = useState(false);
	// userType: 'ENT' = 기업만 선택 가능 / 'IND' = 개인만 선택 가능 / null = 둘 다 선택 가능
	const [userType, setUserType] = useState<'ENT' | 'IND' | null>(null);

	// URL에서 파라미터를 읽어 Context에 저장
	// 신규 흐름: signed_request JWT → conversionInit API 호출
	// 레거시 흐름: redirect_uri + mbrId + return_client URL 파라미터 직접 사용
	useEffect(() => {
		const params = new URLSearchParams(window.location.search);
		const signedRequest = params.get('signed_request');

		if (signedRequest) {
			// 신규 흐름: signed_request JWT 처리
			(async (): Promise<void> => {
				const result = await conversionInit({ signed_request: signedRequest });
				if (result.statusCode === 200 && result.payload) {
					const { conversion_session_id, user_type } = result.payload;
					updateData({ conversionSessionId: conversion_session_id });
					// user_type: INDIVIDUAL → IND, ENTERPRISE → ENT
					const ut: 'ENT' | 'IND' | null =
						user_type === 'ENTERPRISE' || user_type === 'ENT'
							? 'ENT'
							: user_type === 'INDIVIDUAL' || user_type === 'IND'
								? 'IND'
								: null;
					setUserType(ut);
					if (ut === 'ENT') {
						setSelected('business');
						updateData({ memberType: 'business' });
					} else if (ut === 'IND') {
						setSelected('member');
						updateData({ memberType: 'member' });
					}
				} else {
					// conversionInit 실패 → 잘못된 접근 처리
					setMissingParams(true);
				}
			})();
		} else {
			// 레거시 흐름: URL 파라미터 직접 처리
			const redirectUri = params.get('redirect_uri');
			const mbrId = params.get('mbrId');
			const clientId = params.get('return_client') || '';
			const rawUserType = params.get('userType');
			const ut = rawUserType === 'ENT' || rawUserType === 'IND' ? rawUserType : null;
			setUserType(ut);

			if (ut === 'ENT') {
				setSelected('business');
				updateData({ memberType: 'business' });
			} else if (ut === 'IND') {
				setSelected('member');
				updateData({ memberType: 'member' });
			}

			if (redirectUri && mbrId && clientId) {
				updateData({ redirectUri, mbrId, initialClientId: clientId });
			} else {
				setMissingParams(true);
			}
		}
	}, [updateData]);

	const memberDisabled = userType === 'ENT';
	const businessDisabled = userType === 'IND';

	const handleModalClose = (): void => {
		setMissingParams(false);
		window.history.back();
	};

	const layoutType: MemberType = selected === 'business' ? 'business' : 'member';
	const nextRoute = missingParams ? undefined : (selected ? getConversionRoute(2, selected) : undefined);

	return (
		<>
		<ConversionLayout
			currentStep={1}
			nextRoute={nextRoute}
			memberType={layoutType}
		>
			<div
				className="check-box-wrap"
				role="radiogroup"
				aria-label="회원유형 선택"
			>
				<label className={`check-box style2${memberDisabled ? ' disabled' : ''}`}>
					<input
						type="radio"
						id="type_member"
						name="type"
						checked={selected === 'member'}
						onChange={(): void => { setSelected('member'); updateData({ memberType: 'member' }); }}
						disabled={memberDisabled}
					/>
					<div className="right-box">
						<figure>
							<img
								src={IMAGES.MEMBER}
								alt="개인 회원"
							/>
						</figure>
						<div className="text-box">
							<strong className="tit">개인회원</strong>
							<p className="text">
								정책 정보를 자유롭게 탐색하고 <br />
								예비창업 지원을 준비하세요.
							</p>
						</div>
					</div>
				</label>
				<label className={`check-box style2${businessDisabled ? ' disabled' : ''}`}>
					<input
						type="radio"
						id="type_business"
						name="type"
						checked={selected === 'business'}
						onChange={(): void => { setSelected('business'); updateData({ memberType: 'business' }); }}
						disabled={businessDisabled}
					/>
					<div className="right-box">
						<figure>
							<img
								src={IMAGES.BUSINESS}
								alt="기업 회원"
							/>
						</figure>
						<div className="text-box">
							<strong className="tit">기업회원</strong>
							<p className="text">
								사업자 정보로 지원사업 신청과 관리를 한 번에 해결하세요.
							</p>
						</div>
					</div>
				</label>
			</div>
			<div className="text-align-right">
				<button
					type="button"
					className="btn text medium age-14-btn"
					onClick={(): void => history.push(ROUTES.REGISTER_MINOR_STEP1)}
				>
					<i className="icon ico-arrow-forward small" aria-hidden="true" />
					<p>14세 미만만 회원가입</p>
				</button>
			</div>
		</ConversionLayout>

		<Modal
			id="modal_missing_params"
			isOpen={missingParams}
			onClose={handleModalClose}
			topText="접근 오류"
			title="잘못된 접근입니다."
			size="small"
			buttons={[{ label: '확인', variant: 'primary', onClick: handleModalClose }]}
		>
			<p className="text">
				전환 페이지는 대상 시스템을 통해 접근해주세요.
			</p>
		</Modal>
		</>
	);
}

export default ConversionStep1;
