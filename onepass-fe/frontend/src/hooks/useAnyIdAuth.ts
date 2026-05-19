/**
 * Any-ID 정부 통합인증 SDK 훅
 *
 * 흐름:
 * 1. startAuth() 호출 → txId 발급 → showModal=true
 * 2. AnyIdLoginModal 마운트 후 initSdk() 호출 → AnyidC.LOAD_MODULE()
 * 3. 사용자 인증수단 선택 → SDK가 anyidAdaptor.success(data) 콜백 호출
 * 4. bypass !== 0 || !data.useSso  → handleOrgLogin() → POST /api/v1/anyid/ssob
 *    else                           → handleSsoLogin() → GET /api/v1/anyid/oidc/ssoLogin
 * 5. orgLogin 성공 시 onSuccess(result) 콜백 → Login.tsx가 easyAuthFormRef.submit()
 *
 * @see AnyIdLoginModal
 * @see Login.tsx (onepass-fe)
 * @see AnyIdController (ido 모듈)
 */

import beInstance from 'api/beInstance';
import { useCallback, useEffect, useRef, useState } from 'react';

// ─────────────────────────────────────────────────────────────────────────────
// SDK 전역 타입 선언 (window.AnyidC)
// ─────────────────────────────────────────────────────────────────────────────

declare global {
	interface Window {
		AnyidC?: {
			LOAD_MODULE: (options: AnyidcModuleOptions) => void;
		};
	}
}

interface AnyidcModuleOptions {
	cfg: string;
	txId: string;
	tag: string;
	lvl: number;
	bypass: number;
	theme: string;
	toggle: boolean;
	success: (data: AnyidSuccessData) => void;
	fail: (err: unknown) => void;
	log?: (data: unknown) => void;
}

// ─────────────────────────────────────────────────────────────────────────────
// 공개 타입
// ─────────────────────────────────────────────────────────────────────────────

/** anyidAdaptor.success(data) 콜백 데이터 구조 */
export interface AnyidSuccessData {
	ssob: string;
	txId: string;
	tag: string;
	userSeCd: string;
	afData: string | null;
	useSso: boolean;
}

/** POST /api/v1/anyid/ssob 응답 구조 */
export interface AnyIdAuthResult {
	/** "2000" = 성공 */
	resultCode: string;
	resultMsg: string;
	/** 암호화된 CI — easyAuthFormRef의 encCi 필드에 세팅 */
	ci?: string;
	name?: string;
	authLevel?: string;
}

export interface UseAnyIdAuthReturn {
	busy: boolean;
	showModal: boolean;
	startAuth: () => Promise<void>;
	closeModal: () => void;
	/** AnyIdLoginModal이 마운트된 직후 호출 → AnyidC.LOAD_MODULE() 실행 */
	initSdk: () => void;
}

// ─────────────────────────────────────────────────────────────────────────────
// txId 발급 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * BE에서 txId를 발급받는다.
 * /api/v1/anyid/txId POST 가 없으면 클라이언트 생성 값으로 대체.
 */
