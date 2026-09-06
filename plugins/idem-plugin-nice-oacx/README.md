# idem-plugin-nice-oacx — NICE/OACX/EzAuth 플러그인 (P2 골격)

> 상태 2026-09-06: **골격**. 실제 벤더 코드는 아직 `idem-hub` 에 있다. 이 문서의 이동표대로 P2 본작업을 진행한다.
> 선행: `docs/vendor-plugin-plan.md` §2·P2, `docs/identity-provider-spi.md`

## 1. 골격에 포함된 것

| 파일 | 역할 |
|---|---|
| `build.gradle.kts` | 벤더 SDK 외부 공급(`-PvendorLibsDir` / `IDEM_VENDOR_LIBS` / `~/.idem/vendor-libs`), SDK 부재 시 `oacx` 패키지 자동 제외 |
| `NiceOacxAutoConfiguration` | `idem.plugins.nice-oacx.enabled=true` 일 때 활성. NICE 제공자는 `NicePhoneGateway` 빈이 있을 때, OACX 제공자는 `OACX.OacxUtil` 클래스가 있을 때만 등록 |
| `NicePhoneGateway` | NICE API 포트(플러그인 내부 계약). 본작업에서 `NiceAuthService`/`NiceApiClient` 가 이 뒤로 이동 |
| `NicePhoneIdentityVerificationProvider` | SPI 구현 (`NICE_PHONE`, L2, EzAuth 위젯 기술자 포함) |
| `oacx/OacxEasySignIdentityVerificationProvider` | SPI 구현 골격 (`OACX_EASYSIGN`). SDK 재수령 후 구현 |
| `NiceEzAuthWidget` | 위젯 기술자 상수 (`/plugins/nice-oacx/ezauth/js/EzAuth.bundle.js`, 전역 `EzAuth`) |
| `static/plugins/nice-oacx/` | EzAuth 자산이 들어올 자리 |

## 2. 이동표 (P2 본작업)

| 현재 위치 (idem-hub) | 이동 후 (플러그인) | 비고 |
|---|---|---|
| `auth/service/NiceAuthService.java` (499줄) | `NicePhoneGatewayImpl` (NicePhoneGateway 구현) | 토큰 캐시(`NiceTokenStore`)·Redisson 분산락·`NiceCryptoUtil` 함께 이동. Redis/Redisson 은 코어가 제공하는 빈을 주입받는다 |
| `auth/client/NiceApiClient.java` | 플러그인 내부 | WebClient + Resilience4j. `AuthWebClientConfig` 의 NICE 부분 분리 |
| `auth/dto/nice/*`, `auth/dto/NicePhone*` | 플러그인 내부 DTO | 외부 노출 형식은 SPI 의 `VerifiedIdentity` 로 대체 |
| `auth/client/OacxClient.java` | `oacx/OacxClientAdapter` | `import OACX.*` 는 oacx 패키지에만. SDK 최신판 재수령 후 |
| `AuthController` 의 `/nice/phone/url`, `/nice/phone/result`, `/oacx/access-info`, `/oacx/easysign` | 삭제 → `/api/v1/auth/providers/{code}/initiate|complete` 로 통일 | FE 전환과 동시 |
| `auth/spi/NiceIdentityVerificationProvider` (P1 코어 어댑터) | 삭제 | 플러그인 제공자가 같은 코드(`NICE_PHONE`)를 맡음 |
| `idem-console/frontend/public/ezauth/**` (109파일) | `static/plugins/nice-oacx/ezauth/` | FE `useEzAuth` → `useAuthWidget(providerCode)` |
| `application.yml` `ido.auth.nice.*`, `ido.auth.oacx.*` | `idem.plugins.nice-oacx.*` | 환경변수명은 유지(`NICE_CLIENT_ID` 등) |
| `idem-hub/libs/OACX-SDK-v1.3.2.jar` | 삭제 → vendor-libs 외부 공급 | 공개 준비 B2 |

## 3. 전환 스위치

- 플러그인 켜기: `IDEM_PLUGINS_NICEOACX_ENABLED=true`
- 코어 어댑터 끄기(코드 충돌 방지): `ido.auth.nice.provider-enabled=false`
- 두 스위치를 함께 바꾼다. 레지스트리가 코드 중복을 부팅 시 검출하므로 실수하면 기동이 실패한다.

## 4. 게이트

- SDK 없는 환경(코어 CI): 이 모듈이 `oacx` 패키지 없이 컴파일·테스트 통과
- SDK 있는 환경(자체 호스팅 러너, `vendor-libs` 에 `OACX-SDK-*.jar`): `oacx` 패키지 포함 컴파일 + OACX 계약 테스트
- 플러그인 on/off 두 프로파일에서 idem-hub 기동 + k6 스모크
