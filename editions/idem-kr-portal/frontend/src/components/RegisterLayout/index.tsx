import { FormEvent, ReactNode, useEffect, useState } from 'react';
import cx from 'classnames';
import history from 'lib/history';
import { getSteps } from 'components/StepIndicator';
import type { MemberType } from 'components/StepIndicator';
import IMAGES from 'constants/images';

interface RegisterLayoutProps {
	currentStep: number;
	children: ReactNode;
	prevRoute?: string;
	nextRoute?: string;
	skipRoute?: string;
	prevLabel?: string;
	nextLabel?: string;
	skipLabel?: string;
	memberType?: MemberType;
	title?: string;
	sectionTitle?: string;
	/** true 이면 children 을 .white-wrap 으로 자동 감싸지 않음 — 페이지가 자체적으로 다중 .white-wrap 을 렌더할 때 사용 */
	noWrap?: boolean;
	onNext?: () => Promise<boolean> | boolean;
	onSkip?: () => void;
}

function useSubPageAnimation(): void {
	useEffect(() => {
		const sectionOn = (): void => {
			const offset = -window.innerHeight * 0.9;
			const scrollTop = window.scrollY;
			const sections = document.querySelectorAll<HTMLElement>(
				'.sub .form-container > *',
			);

			sections.forEach((section) => {
				const top = section.getBoundingClientRect().top + window.scrollY;
				if (scrollTop > top + offset) {
					section.classList.add('show');
				}
			});
		};

		const applyDelay = (
			parentSelector: string,
			delayDiv: number,
			delayBase: number,
		): void => {
			document.querySelectorAll<HTMLElement>(parentSelector).forEach((parent) => {
				Array.from(parent.children).forEach((child, index) => {
					(child as HTMLElement).style.animationDelay = `${(index + 1) / delayDiv + delayBase}s`;
				});
			});
		};

		applyDelay('.sub .form-container', 10, 0.1);
		applyDelay('.sub .form-container .white-wrap', 10, 0.1);
		applyDelay('.check-box-wrap', 10, 0.1);

		document
			.querySelectorAll<HTMLElement>('.sub .form-container > *')
			.forEach((section) => section.classList.add('show'));

		window.addEventListener('scroll', sectionOn);
		return (): void => window.removeEventListener('scroll', sectionOn);
	}, []);
}

function RegisterLayout({
	currentStep,
	children,
	prevRoute,
	nextRoute,
	skipRoute,
	prevLabel = '이전',
	nextLabel = '다음',
	skipLabel = '건너뛰기',
	memberType = 'member',
	title,
	sectionTitle,
	noWrap = false,
	onNext,
	onSkip,
}: RegisterLayoutProps): JSX.Element {
	const steps = getSteps(memberType, 'register');
	const stepName = title ?? steps[currentStep - 1];
	const isBusiness = memberType === 'business';
	const totalSteps = steps.length;
	const progressWidth = `calc(100% / ${totalSteps} * ${currentStep})`;
	const [isLoading, setIsLoading] = useState(false);

	const handleNext = async (): Promise<void> => {
		if (!nextRoute) return;

		if (onNext) {
			setIsLoading(true);
			try {
				const result = await onNext();
				if (result) {
					history.push(nextRoute);
				}
			} finally {
				setIsLoading(false);
			}
		} else {
			history.push(nextRoute);
		}
	};

	useSubPageAnimation();

	return (
		<main
			id="main-content"
			className={cx('container', 'sub', 'register', `step${currentStep}`, {
				business: isBusiness,
				member: !isBusiness,
			})}
		>
			<div className="sub-body inner">
				<div className="page-title-wrap">
					<div className="page-title-text-box">
						<h2 className="page-title">중기원패스 회원 가입</h2>
						<p className="page-text">
							하나의 아이디로 중소벤처기업부 유관기관의 서비스를 모두 이용해보세요!
						</p>
					</div>
					<figure className="img-box">
						<img
							src={IMAGES.RENEWAL_SUB_CONVERSION_TITLE_IMG}
							alt=""
							aria-hidden="true"
						/>
					</figure>
				</div>
				<form
					className="form-container"
					onSubmit={(e: FormEvent): void => e.preventDefault()}
					aria-label={`회원가입 ${currentStep}단계: ${stepName}`}
				>
					<div className="cont-title-box">
						<h3 className="tit">{stepName}</h3>
						<div
							className="step-wrap"
							role="group"
							aria-label={`${currentStep}단계 / ${totalSteps}단계`}
						>
							<div className="step-line">
								<div className="now-line" style={{ width: progressWidth }}>
									<span className="text">{currentStep}단계</span>
								</div>
							</div>
							<p className="step-text" aria-hidden="true">
								<strong className="now">{currentStep}</strong>
								<strong>/</strong>
								<span className="total">{totalSteps}</span>
							</p>
						</div>
					</div>
					{noWrap ? (
						children
					) : (
						<div className="white-wrap">
							{sectionTitle && <h3 className="h3-title">{sectionTitle}</h3>}
							{children}
						</div>
					)}
					{(prevRoute || skipRoute || nextRoute) && (
						<div className="btn-box" role="group" aria-label="페이지 이동">
							{prevRoute && (
								<button
									type="button"
									className="btn white prev"
									onClick={(): void => history.push(prevRoute)}
								>
									<span>{prevLabel}</span>
									<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
								</button>
							)}
							{skipRoute && (
								<button
									type="button"
									className="btn white"
									onClick={(): void => {
										onSkip?.();
										history.push(skipRoute);
									}}
								>
									<span>{skipLabel}</span>
								</button>
							)}
							{nextRoute && (
								<button
									type="button"
									className="btn point"
									onClick={handleNext}
									disabled={isLoading}
									aria-busy={isLoading}
								>
									<span>{isLoading ? '처리중...' : nextLabel}</span>
									<i className="icon ico-arrow-forward-ios small" aria-hidden="true" />
								</button>
							)}
						</div>
					)}
				</form>
			</div>
		</main>
	);
}

export default RegisterLayout;
