/**
 * 개인 간편인증 (OACX EasySign) 커스텀 훅
 *
 * 흐름:
 * 1. access-info API 호출 → accKey/accToken 발급
 * 2. EasySign 팝업 오픈 → postMessage로 accKey/accToken 전달
 * 3. 인증 완료 시 easysign API 호출 → 결과 콜백
 */
import idoInstance from 'api/idoInstance';
import { useCallback, useEffect, useRef, useState } from 'react';

const EASYSIGN_URL = process.env.EASYSIGN_URL || '';
const EASYSIGN_ORIGIN = process.env.EASYSIGN_ORIGIN || '';

export interface EasysignResult {
	resultCode: string;
	resultMsg: string;
	ci?: string;
	name?: string;
	birthday?: string;
	phone?: string;
}

interface AccessInfoResponse {
	resultCode: string;
	resultMsg: string;
	fn: string | null;
	accKey: string | null;
	accToken: string | null;
}

interface UsePersonalEasyAuthReturn {
	busy: boolean;
	startAuth: () => Promise<void>;
}

function usePersonalEasyAuth(
	onSuccess: (result: EasysignResult) => void,
	onError: (message: string) => void,
): UsePersonalEasyAuthReturn {
	const [busy, setBusy] = useState(false);

	const popupRef = useRef<Window | null>(null);
	const tokenRef = useRef<{ accKey: string; accToken: string } | null>(null);
	const initSentRef = useRef(false);
	const initRequestedRef = useRef(false);
	const callbacksRef = useRef({ onSuccess, onError });

	useEffect(() => {
		callbacksRef.current = { onSuccess, onError };
	}, [onSuccess, onError]);

	const sendTokenToPopup = useCallback((): void => {
		if (initSentRef.current || !popupRef.current || !tokenRef.current) return;
		popupRef.current.postMessage(
			JSON.stringify({
				simpleType: 'simpleAuth',
				accKey: tokenRef.current.accKey,
				accToken: tokenRef.current.accToken,
			}),
			EASYSIGN_ORIGIN,
		);
		initSentRef.current = true;
	}, []);

	const cleanup = useCallback((): void => {
		popupRef.current = null;
		tokenRef.current = null;
		initSentRef.current = false;
		initRequestedRef.current = false;
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

	// postMessage 핸들러
	useEffect(() => {
		const handleMessage = async (event: MessageEvent): Promise<void> => {
			if (event.origin !== EASYSIGN_ORIGIN) return;

			let data: { initFlag?: string; status?: string; fn?: string };
			try {
				data = JSON.parse(event.data);
			} catch {
				return;
			}

			// 팝업 초기화 신호 → accKey/accToken 전달 (토큰 미도착 시 플래그 저장)
			if (data.initFlag === 'true') {
				if (initSentRef.current) return;
				initRequestedRef.current = true;
				sendTokenToPopup();
				return;
			}

			// 인증 완료 신호
			if (data.status === 'success' && data.fn === 'authComplete') {
				try {
					const res = await idoInstance.post('/api/v1/auth/oacx/easysign', event.data);
					const result = res.data as EasysignResult;
					callbacksRef.current.onSuccess(result);
				} catch (e) {
					const msg = e instanceof Error ? e.message : String(e);
					callbacksRef.current.onError(msg);
				} finally {
					if (popupRef.current && !popupRef.current.closed) popupRef.current.close();
					cleanup();
				}
			}
		};

		window.addEventListener('message', handleMessage);
		return (): void => window.removeEventListener('message', handleMessage);
	}, [cleanup]);

	const startAuth = useCallback(async (): Promise<void> => {
		if (busy) return;
		setBusy(true);

		const width = 838;
		const height = 611;
		const left = window.screenX + (window.outerWidth - width) / 2;
		const top = window.screenY + (window.outerHeight - height) / 2;
		const features = `toolbar=no,scrollbars=no,location=no,resizable=no,status=no,menubar=no,width=${width},height=${height},left=${left},top=${top}`;
		const popup = window.open(EASYSIGN_URL, 'simpleAuth', features);
		if (!popup) {
			callbacksRef.current.onError('팝업이 차단되었습니다');
			setBusy(false);
			return;
		}
		popupRef.current = popup;

		try {
			const res = await idoInstance.post('/api/v1/auth/oacx/access-info', 'simpleAuth');
			const data = res.data as AccessInfoResponse;

			if (data.resultCode !== '2000' || !data.accKey || !data.accToken) {
				callbacksRef.current.onError(`인증 초기화 실패: ${data.resultMsg}`);
				if (!popup.closed) popup.close();
				cleanup();
				return;
			}

			tokenRef.current = { accKey: data.accKey, accToken: data.accToken };

			// 팝업이 먼저 initFlag를 보냈으면 즉시 토큰 전달
			if (initRequestedRef.current) {
				sendTokenToPopup();
			}
		} catch (e) {
			const msg = e instanceof Error ? e.message : String(e);
			callbacksRef.current.onError(msg);
			if (!popup.closed) popup.close();
			cleanup();
		}
	}, [busy, cleanup]);

	return { busy, startAuth };
}

export default usePersonalEasyAuth;
