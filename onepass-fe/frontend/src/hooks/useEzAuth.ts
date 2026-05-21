import { useCallback, useEffect, useRef, useState } from 'react';

export interface EzAuthBizResult {
	name: string;
	businessNumber?: string;
	birth?: string;
	phone?: string;
	bizOpendt?: string;
}

interface EzAuthCallbackResult {
	errno: number;
	error?: string;
	data?: {
		resultCode: string;
		resultMsg: string;
		result?: EzAuthBizResult;
		txId?: string;
		tokenId?: string;
	};
}

interface UseEzAuthReturn {
	ready: boolean;
	loading: boolean;
	startAuth: (brno?: string) => void;
}

declare global {
	interface Window {
		EzAuth?: {
			makeEzauthSimple: (
				optionsOrCallback:
					| Record<string, unknown>
					| ((result: EzAuthCallbackResult) => void),
				callback?: (result: EzAuthCallbackResult) => void,
			) => void;
		};
		EzauthConfig?: Record<string, unknown>;
	}
}

function useEzAuth(
	onSuccess: (data?: EzAuthBizResult) => void,
	onError: (errno: number, error?: string) => void,
): UseEzAuthReturn {
	const [ready, setReady] = useState<boolean>(false);
	const [loading, setLoading] = useState<boolean>(false);
	const callbacksRef = useRef({ onSuccess, onError });

	useEffect(() => {
		callbacksRef.current = { onSuccess, onError };
	}, [onSuccess, onError]);

	useEffect(() => {
		if (window.EzAuth) {
			setReady(true);
			return undefined;
		}

		const timer = setInterval(() => {
			if (window.EzAuth) {
				setReady(true);
				clearInterval(timer);
			}
		}, 100);

		return (): void => {
			clearInterval(timer);
		};
	}, []);

	const startAuth = useCallback((brno?: string): void => {
		if (!window.EzAuth) return;

		setLoading(true);

		const options: Record<string, unknown> = {};
		if (brno) {
			options.userInfo = { businessNumber: brno };
		}

		window.EzAuth.makeEzauthSimple(options, (result: EzAuthCallbackResult) => {
			if (result.errno === 10) return;

			setLoading(false);
			if (result.errno === 0) {
				callbacksRef.current.onSuccess(result.data?.result);
			} else {
				callbacksRef.current.onError(result.errno, result.error);
			}
		});
	}, []);

	return { ready, loading, startAuth };
}

export default useEzAuth;
