import React, { Component, ErrorInfo, ReactNode } from 'react';

// ── 타입 정의 ─────────────────────────────────────────────────────────────────

interface ErrorBoundaryProps {
  /** 정상 렌더링 대상 자식 컴포넌트 */
  children: ReactNode;
  /**
   * 오류 발생 시 렌더링할 Fallback UI.
   * 미제공 시 기본 오류 화면이 표시됨.
   */
  fallback?: ReactNode | ((error: Error, reset: () => void) => ReactNode);
  /**
   * 오류 발생 시 외부에서 처리할 콜백 (모니터링/Sentry 전송 등).
   */
  onError?: (error: Error, errorInfo: ErrorInfo) => void;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
  errorInfo: ErrorInfo | null;
}

// ── ErrorBoundary 컴포넌트 ────────────────────────────────────────────────────

/**
 * React 컴포넌트 트리 충돌 방지 ErrorBoundary
 *
 * @description
 * React 16+의 `componentDidCatch` 라이프사이클을 이용하여 하위 컴포넌트 트리에서
 * 발생하는 렌더링 오류를 포착한다.
 * - 오류 발생 시 전체 화면 빈 화면 대신 Fallback UI를 표시
 * - onError 콜백으로 외부 오류 모니터링(Sentry 등) 연동 지원
 * - reset() 함수를 Fallback에 전달하여 사용자가 복구 시도 가능
 *
 * @example
 * // 기본 사용
 * <ErrorBoundary>
 *   <MyPage />
 * </ErrorBoundary>
 *
 * @example
 * // 커스텀 Fallback
 * <ErrorBoundary fallback={(error, reset) => (
 *   <div>
 *     <p>{error.message}</p>
 *     <button onClick={reset}>다시 시도</button>
 *   </div>
 * )}>
 *   <MyPage />
 * </ErrorBoundary>
 *
 * @example
 * // App.tsx 최상위 감싸기 (권장)
 * <ErrorBoundary onError={(e, info) => Sentry.captureException(e, { extra: info })}>
 *   <Suspense fallback={<PageLoader />}>
 *     <Routes>...</Routes>
 *   </Suspense>
 * </ErrorBoundary>
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {

  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = {
      hasError:  false,
      error:     null,
      errorInfo: null,
    };
    this.handleReset = this.handleReset.bind(this);
  }

  // ── 정적 메서드 ────────────────────────────────────────────────────────────

  /**
   * 렌더링 오류 발생 시 React가 호출.
   * state를 업데이트하여 다음 렌더링에서 Fallback UI를 표시한다.
   */
  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return { hasError: true, error };
  }

  // ── 라이프사이클 ───────────────────────────────────────────────────────────

  /**
   * 오류 및 컴포넌트 스택 정보를 외부로 전달.
   * Sentry / DataDog 등 외부 모니터링 도구 연동에 사용.
   */
  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    this.setState({ errorInfo });

    // 콘솔 출력 (개발 환경 디버깅용)
    console.error('[ErrorBoundary] 컴포넌트 오류 발생:', error);
    console.error('[ErrorBoundary] 컴포넌트 스택:', errorInfo.componentStack);

    // 외부 오류 핸들러 콜백 (onError prop)
    if (this.props.onError) {
      this.props.onError(error, errorInfo);
    }
  }

  // ── 오류 복구 ──────────────────────────────────────────────────────────────

  /**
   * 오류 상태를 초기화하여 자식 컴포넌트를 다시 렌더링 시도.
   * Fallback UI의 "다시 시도" 버튼에 바인딩하여 사용.
   */
  handleReset(): void {
    this.setState({
      hasError:  false,
      error:     null,
      errorInfo: null,
    });
  }

  // ── 렌더링 ────────────────────────────────────────────────────────────────

  render(): ReactNode {
    const { hasError, error } = this.state;
    const { children, fallback } = this.props;

    if (!hasError || !error) {
      return children;
    }

    // 커스텀 Fallback이 제공된 경우
    if (fallback !== undefined) {
      if (typeof fallback === 'function') {
        return fallback(error, this.handleReset);
      }
      return fallback;
    }

    // 기본 Fallback UI
    return (
      <DefaultErrorFallback
        error={error}
        onReset={this.handleReset}
      />
    );
  }
}

