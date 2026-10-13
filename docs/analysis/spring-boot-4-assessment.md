# Spring Boot 4 이행 영향 평가 (2026-10-13, 1.1.2 기준)

취약점 점검 1회차(`docs/certification/vulnerability-review-2026-10.md` §4·§6)가 남긴 숙제다. Spring Framework 6.2 줄기가 OSS 에서 닫혀 1.1.2 는 **억제 17건(만료 2027-01-31)** 을 안고 있고, OSS 수정판은 전부 Spring Boot 4 줄기(Framework 7.0.9·Reactor Netty 1.3.7·Spring Data JPA 4.x)에만 있다. 이 문서는 "언제·어떻게 Spring Boot 4 로 가는가" 를 2026-11 점검 회차에서 결정할 수 있게, 1.1.2 코드를 Spring Boot 4.1.1 에 실제로 올려 본 결과와 영향 범위·단계 계획·권고를 적는다.

## 0. 요약

| 항목 | 결과 |
|---|---|
| 대상 | Spring Boot **4.1.1**(2026-10 현재 최신; 4.0.8 은 4.0 줄기) — Framework 7.0.9·Security 7.1.1·Data 2026.0.1·Kafka 4.1.1·Jackson 3.1.5·Tomcat 11·Netty 4.2·Hibernate 7.4·Flyway 12.4·Testcontainers 2.0·JUnit 6 |
| 시험 컴파일(1.1.2 코드 그대로 + 빌드 파일만 교체) | 두 번 돌렸다. ① idem-common 에서 멈춤 — Boot 4 가 Kafka·Flyway 자동 구성을 기술별 모듈로 옮겨 패키지가 사라짐(16 오류). ② 그 둘만 고치자 **코어 main 코드는 registry·authz·relay·플러그인 3종이 그대로 컴파일**됐고, hub·gate·tenant-sample 은 사라진 `spring-boot-starter-aop` 때문에 의존성 해석에서 멈췄으며(코드 오류 아님), kr-registry 는 Flyway customizer 패키지 1건. 테스트 코드는 `@DataJpaTest`·`AutoConfigureTestDatabase` 패키지 이동(registry 38건)·`NoResourceFoundException` 생성자 변경(authz)·Kafka 자동 구성 패키지(common) |
| 가장 큰 일 | **Jackson 3**(`tools.jackson`): databind/core import 154 파일(hub 89), `ObjectMapper` 152 파일 371곳, `JsonNode` 32 파일, `ObjectMapper` 빈 3개(gate·hub·tenant-sample)와 `Jackson2ObjectMapperBuilder`/`MappingJackson2*` 3 파일. 컴파일은 Jackson 2 가 전이로 남아 통과하지만 **런타임의 HTTP 메시지 변환은 Jackson 3 로 바뀌어 우리 `ObjectMapper` 빈 설정(날짜·null·미지 속성)이 더는 응답에 적용되지 않는다** — 1단계는 `spring-boot-jackson2`(deprecated) 로 공존, 2단계에서 패키지 이행 |
| 생태계 | 전부 Boot 4 판이 있다: Redisson 4.8.0(4.6.0 부터 Boot 4.1·Data Redis 4.1 통합), Resilience4j `spring-boot4` 2.4.0, springdoc 3.1.1, networknt json-schema-validator 3.0.8(Jackson 3), Testcontainers 2.0.5. 없는 것: jjwt(0.13.0) 의 Jackson 3 모듈 → `jjwt-gson` 로 교체 또는 Jackson 2 공존 |
| Spring Security | 코어는 Spring Security 를 쓰지 않는다(0 파일 — 관리 세션·내부 API 키·HMAC 은 자체 구현) → Security 7 영향 없음(카탈로그의 미사용 oauth2 스타터 선언만 정리) |
| 공수(추정) | 1단계(Boot 4.1 + Jackson 2 공존) 2~3주, 2단계(Jackson 3 패키지 이행 + 직렬화 골든 테스트) 1~2주, 리허설(설치본 스모크·K8s·IT·캡처 스크립트) 1주 — AI 작업 기준, 사용자 리허설 1회 |
| 권고 | **GS 시험 일정이 정한다**(플랜 §4: 시험 중엔 보안·결함만). 사전 검토 전에 끝낼 수 있으면 **1.2 로 이행해 1.2 를 시험 대상으로**, 아니면 1.1.x 로 시험하고 인증 뒤 2.0 에서 개명과 함께 이행하되 억제 만료를 2027-06-30 으로 늘리고 월 1회 전제 조건을 재확인한다. 결정은 2026-11 회차 |

