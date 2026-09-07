# OnePass Agency SDK 사용 가이드

> **명칭 안내 (2026-09-07)** — 이 문서의 `onepass.agent.*` 설정 키, `onepass-agent.properties`, `ONEPASS_*` 환경변수, `OnePass-*` 헤더, `OnePassAgent*` 클래스명은 개명 4단계(Java 패키지·런타임 식별자) 전까지 **구명을 그대로 사용**한다. 모듈·이미지·파일 이름만 Idem 신명이다. 대응표: [docs/naming.md](naming.md) §3.

> **대상**: 유관기관 개발자
> **버전**: `idem-sdk-java 0.1.0-SNAPSHOT`
> **최종 수정**: 2026-05-18
> **브랜치**: `shipster`

---

## 이 문서를 읽기 전에

이 가이드는 **지금 당장 OnePass Gateway에 이벤트를 전송**하는 데 필요한 것만 담았다.  
SDK를 받아서 → 클라이언트를 만들고 → 이벤트를 보내고 → 응답을 처리하는 네 단계가 전부다.

**준비물 체크리스트** — 이 세 가지가 없으면 시작할 수 없다.

| 항목 | 설명 | 수령처 |
|---|---|---|
| **`X-Agency-Key`** (API 키) | 기관 인증에 사용하는 비밀 키 | OnePass 플랫폼 운영팀 |
| **기관 코드** (`agencyCode`) | 기관을 식별하는 영문 코드 (예: `MOIS`, `NTS`) | OnePass 플랫폼 운영팀 |
| **IdO 서버 URL** | OnePass Gateway 서버 주소 (포트 8083) | 인프라팀 / 운영팀 |

