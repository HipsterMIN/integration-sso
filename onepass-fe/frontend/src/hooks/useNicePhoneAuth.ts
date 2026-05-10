/**
 * NICE 휴대폰 본인인증 커스텀 훅
 *
 * 흐름:
 * 1. 백엔드에 인증 URL 요청 → authUrl, requestNo 수신
 * 2. window.open으로 NICE 표준창 오픈
 * 3. 인증 완료 후 nice-callback.html로 리다이렉트 → postMessage로 web_transaction_id 전달
 * 4. 백엔드에 결과 조회 요청 → 복호화된 인증 결과 수신
 */
import beInstance from 'api/beInstance';
import { useCallback, useEffect, useRef, useState } from 'react';

export interface NicePhoneAuthResult {
	resultCode: string;
	resultMsg: string;
	ci?: string;
	name?: string;
	birthdate?: string;
	phone?: string;
}

interface NiceAuthUrlResponse {
	resultCode: string;
	resultMsg: string;
	authUrl?: string;
	requestNo?: string;
}

interface NiceAuthResultData {
	name?: string;
	birthdate?: string;
	gender?: string;
	nationalInfo?: string;
	ci?: string;
	di?: string;
	mobileCo?: string;
	mobileNo?: string;
}

interface NiceAuthResultResponse {
	resultCode: string;
	resultMsg: string;
	resultData?: NiceAuthResultData;
}

interface UseNicePhoneAuthReturn {
	busy: boolean;
	startAuth: () => Promise<void>;
}

function useNicePhoneAuth(
	onSuccess: (result: NicePhoneAuthResult) => void,
	onError: (message: string) => void,
): UseNicePhoneAuthReturn {
	const [busy, setBusy] = useState(false);

	const popupRef = useRef<Window | null>(null);
	const requestNoRef = useRef<string | undefined>(undefined);
	const callbacksRef = useRef({ onSuccess, onError });

	useEffect(() => {
		callbacksRef.current = { onSuccess, onError };
	}, [onSuccess, onError]);

	const cleanup = useCallback((): void => {
		popupRef.current = null;
		requestNoRef.current = undefined;
		setBusy(false);
	}, []);

	// 팝업 닫힘 감지
	useEffect(() => {
		if (!busy) return;
		const timer = setInterval(() => {
			if (popupRef.current && popupRef.current.closed) {
				cleanup();
			}
		}, 500);
		return (): void => clearInterval(timer);
	}, [busy, cleanup]);

	const fetchAuthResult = useCallback(
		async (webTransactionId: string, requestNo?: string): Promise<void> => {
			try {
				const res = await beInstance.post('/api/v1/auth/nice/phone/result', {
					web_transaction_id: webTransactionId,
					request_no: requestNo,
				});
				const result = res.data as NiceAuthResultResponse;

				if (result.resultCode === '2000' && result.resultData) {
					callbacksRef.current.onSuccess({
						resultCode: result.resultCode,
						resultMsg: result.resultMsg,
						ci: result.resultData.ci,
						name: result.resultData.name,
						birthdate: result.resultData.birthdate,
						phone: result.resultData.mobileNo,
					});
				} else {
					callbacksRef.current.onSuccess({
						resultCode: result.resultCode,
						resultMsg: result.resultMsg,
					});
				}
			} catch (e) {
				const msg = e instanceof Error ? e.message : String(e);
				callbacksRef.current.onError(msg);
			} finally {
				if (popupRef.current && !popupRef.current.closed) popupRef.current.close();
				cleanup();
			}
		},
		[cleanup],
	);

	// postMessage 수신 핸들러
	useEffect(() => {
		if (!busy) return;

		const handleMessage = async (event: MessageEvent): Promise<void> => {
			if (event.origin !== window.location.origin) return;

			let data: { type?: string; web_transaction_id?: string; request_no?: string };
			try {
				data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
			} catch {
				return;
			}

			if (data.type !== 'nice-phone-auth' || !data.web_transaction_id) return;

			const requestNo = data.request_no || requestNoRef.current;
			await fetchAuthResult(data.web_transaction_id, requestNo);
		};

		window.addEventListener('message', handleMessage);
		return (): void => window.removeEventListener('message', handleMessage);
	}, [busy, fetchAuthResult]);

	const startAuth = useCallback(async (): Promise<void> => {
		if (busy) return;
		setBusy(true);

		try {
			const returnUrl = `${window.location.origin}/nice-callback.html`;
			const res = await beInstance.get('/api/v1/auth/nice/phone/url', {
				params: { returnUrl },
			});
			const data = res.data as NiceAuthUrlResponse;

			if (data.resultCode !== '2000' || !data.authUrl) {
				callbacksRef.current.onError(`NICE 인증 URL 발급 실패: ${data.resultMsg}`);
				setBusy(false);
				return;
			}

			requestNoRef.current = data.requestNo;

			const features =
				'toolbar=no,scrollbars=no,location=no,resizable=no,status=no,menubar=no,width=500,height=700';
			const popup = window.open(data.authUrl, 'niceAuth', features);
			if (!popup) {
				callbacksRef.current.onError('팝업이 차단되었습니다. 팝업 허용 후 다시 시도해주세요.');
				setBusy(false);
				return;
			}
			popupRef.current = popup;
		} catch (e) {
			const msg = e instanceof Error ? e.message : String(e);
			callbacksRef.current.onError(msg);
			cleanup();
		}
	}, [busy, cleanup, fetchAuthResult]);

	return { busy, startAuth };
}

export default useNicePhoneAuth;