## 1. 왜 지금 보는가

- Spring Framework 6.2.x 의 2026-08-20 이후 권고는 전부 "6.2.20 Enterprise 전용 / OSS 7.0.9" 다. 1회차에서 전제 조건이 없음을 확인해 억제했지만(17건), 매달 같은 종류가 더 나오고 억제 목록이 길어질수록 "전제 조건 없음" 을 코드로 묶는 가드도 길어진다. OWASP 게이트는 억제 파일로만 통과한다.
- Reactor Netty 1.2(CVE-2026-47874)·Spring Data JPA 3.5(CVE-2026-47834)도 같은 구조다. Spring Boot 3.5 자체는 아직 OSS 패치(3.5.16)를 받지만 그 안의 Framework 가 멈췄다.
- 억제 만료일 2027-01-31 은 "그 전에 결정하라" 는 뜻이지 "그 전에 이행하라" 가 아니다 — 연장은 가능하되 근거(전제 조건 재확인)를 매 회차 새로 적어야 한다.

## 2. 대상 버전과 BOM 차이

| 구성 | 1.1.2 (Boot 3.5.16 + 덮어쓰기) | Boot 4.1.1 BOM | 비고 |
|---|---|---|---|
| Spring Framework | 6.2.19 | **7.0.9** | 억제 17건 전부 해소 |
| Spring Security | 6.5.11(미사용) | 7.1.1 | 영향 없음 |
| Spring Data | 2025.0.13(JPA 3.5.13) | **2026.0.1**(JPA 4.1) | CVE-2026-47834 해소, Hibernate 7.4 |
| Spring Kafka / kafka-clients | 3.3.16 / 3.9.2 | 4.1.1 / 4.2.1 | 브로커 CP 7.6.1(Kafka 3.6) 은 클라이언트 4.x 가 지원(≥2.1) |
| Jackson | 2.21.7 | **3.1.5**(`tools.jackson`) + `spring-boot-jackson2` 공존 모듈(deprecated) | §4.1 |
| Tomcat / Servlet | 10.1.60 / 6.0 | 11.0.24 / **6.1** | 덮어쓰기 7종은 전부 사라진다 |
| Netty / Reactor | 4.1.139 / 2024.0.18(Reactor Netty 1.2.18) | 4.2.17 / 2025.0.7(**1.3.7**) | CVE-2026-47874 해소 |
| Hibernate / Flyway | 6.6.53 / 11.8.0(고정) | **7.4.5** / 12.4.0 + `spring-boot-starter-flyway` | 마이그레이션 SQL 은 그대로, Hibernate 7 동작 차이 확인 |
| Micrometer / OTel | 1.15.12 / 1.49.0 | 1.17.1 / 1.62.0 | 스타터 이름 변경(`starter-micrometer-metrics`·`starter-opentelemetry`) |
| Testcontainers / JUnit | 1.20.4(고정) / 5.12 | 2.0.5 / **6.0.3** | IT 베이스 클래스 손질 |
| httpclient5/httpcore5·pgjdbc·log4j·commons-lang3 | 5.6.4/5.4.4·42.7.14·2.25.5·3.18.0 | 5.6.4/5.4.3·42.7.13·2.25.5·3.20.0 | 덮어쓰기 없이 BOM 이 맞춘다 |
| Java / Gradle | 21 / 9.5 | 17+ / 8.14+ | 변경 없음 |

## 3. 생태계 의존성 호환