> HMAC 서명 기능(`hmacSecret`)은 Sprint 17 Phase 4 이후에 필수화될 예정이다.  
> 지금은 없어도 동작한다. [HMAC 서명 설정](#5-hmac-서명-설정-phase-4-대비) 참고.

---

## 목차

1. [SDK 추가](#1-sdk-추가)
2. [클라이언트 생성](#2-클라이언트-생성)
3. [이벤트 전송 — `sendInbound()`](#3-이벤트-전송--sendinbound)
4. [응답 처리 — `GatewayResponse`](#4-응답-처리--gatewayresponse)
5. [HMAC 서명 설정 (Phase 4 대비)](#5-hmac-서명-설정-phase-4-대비)
6. [예외 처리](#6-예외-처리)
7. [멱등성 키 전략](#7-멱등성-키-전략)
8. [HTTP 어댑터 교체 (선택)](#8-http-어댑터-교체-선택)
9. [Spring Bean으로 등록하기](#9-spring-bean으로-등록하기)
10. [연동 상태 조회 — `getStatus()`](#10-연동-상태-조회--getstatus)
11. [전체 코드 예시](#11-전체-코드-예시)
12. [자주 묻는 질문](#12-자주-묻는-질문)

---

## 1. SDK 추가

### Gradle (권장)

`build.gradle.kts` 또는 `build.gradle`에 아래 한 줄을 추가한다.

```kotlin
// build.gradle.kts
dependencies {
    implementation("kr.go.smes:idem-sdk-java:0.1.0-SNAPSHOT")
}
```

```groovy
// build.gradle
dependencies {
    implementation 'kr.go.smes:idem-sdk-java:0.1.0-SNAPSHOT'
}
```

### Maven

`pom.xml`에 추가한다.

```xml
<dependency>
    <groupId>kr.go.smes</groupId>
    <artifactId>onepass-agency-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 확인 사항

- **Java 8 이상**이면 동작한다 (Android, Spring Boot 2.x/3.x 포함).
- **런타임 의존성이 없다.** JDK 내장 `HttpURLConnection`을 기본으로 사용한다.
- OkHttp, Apache HttpClient를 이미 사용하는 프로젝트라면 해당 어댑터로 교체 가능하다. → [8장 참고](#8-http-어댑터-교체-선택)

---

## 2. 클라이언트 생성

`AgencyGatewayClient`는 **한 번 만들면 전체 애플리케이션에서 재사용**한다.  
매 요청마다 새로 만들지 않는다. 이 객체는 스레드 안전(thread-safe)하다.

```java
import kr.go.smes.sdk.agency.AgencyGatewayClient;

AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("http://ido-service:8083")     // ① IdO 서버 URL
    .apiKey("여기에-API-키-입력")             // ② 운영팀에서 받은 X-Agency-Key
    .agencyCode("MOIS")                     // ③ 기관 코드
    .build();
```

**반드시 확인할 것 세 가지:**

| 항목 | 잘못된 예 | 올바른 예 |
|---|---|---|
| `baseUrl` | `http://idem-console:3000` (UI 서버 X) | `http://ido-service:8083` (IdO 서버) |
| `baseUrl` | `http://ido-service:8083/` (슬래시 붙이면 안 됨) | `http://ido-service:8083` |
| `apiKey` | 비워두거나 null | 운영팀에서 받은 실제 키 값 |

> **로컬 개발 환경**에서는 `http://localhost:8083`을 사용한다.  
> **Docker Compose** 환경에서는 컨테이너 서비스명을 사용한다. (예: `http://ido:8083`)  
> **Kubernetes** 환경에서는 서비스 DNS를 사용한다. (예: `http://ido-service:8083`)

### 타임아웃 변경이 필요할 때

기본값은 연결 5초, 읽기 30초다. 변경이 필요하면 Builder에서 설정한다.

```java
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("http://ido-service:8083")
    .apiKey("여기에-API-키-입력")
    .agencyCode("MOIS")
    .connectTimeoutMs(3_000)    // 연결 타임아웃: 3초
    .readTimeoutMs(10_000)      // 읽기 타임아웃: 10초
    .build();
```

---

## 3. 이벤트 전송 — `sendInbound()`

기관 시스템에서 OnePass로 이벤트를 보낼 때 사용하는 유일한 메서드다.

### 기본 전송 (가장 간단한 형태)

```java
import kr.go.smes.sdk.agency.model.InboundEvent;
import kr.go.smes.sdk.agency.model.GatewayResponse;

// 이벤트 객체 생성
InboundEvent event = InboundEvent.builder()
    .eventType("USER_REGISTERED")               // ① 이벤트 타입 (필수)
    .agencyCode("MOIS")                         // ② 기관 코드 (필수)
    .payloadJson("{\"userId\":\"hong-001\"}")    // ③ 전송할 데이터 (선택, 기본값 {})
    .build();
// ※ idempotencyKey를 지정하지 않으면 UUID v4를 자동 생성한다

// 전송
GatewayResponse response = client.sendInbound(event);

// 결과 확인
if (response.isSuccess()) {
    System.out.println("전송 완료. 추적 ID: " + response.getCorrelationId());
}
```

### `InboundEvent` 필드 설명

| 필드 | 필수 | 설명 |
|---|---|---|
| `eventType` | **필수** | 이벤트 종류. OnePass 운영팀과 사전에 협의한 코드를 사용한다 (예: `USER_REGISTERED`, `BIZ_CONVERTED`) |
| `agencyCode` | **필수** | 기관 코드. Builder에 설정한 값과 동일하게 넣는다 |
| `idempotencyKey` | 선택 | 생략하면 UUID v4 자동 생성. **같은 이벤트를 재전송할 때는 반드시 처음과 동일한 키를 지정해야 한다** |
| `payloadJson` | 선택 | JSON 객체(`{...}`) 또는 배열(`[...]`) 형식만 허용. 생략하면 `{}` |
| `correlationId` | 선택 | 요청 추적 ID. 로그 연계 시 유용. 생략하면 자동 생성 |

### `payloadJson` 작성 규칙

**반드시 JSON 객체나 배열 형식**이어야 한다. 일반 문자열은 허용하지 않는다.

```java
// ✅ 올바른 예
.payloadJson("{\"userId\":\"hong-001\",\"action\":\"register\"}")
.payloadJson("{}")                       // 빈 객체도 허용
.payloadJson("[{\"id\":1},{\"id\":2}]")  // 배열도 허용

// ❌ 잘못된 예 — IllegalArgumentException 발생
.payloadJson("hong-001")                 // 일반 문자열 불가
.payloadJson("null")                     // JSON 값 불가
```

### 멱등성 키를 직접 지정하는 경우

동일 이벤트를 안전하게 재전송하려면 매번 같은 키를 사용해야 한다.

```java
import kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator;

// 이벤트마다 고유한 키를 미리 생성해서 저장해 두고 재사용한다
String idempotencyKey = IdempotencyKeyGenerator.generateWithPrefix("MOIS");
// 예: "MOIS-550e8400-e29b-41d4-a716-446655440000"

InboundEvent event = InboundEvent.builder()
    .eventType("USER_REGISTERED")
    .agencyCode("MOIS")
    .idempotencyKey(idempotencyKey)     // 재전송 시 이 값을 그대로 재사용
    .payloadJson("{\"userId\":\"hong-001\"}")
    .build();
```

---

## 4. 응답 처리 — `GatewayResponse`

`sendInbound()`는 항상 `GatewayResponse`를 반환하거나, 오류 시 예외를 던진다.

### 응답 객체 주요 메서드

| 메서드 | 반환 타입 | 설명 |
|---|---|---|
| `isSuccess()` | `boolean` | HTTP 2xx이면 `true` |
| `isIdempotencyConflict()` | `boolean` | HTTP 409이면 `true` (중복 이벤트) |
| `getHttpStatus()` | `int` | HTTP 상태 코드 (202, 409 등) |
| `getBody()` | `String` | 서버 응답 전체 (raw JSON 문자열) |
| `getBodyField(String key)` | `String` | 응답 JSON에서 특정 필드 값 추출 |
| `getCorrelationId()` | `String` | 서버 응답의 추적 ID |
| `getRequestId()` | `String` | 서버 응답의 요청 ID |

### 응답 처리 패턴

```java
GatewayResponse response = client.sendInbound(event);

if (response.isSuccess()) {
    // ✅ 정상 수신 완료 (202 Accepted)
    String correlationId = response.getCorrelationId();
    System.out.println("수신 완료 / 추적 ID: " + correlationId);

} else if (response.isIdempotencyConflict()) {
    // ⚠️ 이미 처리된 키 (409 Conflict) → 재전송 불필요, 정상 케이스로 처리
    System.out.println("이미 처리된 이벤트입니다 (멱등성 충돌).");
}
```

### `getBodyField()` — 응답 JSON에서 값 꺼내기

서버 응답이 `{"status":"accepted","requestId":"abc-123"}` 형태일 때:

```java
GatewayResponse response = client.sendInbound(event);

// 특정 필드 값 추출 (JSON 파서 없이 사용 가능)
String status    = response.getBodyField("status");    // "accepted"
String requestId = response.getBodyField("requestId"); // "abc-123"
String missing   = response.getBodyField("no_such");   // null (없는 필드는 null)

// 숫자, 불리언도 문자열로 반환된다
String code      = response.getBodyField("code");      // "202"
String active    = response.getBodyField("active");    // "true"
```

> **주의**: 최상위(depth 1) 스칼라 필드만 추출할 수 있다.  
> 중첩 객체 내부 값은 `getBody()`로 전체 JSON을 받아서 직접 파싱해야 한다.

---

## 5. HMAC 서명 설정 (Phase 4 대비)

> 현재(`IDO_HMAC_SIG_REQUIRED=false`)는 서명이 없어도 동작한다.  
> **Sprint 17 Phase 4 전환 이후** 서명이 없으면 모든 요청이 401로 거부된다.  
> 전환 전에 미리 설정하고 Staging 환경에서 검증해 두는 것을 강력히 권장한다.

### 서명 활성화 방법

`hmacSecret`은 **API 키(`X-Agency-Key`)와 완전히 다른 별개의 키**다.  
OnePass 운영팀에서 기관별로 별도 발급한다.

```java
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("http://ido-service:8083")
    .apiKey("여기에-API-키-입력")
    .agencyCode("MOIS")
    .hmacSecret("운영팀에서-받은-HMAC-전용-비밀키")  // ← API 키와 다른 별개 키
    .signRequests(true)                              // ← 이 줄이 있어야 서명 활성화
    .build();
```

서명이 활성화되면 모든 `sendInbound()` 요청에 `X-Internal-Sig` 헤더가 자동으로 추가된다.  
개발자가 직접 서명을 계산하거나 헤더를 설정할 필요가 없다.

### 서명 알고리즘 (참고용)

```
서명 페이로드 = "{기관코드}:{멱등성키}:{현재시각epoch초}"
X-Internal-Sig = HEX( HMAC-SHA256(hmacSecret, 서명페이로드) )
```

SDK가 자동으로 처리하므로 직접 구현할 필요 없다.  
서버는 ±60초 범위에서 서명을 검증한다. 서버와 시계 편차가 60초를 초과하면 서명 실패가 발생할 수 있다.

---

## 6. 예외 처리

SDK는 두 가지 예외만 던진다. 둘 다 `RuntimeException`이므로 `try-catch`를 강제하지 않지만,  
**프로덕션 코드에서는 반드시 처리해야 한다.**

| 예외 클래스 | 발생 조건 |
|---|---|
| `AgencyHttpException` | 서버가 4xx/5xx 응답을 반환하거나, 네트워크 연결 자체가 실패했을 때 |
| `AgencySdkException` | Builder 설정 오류, HMAC 키 이상 등 SDK 내부 설정 오류 |

### 예외 처리 코드 패턴

```java
import kr.go.smes.sdk.agency.exception.AgencyHttpException;
import kr.go.smes.sdk.agency.exception.AgencySdkException;

try {
    GatewayResponse response = client.sendInbound(event);

    if (response.isSuccess()) {
        // ✅ 정상 처리
        log.info("이벤트 전송 성공. 추적 ID: {}", response.getCorrelationId());

    } else if (response.isIdempotencyConflict()) {
        // ⚠️ 409: 이미 처리된 이벤트 → 성공으로 간주하고 넘어간다
        log.warn("중복 이벤트 (409). 이미 처리된 멱등성 키: {}", event.getIdempotencyKey());
    }

} catch (AgencyHttpException e) {

    if (e.isClientError()) {
        // ❌ 4xx: 요청 자체가 잘못됨 (재시도해도 같은 결과)
        // 예: 401(API 키 오류), 400(파라미터 오류), 422(유효성 오류)
        log.error("요청 오류 ({}): {}", e.getHttpStatus(), e.getMessage());
        // → 관리자에게 알림, 로그 기록 후 처리 중단

    } else if (e.isServerError()) {
        // ❌ 5xx: 서버 오류 → SDK가 이미 3회 재시도한 후 이 예외를 던진다
        log.error("서버 오류 ({}). 잠시 후 재시도 필요: {}", e.getHttpStatus(), e.getMessage());
        // → 별도 재시도 큐에 넣거나, 알림 발송

    } else if (e.getHttpStatus() == -1) {
        // ❌ 네트워크 오류 (타임아웃, DNS 실패 등) → 역시 3회 재시도 후 발생
        log.error("네트워크 오류: {}", e.getMessage());
        // → 네트워크 상태 점검
    }

} catch (AgencySdkException e) {
    // ❌ SDK 설정 오류 (Builder 값 누락, HMAC 키 이상 등)
    // 애플리케이션 기동 시점에 발생하므로 서버 시작 실패로 처리하는 것이 좋다
    log.error("SDK 설정 오류 [{}]: {}", e.getErrorCode(), e.getMessage());
    throw e;  // 기동 실패 처리
}
```

### 재시도 정책 (SDK 내장)

`HttpUrlConnectionAdapter`(기본 어댑터)는 오류 발생 시 **자동으로 최대 3회 재시도**한다.

| 상황 | 재시도 여부 | 대기 시간 |
|---|---|---|
| 5xx 서버 오류 | ✅ 재시도 | 200ms → 400ms → 800ms (지수 백오프) |
| 네트워크 오류 (IOException) | ✅ 재시도 | 200ms → 400ms → 800ms |
| 4xx 클라이언트 오류 | ❌ 즉시 예외 | 없음 (재시도해도 동일한 결과) |

3회 재시도를 모두 소진한 후에도 실패하면 `AgencyHttpException`이 던져진다.

---

## 7. 멱등성 키 전략

멱등성 키(`idempotencyKey`)는 **동일 이벤트가 두 번 처리되지 않도록** 서버가 사용하는 식별자다.  
서버는 이 키를 **24시간** 보관하며, 같은 키가 24시간 이내에 다시 들어오면 중복으로 처리한다.

### 언제 어떤 전략을 쓸까

```java
import kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator;

// 전략 1: 자동 생성 (가장 간단) — 단발성 이벤트에 적합
InboundEvent event = InboundEvent.builder()
    .eventType("USER_REGISTERED")
    .agencyCode("MOIS")
    // idempotencyKey 생략 → UUID v4 자동 생성
    .build();

// 전략 2: 접두사 포함 UUID — 운영 로그 추적에 유리
String key = IdempotencyKeyGenerator.generateWithPrefix("MOIS");
// 예: "MOIS-550e8400-e29b-41d4-a716-446655440000"

// 전략 3: 시퀀스 기반 — 디버그·정렬이 필요할 때
String seqKey = IdempotencyKeyGenerator.generateSequential("MOIS");
// 예: "MOIS-1715641234567-0000000001"
```

### 재전송 시 반드시 지켜야 할 규칙

```java
// ✅ 올바른 재전송: 동일 키 재사용
String idempotencyKey = IdempotencyKeyGenerator.generateWithPrefix("MOIS");
// → 이 키를 DB 또는 캐시에 저장해 둔다

InboundEvent event = InboundEvent.builder()
    .eventType("USER_REGISTERED")
    .agencyCode("MOIS")
    .idempotencyKey(idempotencyKey)   // ← 저장해 둔 키를 그대로 사용
    .payloadJson("{\"userId\":\"hong-001\"}")
    .build();

// 첫 번째 시도
try {
    client.sendInbound(event);
} catch (AgencyHttpException e) {
    // 실패 시 동일 event 객체를 그대로 재전송한다 (동일 idempotencyKey 유지)
    client.sendInbound(event);
}

// ❌ 잘못된 재전송: 매번 새 이벤트 객체 생성 → 키가 달라져 중복 이벤트 발생
// client.sendInbound(InboundEvent.builder()...build()); // 새 UUID 생성됨!
```

---

## 8. HTTP 어댑터 교체 (선택)

기본 어댑터(`HttpUrlConnectionAdapter`)는 별도 의존성 없이 동작한다.  
이미 OkHttp나 Apache HttpClient를 사용하는 프로젝트라면 해당 어댑터로 교체하면 된다.

### OkHttp3 어댑터

```kotlin
// build.gradle.kts — 의존성 추가
dependencies {
    implementation("kr.go.smes:idem-sdk-java:0.1.0-SNAPSHOT")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

```java
import kr.go.smes.sdk.agency.http.OkHttpAgencyAdapter;
import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

OkHttpClient okClient = new OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build();

AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("http://ido-service:8083")
    .apiKey("여기에-API-키-입력")
    .agencyCode("MOIS")
    .httpAdapter(new OkHttpAgencyAdapter(okClient))   // ← 어댑터 교체
    .build();
```

> OkHttp 어댑터는 재시도 로직이 없다. 필요하면 OkHttp의 `Interceptor`로 직접 구현한다.

### Apache HttpClient 5.x 어댑터

```kotlin
// build.gradle.kts — 의존성 추가
dependencies {
    implementation("kr.go.smes:idem-sdk-java:0.1.0-SNAPSHOT")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
}
```

```java
import kr.go.smes.sdk.agency.http.ApacheHttpAgencyAdapter;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;

CloseableHttpClient apacheClient = HttpClients.createDefault();

AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("http://ido-service:8083")
    .apiKey("여기에-API-키-입력")
    .agencyCode("MOIS")
    .httpAdapter(new ApacheHttpAgencyAdapter(apacheClient))  // ← 어댑터 교체
    .build();
```

> 공공기관 인트라넷·프록시 환경에서는 Apache HttpClient 5.x 어댑터를 권장한다.

---

## 9. Spring Bean으로 등록하기

Spring Boot 프로젝트에서는 `AgencyGatewayClient`를 싱글톤 Bean으로 등록한다.

### `application.yml` 설정

```yaml
onepass:
  agency:
    base-url: http://ido-service:8083
    api-key: ${ONEPASS_AGENCY_API_KEY}        # 환경 변수로 관리
    agency-code: MOIS
    hmac-secret: ${ONEPASS_AGENCY_HMAC_SECRET} # 환경 변수로 관리
    sign-requests: false                        # Phase 4 전환 시 true로 변경
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
```

### Configuration 클래스

```java
import kr.go.smes.sdk.agency.AgencyGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OnePassSdkConfig {

    @Bean
    public AgencyGatewayClient agencyGatewayClient(
            @Value("${onepass.agency.base-url}")         String baseUrl,
            @Value("${onepass.agency.api-key}")          String apiKey,
            @Value("${onepass.agency.agency-code}")      String agencyCode,
            @Value("${onepass.agency.hmac-secret:}")     String hmacSecret,
            @Value("${onepass.agency.sign-requests:false}") boolean signRequests,
            @Value("${onepass.agency.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${onepass.agency.read-timeout-ms:30000}")   int readTimeoutMs
    ) {
        AgencyGatewayClient.Builder builder = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .agencyCode(agencyCode)
                .connectTimeoutMs(connectTimeoutMs)
                .readTimeoutMs(readTimeoutMs);

        if (signRequests && hmacSecret != null && !hmacSecret.isEmpty()) {
            builder.hmacSecret(hmacSecret).signRequests(true);
        }

        return builder.build();
    }
}
```

### Service 클래스에서 사용

```java
import kr.go.smes.sdk.agency.AgencyGatewayClient;
import kr.go.smes.sdk.agency.exception.AgencyHttpException;
import kr.go.smes.sdk.agency.model.GatewayResponse;
import kr.go.smes.sdk.agency.model.InboundEvent;
import kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator;
import org.springframework.stereotype.Service;

@Service
public class UserEventService {

    private final AgencyGatewayClient onePassClient;

    // 생성자 주입 (권장)
    public UserEventService(AgencyGatewayClient onePassClient) {
        this.onePassClient = onePassClient;
    }

    public void notifyUserRegistered(String userId) {
        String idempotencyKey = IdempotencyKeyGenerator.generateWithPrefix("MOIS");

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode("MOIS")
                .idempotencyKey(idempotencyKey)
                .payloadJson("{\"userId\":\"" + userId + "\"}")
                .build();

        try {
            GatewayResponse response = onePassClient.sendInbound(event);

            if (response.isSuccess()) {
                log.info("[OnePass] 회원가입 이벤트 전송 완료. userId={}, correlationId={}",
                        userId, response.getCorrelationId());
            } else if (response.isIdempotencyConflict()) {
                log.warn("[OnePass] 중복 이벤트 (409). 이미 처리된 키. userId={}", userId);
            }

        } catch (AgencyHttpException e) {
            log.error("[OnePass] 이벤트 전송 실패. userId={}, status={}, msg={}",
                    userId, e.getHttpStatus(), e.getMessage());
            // 필요 시 알림 발송, 재시도 큐 등록 등
        }
    }
}
```

---

## 10. 연동 상태 조회 — `getStatus()`

기관의 OnePass 연동 상태를 조회한다. 초기 설정 확인, 모니터링 대시보드 등에 활용한다.

```java
GatewayResponse status = client.getStatus("MOIS");

if (status.isSuccess()) {
    // 응답 예시: {"agencyCode":"MOIS","active":true,"pendingProvisioning":0}
    String active   = status.getBodyField("active");              // "true"
    String pending  = status.getBodyField("pendingProvisioning"); // "0"

    System.out.println("연동 활성화 여부: " + active);
    System.out.println("처리 대기 건수: " + pending);
}
```

`agencyCode` 인자를 `null`로 넘기면 Builder에 설정한 기관 코드를 사용한다.

```java
GatewayResponse status = client.getStatus(null);  // Builder의 agencyCode 사용
```

---

## 11. 전체 코드 예시

순수 Java 환경 기준으로 처음부터 끝까지 동작하는 예시다.

```java
import kr.go.smes.sdk.agency.AgencyGatewayClient;
import kr.go.smes.sdk.agency.exception.AgencyHttpException;
import kr.go.smes.sdk.agency.exception.AgencySdkException;
import kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator;
import kr.go.smes.sdk.agency.model.GatewayResponse;
import kr.go.smes.sdk.agency.model.InboundEvent;

public class OnePassExample {

    public static void main(String[] args) {

        // ─── Step 1. 클라이언트 생성 (애플리케이션당 1회) ───────────────────────
        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("http://localhost:8083")
                .apiKey("stub-api-key-dev")
                .agencyCode("MOIS")
                .build();

        // ─── Step 2. 멱등성 키 생성 ──────────────────────────────────────────
        // 재전송이 필요한 경우를 대비해 키를 변수에 보관한다
        String idempotencyKey = IdempotencyKeyGenerator.generateWithPrefix("MOIS");

        // ─── Step 3. 이벤트 객체 생성 ─────────────────────────────────────────
        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode("MOIS")
                .idempotencyKey(idempotencyKey)
                .payloadJson("{\"userId\":\"hong-gildong\",\"email\":\"hong@mois.go.kr\"}")
                .correlationId("req-20260518-001")   // 선택: 로그 추적용
                .build();

        // ─── Step 4. 전송 및 결과 처리 ────────────────────────────────────────
        try {
            GatewayResponse response = client.sendInbound(event);

            if (response.isSuccess()) {
                System.out.println("✅ 전송 완료");
                System.out.println("   HTTP 상태  : " + response.getHttpStatus());
                System.out.println("   추적 ID    : " + response.getCorrelationId());
                System.out.println("   응답 상태  : " + response.getBodyField("status"));

            } else if (response.isIdempotencyConflict()) {
                System.out.println("⚠️ 중복 이벤트 (이미 처리됨) — 정상 케이스");
            }

        } catch (AgencyHttpException e) {
            if (e.isClientError()) {
                System.err.println("❌ 요청 오류 (" + e.getHttpStatus() + "): " + e.getMessage());
                System.err.println("   → API 키, 기관 코드, 파라미터 값을 확인하세요.");

            } else if (e.isServerError()) {
                System.err.println("❌ 서버 오류 (" + e.getHttpStatus() + "): " + e.getMessage());
                System.err.println("   → SDK가 3회 재시도 후 실패. 운영팀에 문의하세요.");

            } else {
                System.err.println("❌ 네트워크 오류: " + e.getMessage());
                System.err.println("   → IdO 서버 URL과 네트워크 연결을 확인하세요.");
            }

        } catch (AgencySdkException e) {
            System.err.println("❌ SDK 설정 오류 [" + e.getErrorCode() + "]: " + e.getMessage());
        }
    }
}
```

---

## 12. 자주 묻는 질문

---

**Q. `triggerOutbound()`는 언제 쓰나요?**

쓰지 않는다. 이 메서드는 OnePass **내부 운영자 전용**으로, 기관 시스템에서 호출하면 Kubernetes IngressRule에서 네트워크 차단된다. IDE에서 `@Deprecated` 경고가 뜨는 것이 정상이다. 기관이 OnePass로 이벤트를 보낼 때는 오직 `sendInbound()`만 사용한다.

---

**Q. `idempotencyKey`를 꼭 직접 지정해야 하나요?**

아니다. 지정하지 않으면 UUID v4를 자동으로 생성한다. 단발성 이벤트(재전송이 없는 경우)라면 생략해도 된다. **동일 이벤트를 재전송할 가능성이 있다면** 키를 미리 생성해서 저장해 두고 재사용해야 한다.

---

**Q. `payloadJson`에 특수문자가 있으면 어떻게 해야 하나요?**

JSON 표준에 맞게 이스케이프한다. 직접 문자열을 조립하는 것보다 Jackson이나 Gson을 사용하는 것이 안전하다.

```java
// Jackson을 사용하는 경우
ObjectMapper mapper = new ObjectMapper();
Map<String, Object> payload = new HashMap<>();
payload.put("userId", "hong-001");
payload.put("name", "홍길동");
String payloadJson = mapper.writeValueAsString(payload);
// → {"userId":"hong-001","name":"홍길동"}

InboundEvent event = InboundEvent.builder()
    .eventType("USER_REGISTERED")
    .agencyCode("MOIS")
    .payloadJson(payloadJson)
    .build();
```

---

**Q. `baseUrl`에 포트를 꼭 붙여야 하나요?**

IdO 서버의 기본 포트는 **8083**이다. 로드밸런서나 프록시를 통해 표준 포트(80/443)로 서비스하는 경우엔 생략해도 된다. 운영팀에 확인한다.

---

**Q. 연결 오류가 나는데 어떻게 디버깅하나요?**

아래 순서로 확인한다.

1. `baseUrl`이 올바른지 확인한다 (`http://ido-service:8083`, 슬래시 없음).
2. `curl` 또는 브라우저로 `http://{baseUrl}/api/v1/agency/gateway/status/{agencyCode}` 직접 호출해 본다.
3. 방화벽 / 보안 정책에서 포트 8083이 열려 있는지 확인한다.
4. SDK 로그에서 `AgencyHttpException` 메시지의 HTTP 상태 코드를 확인한다.

---

**Q. 여러 스레드에서 동시에 `client.sendInbound()`를 호출해도 괜찮나요?**

괜찮다. `AgencyGatewayClient`는 내부 상태를 변경하지 않는 불변 객체이므로 스레드 안전(thread-safe)하다. 하나의 인스턴스를 멀티스레드 환경에서 공유해서 사용한다.

---

**Q. HMAC 서명 없이도 지금 당장 연동 테스트를 할 수 있나요?**

할 수 있다. `IDO_HMAC_SIG_REQUIRED=false`(기본값)인 동안은 `X-Internal-Sig` 헤더가 없어도 서버가 요청을 처리한다. `hmacSecret`과 `signRequests` 설정 없이 3장의 기본 예시대로 진행하면 된다.

---

## 참고

- OnePass IdO 서버 포트: **8083**
- OnePass FE 서버 포트: **3000** (UI 전용, API 호출 대상 아님)
- 멱등성 키 서버 보관 TTL: **24시간**
- HMAC 서명 서버 검증 TTL: **±60초**
- SDK 기본 재시도: **3회** (200ms → 400ms → 800ms 지수 백오프)
- SDK 최소 Java 버전: **Java 8**
- 문의: OnePass 플랫폼 운영팀
