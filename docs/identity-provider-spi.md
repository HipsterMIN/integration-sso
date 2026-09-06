# 본인인증 SPI (IdentityVerificationProvider) — P1

> 작성 2026-09-05 · 플랜: `vendor-plugin-plan.md` §2.1·P1 · 상태: 코어 SPI + Mock 플러그인 + NICE 어댑터 완료

## 1. 무엇이 바뀌었나

코어(idem-hub)는 이제 "누가 사람을 인증하는가"를 `IdentityVerificationProvider` 인터페이스로만 안다.

| 구성요소 | 위치 | 역할 |
|---|---|---|
| SPI 계약 | `idem-common` `kr.go.smes.common.spi.identity` | `IdentityVerificationProvider`, `VerificationRequest/Start/Callback`, `VerifiedIdentity`, `AuthWidgetDescriptor`, `IdentityVerificationException`, `IdentityProviderRegistry` (Spring 비의존) |
| 레지스트리 빈 | `idem-hub` `auth/spi/IdentityProviderRegistryConfig` | 부팅 시 발견된 모든 제공자 빈을 코드별로 등록. 코드 중복은 부팅 실패 |
| 표준 엔드포인트 | `idem-hub` `auth/spi/IdentityVerificationController` | `GET /api/v1/auth/providers`, `POST /api/v1/auth/providers/{code}/initiate`, `POST …/{code}/complete` |
| NICE 어댑터 | `idem-hub` `auth/spi/NiceIdentityVerificationProvider` | 코드 `NICE_PHONE`, 등급 L2. 기존 `NiceAuthService` 를 감싼다. P2 에서 플러그인으로 이동 |
| Mock 플러그인 | `plugins/idem-plugin-mock-auth` | 코드 `MOCK`, 등급 L1. `idem.plugins.mock-auth.enabled=true` 일 때만 활성 |

기존 벤더별 엔드포인트(`/api/v1/auth/nice/phone/*`, `/oacx/*`)는 그대로 동작한다. FE 는 당장 바꿀 필요가 없고, P2 에서 `useAuthWidget(providerCode)` 로 전환한다.

## 2. 계약

```java
public interface IdentityVerificationProvider {
    String code();                                        // "NICE_PHONE", "MOCK", …  (대문자·밑줄)
    AuthResult.AuthLevel level();                         // L1 / L2 / L3
    VerificationStart initiate(VerificationRequest req);  // redirectUrl 또는 위젯 파라미터
    VerifiedIdentity complete(VerificationCallback cb);   // 표준 결과, 실패 시 IdentityVerificationException
    default Optional<AuthWidgetDescriptor> widget();      // 위젯형 제공자만
}
```

- `VerifiedIdentity.subjectKey` 가 동일인 판정 키다. NICE 어댑터는 DI 를 넣는다(CI 를 FE 로 돌려주지 않는 기존 설계 유지). 범용 코어는 이메일·전화·외부 IdP `sub` 등을 넣을 수 있고, 판정 로직은 범용화 로드맵의 `IdentityResolver` 몫이다.
- 개인정보 원문(CI 등)은 `attributes` 에 넣지 않는다.
- 제공자 구현은 벤더 오류를 `IdentityVerificationException(providerCode, reasonCode, message)` 로 통일한다. 컨트롤러가 `E-IDO-110`(400) 으로 변환하고, 미등록 코드는 `E-IDO-109`(404) 다.

## 3. 표준 엔드포인트

```http
GET /api/v1/auth/providers
→ [ { "code": "NICE_PHONE", "level": "L2", "widget": null }, { "code": "MOCK", "level": "L1", "widget": null } ]

POST /api/v1/auth/providers/MOCK/initiate
{ "returnUrl": "https://fe/return", "params": { "name": "홍길동", "phone": "01012345678" } }
→ { "providerCode": "MOCK", "txId": "mock-…", "redirectUrl": "https://fe/return?mockTxId=mock-…", "params": {…} }

POST /api/v1/auth/providers/MOCK/complete
{ "txId": "mock-…", "params": {} }
→ { "providerCode": "MOCK", "txId": "mock-…", "subjectKey": "mock:…", "name": "홍길동", "birthDate": "19900101",
    "gender": "1", "phone": "01012345678", "phoneCarrier": "MOCK", "level": "L1", "verifiedAt": "…", "attributes": {"mock":"true"} }
```

NICE 의 경우 `complete.params.web_transaction_id` 가 필수이고 `txId` 는 initiate 가 돌려준 `requestNo` 다.

## 4. Mock 플러그인 사용

| 환경 | 설정 |
|---|---|
| 로컬 | `IDEM_PLUGINS_MOCKAUTH_ENABLED=true` (또는 `idem.plugins.mock-auth.enabled=true`) |
| CI k6 스모크 | `ci.yml` smoke-test 잡 env 에 설정됨. `k6/scenarios/smoke.js` 가 목록·initiate·complete 라운드트립을 검증 |
| 운영 | 설정하지 않는다. 기본값은 비활성이며 `MockAuthAutoConfigurationTest` 가 이를 보증 |

Mock 동작: `params.fail=true` 로 실패 경로, `params.subjectKey` 로 동일인 키 고정, 같은 name/birthDate/phone 은 같은 `subjectKey` 를 만든다. 트랜잭션은 메모리 10분 TTL, 1회용.

## 5. 새 제공자(플러그인) 만들기 — 최소 절차

1. `plugins/idem-plugin-<id>/` 모듈 생성, `api(project(":idem-common"))` + `spring-boot-autoconfigure` 의존.
2. `IdentityVerificationProvider` 구현. 벤더 SDK 는 저장소 밖 `vendor-libs`(`-PvendorLibsDir`, `IDEM_VENDOR_LIBS`, 기본 `~/.idem/vendor-libs`)에서 `compileOnly`/`runtimeOnly` 로 읽고, SDK 부재 시 해당 패키지를 `sourceSets` 에서 제외한다 — 구현 예: `plugins/idem-plugin-nice-oacx/build.gradle.kts`.
3. `@AutoConfiguration` + `@ConditionalOnProperty("idem.plugins.<id>.enabled")` (+ `@ConditionalOnClass(벤더 진입 클래스)`) 로 빈 등록, `META-INF/spring/…AutoConfiguration.imports` 에 등재.
4. `settings.gradle.kts` include + `projectDir` 매핑, 각 서비스 Dockerfile deps 스테이지에 `build.gradle.kts` COPY 추가 (Gradle 9 는 include 된 모듈 디렉터리가 없으면 설정 단계에서 실패).
5. 코드 중복 금지 — 레지스트리가 부팅 시 검출한다.

정식 가이드(계약 테스트 킷 포함)는 P4 에서 작성한다.
