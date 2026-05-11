/**
 * OnePass ErrorBoundary — React 네이티브 에러 경계
 *
 * Sentry.ErrorBoundary(index.tsx 최상위)가 프로덕션 오류를 포착하며,
 * 이 컴포넌트는 개별 페이지·섹션 수준의 부분 에러 격리에 사용한다.
 *
 * 사용 예:
 *   <ErrorBoundary>
 *     <SomePage />
 *   </ErrorBoundary>
 *
 *   // 커스텀 폴백:
 *   <ErrorBoundary fallback={<p>이 섹션을 불러올 수 없습니다.</p>}>
 *     <SomePage />
 *   </ErrorBoundary>
 */
import ErrorBoundaryFallback from 'pages/ErrorBoundaryFallback/ErrorBoundaryFallback';
import { Component, ErrorInfo, ReactNode } from 'react';

interface Props {
	children: ReactNode;
	/** 커스텀 폴백 UI (미제공 시 ErrorBoundaryFallback 사용) */
	fallback?: ReactNode;
	/** 에러 발생 시 호출되는 콜백 (로깅·알림 등) */
	onError?: (error: Error, info: ErrorInfo) => void;
}

interface State {
	hasError: boolean;
	error: Error | null;
}

class ErrorBoundary extends Component<Props, State> {
	constructor(props: Props) {
		super(props);
		this.state = { hasError: false, error: null };
	}

	static getDerivedStateFromError(error: Error): State {
		return { hasError: true, error };
	}

	componentDidCatch(error: Error, info: ErrorInfo): void {
		// 개발 환경: 콘솔 출력
		if (process.env.NODE_ENV === 'development') {
			console.error('[ErrorBoundary] Uncaught error:', error, info);
		}
		// 콜백 실행 (외부 로깅 훅 등)
		this.props.onError?.(error, info);
	}

	handleReset = (): void => {
		this.setState({ hasError: false, error: null });
	};

	render(): ReactNode {
		if (this.state.hasError) {
			if (this.props.fallback !== undefined) {
				return this.props.fallback;
			}
			return <ErrorBoundaryFallback />;
		}
		return this.props.children;
	}
}

export default ErrorBoundary;