// ── 기본 Fallback UI ──────────────────────────────────────────────────────────

interface DefaultErrorFallbackProps {
  error: Error;
  onReset: () => void;
}

const DefaultErrorFallback: React.FC<DefaultErrorFallbackProps> = ({ error, onReset }) => {
  const isDev = process.env.NODE_ENV === 'development';

  return (
    <div
      role="alert"
      style={{
        display:        'flex',
        flexDirection:  'column',
        alignItems:     'center',
        justifyContent: 'center',
        minHeight:      '100vh',
        padding:        '2rem',
        fontFamily:     'sans-serif',
        background:     '#f8f9fa',
      }}
    >
      {/* 오류 아이콘 */}
      <div
        aria-hidden="true"
        style={{ fontSize: '3rem', marginBottom: '1rem' }}
      >
        ⚠️
      </div>

      {/* 제목 */}
      <h1
        style={{
          fontSize:     '1.5rem',
          fontWeight:   700,
          color:        '#212529',
          marginBottom: '0.5rem',
          textAlign:    'center',
        }}
      >
        오류가 발생했습니다
      </h1>

      {/* 안내 문구 */}
      <p
        style={{
          color:        '#6c757d',
          marginBottom: '1.5rem',
          textAlign:    'center',
          maxWidth:     '400px',
        }}
      >
        일시적인 오류가 발생했습니다. 아래 버튼을 눌러 다시 시도하거나,
        문제가 계속되면 관리자에게 문의해 주세요.
      </p>

      {/* 개발 환경 — 오류 상세 표시 */}
      {isDev && (
        <details
          style={{
            marginBottom: '1.5rem',
            padding:      '1rem',
            background:   '#fff3cd',
            border:       '1px solid #ffc107',
            borderRadius: '4px',
            maxWidth:     '600px',
            width:        '100%',
            cursor:       'pointer',
          }}
        >
          <summary style={{ fontWeight: 600, color: '#856404' }}>
            [개발 환경] 오류 상세 정보
          </summary>
          <pre
            style={{
              marginTop:  '0.5rem',
              fontSize:   '0.75rem',
              color:      '#343a40',
              whiteSpace: 'pre-wrap',
              wordBreak:  'break-all',
            }}
          >
            {error.name}: {error.message}
            {error.stack ? '\n\n' + error.stack : ''}
          </pre>
        </details>
      )}

      {/* 액션 버튼 */}
      <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', justifyContent: 'center' }}>
        <button
          type="button"
          onClick={onReset}
          style={{
            padding:         '0.625rem 1.25rem',
            background:      '#0d6efd',
            color:           '#fff',
            border:          'none',
            borderRadius:    '4px',
            fontSize:        '0.9rem',
            fontWeight:      600,
            cursor:          'pointer',
          }}
        >
          다시 시도
        </button>
        <button
          type="button"
          onClick={() => { window.location.href = '/'; }}
          style={{
            padding:         '0.625rem 1.25rem',
            background:      '#fff',
            color:           '#0d6efd',
            border:          '1px solid #0d6efd',
            borderRadius:    '4px',
            fontSize:        '0.9rem',
            fontWeight:      600,
            cursor:          'pointer',
          }}
        >
          홈으로 이동
        </button>
      </div>
    </div>
  );
};

// ── 편의 HOC ──────────────────────────────────────────────────────────────────

/**
 * 컴포넌트를 ErrorBoundary로 감싸는 고차 컴포넌트(HOC)
 *
 * @example
 * const SafeMyPage = withErrorBoundary(MyPage, {
 *   onError: (e) => Sentry.captureException(e),
 * });
 */
export function withErrorBoundary<P extends object>(
  WrappedComponent: React.ComponentType<P>,
  errorBoundaryProps?: Omit<ErrorBoundaryProps, 'children'>,
): React.FC<P> {
  const displayName =
    WrappedComponent.displayName ?? WrappedComponent.name ?? 'Component';

  const ComponentWithBoundary: React.FC<P> = (props) => (
    <ErrorBoundary {...errorBoundaryProps}>
      <WrappedComponent {...props} />
    </ErrorBoundary>
  );

  ComponentWithBoundary.displayName = `withErrorBoundary(${displayName})`;
  return ComponentWithBoundary;
}

export default ErrorBoundary;
