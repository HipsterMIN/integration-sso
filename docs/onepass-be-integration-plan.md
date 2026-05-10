# onepass-be (identity-orchestrator) → integration-sso 통합 플랜

**작성일**: 2026-05-10  
**작성자**: AI 개발자 (Genspark)  
**버전**: v1.0.0  
**대상 브랜치**: `genspark_ai_developer`

---

## 0. 요약 (Executive Summary)

`onepass-be`(identity-orchestrator)는 `onepass-fe`의 **BFF(Backend-for-Frontend)** 로 독립 개발된 Spring Boot 4.0.6 / 헥사고날 아키텍처 서비스다.  
FE가 `beInstance`로 호출하는 **5개 핵심 인증 엔드포인트(NICE/OACX/기업인증)**가 이 서버에 완전 구현되어 있으며, 현재 `integration-sso`의 `ido` 모듈에는 이 엔드포인트들이 **전혀 존재하지 않는다**.

**통합 결정**: **Option B — `onepass-bff` 신규 모듈 추가**  
이유: Spring Boot 버전 충돌 회피, 헥사고날 아키텍처 보존, 책임 분리 유지, 최소 리스크

---

## 1. onepass-be 철저 분석 결과

### 1.1 프로젝트 정보

| 항목 | 값 |
|------|----|
| 프로젝트명 | `identity-orchestrator` |
| Spring Boot | **4.0.6** (integration-sso: 3.5.x — **Major 버전 차이**) |
| Java | 21 |
| 포트 | **9292** |
| 패키지 | `kr.ucube.integratedauth.identityorchestrator` |
| 아키텍처 | **헥사고날 (Ports & Adapters)** |
| DB (local/dev) | H2 인메모리 (Board 샘플 테이블만 존재) |
| DB (prod) | PostgreSQL (환경변수) |
| ORM | MyBatis + Spring Data JDBC |
| HTTP 클라이언트 | Spring WebFlux WebClient (비동기) |
| 관측성 | OpenTelemetry (K8s Instrumentation CRD, Alloy 수집기) |

### 1.2 핵심 API — 포트 인터페이스 완전 분석

#### InPort (AuthInPort)
```java
AuthCallbackResponse  callback(AuthCallbackRequest request)       // 기업인증 콜백
OacxAccessInfoResponse getOacxAccessInfo(String fn)               // OACX accKey/accToken 발급
OacxEasysignResponse  handleOacxEasysign(OacxEasysignRequest req) // OACX 서명 결과 처리
CiCheckResponse       checkNiceCi(CiCheckRequest request)         // NICE CI 확인
```

#### InPort (NiceAuthInPort)
```java
NicePhoneAuthUrlResponse  getNicePhoneAuthUrl(String returnUrl)         // NICE 팝업 URL 발급
NicePhoneAuthResultResponse getNicePhoneAuthResult(NicePhoneAuthResultRequest req) // NICE 결과 조회
```

#### OutPort 전체
| OutPort | 구현체 | 역할 |
|---------|--------|------|
| `NiceApiOutPort` | `NiceApiOutAdapter` | NICE IDO API 3개 (토큰/URL/결과) |
| `OacxOutPort` | `OacxOutAdapter` | OACX SDK (getAccessInfo / jwtDecryptResult) |
| `ImApiOutPort` | `ImApiOutAdapter` | IM API POST /users — **현재 미사용(TODO 주석)** |
| `IntegrationAuthApiOutPort` | `IntegrationAuthApiOutAdapter` | 통합인증 서버 auth-check/v1 |

### 1.3 DB 스키마 분석 결과

**핵심 발견**: onepass-be의 `schema.sql`에는 **Board 샘플 테이블 1개만 존재**.  
인증 관련 데이터(NICE 세션, OACX 결과, 기업인증 이력)가 **DB에 저장되지 않는다**.  
모든 상태는 **인메모리(AtomicReference, ConcurrentHashMap)**로만 관리된다.

```sql
-- schema.sql 전체 내용 (샘플)
CREATE TABLE IF NOT EXISTS board (
  board_no INT GENERATED ALWAYS AS IDENTITY,
  board_title VARCHAR(100) NOT NULL,
  board_content TEXT NOT NULL,
  ...
);
```

