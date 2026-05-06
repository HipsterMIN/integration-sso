// ══════════════════════════════════════════════════════════════════════════════
// Axios 클라이언트 — IdO /api/** 통신
//
// BFF 책임이 ido(port 8083)로 이관됨.
//   개발: webpack-dev-server proxy → localhost:8083 (CORS 불필요)
//   운영: Nginx /api/** → ido:8083 (same origin)
// ══════════════════════════════════════════════════════════════════════════════
import axios, { AxiosError } from 'axios';

const apiClient = axios.create({
  baseURL: '/api',
  timeout: 10_000,
  withCredentials: true,   // feSessionId 쿠키 자동 첨부
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
});

// ── 요청 인터셉터: Correlation-ID 헤더 자동 삽입 ──────────────────────────
apiClient.interceptors.request.use((config) => {
  const correlationId = crypto.randomUUID();
  config.headers['X-Correlation-Id'] = correlationId;
  return config;
});

// ── 응답 인터셉터: 세션 만료(401) → 로그인 리다이렉트 ───────────────────
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      // 세션 만료 — 로그인 페이지로 이동
      window.location.href = '/login';
    }
    return Promise.reject(error);
  },
);

export default apiClient;
