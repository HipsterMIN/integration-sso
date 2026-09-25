import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// 개발·미리보기 서버는 /api 를 hub(8083) 로 프록시해 브라우저에는 같은 출처로 보이게 한다 (관리 세션 쿠키 SameSite=Strict, CORS 없음).
// 운영은 nginx/default.conf 가 같은 일을 한다.
const hub = process.env.IDEM_HUB_URL ?? 'http://localhost:8083';
const proxy = { '/api': { target: hub, changeOrigin: false } };

export default defineConfig({
  plugins: [react()],
  server: { port: 3001, proxy },
  preview: { port: 3001, proxy },
  build: { outDir: 'dist', sourcemap: false },
  test: { environment: 'node', include: ['test/**/*.test.ts'] },
});