**운영 시 필요한 테이블**: 없음 (stateless 설계 의도)  
**단, 다중 인스턴스 운영 시**: NiceTokenStore(Redis), NiceAuthSessionStore(Redis) 교체 필요 — 소스 코드 주석에 명시됨

### 1.4 문서 분석 (docs/)

**oacx-authentication-flow.md**: OACX 전체 연동 흐름 완전 문서화

```
원패스 FE → ① POST /oacx/access-info → 원패스 BE
원패스 BE → ② SDK getAccessInfo() → OACX 서버
           ← ③ accKey, accToken
FE ← ④ {accKey, accToken}
FE → ⑤⑥ window.open(OACX 팝업), postMessage(accKey, accToken)
     [사용자 본인인증 수행]
FE ← ⑦ postMessage({fn, status, res}) ← 암호화된 JWT
FE → ⑧ POST /oacx/easysign {fn, status, res}
BE → ⑨ SDK jwtDecryptResult() → CI, name, birthday, phone
BE → ⑩ register(CI, PII) → IM API   [현재 TODO 미구현]
FE ← ⑪ {name, birthday, phone}        [CI는 FE에 미전달]
```

**중요**: CI는 절대 FE에 반환되지 않으며, IM API에만 전달. 이것이 보안 원칙.

### 1.5 운영 환경 설정 (application-prod.yml 분석)

```yaml
# prod에서도 H2 사용 (테스트 환경용)
# 실제 prod는 PostgreSQL 환경변수로 오버라이드 예상
allowed-origins:
  - https://www.smes.go.kr
  - https://onepass.smes.go.kr
  # localhost 제거 — 보안 강화
```

### 1.6 관측성 설정 (instrumentation.yaml)

OpenTelemetry K8s Instrumentation CRD:
- **수집기**: `http://alloy.lgtm.svc.cluster.local:4317` (LGTM Stack)
- **프로토콜**: gRPC
- **샘플링**: ParentBased TraceIdRatio (100%)
- **전파**: W3C tracecontext + baggage
- **계측 범위**: `onepass..*[*]` (전체 패키지)

→ **통합 시 integration-sso의 TraceparentFilter와 통일** 필요

### 1.7 샘플 코드 처리 결정

| 파일 | 통합 시 처리 |
|------|-------------|
| `BoardController.java` | **삭제** |
| `BoardService.java`, `BoardInPort.java`, `BoardOutPort.java` | **삭제** |
| `BoardMapper.java`, `BoardOutAdapter.java` | **삭제** |
| `BoardMapper.xml` | **삭제** |
| `Board.java`, `BoardWithUser.java`, `WriteBoard.java` | **삭제** |
| `schema.sql` (board 테이블) | **삭제** |
| `data.sql` (board 데이터) | **삭제** |
| `UserApiOutAdapter.java`, `UserApiOutPort.java` | **삭제** |
| `WebClientConfiguration.java` | **삭제** |

---

## 2. FE ↔ BE 전체 아키텍처 플로우 (확정)

```
┌─────────────────────────────────────────────────────────────────┐
│                    onepass-fe (React SPA)                        │
│                                                                  │
│  extInstance (axios)        beInstance (axios)                   │
│  baseURL: EXT_API_URL       baseURL: BE_API_URL                  │
│  Header: EXT_API_KEY        Header: X-BE-API-key                 │
└──────────┬──────────────────────────┬────────────────────────────┘
           │                          │
           ▼                          ▼
┌──────────────────┐    ┌────────────────────────────────────────┐
│   Q-IM (q-im)    │    │   onepass-be / onepass-bff             │
│   port: 8082     │    │   port: 9292                           │
│                  │    │                                        │
│ 25개 프로비저닝  │    │ /api/v1/auth/callback       (기업인증) │
│ CI/동의 API      │    │ /api/v1/auth/oacx/access-info (OACX)  │
│                  │    │ /api/v1/auth/oacx/easysign   (OACX)   │
│ /api/ext/**      │    │ /api/v1/auth/nice/ci-check  (NICE)    │
│ /api/v1/**       │    │ /api/v1/auth/nice/phone/url (NICE)    │
│                  │    │ /api/v1/auth/nice/phone/result(NICE)  │
└──────────────────┘    └───────────────────┬────────────────────┘
                                            │ 외부 시스템 연동
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  NICE API (auth.niceid.co.kr)        │
                         │  OACX SDK (anyid.go.kr)              │
                         │  통합인증 서버 (auth-check/v1)        │
                         │  IM API (PII 저장)                   │
                         └──────────────────────────────────────┘

별도 경로 (window.location):
FE → ido (port: 8083)
  GET  /api/v1/broker/{provider}/authorize  (OIDC 진입)
  POST /api/v1/fe-session                   (세션 생성)
  GET  /api/v1/fe-session/check             (세션 확인)
  POST /api/v1/fe-session/logout            (로그아웃)
  POST /api/v1/handoff/issue                (핸드오프)
```

