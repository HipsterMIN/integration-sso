# onepass-agency-sdk

**OnePass 기관 연동 Java SDK** — 기관 시스템이 OnePass Gateway API를 호출하기 위한 경량 클라이언트 라이브러리.

```
좌표(Coordinates):
  groupId:    kr.go.smes
  artifactId: onepass-agency-sdk
  version:    0.1.0-SNAPSHOT  (현재 개발 버전)
```

---

## 목차

1. [특징](#1-특징)
2. [요구사항](#2-요구사항)
3. [빠른 시작 (Quick Start)](#3-빠른-시작-quick-start)
4. [의존성 추가](#4-의존성-추가)
5. [API 레퍼런스](#5-api-레퍼런스)
   - [AgencyGatewayClient](#agencygatewayclient)
   - [InboundEvent](#inboundevent)
   - [OutboundNotifyRequest](#outboundnotifyrequest)
   - [GatewayResponse](#gatewayresponse)
   - [IdempotencyKeyGenerator](#idempotencykeygenerator)
   - [HmacSigner](#hmacsigner)
6. [HTTP 어댑터 교체 가이드](#6-http-어댑터-교체-가이드)
7. [HMAC 서명 설정 가이드](#7-hmac-서명-설정-가이드)
8. [에러 처리 패턴](#8-에러-처리-패턴)
9. [멱등성 키 전략](#9-멱등성-키-전략)
10. [Spring Boot 연동 예시](#10-spring-boot-연동-예시)
11. [배포 절차](#11-배포-절차)
    - [Maven Central 배포](#maven-central-배포)
    - [내부 Nexus 배포](#내부-nexus-배포)
    - [로컬 테스트 배포](#로컬-테스트-배포)
12. [빌드 및 테스트](#12-빌드-및-테스트)
13. [변경 이력](#13-변경-이력)

---

## 1. 특징

| 특징 | 설명 |
|------|------|
| **Java 8+ 호환** | Android, Spring Boot 2.x/3.x, JDK 8~21 모두 지원 |
| **런타임 의존성 ZERO** | JDK 내장 `HttpURLConnection` 기본 구현 — 추가 라이브러리 불필요 |
| **어댑터 교체** | OkHttp3, Apache HttpClient 5.x, Spring RestTemplate 등 교체 가능 |
| **HMAC-SHA256 서명** | `X-Internal-Sig` 헤더 자동 생성, 타이밍 공격 방지 상수시간 검증 |
| **멱등성 키 3전략** | UUID v4, 접두사+UUID, 시퀀스 기반 중 선택 |
| **Builder 패턴** | 불변(Immutable) 클라이언트, 스레드 안전 |

---

## 2. 요구사항

| 항목 | 최소 요건 |
|------|-----------|
| **JDK** | Java 8 이상 (Java 21 빌드, Java 8 바이트코드 출력) |
| **빌드 도구** | Gradle 7.x+ 또는 Maven 3.6+ |
| **네트워크** | OnePass Gateway API 서버 접근 가능 |
| **API 키** | OnePass 관리자로부터 발급받은 `X-Api-Key` |

---

## 3. 빠른 시작 (Quick Start)

### 3.1 기본 사용 (HttpURLConnection — 외부 의존성 없음)

```java
import kr.go.smes.sdk.agency.AgencyGatewayClient;
import kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator;
import kr.go.smes.sdk.agency.model.GatewayResponse;
import kr.go.smes.sdk.agency.model.InboundEvent;

// 1. 클라이언트 생성 (애플리케이션 시작 시 1회 — 싱글턴으로 관리 권장)
AgencyGatewayClient client = AgencyGatewayClient.builder()
        .baseUrl("https://onepass.go.kr")          // OnePass 서버 URL
        .apiKey("your-x-api-key-here")              // 발급받은 API 키
        .agencyCode("MOIS")                         // 기관 코드
        .build();

// 2. 인바운드 이벤트 전송 (기관 → OnePass)
InboundEvent event = InboundEvent.builder()
        .eventType("USER_REGISTERED")
        .agencyCode("MOIS")
        .idempotencyKey(IdempotencyKeyGenerator.generateWithPrefix("MOIS"))
        .payloadJson("{\"userId\":\"user-123\",\"action\":\"sync\"}")
        .build();

GatewayResponse response = client.sendInbound(event);

if (response.isSuccess()) {
    System.out.println("전송 성공: " + response.getCorrelationId());
} else {
    System.err.println("전송 실패: HTTP " + response.getHttpStatus());
}
```

### 3.2 아웃바운드 알림 요청 (OnePass → 기관 Webhook 트리거)

```java
import kr.go.smes.sdk.agency.model.OutboundNotifyRequest;

OutboundNotifyRequest notify = OutboundNotifyRequest.builder()
        .agencyCode("MOIS")
        .eventType("USER_PROVISIONED")
        .payload("{\"status\":\"ok\",\"onepassId\":\"op-abc-123\"}")
        .idempotencyKey(IdempotencyKeyGenerator.generate())
        .build();

GatewayResponse notifyResp = client.triggerOutbound(notify);
System.out.println("Webhook 트리거 완료: " + notifyResp.isSuccess());
```

### 3.3 기관 연동 상태 조회

```java
GatewayResponse status = client.getStatus("MOIS");
System.out.println("연동 상태: " + status.getBody()); // {"agencyCode":"MOIS","active":true,...}
```

---

## 4. 의존성 추가

### Gradle (Kotlin DSL — `build.gradle.kts`)

```kotlin
dependencies {
    // OnePass Agency SDK (런타임 의존성 없음 — JDK 내장 HttpURLConnection 사용)
    implementation("kr.go.smes:onepass-agency-sdk:0.1.0-SNAPSHOT")

    // [선택] OkHttp3 어댑터 사용 시
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // [선택] Apache HttpClient 5.x 어댑터 사용 시
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
}
```

### Gradle (Groovy DSL — `build.gradle`)

```groovy
dependencies {
    implementation 'kr.go.smes:onepass-agency-sdk:0.1.0-SNAPSHOT'

    // [선택] OkHttp3 어댑터 사용 시
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // [선택] Apache HttpClient 5.x 어댑터 사용 시
    implementation 'org.apache.httpcomponents.client5:httpclient5:5.3.1'
}
```

### Maven (`pom.xml`)

```xml
<dependency>
    <groupId>kr.go.smes</groupId>
    <artifactId>onepass-agency-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- [선택] OkHttp3 어댑터 사용 시 -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
    <version>4.12.0</version>
</dependency>

<!-- [선택] Apache HttpClient 5.x 어댑터 사용 시 -->
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5</artifactId>
    <version>5.3.1</version>
</dependency>
```

> **SNAPSHOT 저장소 추가 필요** (Sonatype OSSRH):
> ```kotlin
> repositories {
>     maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
>     mavenCentral()
> }
> ```
> Release 버전 배포 후에는 `mavenCentral()`만으로 사용 가능.

---

## 5. API 레퍼런스

### AgencyGatewayClient

메인 클라이언트. **Builder 패턴**으로 생성하며, 생성된 인스턴스는 **스레드 안전(thread-safe)**하다.

#### Builder 옵션

| 메서드 | 필수 | 기본값 | 설명 |
|--------|------|--------|------|
| `baseUrl(String)` | ✅ | — | OnePass 서버 URL (예: `https://onepass.go.kr`) |
| `apiKey(String)` | ✅ | — | 발급받은 X-Api-Key |
| `agencyCode(String)` | — | `null` | 기관 코드. 설정 시 모든 요청에 `X-Agency-Code` 헤더 자동 추가 |
| `httpAdapter(AgencyHttpAdapter)` | — | `HttpUrlConnectionAdapter` | HTTP 구현체 교체 |
| `hmacSecret(String)` | — | `null` | HMAC 공유 비밀키 (`signRequests=true` 시 필요) |
| `signRequests(boolean)` | — | `false` | `true` 설정 시 `X-Internal-Sig` 헤더 자동 추가 |
| `connectTimeoutMs(int)` | — | `5000` | 연결 타임아웃 (ms). 커스텀 어댑터 주입 시 무시 |
| `readTimeoutMs(int)` | — | `30000` | 읽기 타임아웃 (ms). 커스텀 어댑터 주입 시 무시 |

#### API 메서드

```java
// 인바운드 이벤트 전송: POST /api/v1/agency/gateway/inbound/event
GatewayResponse sendInbound(InboundEvent event)

// 아웃바운드 Webhook 트리거: PATCH /api/v1/agency/gateway/outbound/notify
GatewayResponse triggerOutbound(OutboundNotifyRequest request)

// 기관 연동 상태 조회: GET /api/v1/agency/gateway/status/{agencyCode}
GatewayResponse getStatus(String agencyCode)
```

---

### InboundEvent

기관 → OnePass 방향 이벤트 모델. **불변(Immutable)** 객체.

```java
InboundEvent event = InboundEvent.builder()
        .eventType("USER_REGISTERED")             // [필수] 이벤트 타입
        .agencyCode("MOIS")                       // [필수] 기관 코드
        .idempotencyKey("MOIS-20260514-0001")     // [필수] 멱등성 키
        .payloadJson("{\"userId\":\"u-123\"}")     // [선택] JSON 페이로드 (raw JSON 문자열)
        .correlationId("corr-abc-123")            // [선택] 요청 추적 ID
        .build();

// JSON 직렬화 (외부 라이브러리 불필요)
String json = event.toJsonString();
```

**toJsonString() 출력 예시:**
```json
{
  "event_type": "USER_REGISTERED",
  "agency_code": "MOIS",
  "idempotency_key": "MOIS-20260514-0001",
  "payload": {"userId": "u-123"},
  "correlation_id": "corr-abc-123"
}
```

---

### OutboundNotifyRequest

OnePass → 기관 Webhook 트리거 요청 모델. **불변(Immutable)** 객체.

```java
OutboundNotifyRequest request = OutboundNotifyRequest.builder()
        .agencyCode("MOIS")                           // [필수] 기관 코드
        .eventType("USER_PROVISIONED")                // [필수] 이벤트 타입
        .payload("{\"onepassId\":\"op-abc\"}")        // [선택] JSON 페이로드
        .idempotencyKey(IdempotencyKeyGenerator.generate()) // [선택] 멱등성 키
        .correlationId("corr-xyz")                    // [선택] 요청 추적 ID
        .build();
```

---

### GatewayResponse

모든 API 호출의 반환 타입.

```java
GatewayResponse response = client.sendInbound(event);

int    status       = response.getHttpStatus();       // HTTP 상태 코드 (202 등)
String body         = response.getBody();             // raw JSON 응답 본문
String correlationId = response.getCorrelationId();  // X-Correlation-Id 헤더값
String requestId    = response.getRequestId();        // X-Request-Id 헤더값
boolean success     = response.isSuccess();           // 2xx 여부
boolean conflict    = response.isIdempotencyConflict(); // 409 여부
```

---

### IdempotencyKeyGenerator

멱등성 키 생성 유틸리티. 모든 메서드는 **static**.

```java
// 전략 1: UUID v4 (완전 랜덤)
String key1 = IdempotencyKeyGenerator.generate();
// 예: "550e8400-e29b-41d4-a716-446655440000"

// 전략 2: 접두사 + UUID (기관 코드 포함, 디버그 용이)
String key2 = IdempotencyKeyGenerator.generateWithPrefix("MOIS");
// 예: "MOIS-550e8400-e29b-41d4-a716-446655440000"

// 전략 3: 시퀀스 기반 (순서 추적 가능, 동시성 안전)
String key3 = IdempotencyKeyGenerator.generateSequential("NTS");
// 예: "NTS-1715641234567-0000000042"

// 상수시간 비교 (타이밍 공격 방지)
boolean equal = IdempotencyKeyGenerator.constantTimeEquals(key1, key2);
```

> **전략 선택 가이드:**
> - 단순 요청: `generate()` (UUID v4)
> - 기관 코드 포함하여 로그 추적 용이성 필요: `generateWithPrefix(agencyCode)`
> - 순서 추적 또는 감사 로그 필요: `generateSequential(agencyCode)`

---

### HmacSigner

HMAC-SHA256 서명 생성/검증. **인스턴스 생성 후 재사용** (스레드 안전).

```java
HmacSigner signer = new HmacSigner("shared-secret-key");

// 서명 생성
String sig = signer.sign("POST", "/api/v1/agency/gateway/inbound/event",
                          System.currentTimeMillis(), requestBody);

// 서명 검증 (상수시간 비교 — 타이밍 공격 방지)
boolean valid = signer.verifySignature(receivedSig, computedSig);

// SHA-256 해시 (정적 메서드)
String hash = HmacSigner.sha256Hex("data");
```

---

## 6. HTTP 어댑터 교체 가이드

SDK는 `AgencyHttpAdapter` 인터페이스 기반으로 HTTP 구현체를 완전히 분리한다.

### 6.1 기본 어댑터 — `HttpUrlConnectionAdapter` (외부 의존성 없음)

```java
// 기본값 (별도 설정 불필요)
AgencyGatewayClient client = AgencyGatewayClient.builder()
        .baseUrl("https://onepass.go.kr")
        .apiKey("your-api-key")
        .connectTimeoutMs(5_000)    // 연결 타임아웃 (기본 5초)
        .readTimeoutMs(30_000)      // 읽기 타임아웃 (기본 30초)
        .build();
```

### 6.2 OkHttp3 어댑터 (HTTP/2, 커넥션 풀링)

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

```java
import kr.go.smes.sdk.agency.http.OkHttpAgencyAdapter;
import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

OkHttpClient okHttp = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build();

AgencyGatewayClient client = AgencyGatewayClient.builder()
        .baseUrl("https://onepass.go.kr")
        .apiKey("your-api-key")
        .httpAdapter(new OkHttpAgencyAdapter(okHttp))
        .build();
```

> **커넥션 풀 공유:** 앱 전체에서 `OkHttpClient`를 싱글턴으로 관리하고,
> `new OkHttpAgencyAdapter(sharedOkHttpClient)`로 주입하면 커넥션이 재사용된다.

### 6.3 Apache HttpClient 5.x 어댑터 (엔터프라이즈 환경)

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
}
```

```java
import kr.go.smes.sdk.agency.http.ApacheHttpAgencyAdapter;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;

// 커넥션 풀 설정
PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
cm.setMaxTotal(100);
cm.setDefaultMaxPerRoute(20);

CloseableHttpClient apacheClient = HttpClients.custom()
        .setConnectionManager(cm)
        .build();

ApacheHttpAgencyAdapter adapter = new ApacheHttpAgencyAdapter(apacheClient);

AgencyGatewayClient client = AgencyGatewayClient.builder()
        .baseUrl("https://onepass.go.kr")
        .apiKey("your-api-key")
        .httpAdapter(adapter)
        .build();

// 애플리케이션 종료 시 명시적 close 필요 (외부 주입 클라이언트는 자동 close 안됨)
// adapter.close();  // 또는 try-with-resources
```

### 6.4 람다/커스텀 어댑터 (Spring RestTemplate, WebClient 등)

```java
import kr.go.smes.sdk.agency.http.AgencyHttpAdapter;
import kr.go.smes.sdk.agency.model.GatewayResponse;

// Spring RestTemplate 기반 예시
AgencyHttpAdapter restTemplateAdapter = (method, url, headers, body) -> {
    RestTemplate restTemplate = new RestTemplate();
    HttpHeaders httpHeaders = new HttpHeaders();
    headers.forEach(httpHeaders::set);
    
    HttpEntity<String> entity = new HttpEntity<>(body, httpHeaders);
    ResponseEntity<String> resp = restTemplate.exchange(
            url, HttpMethod.valueOf(method), entity, String.class);
    
    return GatewayResponse.of(
            resp.getStatusCode().value(),
            resp.getBody(),
            resp.getHeaders().getFirst("X-Correlation-Id"),
            resp.getHeaders().getFirst("X-Request-Id")
    );
};

AgencyGatewayClient client = AgencyGatewayClient.builder()
        .baseUrl("https://onepass.go.kr")
        .apiKey("your-api-key")
        .httpAdapter(restTemplateAdapter)
        .build();
```

---

## 7. HMAC 서명 설정 가이드

Sprint 17 이후 OnePass Gateway는 `X-Internal-Sig` 헤더 검증을 강제화한다.
SDK에서 서명을 자동 생성하려면 다음과 같이 설정한다.

### 7.1 서명 활성화

```java
AgencyGatewayClient client = AgencyGatewayClient.builder()
        .baseUrl("https://onepass.go.kr")
        .apiKey("your-api-key")
        .agencyCode("MOIS")
        .hmacSecret("onepass-mois-shared-secret-2026")  // OnePass 관리자로부터 수령
        .signRequests(true)                              // 서명 활성화
        .build();
```

모든 요청에 다음 헤더가 자동으로 추가된다:
- `X-Internal-Sig`: HMAC-SHA256 서명 (64자 HEX)
- `X-Timestamp`: 서명 생성 시각 (epoch milliseconds)

### 7.2 서명 알고리즘 (OnePass 설계서 §17.2)

```
서명 대상 = "{HTTP_METHOD}\n{PATH}\n{TIMESTAMP_EPOCH_MS}\n{SHA256(BODY)}"
X-Internal-Sig = HEX( HMAC-SHA256(sharedSecret, 서명대상) )
```

예시:
```
POST
/api/v1/agency/gateway/inbound/event
1715641234567
a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3

→ HMAC-SHA256(secret, 위 문자열) → X-Internal-Sig
```

### 7.3 서버 측 검증 (수신 Webhook 검증)

```java
// 수신된 Webhook 요청의 서명 검증
HmacSigner signer = new HmacSigner(sharedSecret);

String receivedSig = request.getHeader("X-Internal-Sig");
long   timestamp   = Long.parseLong(request.getHeader("X-Timestamp"));
String body        = readRequestBody(request);

// 직접 서명 계산 후 비교 (상수시간 비교 — 타이밍 공격 방지)
String computed = signer.sign(request.getMethod(), request.getRequestURI(), timestamp, body);
if (!signer.verifySignature(receivedSig, computed)) {
    throw new SecurityException("HMAC 서명 검증 실패 — 위변조 의심");
}

// 타임스탬프 허용 범위 검증 (재전송 공격 방지)
long now = System.currentTimeMillis();
if (Math.abs(now - timestamp) > 300_000L) { // ±5분
    throw new SecurityException("요청 타임스탬프 만료 — 재전송 공격 의심");
}
```

---

## 8. 에러 처리 패턴

```java
import kr.go.smes.sdk.agency.exception.AgencyHttpException;
import kr.go.smes.sdk.agency.exception.AgencySdkException;

try {
    GatewayResponse response = client.sendInbound(event);
    // 성공 처리

} catch (AgencyHttpException e) {
    // HTTP 레벨 오류 (4xx / 5xx 또는 네트워크 장애)
    int status = e.getHttpStatus();           // -1 = 네트워크 오류
    String body = e.getResponseBody();        // 서버 응답 본문 (null = 네트워크 오류)
    String code = e.getErrorCode();           // "SDK_HTTP_ERROR" | "SDK_HTTP_IO_ERROR"

    if (e.isClientError()) {
        // 4xx: 요청 내용 문제 (API 키 오류, 유효성 검사 실패 등)
        if (status == 409) {
            // 멱등성 충돌 — 이미 처리된 키로 재요청
            // 재시도 없이 성공으로 처리하거나 무시
        } else if (status == 401 || status == 403) {
            // 인증/인가 오류 — API 키 또는 기관 코드 확인
        }
    } else if (e.isServerError()) {
        // 5xx: OnePass 서버 내부 오류 — 지수 백오프(Exponential Backoff) 후 재시도
    } else {
        // status == -1: 네트워크 오류 (타임아웃, DNS 실패 등)
    }

} catch (AgencySdkException e) {
    // SDK 설정 오류 (빌더 필수값 누락 등)
    // 예: "SDK_CONFIG_ERROR", "SDK_NULL_PARAM"
    String errorCode = e.getErrorCode();
    // 개발자 실수 — 코드 수정 필요, 재시도 불가
}
```

### 멱등성 충돌 처리 패턴

```java
try {
    GatewayResponse response = client.sendInbound(event);
    processSuccess(response);

} catch (AgencyHttpException e) {
    if (e.getHttpStatus() == 409) {
        // 409 = 이미 처리됨 → 성공으로 간주 (Transactional Outbox 재발행 시나리오)
        log.info("이미 처리된 요청 (멱등성 키: {}), 성공으로 처리", event.getIdempotencyKey());
        processSuccess(null);
    } else {
        throw e;
    }
}
```

---

## 9. 멱등성 키 전략

OnePass Gateway는 **멱등성 키(X-Idempotency-Key)**로 중복 요청을 감지한다.
동일 키로 중복 요청 시 `409 Conflict`를 반환하지만, 이는 정상 동작이다.

| 전략 | 메서드 | 형식 | 적합한 경우 |
|------|--------|------|------------|
| UUID v4 | `generate()` | `550e8400-e29b-41d4-a716-446655440000` | 일반 단발성 요청 |
| 접두사 + UUID | `generateWithPrefix("MOIS")` | `MOIS-550e8400-...` | 기관 코드 포함 추적 필요 |
| 시퀀스 기반 | `generateSequential("NTS")` | `NTS-1715641234567-0000000042` | 순서 보장, 감사 로그 |

**Transactional Outbox 패턴 권장 구현:**

```java
// DB 저장 + Outbox INSERT를 단일 @Transactional로 처리
@Transactional
public void processAndEmit(UserRegisteredEvent domainEvent) {
    // 1. 비즈니스 로직 DB 저장
    userRepository.save(domainEvent.toEntity());

    // 2. Outbox 테이블에 SDK 요청 정보 저장
    //    (멱등성 키는 도메인 이벤트 ID 기반으로 고정 생성 → 재시도 시 동일 키 재사용)
    String idempotencyKey = "MOIS-" + domainEvent.getEventId();
    outboxRepository.save(OutboxRecord.builder()
            .idempotencyKey(idempotencyKey)
            .eventType("USER_REGISTERED")
            .payloadJson(domainEvent.toJson())
            .build());
}

// Relay가 Outbox를 폴링하여 SDK 호출
public void relay(OutboxRecord record) {
    InboundEvent event = InboundEvent.builder()
            .eventType(record.getEventType())
            .agencyCode("MOIS")
            .idempotencyKey(record.getIdempotencyKey())  // 고정 키로 멱등성 보장
            .payloadJson(record.getPayloadJson())
            .build();

    try {
        client.sendInbound(event);
        outboxRepository.markProcessed(record.getId());
    } catch (AgencyHttpException e) {
        if (e.getHttpStatus() == 409) {
            // 이미 처리됨 → Outbox에서 제거
            outboxRepository.markProcessed(record.getId());
        } else {
            // 재시도 가능한 오류 → Outbox에 남겨두고 다음 폴링에서 재시도
            throw e;
        }
    }
}
```

---

## 10. Spring Boot 연동 예시

### 10.1 Bean 등록 (`@Configuration`)

```java
import kr.go.smes.sdk.agency.AgencyGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OnePassSdkConfig {

    @Value("${onepass.gateway.base-url}")
    private String baseUrl;

    @Value("${onepass.gateway.api-key}")
    private String apiKey;

    @Value("${onepass.gateway.agency-code}")
    private String agencyCode;

    @Value("${onepass.gateway.hmac-secret:}")
    private String hmacSecret;

    @Value("${onepass.gateway.sign-requests:false}")
    private boolean signRequests;

    @Bean
    public AgencyGatewayClient agencyGatewayClient() {
        AgencyGatewayClient.Builder builder = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .agencyCode(agencyCode)
                .connectTimeoutMs(5_000)
                .readTimeoutMs(30_000);

        if (signRequests && !hmacSecret.isEmpty()) {
            builder.hmacSecret(hmacSecret).signRequests(true);
        }

        return builder.build();
    }
}
```

### 10.2 `application.yml` 설정

```yaml
onepass:
  gateway:
    base-url: https://onepass.go.kr
    api-key: ${ONEPASS_API_KEY}          # 환경변수에서 주입
    agency-code: MOIS
    hmac-secret: ${ONEPASS_HMAC_SECRET:} # Sprint 17 이후 필수
    sign-requests: true
```

### 10.3 Service 클래스에서 사용

```java
@Service
@RequiredArgsConstructor
public class UserSyncService {

    private final AgencyGatewayClient agencyGatewayClient;
    private final OutboxRepository outboxRepository;

    @Transactional
    public void syncUser(User user) {
        String idempotencyKey = IdempotencyKeyGenerator.generateWithPrefix("MOIS");

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode("MOIS")
                .idempotencyKey(idempotencyKey)
                .payloadJson(user.toJson())
                .build();

        GatewayResponse response = agencyGatewayClient.sendInbound(event);

        if (!response.isSuccess()) {
            throw new RuntimeException("OnePass 전송 실패: HTTP " + response.getHttpStatus());
        }

        log.info("OnePass 전송 완료 — correlationId={}", response.getCorrelationId());
    }
}
```

---

## 11. 배포 절차

### Maven Central 배포

Maven Central 배포는 **Sonatype OSSRH** 계정과 **GPG 서명 키**가 필요하다.

#### 사전 준비

1. **Sonatype OSSRH 계정 발급** — https://issues.sonatype.org/
   - JIRA 이슈 생성하여 `kr.go.smes` 그룹 ID 승인 요청

2. **GPG 키 생성**
   ```bash
   # GPG 키 생성
   gpg --gen-key
   # 권장: RSA 4096bit, 유효기간 2년
   
   # 공개키 서버에 배포 (Maven Central에서 검증)
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
   ```

3. **환경변수 설정**
   ```bash
   # CI/CD 환경 (GitHub Actions Secrets 등)
   export OSSRH_USERNAME="sonatype-username"
   export OSSRH_PASSWORD="sonatype-password"
   export ORG_GRADLE_PROJECT_signingKey="$(gpg --export-secret-keys --armor YOUR_KEY_ID)"
   export ORG_GRADLE_PROJECT_signingPassword="gpg-passphrase"
   ```

#### 배포 실행

```bash
# SNAPSHOT 버전 배포 (스냅샷 저장소)
./gradlew :onepass-agency-sdk:publish --no-daemon

# Release 버전 배포
# 1. build.gradle.kts(루트)에서 version = "0.1.0" 으로 변경 (-SNAPSHOT 제거)
# 2. 배포 실행
./gradlew :onepass-agency-sdk:publish --no-daemon

# 3. Sonatype Staging Repository에서 수동 검토 후 Release
#    https://s01.oss.sonatype.org/#stagingRepositories
#    "Close" → "Release" 클릭
```

#### GitHub Actions CI/CD 예시

```yaml
# .github/workflows/sdk-publish.yml
name: SDK Publish to Maven Central

on:
  push:
    tags:
      - 'sdk-v*'   # 예: sdk-v0.1.0

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Publish to Maven Central
        run: ./gradlew :onepass-agency-sdk:publish --no-daemon
        env:
          OSSRH_USERNAME: ${{ secrets.OSSRH_USERNAME }}
          OSSRH_PASSWORD: ${{ secrets.OSSRH_PASSWORD }}
          ORG_GRADLE_PROJECT_signingKey: ${{ secrets.GPG_SECRET_KEY }}
          ORG_GRADLE_PROJECT_signingPassword: ${{ secrets.GPG_PASSPHRASE }}
```

---

### 내부 Nexus 배포

정부망 내부 배포 시 사용. **GPG 서명 불필요** (`SKIP_SIGNING=true`).

#### 사전 준비

1. Nexus Repository Manager 3.x 설치 및 `kr.go.smes` 그룹 저장소 구성
2. 배포 계정 생성 (`deployment` 롤 필요)

#### 환경변수 설정

```bash
export NEXUS_URL="http://nexus.internal.go.kr/repository/maven-releases/"
export NEXUS_USERNAME="deployment-user"
export NEXUS_PASSWORD="deployment-password"
export SKIP_SIGNING="true"   # 내부 배포 시 서명 생략
```

> SNAPSHOT 버전은 `maven-snapshots/`, RELEASE 버전은 `maven-releases/`로 URL 설정.

#### 배포 실행

```bash
SKIP_SIGNING=true ./gradlew :onepass-agency-sdk:publish --no-daemon
```

#### 사용자 측 Maven 설정 (`settings.xml`)

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>Nexus</id>
      <username>your-username</username>
      <password>your-password</password>
    </server>
  </servers>
</settings>
```

#### 사용자 측 `build.gradle.kts`

```kotlin
repositories {
    maven {
        url = uri("http://nexus.internal.go.kr/repository/maven-public/")
        isAllowInsecureProtocol = true  // HTTP 허용 (내부망)
        credentials {
            username = System.getenv("NEXUS_USERNAME") ?: ""
            password = System.getenv("NEXUS_PASSWORD") ?: ""
        }
    }
}
```

---

### 로컬 테스트 배포

배포 전 로컬 `~/.m2` 저장소에 설치하여 연동 확인.

```bash
# 로컬 Maven 저장소에 설치 (서명 불필요)
SKIP_SIGNING=true ./gradlew :onepass-agency-sdk:publishToMavenLocal --no-daemon

# 설치 확인
ls ~/.m2/repository/kr/go/smes/onepass-agency-sdk/
```

사용 측 `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()   // 로컬 저장소 우선 조회
    mavenCentral()
}
```

---

### 배포 체크리스트

배포 전 다음 항목을 순서대로 확인한다:

```
□ 1. 버전 확인
      - 루트 build.gradle.kts: version = "x.y.z" (-SNAPSHOT 제거 여부 확인)
      - CHANGELOG.md 최신 릴리즈 항목 작성 완료

□ 2. 빌드 & 테스트 통과
      ./gradlew :onepass-agency-sdk:test --no-daemon
      → 모든 테스트(S16-T1 ~ T8, HmacSigner, Builder, InboundEvent) PASS

□ 3. JAR 패키징 확인
      ./gradlew :onepass-agency-sdk:build --no-daemon
      → build/libs/ 에 다음 3개 파일 존재 확인:
        - onepass-agency-sdk-x.y.z.jar           (메인 JAR)
        - onepass-agency-sdk-x.y.z-sources.jar   (소스 JAR)
        - onepass-agency-sdk-x.y.z-javadoc.jar   (Javadoc JAR)

□ 4. POM 검증
      ./gradlew :onepass-agency-sdk:generatePomFileForMavenJavaPublication --no-daemon
      → build/publications/mavenJava/pom-default.xml 내용 확인
        (name, description, url, license, developer, scm 필드 누락 없음)

□ 5. 로컬 설치 후 연동 테스트
      SKIP_SIGNING=true ./gradlew :onepass-agency-sdk:publishToMavenLocal --no-daemon
      → 연동 대상 프로젝트에서 mavenLocal() 저장소로 의존성 추가 후 동작 확인

□ 6. Git 태그 생성
      git tag -a sdk-v0.1.0 -m "onepass-agency-sdk 0.1.0 release"
      git push origin sdk-v0.1.0

□ 7. 배포 실행
      (Maven Central)  ./gradlew :onepass-agency-sdk:publish --no-daemon
      (내부 Nexus)      SKIP_SIGNING=true ./gradlew :onepass-agency-sdk:publish --no-daemon

□ 8. Maven Central 릴리즈 (해당 시)
      Sonatype OSSRH → Staging Repositories → Close → Release

□ 9. 릴리즈 노트 작성 (GitHub Releases)
      - 변경 사항 요약
      - 의존성 추가 방법
      - 마이그레이션 가이드 (Breaking Change 있는 경우)
```

---

## 12. 빌드 및 테스트

### 빌드

```bash
# SDK 모듈만 빌드
./gradlew :onepass-agency-sdk:build --no-daemon

# Docker 없는 환경 (CI)
DOCKER_UNAVAILABLE=true ./gradlew :onepass-agency-sdk:build --no-daemon
```

### 테스트 실행

```bash
# SDK 테스트 전용
./gradlew :onepass-agency-sdk:test --no-daemon

# 테스트 리포트 확인
open onepass-agency-sdk/build/reports/tests/test/index.html
```

**테스트 목록:**

| 테스트 ID | 설명 |
|-----------|------|
| S16-T1 | `sendInbound` 정상 호출 → HTTP 202 Accepted |
| S16-T2 | 요청 헤더 `X-Api-Key`, `X-Idempotency-Key`, `X-Agency-Code` 포함 검증 |
| S16-T3 | 서버 409 응답 → `AgencyHttpException` 발생 확인 |
| S16-T4 | HMAC 서명 활성화 → `X-Internal-Sig`, `X-Timestamp` 헤더 포함 검증 |
| S16-T5 | `idempotencyKey` 미지정 → UUID v4 자동 생성 |
| S16-T6 | `triggerOutbound` → PATCH `/outbound/notify`, HTTP 200 |
| S16-T7 | `getStatus` → GET `/status/{agencyCode}` 경로 검증 |
| S16-T8 | `HttpUrlConnectionAdapter` 실제 HTTP 왕복 (MockWebServer) |
| HmacSigner | sign + verifySignature 상수시간 비교 정확성 |
| Builder | `baseUrl` 미설정 → `AgencySdkException` |
| InboundEvent | 필수 필드 미설정 → `IllegalArgumentException` |
| InboundEvent | `toJsonString()` payload embed 정확성 |
| IdempotencyKeyGenerator | 3가지 전략 + 상수시간 비교 |

### Javadoc 생성

```bash
./gradlew :onepass-agency-sdk:javadoc --no-daemon
open onepass-agency-sdk/build/docs/javadoc/index.html
```

---

## 13. 변경 이력

[CHANGELOG.md](CHANGELOG.md) 참고.

---

## 라이선스

MIT License — 상세 내용은 [LICENSE](../LICENSE) 파일 참고.

---

> **문의:** OnePass Platform Team — onepass@smes.go.kr
> **이슈:** https://github.com/HipsterMIN/integration-sso/issues
