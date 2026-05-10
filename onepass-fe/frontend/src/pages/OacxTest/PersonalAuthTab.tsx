/**
 * 개인인증 (OACX 간편인증)
 *
 * 흐름:
 * 1. access-info API 호출 → accKey/accToken 발급
 * 2. EasySign 팝업 오픈 → postMessage로 accKey/accToken 전달
 * 3. 인증 완료 시 easysign API 호출 → 결과 수신
 */
import beInstance from 'api/beInstance';
import { useCallback, useEffect, useRef, useState } from 'react';

const EASYSIGN_URL = process.env.EASYSIGN_URL || '';
const EASYSIGN_ORIGIN = process.env.EASYSIGN_ORIGIN || '';

interface AccessInfoResponse {
	resultCode: string;
	resultMsg: string;
	fn: string | null;
	accKey: string | null;
	accToken: string | null;
}

interface EasysignResponse {
	resultCode: string;
	resultMsg: string;
	name?: string;
	birthday?: string;
	phone?: string;
}

type LogEntry = { time: string; message: string };

const preStyle: React.CSSProperties = {
	background: '#f3f4f6',
	padding: 16,
	borderRadius: 8,
	overflowX: 'auto',
	whiteSpace: 'pre-wrap',
};

function PersonalAuthTab(): JSX.Element {
	const [busy, setBusy] = useState(false);
	const [accessResult, setAccessResult] = useState<AccessInfoResponse | null>(null);
	const [easysignResult, setEasysignResult] = useState<EasysignResponse | null>(null);
	const [logs, setLogs] = useState<LogEntry[]>([]);

	const popupRef = useRef<Window | null>(null);
	const tokenRef = useRef<{ accKey: string; accToken: string } | null>(null);
	const initSentRef = useRef(false);
	const initRequestedRef = useRef(false);

	const addLog = useCallback((message: string): void => {
		setLogs((prev) => [...prev, { time: new Date().toLocaleTimeString(), message }]);
	}, []);

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
		addLog('팝업에 accKey/accToken 전달 완료');
	}, [addLog]);

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
				addLog('팝업이 닫혔습니다');
				cleanup();
			}
		}, 500);
		return (): void => clearInterval(timer);
	}, [busy, addLog, cleanup]);

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

			if (data.initFlag === 'true') {
				if (initSentRef.current) return;
				initRequestedRef.current = true;
				sendTokenToPopup();
				return;
			}

			if (data.status === 'success' && data.fn === 'authComplete') {
				addLog('인증 완료, easysign 호출 중...');
				try {
					const res = await beInstance.post('/api/v1/auth/oacx/easysign', event.data);
					const result = res.data as EasysignResponse;
					setEasysignResult(result);
					addLog(`easysign 결과: ${result.resultCode}`);
				} catch (e) {
					const msg = e instanceof Error ? e.message : String(e);
					addLog(`easysign 에러: ${msg}`);
				} finally {
					if (popupRef.current && !popupRef.current.closed) popupRef.current.close();
					cleanup();
				}
			}
		};

		window.addEventListener('message', handleMessage);
		return (): void => window.removeEventListener('message', handleMessage);
	}, [addLog, cleanup]);

	// 개인인증 시작
	const startPersonalAuth = useCallback(async (): Promise<void> => {
		if (busy) return;
		setBusy(true);
		setAccessResult(null);
		setEasysignResult(null);

		const features =
			'toolbar=no,scrollbars=no,location=no,resizable=no,status=no,menubar=no,width=838,height=611';
		const popup = window.open(EASYSIGN_URL, 'simpleAuth', features);
		if (!popup) {
			addLog('팝업이 차단되었습니다');
			setBusy(false);
			return;
		}
		popupRef.current = popup;
		addLog('OACX 팝업 오픈');

		try {
			addLog('access-info 호출 중...');
			const res = await beInstance.post('/api/v1/auth/oacx/access-info', 'simpleAuth');
			const data = res.data as AccessInfoResponse;
			setAccessResult(data);

			if (data.resultCode !== '2000' || !data.accKey || !data.accToken) {
				addLog(`access-info 실패: ${data.resultMsg}`);
				if (!popup.closed) popup.close();
				cleanup();
				return;
			}

			tokenRef.current = { accKey: data.accKey, accToken: data.accToken };
			addLog('accKey/accToken 발급 완료, 팝업 init 대기 중');

			// 팝업이 먼저 initFlag를 보냈으면 즉시 토큰 전달
			if (initRequestedRef.current) {
				sendTokenToPopup();
			}
		} catch (e) {
			const msg = e instanceof Error ? e.message : String(e);
			addLog(`access-info 에러: ${msg}`);
			if (!popup.closed) popup.close();
			cleanup();
		}
	}, [busy, addLog, cleanup]);

	return (
		<div>
			<button
				type="button"
				onClick={startPersonalAuth}
				disabled={busy}
				style={{
					padding: '12px 24px',
					fontSize: 16,
					cursor: busy ? 'not-allowed' : 'pointer',
					background: busy ? '#93b4f5' : '#2563eb',
					color: '#fff',
					border: 'none',
					borderRadius: 6,
				}}
			>
				{busy ? '진행 중...' : '개인인증 시작'}
			</button>

			<h3 style={{ marginTop: 30 }}>access-info 응답</h3>
			<pre style={preStyle}>
				{accessResult ? JSON.stringify(accessResult, null, 2) : '결과가 여기에 표시됩니다'}
			</pre>

			<h3>인증 결과</h3>
			<pre style={preStyle}>
				{easysignResult
					? JSON.stringify(easysignResult, null, 2)
					: '인증 완료 후 결과가 여기에 표시됩니다'}
			</pre>

			<h3>로그</h3>
			<pre style={preStyle}>
				{logs.map((l) => `[${l.time}] ${l.message}`).join('\n') || '로그가 여기에 표시됩니다'}
			</pre>
		</div>
	);
}

export default PersonalAuthTab;
