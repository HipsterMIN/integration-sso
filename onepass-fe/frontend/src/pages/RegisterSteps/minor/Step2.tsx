/**
 * 만14세 미만 회원가입 Step2 — 약관 동의
 *
 * 일반 회원가입 Step2(RegisterStep2)와 구조 동일하나
 * flowContext를 'MINOR_MEMBER_REGISTRATION'으로 전달하여 미성년자 전용 약관 번들을 로드한다.
 *
 * BE Q-IM: flowContext에 따라 법정대리인 개인정보 수집·이용 동의 약관이 포함된 번들 반환.
 * consentEventId는 RegisterContext.guardianConsentEventId 에 저장한다.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import DOMPurify from 'dompurify';
import Modal from 'components/KrdsModal';
import RegisterLayout from 'components/RegisterLayout';
import getTermsBundle from 'api/ext/termsBundle';
import { getConsentToken, submitConsent } from 'api/ext/consent';
import type { Term } from 'types/api/ext/termsBundle';
import { useRegister } from 'providers/Register/RegisterContext';
import { getMinorRegisterRoute } from './routes';

/** displayName에서 "(필수) " / "(선택) " 접두사 제거 */
function stripPrefix(displayName: string): string {
	return displayName.replace(/^\(필수\)\s*/, '').replace(/^\(선택\)\s*/, '');
}

// 미성년자 전용 약관 flowContext
// BE Q-IM 약관 서버가 이 값을 기반으로 법정대리인 동의 약관 포함 번들을 반환
const MINOR_FLOW_CONTEXT = 'MINOR_MEMBER_REGISTRATION';

function RegisterMinorStep2(): JSX.Element {
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

	// 미성년자 전용 약관 번들 + 토큰 병렬 로드
	useEffect(() => {
		let cancelled = false;

		const fetchData = async (): Promise<void> => {
			const [tokenRes, bundleRes] = await Promise.all([
				getConsentToken({
					realm: 'qim',
					clientId: 'sp-smeg',
					flowContext: MINOR_FLOW_CONTEXT,
				}),
				getTermsBundle(),
			]);

			if (cancelled) return;

			if (tokenRes.statusCode === 200 && tokenRes.payload) {
				setConsentToken(tokenRes.payload.data.token);
			}

			if (bundleRes.statusCode === 200 && bundleRes.payload) {
				const bundle = bundleRes.payload;
				const order = [...bundle.requiredOrder, ...bundle.optionalOrder];
				const sorted = order
					.map((code) => bundle.terms.find((t) => t.docCode === code))
					.filter((t): t is Term => !!t);
				setTerms(sorted);

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
			const allRequiredChecked = terms.filter((t) => t.required).every((t) => next[t.docCode]);
			if (allRequiredChecked) setHighlightRequired(false);
			return next;
		});
	}, [terms]);

	const toggleItem = useCallback((docCode: string) => {
		setOpenItems((prev) => ({ ...prev, [docCode]: !prev[docCode] }));
	}, []);

	const handleNext = useCallback(async (): Promise<boolean> => {
		// 필수 약관 전체 동의 검증
		const requiredUnchecked = terms.filter((t) => t.required && !agrees[t.docCode]);
		if (requiredUnchecked.length > 0) {
			setHighlightRequired(true);
			setUncheckedNames(requiredUnchecked.map((t) => stripPrefix(t.displayName)));
			setErrorType('validation');
			setErrorMessage('필수 약관에 모두 동의해 주세요.');
			setErrorModal(true);
			const firstRef = termRefs.current[requiredUnchecked[0].docCode];
			if (firstRef) firstRef.scrollIntoView({ behavior: 'smooth', block: 'center' });
			return false;
		}

		const lines = terms.map((t) => ({
			versionId: t.versionId,
			accepted: !!agrees[t.docCode],
		}));

		let currentToken = consentToken;

		const response = await submitConsent({
			consentToken: currentToken,
			flowContext: MINOR_FLOW_CONTEXT,
			lines,
		});

		if (response.statusCode === 200 && response.payload?.data) {
			// 미성년자 플로우: guardianConsentEventId에 저장
			updateData({ guardianConsentEventId: response.payload.data.eventId });
			return true;
		}

		// 토큰 만료 시 1회 재발급 후 재시도
		const errorBody = 'body' in response ? response.body : '';
		if (response.message?.includes('INVALID_CONSENT_TOKEN') || errorBody?.includes('INVALID_CONSENT_TOKEN')) {
			const tokenRes = await getConsentToken({
				realm: 'qim',
				clientId: 'sp-smeg',
				flowContext: MINOR_FLOW_CONTEXT,
			});
			if (tokenRes.statusCode === 200 && tokenRes.payload) {
				currentToken = tokenRes.payload.data.token;
				setConsentToken(currentToken);

				const retryRes = await submitConsent({
					consentToken: currentToken,
					flowContext: MINOR_FLOW_CONTEXT,
					lines,
				});
				if (retryRes.statusCode === 200 && retryRes.payload?.data) {
					updateData({ guardianConsentEventId: retryRes.payload.data.eventId });
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
				prevRoute={getMinorRegisterRoute(1)}
				nextRoute={getMinorRegisterRoute(3)}
				memberType="member"
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
			prevRoute={getMinorRegisterRoute(1)}
			nextRoute={getMinorRegisterRoute(3)}
			memberType="member"
			title="약관 동의"
			onNext={handleNext}
		>
			{/* 미성년자 플로우 안내 배너 */}
			<div
				className="text-info-wrap"
				style={{
					background: '#fff3cd',
					border: '1px solid #ffc107',
					borderRadius: '8px',
					padding: '12px 16px',
					marginBottom: '16px',
				}}
				role="note"
				aria-label="미성년자 약관 안내"
			>
				<p style={{ color: '#856404', fontSize: '14px' }}>
					<strong>만 14세 미만</strong> 회원가입을 위한 약관입니다. 법정대리인(친권자 또는 후견인)의
					동의가 포함된 약관에 동의해 주세요.
				</p>
			</div>

			<div className="all-agree-wrap" role="group" aria-label="약관 동의">
				<div className="all-box">
					<label className="check-box style1">
						<input
							type="checkbox"
							id="minor_agree_all"
							name="minor_agree_all"
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
						const detailId = `minor_${term.docCode}_detail`;
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
											id={`minor_${term.docCode}`}
											name={`minor_${term.docCode}`}
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
											<strong style={isUncheckedRequired ? { color: '#e74c3c' } : undefined}>
												{label}
											</strong>
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
									aria-labelledby={`minor_${term.docCode}`}
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
				id="modal_minor_consent_error"
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

export default RegisterMinorStep2;
