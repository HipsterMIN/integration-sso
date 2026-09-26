import './ReactI18';
import 'assets/onepass/styles.scss';

import * as Sentry from '@sentry/react';
import AppRoutes from 'AppRoutes';
import { AxiosError } from 'axios';
import ErrorBoundaryFallback from 'pages/ErrorBoundaryFallback/ErrorBoundaryFallback';
import posthog from 'posthog-js';
import { createRoot } from 'react-dom/client';
import { HelmetProvider } from 'react-helmet-async';
import { QueryClient, QueryClientProvider } from 'react-query';
import { ReactQueryDevtools } from 'react-query/devtools';
import { Provider } from 'react-redux';
import store from 'store';
import { initializeFaro, getWebInstrumentations, LogLevel } from '@grafana/faro-web-sdk';
import { TracingInstrumentation } from '@grafana/faro-web-tracing';

 
const appEnv = process.env.APP_ENV || 'local';
const faroAppEnv = process.env.FARO_APP_ENV || appEnv;

// 로컬 환경에서는 Faro 수집 비활성화
const faro = faroAppEnv !== 'local'
  ? initializeFaro({
      url: process.env.FARO_COLLECTOR_URL || '',
      app: {
        name: `onepass-fe-${faroAppEnv}`,
        version: '1.0.0',
        environment: faroAppEnv,
      },
      globalObjectKey: 'faro',
      user: {
        attributes: {
          tenant_id: process.env.FARO_TENANT_ID || window.location.hostname.split('.')[0],
        }
      },
      sessionTracking: {
        enabled: true,
        samplingRate: 1.0,
      },
      batching: {
        enabled: false, // 페이지 이동/종료 시 frontend span 유실 방지
      },
      instrumentations: [
        ...getWebInstrumentations({
          captureConsole: true,
          captureConsoleDisabledLevels: [LogLevel.DEBUG, LogLevel.LOG],
        }),
        new TracingInstrumentation({
          instrumentationOptions: {
            propagateTraceHeaderCorsUrls: [
              /\/api\/.*/,
              // IdO 게이트웨이 단일 채널 (ADR-008) — 트레이스 헤더 전파 대상.
              // Phase 2 / SEC-IDO-02: IDEM_HUB_API_ENDPOINT 우선, 없으면 구 BE_API_ENDPOINT fallback.
              ...((process.env.IDEM_HUB_API_ENDPOINT || process.env.BE_API_ENDPOINT)
                ? [
                    new RegExp(
                      (process.env.IDEM_HUB_API_ENDPOINT || process.env.BE_API_ENDPOINT || '')
                        .replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '.*',
                    ),
                  ]
                : []),
            ],
          },
        }),
      ],
    })
  : null;

// 활성 사용자 추적을 위한 heartbeat (60초 주기, 탭 활성 시에만)
setInterval(() => {
  if (!document.hidden && faro) {
    faro.api.pushEvent('heartbeat');
  }
}, 60000);


const queryClient = new QueryClient({
	defaultOptions: {
		queries: {
			refetchOnWindowFocus: false,
			retry(failureCount, error): boolean {
				if (
					// in case of manually throwing errors please make sure to send error.response.status
					error instanceof AxiosError &&
					error.response?.status &&
					(error.response?.status >= 400 || error.response?.status <= 499)
				) {
					return false;
				}
				return failureCount < 2;
			},
		},
	},
});

const container = document.getElementById('root');

if (process.env.POSTHOG_KEY) {
	posthog.init(process.env.POSTHOG_KEY, {
		api_host: 'https://us.i.posthog.com',
		person_profiles: 'identified_only', // or 'always' to create profiles for anonymous users as well
	});
}

Sentry.init({
	dsn: process.env.SENTRY_DSN,
	tunnel: process.env.TUNNEL_URL,
	environment: 'production',
	integrations: [
		Sentry.browserTracingIntegration(),
		Sentry.replayIntegration({
			maskAllText: false,
			blockAllMedia: false,
		}),
	],
	// Performance Monitoring
	tracesSampleRate: 0.0, //  Capture 0% of the transactions
	// Set 'tracePropagationTargets' to control for which URLs distributed tracing should be enabled
	tracePropagationTargets: [],
	// Session Replay
	replaysSessionSampleRate: 0.1, // This sets the sample rate at 10%. You may want to change it to 100% while in development and then sample at a lower rate in production.
	replaysOnErrorSampleRate: 1.0, // If you're not already sampling the entire session, change the sample rate to 100% when sampling sessions where errors occur.
});

if (container) {
	const root = createRoot(container);

	root.render(
		<Sentry.ErrorBoundary fallback={<ErrorBoundaryFallback />}>
			<HelmetProvider>
				<QueryClientProvider client={queryClient}>
					<Provider store={store}>
						<AppRoutes />
					</Provider>
					{process.env.NODE_ENV === 'development' && (
						<ReactQueryDevtools initialIsOpen={false} position="bottom-right" />
					)}
				</QueryClientProvider>
			</HelmetProvider>
		</Sentry.ErrorBoundary>,
	);
}