| 라이브러리 | 1.1.2 | Boot 4 판 | 메모 |
|---|---|---|---|
| Redisson(분산 락, hub·nice-oacx) | 3.52.0 | **4.8.0** — 4.6.0 "Spring Boot 4.1.0 integration·Spring Data Redis 4.1.0 integration", `redisson-spring-data-41`, Jackson 3 코덱(4.1.0+) | 메이저 올림(3→4) — 릴리스 노트의 breaking change 확인 |
| Resilience4j | `spring-boot3` 2.2.0 | **`spring-boot4` 2.4.0** | 스타터 이름만 바뀜(hub·gate·tenant-sample·kr-hub·nice-oacx) |
| springdoc(authz) | `webmvc-api` 2.8.17 | **3.1.1** | |
| jjwt(hub·kr-hub) | 0.13.0 + `jjwt-jackson` | Jackson 3 모듈 없음 | `jjwt-gson`(0.13.0) 로 교체하거나 Jackson 2 공존 |
| networknt json-schema-validator(hub 프로파일 스키마) | 1.5.9(Jackson 2) | **3.0.8**(Jackson 3) | 2단계에서 |
| Flyway | 11.8.0 고정 | 12.4.0(BOM) + `spring-boot-starter-flyway` | 8개 빌드 파일 |
| Testcontainers | 1.20.4 고정 | 2.0.5 | 5 파일(IntegrationTestBase·Kafka 컨테이너) — 2.0 API |
| WireMock(테스트) | 3.10.0(Jackson 2) | 4.0 은 beta | 3.x 유지(테스트 의존, Jackson 2 공존) |
| java-uuid-generator·MapStruct·Lombok | 5.1.0·1.6.3 | 그대로 | |
| 벤더 SDK(KR 에디션 vendor-libs: NICE/OACX·AnyID) | Jackson 2 사용 여부 미확인 | — | 저장소 밖 jar — 1단계의 Jackson 2 공존이 안전망 |

## 4. 코드 영향 범위 (1.1.2, main·test 합계)

### 4.1 Jackson 3 (가장 큰 덩어리)

| 지표 | 수치 |
|---|---|
| `com.fasterxml.jackson.(databind|core|datatype|module)` import | **154 파일 250곳** — hub 89·gate 16·플러그인 11·tenant-sample 10·registry 7·kr-hub 7·relay 6·authz 4·common 2·kr-registry 2 (SDK 0 — Jackson 미사용) |
| `ObjectMapper` 사용 | 152 파일 371곳 |
| `JsonNode` / `JsonProcessingException` | 32 파일 158곳 / 9 파일 32곳 |
| `ObjectMapper` `@Bean` | 3 (gate `QSignWebConfig`, hub `IdoWebConfig`, tenant-sample `AgencyWebConfig`) + `Jackson2ObjectMapperBuilder`·`MappingJackson2*` 3 파일 |
| `spring.jackson.*` 설정 | 21 파일 51줄(`serialization.write-dates-as-timestamps`·`time-zone`·`deserialization.*`) — Boot 4 는 `spring.jackson.json.*` 로 옮기고 Jackson 2 설정은 `spring.jackson2.*` |
| 어노테이션(`com.fasterxml.jackson.annotation`) | 54 파일 — Jackson 3 에서도 패키지 유지 |

시험 컴파일에서 드러난 사실: Jackson 2 가 전이(spring-kafka·json-schema-validator·jjwt-jackson)로 남아 **컴파일은 그대로 통과**한다. 그러나 Boot 4 의 자동 구성은 Jackson 3 `JsonMapper` 로 HTTP 메시지 변환을 하므로, 우리 `ObjectMapper` 빈의 날짜·시간대·미지 속성 정책이 응답·요청 JSON 에 더는 적용되지 않는다(웹훅 봉투·감사 열·콘솔 응답의 호환이 깨질 수 있다). 따라서:

- **1단계**: `spring-boot-jackson2`(deprecated, `spring.jackson2.*`) 로 Jackson 2 자동 구성을 되살려 지금 동작을 유지한다. 코드 변경 없음.
- **2단계**: import 를 `tools.jackson.*` 로, `ObjectMapper` 빈 3개를 `JsonMapper`(builder) 로, `JsonProcessingException` 을 `JacksonException` 으로, `Jackson2ObjectMapperBuilder` 를 `JsonMapper.Builder` customizer 로 바꾸고 `spring.jackson.use-jackson2-defaults` 없이 직렬화 골든 테스트(웹훅 봉투·감사 행·기관 API 응답·프로파일 JSON)로 호환을 증명한다.

### 4.2 Boot 4 모듈화·이름 변경 (시험 컴파일이 실제로 잡은 것)

