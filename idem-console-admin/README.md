# idem-console-admin — Idem 관리 콘솔 (S7 PR-2)

코어 설치본의 관리 화면. React 19 + TypeScript + Vite, UI 라이브러리 없음. 서버 쪽 인증·인가는 전부 hub 가 한다(`docs/admin-auth.md`) —
이 앱은 `/api/v1/admin/**` 을 **같은 출처**로 부른다(운영: `nginx/default.conf` 가 프록시, 개발: Vite 프록시). 세션은 HttpOnly 쿠키,
모든 쓰기 요청에 `X-Requested-With` 헤더(CSRF).

| 화면 | 하는 일 | 필요한 역할 |
|---|---|---|
| 로그인 | 비밀번호 → 2단계(TOTP). 첫 로그인은 비밀 등록(1회 표시) → 비밀번호 변경 강제 | 전부 |
| 기관 | 목록·검색, **온보딩 폼**(서비스·프로토콜(OIDC_RP/DIRECT/…)·식별자·정책·한도) 또는 JSON 전체 편집, 저장(변경 사유), 활성화/비활성화, API 키 회전(1회 표시), **표준 OIDC client** 상태·secret 회전(1회 표시), 정책 시뮬레이션, 변경 이력 | 읽기 전부 · 쓰기 SYSTEM/POLICY |
| 테넌트 | 목록, 추가·수정 | 읽기 전부 · 쓰기 전역 SYSTEM |
| 감사 | 기간·분류·사건·주체·기관·결과 필터, 상세 펼침, 쪽 이동 | 전부(테넌트 관리자는 자기 기관) |
| 관리자 | 추가(임시 비밀번호 1회 표시)·역할/테넌트/상태 변경·비밀번호 재설정·잠금 해제·2단계 초기화 | 전역 SYSTEM_ADMIN |

```bash
npm ci
npm run dev          # http://localhost:3001, /api → IDEM_HUB_URL(기본 http://localhost:8083)
npm run typecheck && npm test && npm run build   # CI 의 Frontend Build 와 같다
npm run preview      # dist/ 를 3001 에서 같은 프록시로
```

설치본에서는 `infra/docker/compose.install.yml` 의 `idem-console-admin` 컨테이너(nginx)가 `IDEM_PORT_CONSOLE`(기본 3001)로 뜬다.
쿠키 `idemAdminSid` 는 Secure 라 TLS 없는 `localhost` 밖 호스트에서는 `IDEM_ADMIN_COOKIE_SECURE=false` 가 필요하다(운영은 TLS).

- `src/lib/api.ts` — fetch 래퍼(CSRF 헤더·401 → 로그인 화면·오류 `{code,message,detail}`)
- `src/lib/profile.ts` — Service Profile JSON ↔ 폼 모델(폼 밖 키 보존), 자체 검사
- `src/pages/*` — 화면. 라우팅은 해시(`#/services/CODE`), 상태는 React 만
- `test/*.test.ts` — Vitest(Node): 프로파일 모델·API 클라이언트

KR 에디션의 회원 포털(구 `idem-console`)은 `editions/idem-kr-portal/` 이다 — 이 콘솔과 무관하다.
