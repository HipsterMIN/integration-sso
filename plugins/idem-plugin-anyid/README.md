# idem-plugin-anyid — 행안부 Any-ID 설치형 브로커 플러그인

> 상태 2026-09-11: **본작업 완료(S5b, `docs/vendor-plugin-plan.md` P3)**. AnyID 브로커·SDK 래퍼·KMS 클라이언트·정적 번들·설정 파일은 모두
> 이 모듈(과 저장소 밖 vendor-libs)에 있고, `idem-hub` 코어에는 AnyID 클래스·호스트·자격증명이 없다
> (`GeneralizationGuardTest.hubCoreContainsNoVendorTokens` 가 강제).
> 선행 문서: `docs/vendor-plugin-plan.md` §2, `docs/generalization-plan.md` S5, `wiki/iam/06-install-type-integration.md`(벤더 연동 절차)

## 1. 구성

| 파일 | 역할 |
|---|---|
| `build.gradle.kts` | 벤더 SDK·자산 외부 공급(`-PvendorLibsDir` / `IDEM_VENDOR_LIBS` / `~/.idem/vendor-libs`), SDK 부재 시 `sdk` 패키지 자동 제외. 코어 의존은 전부 `compileOnly` |
| `AnyIdAutoConfiguration` | `idem.plugins.anyid.enabled=true` 일 때 활성. 코어 빈 `ObjectMapper`·`BrokerAuthCompletion`·`ResourceLoader` 만 받는다 |
| `AnyIdDefaultsEnvironmentPostProcessor` + `idem-plugin-anyid-defaults.yml` | `ido.anyid.*` 기본값과 환경변수(`ANYID_*`) 매핑을 가장 낮은 우선순위로 올린다. 코어 `application.yml` 에는 AnyID 설정이 없다 |
| `AnyIdProperties` | `ido.anyid.*` 바인딩. 운영기관 식별자(`srvc-no`·`agency-code`·`agency-name`) 3종이 비어 있으면 브로커 진입을 503 으로 거부 |
| `AnyIdBrokerAdapter` | 코어 SPI `DirectBrokerAdapter`(`id()="anyid"`, `supports()` 휴리스틱) — 인증 시작 URL(init)·verify·SSO 토큰 검증·민간ID 리다이렉트 |
| `AnyIdController` | `/api/v1/anyid/*` — initiate · callback · `{provider}/ssob` · `ssob`(FE 호환) · `txId` · `oidc/ssoLogin` · `config` · `health` |
| `SsobDecryptor` → `sdk/AnyIdSdkSsobDecryptor` | ssob 복호화 포트와 SDK(`kr.or.anyid.util.AnyidCertRef`) 구현. SDK 가 있을 때만 빈 등록, 없으면 ssob 계열 엔드포인트가 503(`ANYID_SDK_UNAVAILABLE`) |
| `AnyIdSsob` | 복호화된 ssob 필드 해석(ci·authLvl→L1/L2/L3·name), SDK 비의존 |
| `AnyIdKmsClient` | AnyID KMS(kdist, ARIA-CBC-256) 클라이언트. `ido.anyid.kms.app-key` 가 있을 때만 빈. 코어 `KmsClient` 계약 밖의 플러그인 내부 컴포넌트 |

## 2. 코어(idem-hub)와의 경계

| 코어 | 플러그인 |
|---|---|
| `BrokerService` — provider 코드로 `DirectBrokerAdapter` 선택(`provider_config.broker_mode == id()` → `supports()`) 후 위임 | 인증 시작 URL·벤더 프로토콜 |
| `DirectBrokerAuthCompletion`(`BrokerAuthCompletion` 구현) — AuthResult 저장·감사·이벤트 발행·FE 세션 발급 | ssob 복호화·CI 추출·인증 수준 정규화 후 `BrokerAuthCompletion.complete()` 호출 |
| `ObjectMapper`·`ResourceLoader` 제공 | 위 빈만 사용, 코어 서비스 클래스 참조 없음 |

