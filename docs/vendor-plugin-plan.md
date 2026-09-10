# Idem 벤더 독립화·플러그인 아키텍처 플랜

> 작성 2026-09-05 · 상태 v0.1 (제안) · 선행: `docs/naming.md`(개명), `docs/open-source-readiness.md`(공개 준비 B1~B4)

## 0. 요약

코어(idem-hub 등)는 **벤더 SDK 없이 빌드·테스트·기동**되어야 하고, NICE/OACX·AnyID 같은 벤더 연동은 **플러그인 모듈**로 붙인다. 벤더 바이너리는 저장소에 두지 않는다. 기존에 이미 SPI 형태인 `KmsClient`, `HandoffStrategy`, `IdpBrokerService`, `ProviderRouter` 를 그대로 확장하므로 새 개념은 두 가지뿐이다: **본인인증 SPI**(`IdentityVerificationProvider`)와 **위젯 SPI**(`AuthWidgetDescriptor`). 6단계(P0~P5)로 진행하며 P0는 지금 바로, P1이 끝나면 코어가 Mock 플러그인만으로 CI를 통과한다.

## 1. 현재 결합 실측 (2026-09-05)

| 벤더 자산 | 저장소 내 위치 | 코어 결합 지점 | 결합 강도 |
|---|---|---|---|
| **OACX SDK** v1.3.2 (NICE 전자서명 중계) | `idem-hub/libs/OACX-SDK-v1.3.2.jar` | `auth/client/OacxClient.java` 1파일 (`import OACX.OacxUtil/OacxException`) + `AuthController` 의 `easysign`·`access-info`·`ci-check`·`ci-token` 엔드포인트 | 낮음 — import 1파일. 단, 버전이 오래되어 NICE 재수령 전까지 동작 보증 불가 |
| **NICE 본인인증** (HTTP API, SDK 없음) | `auth/client/NiceApiClient`, `auth/service/NiceAuthService`(494줄), `auth/dto/nice/*` | `AuthService` 가 NICE 결과 DTO 를 직접 사용, `auth/dto` 9파일이 NICE 필드명에 맞춰짐 | 중간 — SDK 는 없지만 도메인 DTO 가 벤더 형식 |
| **AnyID SDK** (auth 1.0.19, util, agson, bc-ref, kdist 1.0.12, pid 1.0.38) + 부속 (commons-codec/configuration/lang, gson 2.8.6, zxing core/javase) | `idem-hub/libs/*.jar` 13개 | `broker/anyid/AnyIdSsobService.java` 1파일 (`import kr.or.anyid.auth.AnyidAuth`, `AnyidCertRef`), `broker/anyid` 5클래스, `crypto/kms/AnyIdKmsClient`(이미 KmsClient SPI 구현), 설정 `anyid.*`(application.yml 272~) | 중간 — import 1파일이지만 정적 자산·KMS·설정이 함께 묶임 |
| **AnyID 프런트 번들** | `idem-hub/src/main/resources/static/anyid/` 10파일 (`vendor.js` 1MB, RSA private key 블록 포함) | `fe/config` 에서 정적 서빙 | 낮음 — 서빙 경로만 |
| **EzAuth 위젯** (간편인증 JS) | `idem-console/frontend/public/ezauth/` 109파일 | `hooks/useEzAuth.ts`, `index.html.ejs` 의 `<script src="/ezauth/js/EzAuth.bundle.js">` | 중간 — 전역 `window.EzAuth` 계약에 FE 코드가 직접 의존 |
| **xecure7.jar** | `idem-hub/libs/` | Java import 0건 | 없음 — 즉시 제거 가능 |
| 벤더 개발 자격증명 | `idem-hub/src/main/resources/application-local.yml` (7개 키, 평문) | 로컬 프로파일 | 공개 차단 항목 B1 |

이미 있는 확장점: `KmsClient`(구현 5종), `HandoffStrategy`(4종), `IdpBrokerService.initiateAuth(providerCode, …)/normalizeResponse(…)`, `broker/provider/ProviderRouter` + `ido.provider_circuit_config(provider_code)` 테이블. 즉 **"어느 IdP·어느 KMS·어느 기관 연동"은 코드로 갈아끼울 수 있는데, "어느 본인인증 벤더"만 갈아끼울 수 없는 상태**다.

## 2. 목표 아키텍처

### 2.1 코어 SPI (`idem-common` 의 `spi` 패키지, 4단계 패키지 이동 전까지 `io.github.hipstermin.idem.common.spi`)

```java
public interface IdentityVerificationProvider {          // 본인인증
    String code();                                         // "NICE", "OACX_EASYSIGN", "ANYID", "MOCK", 향후 "TOSS", "PERSONA" …
    AssuranceLevel level();                                // L1~L3 — 기존 auth_method/level 매핑을 계약에 포함
    VerificationStart initiate(VerificationRequest req);   // 리다이렉트 URL 또는 위젯 파라미터
    VerifiedIdentity complete(VerificationCallback cb);    // 표준 결과: subjectKey(CI 대체 가능), name, birth, phone, provider, level, raw
    Optional<AuthWidgetDescriptor> widget();               // FE 위젯이 필요한 벤더만
}
public record AuthWidgetDescriptor(String scriptUrl, String globalName, Map<String,Object> initParams) {}
```