---

## 3. integration-sso 문제점 제언

### 3.1 구조적 GAP (Critical)

#### GAP-BE-01: NICE/OACX 엔드포인트 전무
- **현상**: ido 모듈에 `/api/v1/auth/nice/*`, `/api/v1/auth/oacx/*` 없음
- **원인**: onepass-be가 별도 BFF로 분리 개발됨
- **영향**: FE가 beInstance로 호출하는 5개 API 전부 404
- **해결**: onepass-bff 모듈 추가 (Section 4)

#### GAP-BE-02: OACX SDK 미포함
- **현상**: `OACX-SDK-v1.3.2.jar`가 integration-sso에 없음
- **영향**: OACX 간편인증 불가
- **해결**: `onepass-bff/libs/` 에 JAR 복사 후 로컬 의존성 선언

#### GAP-BE-03: ImApiOutPort 미구현 (IM 연동 누락)
- **현상**: `checkNiceCi()`와 `handleOacxEasysign()`에서 `// TODO: IM API 연동` 주석만 존재
- **원인**: 시연용으로 CI 검증 후 IM 저장 미구현
- **영향**: CI가 IM에 저장되지 않아 회원 이력 관리 불가
- **해결**: Sprint 8에서 IM API 연동 완성

#### GAP-BE-04: NiceTokenStore / NiceAuthSessionStore 인메모리
- **현상**: AtomicReference(토큰), ConcurrentHashMap(세션) — 단일 인스턴스 전용
- **영향**: 다중 인스턴스 배포 시 토큰/세션 불일치
- **해결**: integration-sso Redis 인프라 활용하여 교체

### 3.2 integration-sso가 잘못된 부분 (제언)

#### 제언-01: OIDC 브로커 모드 복잡성 과도
- `qsign` 모드와 `keycloak` 모드를 동시 지원하는 이중 구조가 복잡성을 가중
- **제언**: onepass-be의 직접 인증(NICE/OACX) 방식을 primary로 채택하고, OIDC는 선택 경로로 단순화

#### 제언-02: FE 세션 쿠키 전략 재검토
- `feSessionId` (HttpOnly/Secure/SameSite=Lax) → ido에서 관리
- onepass-be의 beInstance 호출은 API Key 기반 → 세션 연계 없음
- **제언**: NICE/OACX 결과를 FE 세션에 연결하는 연동 포인트 정의 필요

#### 제언-03: 멀티모듈 Spring Boot 버전 정합
- integration-sso: Spring Boot 3.5.x
- onepass-be: Spring Boot 4.0.6
- **제언**: 통합 시 단일 버전으로 통일. 신규 `onepass-bff` 모듈은 **3.5.x**로 다운그레이드하여 플랫폼 일관성 유지

#### 제언-04: Flyway vs H2 스키마 이원화 해소
- integration-sso: Flyway 마이그레이션 (V1~V13)
- onepass-be: H2 classpath SQL (board 샘플만)
- **제언**: 통합 모듈의 DB는 Flyway로 통일. 인메모리 세션은 Redis로 외재화

#### 제언-05: API Key 보안 정책 통일
- ido: 별도 API Key 정책 없음 (JWT + FE 세션)
- onepass-be: `X-BE-API-key` 헤더 (상수시간 비교, 다중 키 회전)
- **제언**: `ApiKeyInterceptor` 패턴을 integration-sso 공통 보안 정책으로 편입

