import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';
import { useRegister } from 'providers/Register/RegisterContext';
import { useState } from 'react';

import { getRegisterRoute } from '../routes';
import AccountForm from './components/AccountForm';
import MemberInfoForm from './components/MemberInfoForm';
import NotificationSettings from './components/NotificationSettings';

interface Step5Props {
	memberType?: MemberType;
	currentStep?: number;
}

function RegisterStep5({
	memberType = 'member',
	currentStep = 5,
}: Step5Props): JSX.Element {
	const { data, updateData } = useRegister();
	const isBusiness = memberType === 'business';
	const [showAlert, setShowAlert] = useState(false);

	const toggleNotification = (key: string): void => {
		const updated = { ...data.notifications, [key]: !data.notifications[key] };
		updateData({ notifications: updated });
	};

	const handleNext = (): boolean => {
		if (isBusiness) {
			if (
				!data.bzmnNm ||
				!data.rprsvNm ||
				!data.brno ||
				data.brno.length !== 10 ||
				!data.email ||
				!data.emailDomain
			) {
				setShowAlert(true);
				return false;
			}
			return true;
		}

		if (!data.loginId || !data.password) {
			setShowAlert(true);
			return false;
		}
		return true;
	};

	return (
		<>
			<RegisterLayout
				currentStep={currentStep}
				prevRoute={getRegisterRoute(currentStep - 1, memberType)}
				nextRoute={getRegisterRoute(currentStep + 1, memberType)}
				memberType={memberType}
				noWrap
				onNext={handleNext}
			>
				<div className="white-wrap">
					<h3 className="h3-title">기본 정보</h3>
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
						<AccountForm isBusiness />
					) : (
						<div className="form-wrap">
							<AccountForm isBusiness={false} flat />
							<MemberInfoForm isBusiness={false} flat />
						</div>
					)}
				</div>
				{/* <div className="white-wrap">
					<h3 className="h3-title">알림 수신</h3>
					<div className="text-info-wrap point">
						<ul className="text-list-wrap check" aria-label="안내 사항">
							<li>
								<p>
									중기원패스의 알림은 이메일과 SNS 또는 알림톡으로 발송되며, 정책자금
									상담, Q&amp;A, 민원 등의 처리현황 정보가 발송됩니다.
								</p>
							</li>
						</ul>
						<figure className="img">
							<img
								src={IMAGES.RENEWAL_TEXT_LIST_IMG_RECEIVE_NOTIFICATIONS}
								alt=""
								aria-hidden="true"
							/>
						</figure>
					</div>
					<NotificationSettings
						notifications={data.notifications}
						onToggle={toggleNotification}
					/>
				</div> */}
			</RegisterLayout>
			<Modal
				id="validation-alert"
				isOpen={showAlert}
				onClose={(): void => setShowAlert(false)}
				topText="알림"
				title="필수항목을 입력해주세요."
				size="small"
				buttons={[
					{
						label: '확인',
						variant: 'primary',
						onClick: (): void => setShowAlert(false),
					},
				]}
			>
				<p>(필수) 항목을 모두 입력한 후 다음으로 진행해 주세요.</p>
			</Modal>
		</>
	);
}

export default RegisterStep5;
