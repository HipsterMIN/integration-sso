# ADR-015: 관리자 인증·인가 — 자체 계정 + TOTP 2단계, 서블릿 필터

> **상태**: ✅ 채택 (범용화 S7 PR-1, 2026-09-25)  
> **작성일**: 2026-09-25  
> **관련**: `docs/generalization-plan.md` S7, `docs/execution-plan.md` §3.1·결정 D3, `docs/admin-auth.md`, ADR-014(Keycloak 을 숨긴 OIDC 프런트)

---

## 배경

hub 의 관리 API(`/api/v1/admin/**`)는 `X-Admin-Id` 헤더(기본값 `SYSTEM`)만 읽는 무인증 API 였고, actuator 와 Handoff 취소도 열려 있었다.
GS·CC 어느 심사도 관리자 식별·인증(FIA)·보안관리(FMT)·감사 신원 연계(FAU_GEN.2) 없이는 시작할 수 없다(`cc-gs-gap-analysis.md` §3).
`execution-plan.md` 결정 D3 는 "Keycloak 관리자 realm 위임 vs 자체 계정 저장소" 를 열어 두었다.

## 결정

1. **자체 계정 저장소**(`ido.admin_user`)를 쓴다. Keycloak 위임을 택하지 않은 이유:
   - ADR-014 로 Keycloak 은 설치본 내부 구성요소가 되어 **관리 콘솔을 사람에게 노출하지 않는다**. 관리자 로그인을 Keycloak 에 맡기면
     realm 정책(비밀번호·OTP·잠금)을 Keycloak 콘솔에서 관리해야 하고, 그 콘솔이 다시 TOE 안으로 들어온다.
   - 관리자는 수 명이다. 계정·역할·테넌트 범위·잠금·비밀번호 이력·2단계 등록 상태가 **한 테이블**에 있으면 감사·시연·심사 설명이 짧다.
   - 관리자 인증 실패·잠금·권한 거부를 Idem 의 감사 로그(`event_category=ADMIN`)에 직접 남길 수 있다. Keycloak 이벤트를 끌어오는 경로가 필요 없다.
   - Keycloak 이 죽어도 관리자는 들어와 상태를 볼 수 있어야 한다(장애 대응).
2. **2단계는 TOTP(RFC 6238)** — 인증 앱 호환이 목적이라 HMAC-SHA1·6자리·30초를 그대로 따른다. SHA-1 은 `CryptoProvider.hmacSha1` 하나로만 열고
   다른 용도 사용은 `CryptoBoundaryGuardTest` 가 막는다. 비밀은 `IDEM_ADMIN_SECRET_KEY`(AES-256-GCM, AAD 고정)로 봉인해 저장한다.
   SMS·이메일 OTP 는 외부 채널 의존이라 코어에 두지 않는다.
3. **Spring Security 웹 체인 대신 `OncePerRequestFilter`(`AdminAuthFilter`) + `AdminAuthorization`** 한 쌍. hub 는 Spring Security 를 쓰지 않고
   이미 HMAC·내부 서명·API 키 필터를 자체로 갖고 있다. 관리 API 의 규칙은 경로·메서드·역할·테넌트 네 축이라 매트릭스 하나로 표현되며,
   테스트도 필터 단위로 닫힌다. 콘솔(S7 PR-2)이 붙을 때도 같은 쿠키·헤더 규약이면 충분하다.
4. **세션은 Redis**(`ido:admin:session:{sid}`) — 유휴 15분·절대 8시간·동시 1. 쿠키 `idemAdminSid` HttpOnly·Secure·SameSite=Strict.
   CSRF 는 토큰 대신 **모든 쓰기 요청에 `X-Requested-With` 헤더 필수**(브라우저는 교차 출처 단순 요청에 이 헤더를 붙일 수 없다 — 사용자 지정 헤더는
   preflight 를 강제하고 CORS 는 관리 API 를 허용하지 않는다). 로그인·2단계 요청도 같은 규칙.
5. **역할 3개**: `SYSTEM_ADMIN`·`POLICY_ADMIN`·`AUDITOR`. **테넌트 범위 최소 구현**: 관리자에 `tenant_code` 가 있으면 그 테넌트의 기관만
   본다(목록 필터·단건 403·프로파일의 `service.tenant` 검사). 관리자 관리·테넌트 쓰기는 전역 `SYSTEM_ADMIN` 만.
6. **부트스트랩**: 관리자가 0명이면 `IDEM_ADMIN_BOOTSTRAP_PASSWORD` 로 첫 `SYSTEM_ADMIN` 을 만들고 첫 로그인에서 비밀번호 변경·2단계 등록을
   요구한다. `prod`/`stage` 에서 관리자가 없고 비밀번호도 비면 기동 거부(fail-secure, D2 의 `FailSecureBootGuard` 규칙에 편입).
7. **감사**: 로그인 성공/실패/잠금, 2단계 등록/실패, 로그아웃, 비밀번호 변경, 관리자 CRUD, **인가·CSRF 거부**를 `ADMIN_*` 액션으로 남기고,
   기존 관리 행위 감사의 actor 는 인증된 사용자명이다.

## 결과

- 무인증 관리 엔드포인트 0 — 통합 테스트(`AdminAuthIntegrationTest`)와 CI 설치본 스모크(②′)가 401/403 을 확인한다.
- `X-Admin-Id` 는 코드·스크립트·설치 문서에서 사라졌다. 개발 시드·CI 스모크는 `scripts/lib/admin-login.sh` 로 실제 로그인(2단계 포함)한다.
- 잠금 카운터가 트랜잭션 롤백으로 사라지지 않도록 `login`·`verifyMfa` 는 `PlatformException` 에 대해 롤백하지 않는다(통합 테스트가 잡은 결함).
- 남긴 것: 관리 콘솔(PR-2), 관리자 접근 배너·마지막 로그인 표시(콘솔), 감사 무결성(해시체인)·유실 방지는 `execution-plan.md` §3.2 그대로,
  `/api/v1/internal/**` 는 기존 내부 서명·API 키 그대로(관리자 세션 대상 아님).

## 거부한 대안

- **Keycloak 관리자 realm 위임** — 위 1.
- **Spring Security 도입** — 필터 두 개짜리 규칙에 의존성·자동 설정 상호작용(기존 필터·CORS·오류 응답 형식)을 들이는 비용이 크다. 콘솔 OIDC 로그인이
  필요해지면 그때 재검토.
- **API 토큰(Bearer)** — 콘솔이 브라우저이므로 쿠키가 안전하다(HttpOnly). 스크립트는 같은 쿠키를 헤더로 보낸다.