| 항목 | 1.1.2 | Boot 4 | 범위 |
|---|---|---|---|
| 자동 구성 패키지 | `org.springframework.boot.autoconfigure.kafka.*`·`.flyway.*` | `org.springframework.boot.kafka.autoconfigure.*`·`org.springframework.boot.flyway.autoconfigure.*` + 모듈 `spring-boot-kafka`·`spring-boot-flyway` | common 2 클래스(`KafkaOptionalAutoConfiguration`·`LegacySchemaRenameAutoConfiguration`), kr-registry 1(`FlywayConfigurationCustomizer`), common 테스트 1 |
| AOP 스타터 | `spring-boot-starter-aop` | **`spring-boot-starter-aspectj`**(aop 스타터 삭제) | hub·gate·tenant-sample·kr-hub 빌드 파일 — 의존성 해석에서 멈춘 원인 |
| 웹 스타터 | `spring-boot-starter-web`(classic 으로 남음) | `spring-boot-starter-webmvc` 권장 | 11 빌드 파일 14곳 |
| Flyway | `flyway-core` 직접 | `spring-boot-starter-flyway` | 8 빌드 파일 |
| 테스트 슬라이스 | `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest`·`...jdbc.AutoConfigureTestDatabase` | `spring-boot-data-jpa-test`·`spring-boot-jdbc-test` 모듈의 새 패키지 | registry 테스트 38 오류(5 파일), `@DataJpaTest` 5 파일·`@WebMvcTest` 5 파일 |
| `@SpringBootTest` | MockMvc·TestRestTemplate 자동 제공 | `@AutoConfigureMockMvc`·`@AutoConfigureTestRestTemplate` 명시 | `@SpringBootTest` 3 파일, `TestRestTemplate` 17 파일 |
| `@MockBean`·`@SpyBean` | 이미 `@MockitoBean`(4 파일) | 삭제됨 | 영향 없음 |
| `NoResourceFoundException` | (HttpMethod, path) | (HttpMethod, path, servletPath) | authz 테스트 1 |
| `EnvironmentPostProcessor` | `org.springframework.boot.env.*` | `org.springframework.boot.EnvironmentPostProcessor`(옛 인터페이스 deprecated) | 16 파일 42곳 — 1단계에선 그대로, 2단계에서 이동 |
| Actuator | — | liveness·readiness 기본 켜짐(이미 켬), `management.tracing.enabled` → `management.tracing.export.enabled`(3 파일) | 설정만 |
| Nullability | — | Framework 7 JSpecify, `org.springframework.lang.Nullable` 지원 제거 | `@Nullable`/`@NonNull` 3 파일 9곳 |
| Undertow·Jersey·Spring Session Hazelcast/Mongo | 미사용 | 삭제 | 영향 없음 |

### 4.3 영향 없는 것

- Spring Security(0 파일), JUnit 4(0), `javax.*`(0), Spring Retry `@Retryable`(0 — Resilience4j 사용), Testcontainers `@ServiceConnection`(0), Kafka Streams(0).
- RestTemplate(74 파일)·`HttpComponentsClientHttpRequestFactory`(2)·`WebMvcConfigurer`/`HandlerInterceptor`(6) — Framework 7 에서 유지.
- SDK(`idem-sdk-java`): Spring 비의존(Java 8) — 테스트 의존성(`junit-platform-launcher 1.11.4` 고정)이 루트 BOM(JUnit 6) 과 충돌해 시험 컴파일에서 해석 오류 → SDK 테스트 스택은 독립 유지(`-x` 로 제외했다). 이행 때 SDK 의 JUnit 버전을 별도로 올린다.

## 5. 시험 컴파일 기록 (2026-10-13, 로컬)

빌드 파일만 바꿨다: 루트 플러그인·BOM 4.1.1, 덮어쓰기 7종 제거, Resilience4j `spring-boot4` 2.4.0, springdoc 3.1.1, (2회차) common 에 `spring-boot-kafka`·`spring-boot-flyway` compileOnly + 자동 구성 import 2곳. 코드는 손대지 않았고 결과만 적고 **전부 되돌렸다**(커밋 없음).

