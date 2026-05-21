import { useCallback, useEffect, useRef, useState } from 'react';
import DOMPurify from 'dompurify';
import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import type { MemberType } from 'components/StepIndicator';
import getTermsBundle from 'api/ext/termsBundle';
import { getConsentToken, submitConsent } from 'api/ext/consent';
import type { Term } from 'types/api/ext/termsBundle';
import { useRegister } from 'providers/Register/RegisterContext';

import { getRegisterRoute } from '../routes';

interface Step2Props {
	memberType?: MemberType;
}

/** displayName에서 "(필수) " / "(선택) " 접두사 제거 */
function stripPrefix(displayName: string): string {
	return displayName.replace(/^\(필수\)\s*/, '').replace(/^\(선택\)\s*/, '');
}

function RegisterStep2({ memberType = 'member' }: Step2Props): JSX.Element {
	const { updateData } = useRegister();
	const [terms, setTerms] = useState<Term[]>([]);
	const [consentToken, setConsentToken] = useState('');
	const [agrees, setAgrees] = useState<Record<string, boolean>>({});
	const [openItems, setOpenItems] = useState<Record<string, boolean>>({});
	const [loading, setLoading] = useState(true);
	const [errorModal, setErrorModal] = useState(false);
	const [errorMessage, setErrorMessage] = useState('');
	const [errorType, setErrorType] = useState<'validation' | 'server'>('validation');
	const [uncheckedNames, setUncheckedNames] = useState<string[]>([]);
	const [highlightRequired, setHighlightRequired] = useState(false);
	const termRefs = useRef<Record<string, HTMLLIElement | null>>({});

	// 페이지 진입 시 토큰 발급 + 약관 번들 조회 (병렬)
	useEffect(() => {
		let cancelled = false;

		const fetchData = async (): Promise<void> => {
			const [tokenRes, bundleRes] = await Promise.all([
				getConsentToken({ realm: 'qim', clientId: 'sp-smeg', flowContext: 'MEMBER_CONVERSION' }),
				getTermsBundle(),
			]);

			if (cancelled) return;

			// 토큰 저장
			if (tokenRes.statusCode === 200 && tokenRes.payload) {
				setConsentToken(tokenRes.payload.data.token);
			}

			// 약관 목록 정렬 (requiredOrder + optionalOrder 순서)
			if (bundleRes.statusCode === 200 && bundleRes.payload) {
				const bundle = bundleRes.payload;
				const order = [...bundle.requiredOrder, ...bundle.optionalOrder];
				const sorted = order
					.map((code) => bundle.terms.find((t) => t.docCode === code))
					.filter((t): t is Term => !!t);
				setTerms(sorted);

				// 초기 동의 상태: 모두 false
				const initial: Record<string, boolean> = {};
				sorted.forEach((t) => { initial[t.docCode] = false; });
				setAgrees(initial);
			}

			setLoading(false);
		};

		fetchData();
		return (): void => { cancelled = true; };
	}, []);

	const allChecked = terms.length > 0 && terms.every((t) => agrees[t.docCode]);

	const handleAllAgree = useCallback(() => {
		setAgrees((prev) => {
			const next: Record<string, boolean> = {};
			const value = !Object.values(prev).every(Boolean);
			Object.keys(prev).forEach((key) => { next[key] = value; });
			return next;
		});
		setHighlightRequired(false);
	}, []);

	const handleAgree = useCallback((docCode: string) => {
		setAgrees((prev) => {
			const next = { ...prev, [docCode]: !prev[docCode] };
			// 필수 항목이 모두 체크되면 하이라이트 해제
			const allRequiredChecked = terms.filter((t) => t.required).every((t) => next[t.docCode]);
			if (allRequiredChecked) {
				setHighlightRequired(false);
			}
			return next;
		});
	}, [terms]);

	const toggleItem = useCallback((docCode: string) => {
		setOpenItems((prev) => ({ ...prev, [docCode]: !prev[docCode] }));
	}, []);

	// "다음으로" 클릭 시 동의 제출
	const handleNext = useCallback(async (): Promise<boolean> => {
		// 프론트 검증: 필수 약관 전체 동의 확인
		const requiredUnchecked = terms.filter((t) => t.required && !agrees[t.docCode]);
		if (requiredUnchecked.length > 0) {
			setHighlightRequired(true);
			setUncheckedNames(requiredUnchecked.map((t) => stripPrefix(t.displayName)));
			setErrorType('validation');
			setErrorMessage('필수 약관에 모두 동의해 주세요.');
			setErrorModal(true);

			// 첫 번째 미동의 항목으로 스크롤
			const firstRef = termRefs.current[requiredUnchecked[0].docCode];
			if (firstRef) {
				firstRef.scrollIntoView({ behavior: 'smooth', block: 'center' });
			}
			return false;
		}

		// lines 배열 구성
		const lines = terms.map((t) => ({
			versionId: t.versionId,
			accepted: !!agrees[t.docCode],
		}));

		let currentToken = consentToken;

		// 동의 제출
		const response = await submitConsent({
			consentToken: currentToken,
			flowContext: 'MEMBER_CONVERSION',
			lines,
		});

		if (response.statusCode === 200 && response.payload?.data) {
			updateData({ consentEventId: response.payload.data.eventId });
			return true;
		}

		// 토큰 만료/재사용 시 재발급 후 1회 재시도
		const errorBody = 'body' in response ? response.body : '';
		if (response.message?.includes('INVALID_CONSENT_TOKEN') || errorBody?.includes('INVALID_CONSENT_TOKEN')) {
			const tokenRes = await getConsentToken({
				realm: 'qim',
				clientId: 'sp-smeg',
				flowContext: 'MEMBER_CONVERSION',
			});

			if (tokenRes.statusCode === 200 && tokenRes.payload) {
				currentToken = tokenRes.payload.data.token;
				setConsentToken(currentToken);

				const retryRes = await submitConsent({
					consentToken: currentToken,
					flowContext: 'MEMBER_CONVERSION',
					lines,
				});

				if (retryRes.statusCode === 200 && retryRes.payload?.data) {
					updateData({ consentEventId: retryRes.payload.data.eventId });
					return true;
				}
			}
		}

		setErrorType('server');
		setUncheckedNames([]);
		setErrorMessage(response.message || '약관 동의 처리 중 오류가 발생하였습니다.');
		setErrorModal(true);
		return false;
	}, [terms, agrees, consentToken, updateData]);

	if (loading) {
		return (
			<RegisterLayout
				currentStep={2}
				prevRoute={getRegisterRoute(1, memberType)}
				nextRoute={getRegisterRoute(3, memberType)}
				memberType={memberType}
			>
				<div className="all-agree-wrap" role="status" aria-label="약관 로딩 중">
					<p>약관 정보를 불러오고 있습니다...</p>
				</div>
			</RegisterLayout>
		);
	}

	return (
		<RegisterLayout
			currentStep={2}
			prevRoute={getRegisterRoute(1, memberType)}
			nextRoute={getRegisterRoute(3, memberType)}
			memberType={memberType}
			onNext={handleNext}
		>
			<div className="all-agree-wrap" role="group" aria-label="약관 동의">
				<div className="all-box">
					<label className="check-box style1">
						<input
							type="checkbox"
							id="agree_all"
							name="agree_all"
							checked={allChecked}
							onChange={handleAllAgree}
						/>
						<small>
							<strong>모두 동의합니다.</strong>
						</small>
					</label>
				</div>
				<ul className="agree-box">
					{terms.map((term) => {
						const isOpen = !!openItems[term.docCode];
						const detailId = `${term.docCode}_detail`;
						const label = stripPrefix(term.displayName);
						const isUncheckedRequired = highlightRequired && term.required && !agrees[term.docCode];

						return (
							<li
								key={term.docCode}
								className={isOpen ? 'open' : ''}
								ref={(el): void => { termRefs.current[term.docCode] = el; }}
							>
								<div className="agree-title-box">
									<label className="check-box style1">
										<input
											type="checkbox"
											id={term.docCode}
											name={term.docCode}
											checked={!!agrees[term.docCode]}
											onChange={(): void => handleAgree(term.docCode)}
										/>
										<small>
											<span
												className={term.required ? 'point' : 'select'}
												style={isUncheckedRequired ? { color: '#e74c3c', fontWeight: 'bold' } : undefined}
											>
												{term.required ? '(필수)' : '(선택)'}
											</span>
											<strong style={isUncheckedRequired ? { color: '#e74c3c' } : undefined}>{label}</strong>
										</small>
									</label>
									<button
										type="button"
										className="arrow-btn btn text medium"
										onClick={(): void => toggleItem(term.docCode)}
										aria-expanded={isOpen}
										aria-controls={detailId}
										aria-label={`${label} 상세내용 ${isOpen ? '닫기' : '열기'}`}
									>
										<i className="icon arrow-top" aria-hidden="true" />
										<span className="hidden">열고 닫기</span>
									</button>
								</div>
								<div
									className="detail-box"
									id={detailId}
									role="region"
									aria-labelledby={term.docCode}
								>
									<div
										dangerouslySetInnerHTML={{
											__html: DOMPurify.sanitize(term.content.body),
										}}
									/>
								</div>
							</li>
						);
					})}
				</ul>
			</div>

			<Modal
				id="modal_consent_error"
				isOpen={errorModal}
				onClose={(): void => setErrorModal(false)}
				topText={errorType === 'validation' ? '알림' : '약관 동의 오류'}
				title={errorMessage}
				size="small"
				buttons={[
					{ label: '확인', variant: 'primary', onClick: (): void => setErrorModal(false) },
				]}
			>
				{errorType === 'validation' && uncheckedNames.length > 0 ? (
					<div className="text">
						<p>아래 필수 약관에 동의하지 않았습니다.</p>
						<ul style={{ marginTop: '8px', paddingLeft: '20px' }}>
							{uncheckedNames.map((name) => (
								<li key={name} style={{ fontWeight: 700, listStyleType: 'disc' }}>{name}</li>
							))}
						</ul>
					</div>
				) : (
					<p className="text">
						약관 동의 처리 중 문제가 발생하였습니다.<br />
						잠시 후 다시 시도해 주세요.
					</p>
				)}
			</Modal>
		</RegisterLayout>
	);
}

export default RegisterStep2;
