/**
 * NICE 휴대폰 본인인증 테스트
 *
 * 흐름:
 * 1. 백엔드에 인증 URL 요청 → authUrl 수신
 * 2. window.open으로 NICE 표준창 오픈
 * 3. 인증 완료 후 return_url로 web_transaction_id 전달
 * 4. 백엔드에 결과 조회 요청 → 복호화된 인증 결과 수신
 */
import beInstance from 'api/beInstance';
import { useCallback, useEffect, useRef, useState } from 'react';

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

type LogEntry = { time: string; message: string };

const preStyle: React.CSSProperties = {
	background: '#f3f4f6',
	padding: 16,
	borderRadius: 8,
	overflowX: 'auto',
	whiteSpace: 'pre-wrap',
};

function PhoneAuthTab(): JSX.Element {
	const [busy, setBusy] = useState(false);
	const [urlResult, setUrlResult] = useState<NiceAuthUrlResponse | null>(null);
	const [authResult, setAuthResult] = useState<NiceAuthResultResponse | null>(null);
	const [logs, setLogs] = useState<LogEntry[]>([]);

	const popupRef = useRef<Window | null>(null);
	const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
	const requestNoRef = useRef<string | undefined>(undefined);

	const addLog = useCallback((message: string): void => {
		setLogs((prev) => [...prev, { time: new Date().toLocaleTimeString(), message }]);
	}, []);

	const cleanup = useCallback((): void => {
		if (pollingRef.current) {
			clearInterval(pollingRef.current);
			pollingRef.current = null;
		}
		popupRef.current = null;
		requestNoRef.current = undefined;
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

	// return_url로 전달되는 web_transaction_id를 수신하는 message 핸들러
	useEffect(() => {
		const handleMessage = async (event: MessageEvent): Promise<void> => {
			// 같은 origin에서 오는 메시지만 처리
			if (event.origin !== window.location.origin) return;

			let data: { type?: string; webTransactionId?: string; requestNo?: string };
			try {
				data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
			} catch {
				return;
			}

			if (data.type === 'niceAuthComplete' && data.webTransactionId) {
				const requestNo = data.requestNo ?? requestNoRef.current;
				addLog(`web_transaction_id 수신: ${data.webTransactionId}`);
				await fetchAuthResult(data.webTransactionId, requestNo);
			}
		};

		window.addEventListener('message', handleMessage);
		return (): void => window.removeEventListener('message', handleMessage);
	}, [addLog]);

	// 인증 결과 조회
	const fetchAuthResult = async (webTransactionId: string, requestNo?: string): Promise<void> => {
		addLog('인증 결과 조회 중...');
		try {
			const res = await beInstance.post('/api/v1/auth/nice/phone/result', {
				web_transaction_id: webTransactionId,
				request_no: requestNo,
			});
			const result = res.data as NiceAuthResultResponse;
			setAuthResult(result);
			addLog(`인증 결과: ${result.resultCode} - ${result.resultMsg}`);
		} catch (e) {
			const msg = e instanceof Error ? e.message : String(e);
			addLog(`인증 결과 조회 에러: ${msg}`);
		} finally {
			if (popupRef.current && !popupRef.current.closed) popupRef.current.close();
			cleanup();
		}
	};

	// 휴대폰 인증 시작
	const startPhoneAuth = useCallback(async (): Promise<void> => {
		if (busy) return;
		setBusy(true);
		setUrlResult(null);
		setAuthResult(null);

		try {
			// 1단계: 백엔드에 인증 URL 요청
			addLog('NICE 인증 URL 요청 중...');
			const returnUrl = `${window.location.origin}/auth-test`;
			const res = await beInstance.get('/api/v1/auth/nice/phone/url', {
				params: { returnUrl },
			});
			const data = res.data as NiceAuthUrlResponse;
			setUrlResult(data);

			if (data.resultCode !== '2000' || !data.authUrl) {
				addLog(`URL 발급 실패: ${data.resultMsg}`);
				setBusy(false);
				return;
			}

			requestNoRef.current = data.requestNo;
			addLog(`인증 URL 발급 완료 (requestNo: ${data.requestNo ?? '없음'})`);

			// 2단계: NICE 표준창 팝업 오픈
			const features =
				'toolbar=no,scrollbars=no,location=no,resizable=no,status=no,menubar=no,width=500,height=700';
			const popup = window.open(data.authUrl, 'niceAuth', features);
			if (!popup) {
				addLog('팝업이 차단되었습니다. 팝업 허용 후 다시 시도해주세요.');
				setBusy(false);
				return;
			}
			popupRef.current = popup;
			addLog('NICE 인증 팝업 오픈');

			// 3단계: return_url에서 web_transaction_id를 받기 위해
			// 팝업의 URL 변화를 폴링으로 감지
			pollingRef.current = setInterval(async () => {
				try {
					if (!popup || popup.closed) {
						cleanup();
						return;
					}
					const popupUrl = popup.location.href;
					if (popupUrl && popupUrl.includes('web_transaction_id=')) {
						const url = new URL(popupUrl);
						const webTransactionId = url.searchParams.get('web_transaction_id');
						const requestNo = url.searchParams.get('request_no') ?? requestNoRef.current;
						if (webTransactionId) {
							addLog(`web_transaction_id 감지: ${webTransactionId}, request_no: ${requestNo ?? '없음'}`);
							if (pollingRef.current) {
								clearInterval(pollingRef.current);
								pollingRef.current = null;
							}
							await fetchAuthResult(webTransactionId, requestNo);
						}
					}
				} catch {
					// cross-origin 접근 시 예외 발생은 무시 (NICE 도메인일 때)
				}
			}, 500);
		} catch (e) {
			const msg = e instanceof Error ? e.message : String(e);
			addLog(`에러: ${msg}`);
			cleanup();
		}
	}, [busy, addLog, cleanup]);

	return (
		<div>
			<button
				type="button"
				onClick={startPhoneAuth}
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
				{busy ? '진행 중...' : '휴대폰 인증 시작'}
			</button>

			<h3 style={{ marginTop: 30 }}>인증 URL 응답</h3>
			<pre style={preStyle}>
				{urlResult ? JSON.stringify(urlResult, null, 2) : '결과가 여기에 표시됩니다'}
			</pre>

			<h3>인증 결과</h3>
			<pre style={preStyle}>
				{authResult
					? JSON.stringify(authResult, null, 2)
					: '인증 완료 후 결과가 여기에 표시됩니다'}
			</pre>

			<h3>로그</h3>
			<pre style={preStyle}>
				{logs.map((l) => `[${l.time}] ${l.message}`).join('\n') || '로그가 여기에 표시됩니다'}
			</pre>
		</div>
	);
}

export default PhoneAuthTab;
