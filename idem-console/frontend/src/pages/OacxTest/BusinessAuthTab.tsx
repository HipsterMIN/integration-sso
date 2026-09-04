/**
 * 기업인증 (EzAuth SDK)
 *
 * 흐름:
 * 1. 사업자등록번호 입력
 * 2. EzAuth SDK startAuth(brno) 호출 → 드림시큐리티 인증 팝업
 * 3. 인증 성공 콜백 → auth-status API → auth-result API → 결과 수신
 */
import type { EzAuthBizResult } from 'hooks/useEzAuth';
import useEzAuth from 'hooks/useEzAuth';
import { useCallback, useState } from 'react';

type LogEntry = { time: string; message: string };

const preStyle: React.CSSProperties = {
	background: '#f3f4f6',
	padding: 16,
	borderRadius: 8,
	overflowX: 'auto',
	whiteSpace: 'pre-wrap',
};

function BusinessAuthTab(): JSX.Element {
	const [brno, setBrno] = useState('');
	const [bizAuthResult, setBizAuthResult] = useState<EzAuthBizResult | null>(
		null,
	);
	const [bizLogs, setBizLogs] = useState<LogEntry[]>([]);

	const addBizLog = useCallback((message: string): void => {
		setBizLogs((prev) => [
			...prev,
			{ time: new Date().toLocaleTimeString(), message },
		]);
	}, []);

	// EzAuth 콜백
	const handleBizAuthSuccess = useCallback(
		(resultData?: EzAuthBizResult): void => {
			if (!resultData) {
				addBizLog('인증 결과 데이터가 없습니다');
				return;
			}
			setBizAuthResult(resultData);
			addBizLog(
				`기업인증 완료: ${resultData.name} (${resultData.businessNumber || ''})`,
			);
		},
		[addBizLog],
	);

	const handleBizAuthError = useCallback(
		(errno: number, error?: string): void => {
			if (errno === 302) {
				addBizLog('사용자가 인증을 취소했습니다');
				return;
			}
			addBizLog(`기업인증 에러: errno=${errno}${error ? `, ${error}` : ''}`);
		},
		[addBizLog],
	);

	const {
		ready: ezAuthReady,
		loading: ezAuthLoading,
		startAuth: startEzAuth,
	} = useEzAuth(handleBizAuthSuccess, handleBizAuthError);

	const handleBrnoChange = (e: React.ChangeEvent<HTMLInputElement>): void => {
		setBrno(e.target.value.replace(/\D/g, '').slice(0, 10));
	};

	const startBusinessAuth = (): void => {
		if (brno.length !== 10) {
			addBizLog('사업자등록번호는 10자리를 입력해주세요');
			return;
		}
		if (!ezAuthReady) {
			addBizLog('EzAuth SDK가 아직 로딩되지 않았습니다');
			return;
		}
		addBizLog(`사업자등록번호: ${brno}, EzAuth 기업인증 시작...`);
		startEzAuth(brno);
	};

	return (
		<div>
			<div style={{ marginBottom: 16 }}>
				<label
					htmlFor="brno_input"
					style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}
				>
					사업자등록번호
				</label>
				<input
					id="brno_input"
					type="text"
					placeholder="사업자등록번호 10자리 입력"
					value={brno}
					onChange={handleBrnoChange}
					maxLength={10}
					style={{
						padding: '10px 14px',
						fontSize: 15,
						border: '1px solid #d1d5db',
						borderRadius: 6,
						width: 260,
					}}
				/>
			</div>

			<button
				type="button"
				onClick={startBusinessAuth}
				disabled={!ezAuthReady || ezAuthLoading}
				style={{
					padding: '12px 24px',
					fontSize: 16,
					cursor: !ezAuthReady || ezAuthLoading ? 'not-allowed' : 'pointer',
					background: !ezAuthReady || ezAuthLoading ? '#93b4f5' : '#2563eb',
					color: '#fff',
					border: 'none',
					borderRadius: 6,
				}}
			>
				{!ezAuthReady
					? 'EzAuth 로딩 중...'
					: ezAuthLoading
					? '인증 진행 중...'
					: '기업인증 시작'}
			</button>

			<h3 style={{ marginTop: 30 }}>인증 결과</h3>
			<pre style={preStyle}>
				{bizAuthResult
					? JSON.stringify(bizAuthResult, null, 2)
					: '기업인증 완료 후 결과가 여기에 표시됩니다'}
			</pre>

			<h3>로그</h3>
			<pre style={preStyle}>
				{bizLogs.map((l) => `[${l.time}] ${l.message}`).join('\n') ||
					'로그가 여기에 표시됩니다'}
			</pre>
		</div>
	);
}

export default BusinessAuthTab;