---

## 4. 통합 옵션 비교 및 결정

### Option A: onepass-be → ido 모듈 병합
- **장점**: 모듈 수 최소화, 단일 배포
- **단점**: Spring Boot 4.0.6 → 3.5.x 다운그레이드 필요, 헥사고날 + 레이어드 혼재, ido 책임 비대화
- **리스크**: 높음

### Option B: integration-sso에 `onepass-bff` 신규 모듈 추가 ⭐ 채택
- **장점**: 책임 명확 분리, 헥사고날 아키텍처 보존, Spring Boot 3.5.x 통일, 기존 ido 변경 최소
- **단점**: 모듈 1개 추가, 포트 추가 (9292)
- **리스크**: 낮음

### Option C: Docker Compose만으로 독립 서비스 유지
- **장점**: 코드 변경 없음
- **단점**: 별도 빌드·배포 파이프라인, 패키지 구조 분리, OACX SDK 별도 관리
- **리스크**: 중간 (운영 복잡도)

### **결정: Option B** — 이유
1. Spring Boot 버전 충돌 없이 3.5.x로 통일 가능
2. 헥사고날 아키텍처 유지 — 포트/어댑터 패턴 그대로 이식
3. integration-sso 멀티모듈 생태계 내 통합 빌드 가능
4. Redis, Kafka, 공통 보안 설정 재사용 가능
5. 향후 IM API 연동 완성 시 Q-IM 모듈과 직접 통신 가능

---

## 5. 통합 실행 플랜

### Phase 1: onepass-bff 모듈 스캐폴딩 (S7-T2)

#### 5.1 디렉토리 구조

```
integration-sso/
├── platform-common/
├── q-sign/
├── q-im/
├── ido/
├── agency-stub/
├── onepass-fe/
└── onepass-bff/                          ← 신규 모듈
    ├── build.gradle.kts
    ├── libs/
    │   └── OACX-SDK-v1.3.2.jar          ← onepass-be/libs/ 에서 복사
    └── src/main/java/kr/go/smes/bff/
        ├── BffApplication.java
        ├── adapter/
        │   ├── in/controller/
        │   │   └── AuthController.java   ← 이식 + 패키지 변경
        │   └── out/apis/
        │       ├── NiceApiOutAdapter.java
        │       ├── OacxOutAdapter.java
        │       ├── ImApiOutAdapter.java
        │       ├── IntegrationAuthApiOutAdapter.java
        │       ├── config/
        │       │   └── AuthApiWebClientConfiguration.java
        │       └── nice/
        │           ├── NiceCryptoUtil.java
        │           ├── NiceTokenStore.java      ← Redis 교체 대상
        │           └── NiceAuthSessionStore.java ← Redis 교체 대상
        ├── biz/auth/
        │   ├── port/in/
        │   │   ├── AuthInPort.java
        │   │   └── NiceAuthInPort.java
        │   ├── port/out/
        │   │   ├── NiceApiOutPort.java
        │   │   ├── OacxOutPort.java
        │   │   ├── ImApiOutPort.java
        │   │   └── IntegrationAuthApiOutPort.java
        │   └── service/
        │       ├── AuthService.java
        │       └── NiceAuthService.java
        ├── common/security/
        │   ├── WebMvcSecurityConfig.java
        │   ├── ApiKeyInterceptor.java
        │   ├── OriginInterceptor.java
        │   └── AuthProperties.java
        └── dto/auth/
            └── *.java                    ← 9개 DTO 전체 이식
```

#### 5.2 build.gradle.kts (onepass-bff)