| 회차 | 결과 |
|---|---|
| 1 | `idem-common:compileJava` 16 오류(Kafka·Flyway 자동 구성 패키지 이동) — 여기서 멈춰 나머지 모듈은 시도되지 않음. SDK 테스트 클래스패스는 JUnit 플랫폼 버전 충돌로 해석 실패 |
| 2 | main: registry·authz·relay·플러그인 3종 **통과**, hub·gate·tenant-sample 은 `spring-boot-starter-aop` 없음(의존성 해석, 코드 오류 아님), kr-registry 2 오류(Flyway customizer 패키지), kr-hub 는 hub 가 멈춰 미시도. test: registry 38(JPA 테스트 슬라이스 패키지), authz 2(`NoResourceFoundException`), common 2(Kafka 자동 구성 패키지) |

읽는 법: Boot 4 의 **모듈화가 1차 장벽**이고(빌드 파일·import 수준, 하루 분량), 코드 수준의 큰 일은 Jackson 3 뿐이다. 3회차(aop → aspectj, 테스트 패키지 교체)까지 가면 hub 본체의 컴파일 결과를 볼 수 있다 — 1단계 착수 때 첫 작업.

## 6. 단계 계획과 공수

| 단계 | 내용 | 검증 | 공수(AI) |
|---|---|---|---|
| 0 | 현상 유지(1.1.x): 억제 17건, 월 1회 전제 조건 재확인, 2027-01-31 전에 연장 여부 결정 | 점검 회차 | 회차당 반나절 |
| 1 | **Boot 4.1 + Jackson 2 공존**: 빌드 파일(스타터 이름·aspectj·flyway·redisson 4.8·resilience4j·springdoc·testcontainers 2·JUnit 6), 자동 구성 패키지 3곳, 테스트 슬라이스·`@SpringBootTest` 보강, `NoResourceFoundException`, 속성 이름(`management.tracing.export.enabled`·`spring.jackson2.*`), Hibernate 7 차이(ID 생성·쿼리·`@Entity` 20), Kafka 4 클라이언트, SDK JUnit 분리 | 단위 1,844 + IT 61 + CI 스모크 ①~⑧c + K8s 리허설 + 캡처 스크립트 2종 + OWASP 게이트(억제 17건 제거) | 2~3주 |
| 2 | **Jackson 3 패키지 이행**: 154 파일 import, `ObjectMapper` 빈 3 → `JsonMapper`, 예외 타입, builder customizer, networknt 3.x, jjwt-gson; `spring-boot-jackson2` 제거 | 직렬화 골든 테스트(웹훅 봉투·감사 행·기관 API·프로파일 JSON·콘솔 응답) + 위 전체 | 1~2주 |
| 3 | 리허설: 오프라인 번들 → 설치 → 1.1.x 에서 업그레이드 → 스모크·백업/복구 — 설치 매뉴얼 업그레이드 절차 갱신 | 사용자 환경 1회 | 1주 + 사용자 1일 |

위험: 벤더 SDK(Jackson 2 의존 가능성 — 1단계 공존이 안전망), Hibernate 7 의 조용한 동작 차이(쿼리 플랜·ID 생성·`@Transactional` 경계 — IT 가 잡는다), Kafka 4 클라이언트 ↔ 브로커 3.6(지원 범위 안), Testcontainers 2 API, Redisson 4 메이저, GS 시험 중 버전 변경 금지(플랜 §4).

## 7. 권고와 결정 사항 (2026-11 회차)

1. **GS 일정 기준**: 시험원 사전 검토 전까지 4~6주가 있으면 1단계+2단계를 **1.2** 로 묶어 시험 대상을 1.2 로 올린다(억제 0, Framework 7). 없으면 1.1.x 로 시험을 치르고 **2.0**(API 개명·`LegacyNames` 제거, 플랜 §6)에서 이행한다.
2. 2.0 에서 이행할 경우: 억제 만료를 2027-06-30 으로 늘리고, 매 회차 가드 테스트 토큰과 전제 조건을 재확인하며, Spring 6.2 의 새 CVE 는 같은 절차로 분류한다. 수정판 없는 CRITICAL(전제 조건이 있는 것)이 나오면 그때는 즉시 1단계를 당긴다.
3. 어느 쪽이든 **1단계의 첫 작업은 시험 컴파일 3회차**(aspectj·테스트 패키지)로 hub 본체 결과를 얻는 것이다 — 반나절.
4. 사용자 결정: (a) 1.2 vs 2.0, (b) Jackson 3 이행을 1.2 안에 넣을지(공존으로 1.2 를 내고 2.0 에서 패키지 이행도 가능), (c) SDK 의 JUnit/테스트 스택 분리 방식.
