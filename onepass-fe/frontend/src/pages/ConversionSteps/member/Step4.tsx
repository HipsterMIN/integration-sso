import ConversionLayout from 'components/ConversionLayout';
import Modal from 'components/KrdsModal';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useConversion } from 'providers/Conversion/ConversionContext';
import { useCallback, useState } from 'react';

import { getConversionRoute } from '../routes';
import AccountForm from './components/AccountForm';
import MemberInfoForm from './components/MemberInfoForm';

interface Step4Props {
	memberType?: MemberType;
	currentStep?: number;
}

/**
 * 정보입력 단계 (이전엔 5단계였으나 단계 swap 으로 4단계로 이동).
 * 폼 검증만 수행하고 데이터는 context.updateData 로 저장된 채로 다음 단계(유관기관)로 진행.
 * provisionUser/provisionEnterprise 호출은 새 5단계(유관기관 + 가입 처리) 에서 수행.
 */
function ConversionStep4({
	memberType = 'member',
	currentStep = 4,
}: Step4Props): JSX.Element {
	const { data } = useConversion();
	const isBusiness = memberType === 'business';
	const [showAlert, setShowAlert] = useState(false);
	const [alertMessage, setAlertMessage] = useState('');
	const [verificationStatus, setVerificationStatus] = useState({
		validateOk: false,
		duplicateOk: false,
		loginIdDupOk: false,
		formValid: false,
	});

	const handleVerificationChange = useCallback(
		(status: {
			validateOk: boolean;
			duplicateOk: boolean;
			loginIdDupOk: boolean;
			formValid: boolean;
		}) => {
			setVerificationStatus(status);
		},
		[],
	);

	const handleNext = async (): Promise<boolean> => {
		if (isBusiness) {
			if (
				!data.bzmnNm ||
				!data.rprsvNm ||
				!data.brno ||
				data.brno.length !== 10 ||
				!data.email ||
				!data.emailDomain ||
				!data.loginId ||
				!data.password
			) {
				setAlertMessage('(필수) 항목을 모두 입력한 후 다음으로 진행해 주세요.');
				setShowAlert(true);
				return false;
			}
			if (!verificationStatus.validateOk) {
				setAlertMessage('국세청 진위확인을 완료해 주세요.');
				setShowAlert(true);
				return false;
			}
			if (!verificationStatus.duplicateOk) {
				setAlertMessage('사업자등록번호 중복확인을 완료해 주세요.');
				setShowAlert(true);
				return false;
			}
			if (!verificationStatus.loginIdDupOk) {
				setAlertMessage('아이디 중복확인을 완료해 주세요.');
				setShowAlert(true);
				return false;
			}
			return true;
		}

		// --- 개인회원 ---
		if (!data.loginId || !data.password) {
			setAlertMessage('(필수) 항목을 모두 입력한 후 다음으로 진행해 주세요.');
			setShowAlert(true);
			return false;
		}
		if (!verificationStatus.formValid) {
			setAlertMessage('아이디 또는 비밀번호 형식을 확인해 주세요.');
			setShowAlert(true);
			return false;
		}
		if (!data.ciToken) {
			setAlertMessage(
				'본인인증이 완료되지 않았습니다. 이전 단계를 확인해 주세요.',
			);
			setShowAlert(true);
			return false;
		}
		if (!verificationStatus.loginIdDupOk) {
			setAlertMessage('아이디 중복확인을 완료해 주세요.');
			setShowAlert(true);
			return false;
		}
		return true;
	};

	return (
		<>
			<ConversionLayout
				currentStep={currentStep}
				prevRoute={getConversionRoute(currentStep - 1, memberType)}
				nextRoute={getConversionRoute(currentStep + 1, memberType)}
				memberType={memberType}
				noWrap
				onNext={handleNext}
			>
				<div className="white-wrap">
					<h3 className="h3-title">{isBusiness ? '기본 정보' : '기본/회원 정보'}</h3>
					<div className="text-info-wrap point">
						<ul className="text-list-wrap check" aria-label="안내 사항">
							<li>
								<p>
									회원정보는 정책지원 및 맞춤형 서비스를 제공하는데 사용되므로 정확한
									정보를 입력해 주세요.
								</p>
							</li>
							<li>
								<p>
									<span className="essential">필수</span>항목은 반드시 기입해 주시기
									바랍니다.
								</p>
							</li>
						</ul>
						<figure className="img">
							<img src={IMAGES.RENEWAL_TEXT_LIST_IMG} alt="" aria-hidden="true" />
						</figure>
					</div>
					{isBusiness ? (
						<AccountForm
							isBusiness
							isConversion
							onVerificationChange={handleVerificationChange}
						/>
					) : (
						<div className="form-wrap">
							<AccountForm
								isBusiness={false}
								flat
								onVerificationChange={handleVerificationChange}
							/>
							<MemberInfoForm isBusiness={false} flat />
						</div>
					)}
				</div>
			</ConversionLayout>
			<Modal
				id="validation-alert"
				isOpen={showAlert}
				onClose={(): void => setShowAlert(false)}
				topText="알림"
				title="입력 정보를 확인해 주세요."
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setShowAlert(false),
					},
				]}
			>
				<p>{alertMessage}</p>
			</Modal>
		</>
	);
}

export default ConversionStep4;
