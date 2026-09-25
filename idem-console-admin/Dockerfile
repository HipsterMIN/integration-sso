# Idem 관리 콘솔 (S7 PR-2) — Node 빌드 → nginx 정적 서빙 + /api/v1/admin → idem-hub 프록시
# build context: 프로젝트 루트.  docker build -f idem-console-admin/Dockerfile -t idem-console-admin .
FROM node:20-alpine AS build
WORKDIR /app
COPY idem-console-admin/package.json idem-console-admin/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY idem-console-admin/ .
RUN npm run build

FROM nginx:1.27-alpine
LABEL description="Idem admin console (React + nginx, /api/v1/admin -> idem-hub)"
COPY --from=build /app/dist /usr/share/nginx/html
COPY idem-console-admin/nginx/default.conf /etc/nginx/conf.d/default.conf
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://localhost/nginx-health || exit 1
EXPOSE 80