- `VerifiedIdentity.subjectKey` 가 **동일인 판정 키**다. KR 에디션은 CI 를 넣고, 범용 코어는 이메일·전화·외부 IdP `sub` 등 다른 키를 넣는다. 범용화 로드맵 2단계의 `IdentityResolver` 와 접점이며, 이 플랜에서는 SPI 계약에 필드만 두고 판정 로직은 건드리지 않는다.
- `IdpBrokerService` 는 그대로 두고, AnyID SSOB 같은 브로커형 IdP 는 플러그인이 `IdpBrokerAdapter` 구현체를 등록한다.
- `KmsClient` 는 이미 SPI. `AnyIdKmsClient` 만 플러그인으로 옮긴다.

### 2.2 플러그인 모듈

```
plugins/
  idem-plugin-mock-auth/       # 개발·CI 기본. 항상 코어와 함께 빌드
  idem-plugin-nice-oacx/       # NICE 본인인증 + OACX 전자서명 + EzAuth 위젯 자산
  idem-plugin-anyid/           # AnyID SSOB 브로커 + AnyIdKmsClient + anyid 정적 번들
```

- 각 플러그인은 Spring Boot **AutoConfiguration**(`META-INF/spring/…AutoConfiguration.imports`) + `@ConditionalOnProperty("idem.plugins.<id>.enabled")` + `@ConditionalOnClass(벤더 진입 클래스)` 로 활성화된다. 코어는 `List<IdentityVerificationProvider>` 를 주입받아 `ProviderRegistry` 에 code 별로 등록하고, 기존 `ProviderRouter`/`provider_circuit_config` 가 그 code 로 라우팅한다.
- 벤더 jar 는 플러그인 모듈에서만 `compileOnly` + `runtimeOnly` 로 참조하고, 경로는 Gradle 속성 `-PvendorLibsDir=…`(기본 `$HOME/.idem/vendor-libs`) 로 받는다. 저장소에는 `**/vendor-libs/` 를 `.gitignore` 한다. SDK 가 없으면 해당 플러그인 모듈은 **빌드에서 자동 제외**(`settings.gradle.kts` 에서 존재 여부로 include)되므로 코어 CI 는 벤더 없이 통과한다.
- FE: `useEzAuth` 를 `useAuthWidget(providerCode)` 로 일반화. 위젯 스크립트 URL 과 전역명은 코어 API `GET /api/v1/auth/providers` 가 `AuthWidgetDescriptor` 로 내려주고, 스크립트 자산은 플러그인 jar 의 `static/plugins/<id>/` 에서 서빙한다. `index.html.ejs` 의 고정 `<script>` 태그는 제거한다.

### 2.3 설정·배포

- 설정 스키마: `idem.plugins.<id>.enabled`, `idem.auth.providers.<CODE>.plugin=<id>`, 자격증명은 **환경변수·Secret 만**(`IDEM_PLUGIN_NICE_CLIENT_ID` 등). `application-local.yml` 의 평문 키는 P0 에서 제거한다.
- 이미지: 코어 이미지(`idem-hub`) 는 mock 만 포함. 에디션 이미지(`idem-hub:kr-public`) 는 빌드 시 `plugins/` 를 `/app/plugins/` 에 넣고 `loader.path` 로 로드하거나, Helm `plugins: [nice-oacx, anyid]` 로 init-container 가 사설 레지스트리에서 받아 넣는다. 둘 중 하나는 P4 에서 결정.
- 에디션 정의: **Idem Core**(플러그인 0, mock) / **Idem KR Public Edition**(nice-oacx, anyid). 향후 `idem-plugin-toss`, `idem-plugin-persona` 같은 제3자 플러그인도 같은 계약으로 붙는다.

## 3. 단계

