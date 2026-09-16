# idem-plugin-nice-oacx — NICE 본인확인 / OACX 간편인증 플러그인

> 상태 2026-09-10: **본작업 완료(S5a)**. NICE 휴대폰 본인확인·OACX 간편인증의 벤더 코드는 모두 이 모듈에 있고, `idem-hub` 코어에는 벤더 클래스가 없다(`GeneralizationGuardTest.hubCoreContainsNoVendorTokens` 가 강제).
> 선행 문서: `docs/vendor-plugin-plan.md` §2·P2, `docs/identity-provider-spi.md`, `docs/generalization-plan.md` S5

## 1. 구성

| 파일 | 역할 |
|---|---|
| `build.gradle.kts` | 벤더 SDK 외부 공급(`-PvendorLibsDir` / `IDEM_VENDOR_LIBS` / `~/.idem/vendor-libs`), SDK 부재 시 `oacx` 패키지 자동 제외. 코어 의존은 전부 `compileOnly`(hub 가 준다) |
| `NiceOacxAutoConfiguration` | `idem.plugins.nice-oacx.enabled=true` 일 때 활성. 코어 빈 `RedisTemplate<String,String>`·`RedissonClient`·`ObjectMapper` 만 받아 기본 게이트웨이(`NicePhoneService`)를 만든다. `NicePhoneGateway` 빈을 따로 주면 그것을 쓴다(테스트·대체 구현) |
| `NiceProperties` / `OacxProperties` | `ido.auth.nice.*`(clientId·clientSecret·returnUrl·timeoutSeconds·baseUrl) / `ido.auth.oacx.*`. 키 이름은 4b 개명까지 유지 — 환경변수 `NICE_CLIENT_ID` 등 그대로 |
| `NiceCredentialsValidator` | `prod` 프로파일에서 자격증명 누락 시 기동 실패(`ido.auth.allow-missing-credentials=true` 로 완화) |
| `NicePhoneGateway` → `NicePhoneService` | NICE API 포트와 기본 구현: 토큰 캐시(`NiceTokenStore`, Redisson 분산 락으로 갱신)·세션(`NiceAuthSessionStore`)·`NiceApiClient`(WebClient + Resilience4j)·`NiceCryptoUtil`(PBKDF2/HMAC/AES-GCM). registry 등록·감사는 하지 않는다 — 코어 몫 |
| `NicePhoneIdentityVerificationProvider` | SPI 구현 `NICE_PHONE`(L2, EzAuth 위젯 기술자). `complete` 는 CI 스킴 `VerifiedIdentity`(속성 `nationalInfo`·`di`) |
| `oacx/OacxClientAdapter`, `oacx/OacxEasySignIdentityVerificationProvider` | SPI 구현 `OACX_EASYSIGN`. `import OACX.*` 는 이 패키지에만. `OACX.OacxUtil` 이 클래스패스에 있을 때만 빈 등록 |
| `NiceEzAuthWidget` | 위젯 기술자 상수 (`/plugins/nice-oacx/ezauth/js/EzAuth.bundle.js`, 전역 `EzAuth`) |
| `static/plugins/nice-oacx/` | EzAuth 자산이 들어올 자리(아직 `idem-console/frontend/public/ezauth` 에 있음) |

실패 코드는 `IdentityVerificationException(providerCode, reasonCode, message)` 의 `reasonCode` 로 나간다: `4000`(인증 미완료/세션 없음) · `4001`(OACX 결과 실패) · `5001`(벤더 API 호출 실패) · `5002`(복호화·CI 없음) · `5003`(무결성 검증 실패) · `5000`(토큰 갱신 락 실패). hub 의 레거시 프록시는 이 값을 종전 `resultCode` 로 그대로 돌려준다.

## 2. 코어(idem-hub)와의 경계

| 코어 | 플러그인 |
|---|---|
| `POST /api/v1/auth/providers/{code}/initiate\|complete`(`IdentityVerificationController`) · 감사(`AUTH_PROVIDER_*`) · 추적 스팬 · `SubjectRegistrationService`(registry 등록) | 벤더 API 호출·암복호화·토큰/세션 캐시 |
| `auth/legacy/LegacyVendorAuthController`(`@Deprecated`, `/api/v1/auth/nice/*`·`/oacx/*`) — 콘솔 훅·k6 가 SPI 경로로 옮겨간 뒤 삭제 | — |
| `RedisTemplate<String,String>`·`RedissonClient`·`ObjectMapper` 빈 제공 | 위 빈만 사용, 코어 서비스 참조 없음 |

## 3. 전환 스위치

- 켜기/끄기: `IDEM_PLUGINS_NICE_OACX_ENABLED`(`idem.plugins.nice-oacx.enabled`, 기본 true — 현행 KR 배포 호환). 끄면 `NICE_PHONE`·`OACX_EASYSIGN` 제공자가 등록되지 않고 레거시 프록시는 `5001` 을 돌려준다
- 코어 에디션은 `IDEM_PLUGINS_MOCK_AUTH_ENABLED=true` 만 켠다

## 4. 게이트

- SDK 없는 환경(코어 CI): 이 모듈이 `oacx` 패키지 없이 컴파일·테스트 통과(`NiceOacxPluginTest` 는 SDK 유무를 감지해 OACX 빈 유무를 확인)
- SDK 있는 환경(자체 호스팅 러너, `vendor-libs` 에 `OACX-SDK-*.jar`): `oacx` 패키지 포함 컴파일 + OACX 계약 테스트
- hub 통합 테스트 `NiceAuthIntegrationTest`: 플러그인 on 프로파일에서 WireMock NICE 를 거쳐 SPI initiate 와 레거시 `/nice/phone/url` 이 같은 흐름으로 200

## 5. 남은 일

- EzAuth 자산(`idem-console/frontend/public/ezauth`, 109 파일) → `static/plugins/nice-oacx/ezauth/`, FE `useEzAuth` → `useAuthWidget(providerCode)` — S7 콘솔 작업과 함께
- NICE 로부터 OACX SDK 최신판 재수령 후 실검증