```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

group = "kr.go.smes"
version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    // Platform 공통
    implementation(project(":platform-common"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")  // WebClient
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")  // NiceTokenStore

    // OACX SDK (로컬 JAR)
    implementation(files("libs/OACX-SDK-v1.3.2.jar"))

    // BouncyCastle (NiceCryptoUtil — PBKDF2/AES-GCM)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // JWT (OACX 결과 복호화)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Springdoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.8")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

#### 5.3 settings.gradle.kts 수정

```kotlin
// 기존 include에 추가
include(
    "platform-common",
    "q-sign",
    "q-im",
    "ido",
    "onepass-fe",
    "agency-stub",
    "onepass-bff"   // 추가
)
```

### Phase 2: 핵심 로직 이식 및 패키지 변환

#### 5.4 패키지 변환 규칙

| 원본 패키지 | 이식 패키지 |
|-------------|-------------|
| `kr.ucube.integratedauth.identityorchestrator` | `kr.go.smes.bff` |
| `identityorchestrator.adapter.in.controller` | `bff.adapter.in.controller` |
| `identityorchestrator.adapter.out.apis` | `bff.adapter.out.apis` |
| `identityorchestrator.biz.auth` | `bff.biz.auth` |
| `identityorchestrator.common.security` | `bff.common.security` |
| `identityorchestrator.dto.auth` | `bff.dto.auth` |

#### 5.5 이식 제외 (삭제) 목록

```
BoardController, BoardService, BoardInPort, BoardOutPort
BoardMapper, BoardOutAdapter, BoardMapper.xml
Board.java, BoardWithUser.java, WriteBoard.java
UserApiOutAdapter, UserApiOutPort
WebClientConfiguration (샘플)
schema.sql, data.sql (Board 샘플)
BoardEntity
application-prod.yml의 H2 설정 → PostgreSQL 설정으로 교체
```

#### 5.6 application.yml (onepass-bff)

```yaml
server:
  port: 9292

spring:
  application:
    name: onepass-bff
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms

onepass:
  auth:
    oacx:
      provider-key-path: ${OACX_PROVIDER_KEY_PATH:}
      debug-mode: ${OACX_DEBUG_MODE:false}
    incoming-api-keys: ${ONEPASS_AUTH_INCOMING_KEYS:dev-api-key-001}
    allowed-origins:
      - https://www.smes.go.kr
      - https://onepass.smes.go.kr
      - http://localhost:3301
      - http://localhost:9292
    integration:
      base-url: ${INTEGRATION_AUTH_BASE_URL:http://localhost:8083}
      timeout-seconds: 10
    im:
      base-url: ${IM_BASE_URL:http://localhost:8082}
      api-key: ${IM_API_KEY:}
      timeout-seconds: 10
    nice:
      client-id: ${NICE_CLIENT_ID:}
      client-secret: ${NICE_CLIENT_SECRET:}
      return-url: ${NICE_RETURN_URL:http://localhost:3301/otp/auth-test}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

### Phase 3: Redis 기반 인메모리 세션 교체

#### 5.7 NiceTokenStore → Redis 교체

```java
@Component
@RequiredArgsConstructor
public class NiceTokenStore {
    
    private static final String REDIS_KEY = "nice:access-token";
    private static final long SAFE_MARGIN_SECONDS = 60;
    
    private final StringRedisTemplate redisTemplate;
    
    public void update(String token, Instant expiresAt) {
        long ttlSeconds = Instant.now().until(expiresAt, ChronoUnit.SECONDS) - SAFE_MARGIN_SECONDS;
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set(REDIS_KEY, token, Duration.ofSeconds(ttlSeconds));
        }
    }
    
    public Optional<String> getValidToken() {
        String token = redisTemplate.opsForValue().get(REDIS_KEY);
        return Optional.ofNullable(token);
    }
}
```

#### 5.8 NiceAuthSessionStore → Redis 교체

```java
@Component
@RequiredArgsConstructor
public class NiceAuthSessionStore {
    
    private static final String KEY_PREFIX = "nice:session:";
    private static final long SESSION_TTL_MINUTES = 10;
    
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    public void save(String tokenVersionId, NiceAuthSession session) {
        try {
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(
                KEY_PREFIX + tokenVersionId, json,
                Duration.ofMinutes(SESSION_TTL_MINUTES)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("NICE 세션 직렬화 실패", e);
        }
    }
    
    public Optional<NiceAuthSession> get(String tokenVersionId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + tokenVersionId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, NiceAuthSession.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
    
    public void remove(String tokenVersionId) {
        redisTemplate.delete(KEY_PREFIX + tokenVersionId);
    }
}
```

---

## 6. onepass-fe React 소스 통합 플랜

### 6.1 현재 상태 분석

이전 세션에서 업로드된 `/tmp/onepass-new/onepass/src`와 현재 `onepass-fe/frontend/src`를 비교한 결과:

**차이 파일: 4건**

| 파일 | 차이 내용 | 처리 방안 |
|------|-----------|-----------|
| `src/api/ext/members.ts` | NEW: `modifyEnterprise`, `modifyMember` 2개 함수 추가 (POST .../modify_local) | **통합 필요** |
| `src/pages/Mypage/pages/InformationStep3.tsx` | NEW: DateInput 컴포넌트, formatDateInput, isValidDate, isValidEmail 유틸, 정보수정 폼 완성 | **통합 필요** |
| `src/pages/Mypage/pages/useInfoStore.ts` | NEW: `loadUserId` 함수, 기업/개인 정보수정 액션 추가 | **통합 필요** |
| `src/types/api/ext/members.ts` | NEW: `EnterpriseModifyRequest`, `MemberModifyRequest` 인터페이스 추가 | **통합 필요** |

**동일한 파일**: hooks(useNicePhoneAuth.ts, usePersonalEasyAuth.ts), api/nice/ciCheck.ts 등 핵심 파일들 **이미 통합 완료**

### 6.2 통합 필요 파일 상세

#### 6.2.1 `types/api/ext/members.ts`에 추가할 타입

```typescript
/** 기업회원 정보 수정 요청 (POST /api/ext/provision/enterprises/modify_local) */
export interface EnterpriseModifyRequest {
  mbrUuid: string;
  bzmnNm?: string;
  rprsvNm?: string;
  rprsTelno?: string;
  rprsMblTelno?: string;
  rprsEmlAddr?: string;
  estbDt?: string;
  notiPrefs?: { sms: boolean; kakao: boolean; email: boolean };
}

/** 개인회원 정보 수정 요청 (POST /api/ext/provision/users/modify_local) */
export interface MemberModifyRequest {
  mbrUuid: string;
  memberName?: string;
  indvMblTelno?: string;
  emlAddr?: string;
  addr?: string;
  daddr?: string;
  notiPrefs?: { sms: boolean; kakao: boolean; email: boolean };
}
```

#### 6.2.2 `api/ext/members.ts`에 추가할 함수

```typescript
/** 기업회원 정보 수정 (POST /api/ext/provision/enterprises/modify_local) */
export const modifyEnterprise = async (
  body: EnterpriseModifyRequest,
): Promise<SuccessResponse<EnterpriseResponse> | ErrorResponse> => { ... };

/** 개인회원 정보 수정 (POST /api/ext/provision/users/modify_local) */
export const modifyMember = async (
  body: MemberModifyRequest,
): Promise<SuccessResponse<MemberResponse> | ErrorResponse> => { ... };
```

#### 6.2.3 InformationStep3.tsx 추가 기능

- **DateInput 컴포넌트**: YYYY-MM-DD 자동 포맷팅 입력 컴포넌트
- **isValidDate**: 날짜 유효성 검증 (1900~현재, 월/일 범위)
- **isValidEmail**: 이메일 형식 검증
- **정보수정 폼**: 기업/개인 회원 정보 수정 UI 완성

#### 6.2.4 useInfoStore.ts 추가 기능

- **loadUserId**: 현재 로그인 사용자 UUID 로드
- **기업/개인 정보수정 액션**: modifyEnterprise / modifyMember 호출

### 6.3 통합 실행 계획 (S7-T5: FE 마이페이지 정보수정 통합)

```bash
# Step 1: types 업데이트
# src/types/api/ext/members.ts 에 2개 인터페이스 추가

# Step 2: API 함수 추가  
# src/api/ext/members.ts 에 modifyEnterprise, modifyMember 추가

# Step 3: Mypage 컴포넌트 통합
# src/pages/Mypage/pages/InformationStep3.tsx 전체 교체
# src/pages/Mypage/pages/useInfoStore.ts 업데이트

# Step 4: 빌드 검증
cd onepass-fe && npm run build
```

**주의**: `/api/ext/provision/enterprises/modify_local`, `/api/ext/provision/users/modify_local` 엔드포인트가 Q-IM에 실제 구현되어 있는지 확인 필요

---

## 7. Sprint 7 플랜 업데이트

### 7.1 S7-T2 수정 — "NICE/OACX BE 직접 구현" → "onepass-bff 모듈 이식"

| 항목 | 이전 계획 | 수정 계획 |
|------|-----------|-----------|
| 작업명 | S7-T2: NICE/OACX 엔드포인트 신규 구현 | S7-T2: onepass-bff 모듈 이식 |
| 공수 | 5일 | 3일 (이식이므로 단축) |
| 접근 | ido에 NICE/OACX 코드 신규 작성 | onepass-bff 모듈 생성 + 패키지 변환 이식 |
| 리스크 | 높음 (OACX SDK 연동, 암호화 신규) | 낮음 (검증된 코드 이식) |

### 7.2 전체 Sprint 7 플랜 (확정)

| Task | 설명 | Phase | 공수 | 선행조건 |
|------|------|-------|------|----------|
| S7-T1 | Register Step5 프로비저닝 연동 (FE) | P1 | 2일 | 없음 |
| **S7-T2** | **onepass-bff 모듈 이식 + Redis 세션 교체** | **P1** | **3일** | OACX JAR 확보 |
| S7-T3 | 긴급 보안 조치 (SEC-001/003/004) | P1 | 1일 | 없음 |
| S7-T4 | onepass-bff → Docker Compose 통합 | P2 | 1일 | S7-T2 완료 |
| S7-T5 | FE 마이페이지 정보수정 통합 (4개 파일) | P2 | 0.5일 | 없음 |
| S7-T6 | ImApiOutPort 완성 (IM 연동) | P2 | 1일 | S7-T2 완료 |
| S7-T7 | NICE CI-Check IM 연동 완성 | P2 | 1일 | S7-T6 완료 |
| S7-T8 | FE-BFF 세션 연동 (feSessionId ↔ beInstance) | P3 | 2일 | S7-T2, T4 |
| S7-T9 | E2E 통합 테스트 (NICE + OACX + 기업인증) | P3 | 2일 | 전체 완료 |

---

## 8. 기술 리스크 및 대응

### 8.1 OACX SDK 의존성

| 리스크 | 내용 | 대응 |
|--------|------|------|
| JAR 확보 | `OACX-SDK-v1.3.2.jar`를 onepass-bff/libs/에 배치 | onepass-be/libs/ 에서 복사 |
| 라이선스 | OACX SDK 재배포 정책 확인 필요 | 담당자 확인 후 .gitignore 처리 |
| provider-key.json | 운영 환경 키 파일 경로 환경변수화 | `OACX_PROVIDER_KEY_PATH` 사용 |

### 8.2 Spring Boot 버전 전략

```
onepass-bff 모듈: Spring Boot 3.5.x (platform 통일)
                  ↑ onepass-be 4.0.6에서 다운그레이드
변경 사항:
- Spring Boot 4.x 전용 API 사용 확인 (WebFlux, Security 등)
- Jakarta EE 11 → Jakarta EE 10 호환성 확인
- 대부분 Spring Boot 3.x 호환 (API 큰 변화 없음)
```

### 8.3 NICE 인증 외부 의존성

```
개발환경: NICE 테스트 환경 자격증명 필요
  NICE_CLIENT_ID, NICE_CLIENT_SECRET
  NICE_RETURN_URL (로컬: http://localhost:3301/otp/auth-test)
  
로컬 테스트: MockNiceApiOutAdapter 구현 권장
  → NiceApiOutPort 구현체를 환경별로 교체 (Profile 활용)
```

---

## 9. Docker Compose 통합 설계

```yaml
# docker-compose.yml 추가
services:
  onepass-bff:
    build:
      context: .
      dockerfile: onepass-bff/Dockerfile
    ports:
      - "9292:9292"
    environment:
      - SPRING_PROFILES_ACTIVE=local
      - REDIS_HOST=redis
      - INTEGRATION_AUTH_BASE_URL=http://ido:8083
      - IM_BASE_URL=http://q-im:8082
      - ONEPASS_AUTH_INCOMING_KEYS=${BFF_API_KEY:-dev-key-001}
      - OACX_PROVIDER_KEY_PATH=/etc/oacx/
      - OACX_DEBUG_MODE=false
      - NICE_CLIENT_ID=${NICE_CLIENT_ID:-}
      - NICE_CLIENT_SECRET=${NICE_CLIENT_SECRET:-}
    volumes:
      - ./onepass-bff/keys/:/etc/oacx/:ro  # provider-key.json 마운트
    depends_on:
      - redis
      - ido
      - q-im
    networks:
      - onepass-net
```

---

## 10. 포트 배치 정리

| 서비스 | 포트 | 역할 |
|--------|------|------|
| q-sign | 8081 | Q-Sign 인증 이벤트 |
| q-im | 8082 | Q-IM 멤버/프로비저닝 |
| ido | 8083 | OIDC 브로커, FE 세션, 핸드오프 |
| **onepass-bff** | **9292** | **NICE/OACX/기업인증 BFF** |
| onepass-fe | 3301 | React SPA |
| agency-stub | 8090 | 유관기관 시뮬레이터 |

---

## 11. 실행 체크리스트

### S7-T2 실행 전 준비

- [ ] `onepass-be/libs/OACX-SDK-v1.3.2.jar` 파일 접근 가능 확인
- [ ] NICE 테스트 자격증명 확보 (CLIENT_ID, CLIENT_SECRET)
- [ ] `provider-key.json` 파일 확보 (OACX SDK 필수)
- [ ] Redis 로컬 환경 실행 확인

### S7-T2 실행 순서

1. `settings.gradle.kts` — `onepass-bff` include 추가
2. `onepass-bff/` 디렉토리 + `build.gradle.kts` 생성
3. `onepass-bff/libs/OACX-SDK-v1.3.2.jar` 복사
4. 소스 이식 (패키지명 일괄 변환): `sed -i 's/kr.ucube.integratedauth.identityorchestrator/kr.go.smes.bff/g'`
5. 샘플 코드 삭제 (Board 관련 전체)
6. `NiceTokenStore`, `NiceAuthSessionStore` Redis 버전으로 교체
7. `application.yml` 작성
8. `./gradlew :onepass-bff:compileJava` 빌드 확인
9. 통합 테스트 작성 (MockWebServer로 NICE API 모킹)

### S7-T5 실행 순서 (FE 4개 파일)

1. `types/api/ext/members.ts` — 2개 인터페이스 추가
2. `api/ext/members.ts` — import 수정 + 2개 함수 추가
3. `pages/Mypage/pages/InformationStep3.tsx` — new source로 교체
4. `pages/Mypage/pages/useInfoStore.ts` — loadUserId + 수정 액션 추가
5. `npm run build` 확인

---

## 12. onepass-be 분석 최종 결론

### ✅ 이식 가치 있는 핵심 자산

1. **NiceCryptoUtil**: PBKDF2WithHmacSHA256 + AES-GCM + HMAC-SHA256 완전 구현 — 재사용
2. **NiceAuthService**: 토큰 캐싱 + 세션 저장 + AES-GCM 복호화 플로우 — 검증된 코드
3. **ApiKeyInterceptor**: 상수시간 비교 + 다중 키 회전 — 보안 강점
4. **OriginInterceptor**: Origin/Referer 이중 화이트리스트 — 보안 강점
5. **OacxOutAdapter**: OACX SDK 정확한 래핑 + JWT 복호화
6. **docs/oacx-authentication-flow.md**: 완전한 연동 가이드

### ⚠️ 이식 시 수정 필요

1. **Spring Boot 3.5.x 다운그레이드** — API 호환성 검증
2. **Redis 세션 교체** — NiceTokenStore, NiceAuthSessionStore
3. **ImApiOutPort 구현 완성** — CI → IM API 저장
4. **패키지명 일괄 변환** — ucube → smes

### ❌ 이식 제외 (삭제)

- Board 샘플 코드 전체 (13개 파일)
- H2 schema.sql, data.sql (Board 테이블)
- application-prod.yml H2 설정

---

_문서 끝 — 작성: AI 개발자 (Genspark), 2026-05-10_