| 단계 | 내용 | 산출물 | 게이트 | 규모 |
|---|---|---|---|---|
| **P0 즉시 정리** ✅ 2026-09-05 | `xecure7.jar` 삭제 · `application-local.yml` 벤더키를 env 플레이스홀더로 교체하고 벤더에 키 교체 요청 · `idem-console/frontend/.env` untrack · OACX 구버전 리스크 기록 | 커밋 1건, 공개 준비 B1·B4 해소 | 로컬 프로파일 기동 확인 | S |
| **P1 SPI + Mock** ✅ 2026-09-05 (`identity-provider-spi.md`) | `IdentityVerificationProvider`/`VerifiedIdentity`/`AuthWidgetDescriptor`/`ProviderRegistry` 정의 · `idem-plugin-mock-auth` · `NiceAuthService` 를 SPI 어댑터 뒤로 이동(아직 코어 안) · `/api/v1/auth/providers` API | SPI 문서, mock 플러그인 | **코어가 mock 만으로 기동·k6 통과**, 기존 NICE 통합 테스트 그린 | M |
| **P2 NICE/OACX 플러그인** 🔧 골격 2026-09-06 (`plugins/idem-plugin-nice-oacx/README.md` 이동표) · 본작업은 OACX SDK 재수령 후 | `OacxClient`·`NiceApiClient`·`NiceAuthService`·`dto/nice`·OACX 엔드포인트·EzAuth 자산을 `idem-plugin-nice-oacx` 로 이동 · OACX SDK 를 vendor-libs 외부 공급 · `useAuthWidget` 전환 | 플러그인 모듈, FE 훅 | 플러그인 on/off 두 프로파일 통합 테스트, 공개 준비 B2 일부·B3(EzAuth) 해소 | M |
| **P3 AnyID 플러그인** | `broker/anyid` 5클래스·`AnyIdKmsClient`·`static/anyid`·SDK 13 jar 를 `idem-plugin-anyid` 로 이동 · `vendor.js`(RSA key) 저장소 제거 | 플러그인 모듈 | AnyID 브로커·KMS 회귀 테스트, B2·B3 완전 해소 | M~L |
| **P4 설정·배포·문서** | 설정 스키마 통일 · Helm `plugins` · 코어/KR 이미지 변형 · **플러그인 작성 가이드**(새 벤더를 붙이는 절차, 계약 테스트 킷) | 가이드, Helm, CI 매트릭스(core / kr-public) | 두 이미지 모두 Docker Build·k6 통과 | M |
| **P5 공개** | `idem-hub/libs/` 삭제 · 히스토리 없는 스냅샷 공개 저장소 · KR 플러그인은 사설 저장소 `idem-plugins-kr` 로 분리 | 공개 저장소 | `docs/open-source-readiness.md` 체크리스트 전항 통과 | S |

의존: P0 → P1 → (P2 ∥ P3) → P4 → P5. P2 는 NICE 로부터 OACX SDK 최신판을 다시 받은 뒤에 플러그인 검증이 가능하다. 그 전까지는 플러그인 골격만 만들고 실제 호출은 mock 으로 대체한다.

## 4. 리스크와 대응

- **OACX v1.3.2 구버전**: 최신 SDK 재수령 전에는 동작 보증 불가. P2 에서 SDK 버전을 플러그인 속성으로 분리하고, 계약 테스트는 mock 응답으로 작성한다.
- **AnyID SDK 재배포 조건**: 사설 저장소·vendor-libs 외부 공급으로 저장소에서 완전히 분리. 라이선스 조건은 P3 착수 전 확인.
- **테스트 커버리지 공백**: 벤더 없는 CI 는 mock 만 검증한다. 벤더 플러그인은 SDK 가 있는 자체 호스팅 러너(`CI_HEAVY_RUNNER`) 또는 사설 저장소 CI 에서 별도 매트릭스로 돌린다.
- **provider_code 하위호환**: 기존 코드(`PASS`, `KAKAO`, `NAVER`, `KAKAO_OIDC`, ANYID 계열)와 DB `provider_circuit_config` 는 유지. 새 SPI 는 같은 code 체계를 쓴다.
- **FE 위젯 로딩 순서**: 고정 `<script>` 제거 후 동적 로딩으로 바뀌므로 `useAuthWidget` 이 로드 완료를 보장해야 한다. P2 에서 EzAuth 로 검증.
- **Flyway**: 마이그레이션 변경 없음. 새 테이블·컬럼은 P1 에서 필요 시 V-신규로만 추가.

## 5. 결정이 필요한 사항

1. 플러그인 위치: 모노레포 `plugins/`(권장, P2~P4) → 공개 시점에 KR 플러그인만 사설 저장소로 분리.
2. 벤더 jar 공급: 로컬 `vendor-libs` 경로(간단) vs GitHub Packages 사설 Maven(팀 공유). 1인 개발 단계에서는 전자로 시작.
3. NICE OACX SDK 최신판 재수령 시점 — P2 의 실검증 일정을 정한다.
4. 코어 기본 본인인증: mock 만 둘지, 이메일 OTP 같은 범용 구현을 코어에 하나 넣을지. 범용 채택성을 위해 후자를 P4 이후 검토.

## 6. 범용화 로드맵과의 관계

`docs/naming.md` 4·5단계(패키지 이동·런타임 식별자·DB명)와 독립적으로 진행할 수 있다. 다만 P1 의 SPI 패키지는 4단계 패키지 이동 대상이므로 처음부터 별도 패키지(`…common.spi`)에 두어 이동 비용을 줄인다. 범용화 로드맵의 "IdentityResolver SPI" 는 이 플랜의 `VerifiedIdentity.subjectKey` 를 입력으로 받는다.