## 3. 벤더 파일 공급 (저장소에 두지 않는다)

```
~/.idem/vendor-libs/
  anyid-agson-*.jar  anyid-auth-sdk-*.jar  anyid-auth-util-sdk-*.jar  anyid-bc-ref-*.jar
  kdist-api-*.jar    pid_api-*.jar
  anyid/resources/                     ← jar 루트에 그대로 복사된다
    static/anyid/{js,css,fonts,img}/…  (AnyID JS 번들 — /anyid/** 로 서빙)
    static/config/config.anyidc.json
    config/anyid/kdist-local.json
    config/anyid/pid_api.json
    config/anyid/config.anyidc.json
    config/anyid/sso-adaptor-conf-local.properties
```

- SDK 의 공개 의존성(commons-codec/configuration/lang·gson·zxing)은 Maven Central 에서 받는다. 벤더 배포본에 동봉된 사본은 쓰지 않는다.
- `config/anyid/*.json`·`sso-adaptor-conf-local.properties` 에는 기관 식별자와 **자격증명(app_key·client_info·PID clientId/Secret/ApiKey·SSO secret)** 이 들어간다.
  저장소·이슈·채팅에 값을 적지 않는다. 2026-09-11 이전 커밋에 남아 있던 값은 히스토리에 잔존하므로 벤더에 키 교체를 요청한다.
- 자격증명은 파일보다 환경변수·K8s Secret(`ANYID_SSO_SECRET_CODE`, `ANYID_KMS_APP_KEY`, `ANYID_KMS_CLIENT_INFO`, `ANYID_PID_CLIENT_ID/SECRET/API_KEY`)을 우선한다.

## 4. 전환 스위치

- 켜기/끄기: `IDEM_PLUGINS_ANYID_ENABLED`(`idem.plugins.anyid.enabled`, 기본 true — 현행 KR 배포 호환). 끄면 `DirectBrokerAdapter` 가 없어 AnyID 인증수단은 코어의 비OIDC 경로로 떨어지고 `/api/v1/anyid/*` 는 404
- 운영기관 식별자 `ANYID_SRVC_NO`·`ANYID_AGENCY_CODE`·`ANYID_AGENCY_NAME` 이 비어 있으면 플러그인이 켜져 있어도 브로커 진입은 503(`E-IDO-112`)
- 코어 에디션은 `IDEM_PLUGINS_MOCK_AUTH_ENABLED=true` 만 켠다

## 5. 게이트

- SDK 없는 환경(코어 CI): 이 모듈이 `sdk` 패키지 없이 컴파일·테스트 통과(`AnyIdAutoConfigurationTest` 가 SDK 유무를 감지해 `SsobDecryptor` 빈 유무를 확인)
- SDK 있는 환경(자체 호스팅 러너·개발 PC, vendor-libs 에 `anyid-*.jar`): `sdk` 패키지 포함 컴파일
- hub 통합 테스트: 플러그인 on 프로파일에서 코어가 `DirectBrokerAdapter`·`/api/v1/anyid/*` 를 등록하고 기동

## 6. 남은 일

- 콘솔의 `useAnyIdAuth.ts`·`AnyIdLoginModal` 은 어디서도 import 되지 않는 죽은 코드이고 SDK 스크립트(`/anyid/js/*.js`)를 로드하는 곳도 없다 — S7 콘솔 작업에서 `useAuthWidget` 로 살리거나 제거
- `provider_config` 의 AnyID 시드(V19)는 Flyway 이력이라 그대로 두고, 개명 5단계(DB 재구축)에서 KR 에디션 시드로 옮긴다
- `AnyIdKmsClient` 는 현재 호출처가 없다(종전 코어에서도 보조 빈으로만 존재). kdist 키 배포 흐름을 쓰는 시점에 계약을 다시 정한다
