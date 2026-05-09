import React, { Suspense, lazy } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import { ErrorBoundary } from '@components/ErrorBoundary';

// ── Lazy-loaded pages ──────────────────────────────────────────────────────
const LoginPage          = lazy(() => import('@pages/Login/LoginPage'));
const ConversionPage     = lazy(() => import('@pages/Conversion/ConversionPage'));
const ErrorPage          = lazy(() => import('@pages/Error/ErrorPage'));

const PageLoader = () => (
  <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh' }}>
    <Spin size="large" tip="로딩 중..." />
  </div>
);

/**
 * 전역 오류 로깅 핸들러
 * 운영 환경에서는 Sentry / DataDog 등 외부 모니터링 연동으로 교체한다.
 */
const handleGlobalError = (error: Error): void => {
  // TODO: Sentry.captureException(error) 또는 DataDog RUM 연동
  console.error('[App] 전역 오류 포착:', error.message);
};

const App: React.FC = () => (
  <ErrorBoundary onError={handleGlobalError}>
    <Suspense fallback={<PageLoader />}>
      <Routes>
        {/* 진입점 — 세션 유효 여부에 따라 분기 */}
        <Route path="/"                        element={<Navigate to="/login" replace />} />
        <Route path="/login"                   element={<LoginPage />} />

        {/* 전환(인증) 흐름 — 7단계 */}
        <Route path="/conversion/*"            element={<ConversionPage />} />

        {/* 에러 */}
        <Route path="/error"                   element={<ErrorPage />} />
        <Route path="*"                        element={<Navigate to="/error" replace />} />
      </Routes>
    </Suspense>
  </ErrorBoundary>
);

export default App;
