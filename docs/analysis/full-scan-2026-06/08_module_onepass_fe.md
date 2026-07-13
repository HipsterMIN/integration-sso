# 08 · Module Deep Dive — onepass-fe (React SPA · Single Channel)

> 원천: `/home/user/webapp/onepass-fe/frontend/` · 정본 `docs/internal/spec/03f-module-onepass-fe.md` (PR #202/#203/#204 반영).
> 스택: React + TypeScript + Webpack · Jest 테스트 · yarn.

---

## 1. 삼각 요약

| 항목 | 값 |
|---|---|
| 포트 | 3000 (dev) / 80·443 (nginx prod) |
| 기술 | React + TSX, Webpack, Jest, i18n |
| 정본 헌법 | **ADR-008** — FE 군 ↔ IdO 단일 채널 |
| 정본 데이터흐름 | `03f-module-onepass-fe.md` (PR #202 신설, #204 최종 정합) |
| Phase 2 rename | PR #203 — `BE_*` → `IDO_*` |

---

## 2. 프론트엔드 트리 (`src/`)

```
onepass-fe/frontend/src/
├── AppRoutes/       — React Router 라우팅
├── ReactI18/        — i18n 리소스
├── api/             — IdO REST 호출 클라이언트 (Single Channel 원칙)
├── assets/          — 정적 이미지·아이콘
├── components/      — 공통 컴포넌트
├── constants/       — 상수 (IDO_* 심볼)
├── container/       — 페이지 컨테이너
├── hooks/           — 커스텀 훅
├── index.tsx        — 엔트리
├── lib/             — 유틸 라이브러리
├── pages/           — 라우트별 페이지
├── providers/       — Context Provider
├── setupProxy.js    — dev-server 프록시 (→ IdO)
├── store/           — 상태 관리 (Redux/Context)
├── types/           — TS 타입 정의
└── utils/           — 유틸 함수
```

---

## 3. ADR-008 헌법 (3단 명제)

**PR #202 `5fff02a`** 로 헌법화:

1. **책임 종류** — FE 는 UI 만. 서버 상태·인가·감사는 IdO.
2. **모듈 군** — onepass-fe 는 Q-Sign/Q-IM/기관 API 직접 호출 **금지**.
3. **단일 게이트웨이** — 모든 데이터/부작용 트랜잭션은 IdO 경유.

---

## 4. `IDO_*` 심볼 규약 (PR #203 `a7065ae`)

**Before**: `BE_LOGIN_URL`, `BE_AUTH_TOKEN`, `BE_HANDOFF_URL`, ...
**After**: `IDO_LOGIN_URL`, `IDO_AUTH_TOKEN`, `IDO_HANDOFF_URL`, ...

**이유**: "BE" 는 다중 백엔드 착시 → "IDO" 는 단일 목적지 명시.

**PR #204 `63fa851`**: 후속 cross-ref 정합화 — `03f`, `DEVELOPMENT.md`, `09 §6.A.1` 동기.

---

## 5. FE ↔ IdO 통신 원칙

| 원칙 | 구체 |
|---|---|
| 인증 상태 | fe-session-id 쿠키 (HttpOnly, Secure, SameSite=Lax) |
| 서버 상태 조회 | GET `/api/v1/*` → IdO |
| 외부 프록시 | GET/POST `/api/ext/**` → IdO E-역할 → 기관 |
| 인가 헤더 위조 방지 | FE는 `X-Authz-*` 를 절대 전송하지 않음 (전송해도 IdO 가 삭제) |
| Handoff 요청 | POST `/api/v1/handoff` (Idempotency-Key 필수) |
| SSE / WebSocket | (정본 정의 없음, 필요 시 IdO 경유) |

---

## 6. 정본 데이터 흐름 (03f 요약)

`docs/internal/spec/03f-module-onepass-fe.md` 는 다음 흐름을 정본화:
1. 최초 진입: `GET /api/v1/fe-session` → fe-session-id 쿠키
2. 로그인: `POST /api/v1/auth/*` → IdO → Q-Sign OIDC
3. 사용자 조회: `GET /api/v1/members/lookup` → IdO → Q-IM
4. Handoff 발급: `POST /api/v1/handoff` → SP redirect
5. 기관 API 호출: `/api/ext/**` → IdO E-역할 프록시
6. 로그아웃: `POST /api/v1/auth/logout` → IdO → 전 채널 세션 종료

---

## 7. 빌드/테스트 인프라

| 파일 | 역할 |
|---|---|
| `package.json` | 의존성 + 스크립트 |
| `webpack.config.js` / `webpack.config.prod.js` | dev / prod 빌드 |
| `babel.config.js` | Babel |
| `jest.config.js` / `jest.config.ts` / `jest.setup.ts` | Jest |
| `bundlesize.config.json` | 번들 사이즈 예산 |
| `commitlint.config.ts` | 커밋 메시지 lint |
| `tsconfig.json` | TypeScript 컴파일 |
| `i18-generate-hash.js` + `i18n-translations-hash.json` | i18n 리소스 해시 |
| `__mocks__/` | Jest 목 |

**CI**: `ci.yml` `frontend-check` 잡에서 `tsc --noEmit + webpack build` 수행. **주의**: 과거 `9f2ede1` 에서 tsc 오류 임시 마스킹(continue-on-error) — PR #196 (`8f3be29`) 로 14건 정리 후 `cf56f0d` 로 마스킹 제거.

---

## 8. Phase 2 완료 · Phase 3 대기 (SEC-IDO-01~15)

정본 `09-gap-and-roadmap.md` §Phase:

| 단계 | 상태 | 내용 |
|---|---|---|
| Phase 1 (기반) | 완료 | 컴포넌트 분리, fe-session-id 표준화 |
| **Phase 2 (rename + 문서)** | **완료** (PR #203/#204) | `BE_*` → `IDO_*`, 03f cross-ref |
| **Phase 3 (감사 + 정책)** | **대기** | 감사 로그·CSP 강화·CORS 축소 |

---

## 9. 관찰된 리스크

| # | 항목 | 위험 | 근거 |
|---|---|---|---|
| L1 | Phase 3 CSP/CORS 강화 미완 | 🟡 MED | 09 gap |
| L2 | 번들 사이즈 예산 회귀 감시 필요 | 🟢 LOW | bundlesize.config.json |
| L3 | q-authz `X-Authz-*` 헤더는 FE 가 사용 불가(=IdO 만) — 문서 재확인 필요 | 🟢 LOW | ExtProxyController anti-spoof |
| L4 | 새로운 IDO_* 심볼 이후 lint rule (BE_* 금지) 자동화 여부 | 🟡 MED | commitlint/eslint 확인 필요 |

---

## 10. 참조
- 소스: `/home/user/webapp/onepass-fe/frontend/src/`
- 정본: `docs/internal/spec/03f-module-onepass-fe.md`
- 헌법: `wiki/adr/` 하위 ADR-008 (or `docs/internal/architecture/`)
- 관련 PR: #202/#203/#204