async function fetchTxId(): Promise<string> {
	try {
		const res = await beInstance.post<{ txId: string }>('/api/v1/anyid/txId');
		if (res.data?.txId) return res.data.txId;
	} catch {
		// BE 미구현 또는 네트워크 오류 → 클라이언트 생성
	}
	const now = new Date()
		.toISOString()
		.replace(/[-:T.Z]/g, '')
		.slice(0, 14); // 'YYYYMMDDHHmmss'
	const rand = Math.random().toString(36).slice(2, 10);
	return `${now}-${rand}`;
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅 본체
// ─────────────────────────────────────────────────────────────────────────────

function useAnyIdAuth(
	onSuccess: (result: AnyIdAuthResult) => void,
	onError: (message: string) => void,
	options: {
		/** Keycloak action_url — orgLogin 후 easyAuthFormRef.submit() 전 확인용 */
		actionUrl: string | null;
		/** 인증 수준 1=L1 / 2=L2 / 3=L3. 기본 2 */
		authLevel?: number;
		/**
		 * SSO 우회 여부.
		 * 0 = SSO 경유(default), 1 = 기관 자체 로그인만.
		 * bypass=0 이어도 data.useSso=false 이면 orgLogin으로 분기.
		 */
		bypass?: number;
	},
): UseAnyIdAuthReturn {
	const { authLevel = 2, bypass = 0 } = options;

	const [busy, setBusy] = useState(false);
	const [showModal, setShowModal] = useState(false);

	const txIdRef = useRef<string>('');
	const callbacksRef = useRef({ onSuccess, onError });
	// initSdk 재시도 타이머
	const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

	useEffect(() => {
		callbacksRef.current = { onSuccess, onError };
	}, [onSuccess, onError]);

	// 언마운트 시 타이머 정리
	useEffect(() => {
		return (): void => {
			if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
		};
	}, []);

	// ── closeModal ──────────────────────────────────────────────────────────

	const closeModal = useCallback((): void => {
		setShowModal(false);
		setBusy(false);
		if (retryTimerRef.current) {
			clearTimeout(retryTimerRef.current);
			retryTimerRef.current = null;
		}
	}, []);

	// ── orgLogin 처리 ────────────────────────────────────────────────────────

	/**
	 * anyidAdaptor.orgLogin() 에 해당.
	 * ssob + tag → POST /api/v1/anyid/ssob → ci 수신 → onSuccess 콜백
	 */
	const handleOrgLogin = useCallback(
		async (data: AnyidSuccessData): Promise<void> => {
			try {
				const res = await beInstance.post<AnyIdAuthResult>('/api/v1/anyid/ssob', {
					ssob: data.ssob,
					tag: data.tag || txIdRef.current,
					txId: data.txId || txIdRef.current,
				});

				const result = res.data;

				if (result.resultCode === '2000' && result.ci) {
					callbacksRef.current.onSuccess(result);
				} else {
					callbacksRef.current.onError(
						`Any-ID 인증 실패: ${result.resultMsg ?? '알 수 없는 오류'}`,
					);
				}
			} catch (e) {
				const msg = e instanceof Error ? e.message : String(e);
				callbacksRef.current.onError(`Any-ID 서버 오류: ${msg}`);
			} finally {
				closeModal();
			}
		},
		[closeModal],
	);

	// ── ssoLogin 처리 ────────────────────────────────────────────────────────

	/**
	 * anyidAdaptor.ssoLogin() 에 해당.
	 * Base64 인코딩된 payload → GET /api/v1/anyid/oidc/ssoLogin?data=...
	 * 서버가 처리 후 Keycloak action_url 로 redirect.
	 */
	const handleSsoLogin = useCallback((certData: AnyidSuccessData): void => {
		const payload = {
			txId: certData.txId,
			ssob: certData.ssob,
			userSeCd: certData.userSeCd,
			afData: certData.afData,
		};
		const encoded = btoa(JSON.stringify(payload));
		window.location.href = `/api/v1/anyid/oidc/ssoLogin?data=${encoded}`;
	}, []);

	// ── SDK 초기화 ────────────────────────────────────────────────────────────

	/**
	 * AnyIdLoginModal이 DOM에 마운트된 후 호출한다.
	 * window.AnyidC 가 아직 없으면 300ms 간격으로 최대 10회 재시도.
	 */
	const initSdk = useCallback((): void => {
		if (retryTimerRef.current) {
			clearTimeout(retryTimerRef.current);
			retryTimerRef.current = null;
		}

		if (typeof window.AnyidC === 'undefined') {
			// SDK JS 아직 로드 중 → 재시도
			let attempt = 0;
			const MAX_ATTEMPTS = 10;

			const retry = (): void => {
				attempt++;
				if (typeof window.AnyidC !== 'undefined') {
					doInit();
					return;
				}
				if (attempt >= MAX_ATTEMPTS) {
					callbacksRef.current.onError(
						'Any-ID SDK 로드에 실패했습니다. 페이지를 새로고침 후 다시 시도해 주세요.',
					);
					closeModal();
					return;
				}
				retryTimerRef.current = setTimeout(retry, 300);
			};

			retryTimerRef.current = setTimeout(retry, 300);
			return;
		}

		doInit();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [authLevel, bypass, handleOrgLogin, handleSsoLogin, closeModal]);

	/** 실제 LOAD_MODULE 호출 */
	const doInit = (): void => {
		window.AnyidC!.LOAD_MODULE({
			cfg: '/config/config.anyidc.json',
			txId: txIdRef.current,
			tag: txIdRef.current,
			lvl: authLevel,
			bypass: bypass,
			theme: '4.1.0',
			toggle: true,
			success: (data: AnyidSuccessData) => {
				if (bypass !== 0 || !data.useSso) {
					void handleOrgLogin(data);
				} else {
					handleSsoLogin(data);
				}
			},
			fail: (err: unknown) => {
				const msg =
					err && typeof err === 'object' && 'message' in err
						? String((err as { message: unknown }).message)
						: 'Any-ID 인증에 실패했습니다.';
				callbacksRef.current.onError(msg);
				closeModal();
			},
			log: (data: unknown) => {
				if (process.env.NODE_ENV === 'development') {
					// eslint-disable-next-line no-console
					console.log('[AnyId SDK]', data);
				}
			},
		});
	};

	// ── startAuth ────────────────────────────────────────────────────────────

	const startAuth = useCallback(async (): Promise<void> => {
		if (busy) return;
		setBusy(true);

		txIdRef.current = await fetchTxId();
		setShowModal(true);
	}, [busy]);

	return { busy, showModal, startAuth, closeModal, initSdk };
}

export default useAnyIdAuth;
