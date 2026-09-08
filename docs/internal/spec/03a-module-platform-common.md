# 03-A. platform-common 모듈 상세 명세

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09  
> **모듈 경로**: `idem-common/`  
> **완성도**: 100%

---

## 1. 모듈 역할

`platform-common`은 전 모듈이 공유하는 **도메인 모델·이벤트 정의·에러 코드·유틸리티**를 제공하는 의존성 전용 라이브러리다. Spring Boot 애플리케이션을 직접 실행하지 않으며, 각 서비스 모듈에서 `implementation project(':idem-common')`으로 참조한다.

---

## 2. 패키지 구조

```
idem-common/src/main/java/io/github/hipstermin/idem/common/
├── domain/
│   ├── AuthResult.java          # 인증 결과 핵심 도메인
│   └── HandoffPayload.java      # Handoff 페이로드 전체 정의
├── error/
│   └── PlatformErrorCode.java   # 전체 에러코드 열거형
├── event/
│   ├── DomainEvent.java         # 이벤트 추상 기반 클래스
│   ├── AuthEvent.java           # 인증 이벤트 (Q-Sign → Kafka)
│   ├── HandoffEvent.java        # Handoff 이벤트 (IdO → Kafka)
│   ├── AuditLogEvent.java       # 감사 로그 이벤트 (platform.audit.log)
│   └── AdvisoryEvent.java       # FE 세션 어드바이저리 이벤트
└── exception/
    └── PlatformException.java   # 플랫폼 공통 예외
```

---

## 3. AuthResult 도메인

```java
// io.github.hipstermin.idem.common.domain.AuthResult
public class AuthResult {
    private String authResultId;       // UUID (36자)
    private String correlationId;      // 요청 추적 ID
    private String providerCode;       // IdP 코드 (KAKAO_OIDC, PASS 등)
    private String identifierHash;     // SHA-256(CI 또는 sub)
    private AuthLevel authLevel;       // L1 / L2 / L3
    private VerificationResult verificationResult; // SUCCESS / FAIL / BLOCKED
    private String authMethod;         // KAKAO_OIDC, NAVER_OIDC, PASS 등 (§24.4.1)
    private Instant issuedAt;          // 인증 발생 시각
    private Instant expiresAt;         // 인증 만료 시각

    public enum AuthLevel   { L1, L2, L3 }
    public enum VerificationResult { SUCCESS, FAIL, BLOCKED }
    
    /** auth_method 분류 코드 해석 헬퍼 */
    public static String resolveAuthMethod(String providerCode) {
        return switch (providerCode) {
            case "KAKAO_OIDC"     -> "KAKAO_OIDC";
            case "NAVER_OIDC"     -> "NAVER_OIDC";
            case "PASS_OIDC","PASS" -> "PASS";
            case "FINANCIAL_CERT" -> "FINANCIAL_CERT";
            case "GPKI","GPKI_OIDC" -> "GPKI";
            case "JOINT_CERT"     -> "JOINT_CERT";
            default               -> "UNKNOWN";
        };
    }
}
```

---

## 4. HandoffPayload 도메인

Handoff Ticket 검증 후 유관기관에 반환되는 최종 페이로드.

```java
public class HandoffPayload {
    private String  status;            // APPROVED | REJECTED | HOLD
    private Subject subject;           // 식별자 정보
    private AuthContext authContext;   // 인증 컨텍스트
    private PolicyInfo policyInfo;     // 정책 정보
    private TraceContext traceContext; // 분산 추적 컨텍스트
    private SignatureInfo signature;   // 페이로드 서명

    public static class Subject {
        private String qimUserId;          // Q-IM 내부 UUID
        private String agencySubjectId;    // HMAC-SHA256(qimUserId+agencyCode) Base64URL
        private String identifierHash;     // SHA-256(CI/sub)
        private String instMbrId;          // Q-IM instMbrId (SP 수신 API 연동)
    }

    public static class AuthContext {
        private String authResultId;
        private String authLevel;          // L1 / L2 / L3
        private String authMethod;         // KAKAO_OIDC 등
        private String providerCode;
        private String providerType;       // STANDARD_OIDC / SEMI_STANDARD_OIDC / NON_STANDARD
        private Instant authenticatedAt;
        private Instant expiresAt;
    }

    public static class PolicyInfo {
        private String policyVersion;      // 외부화된 버전 (application.yml)
        private String agencyCode;
        private boolean needsSync;         // Q-IM 선택적 Pull 여부
        private String syncReason;
    }

    public static class TraceContext {
        private String correlationId;      // 업무 추적 기본키
        private String traceparent;        // W3C Trace Context (보조)
        private String feSessionId;
    }
}
```

---

## 5. 에러 코드 체계 (PlatformErrorCode)

### 5.1 Q-Sign 에러

| 코드 | HTTP | 의미 |
|------|------|------|
| `E-QS-001` | 401 | 인증 실패 |
| `E-QS-002` | 423 | 계정 잠금 (AUTH_LOCKED) |
| `E-QS-003` | 409 | 중복 인증 시도 |

### 5.2 브로커 / IdP 에러

| 코드 | HTTP | 의미 |
|------|------|------|
| `E-AUTH-001` | 401 | 인증 실패 (일반) |
| `E-AUTH-002` | 423 | 계정 잠금 |
| `E-IDP-401`  | 502 | 외부 IdP 타임아웃 |
| `E-IDP-402`  | 503 | 외부 IdP 서킷브레이커 OPEN |
| `E-IDP-403`  | 500 | 외부 IdP 예상치 못한 응답 |
| `E-IDP-404`  | 401 | IdP 서명 검증 실패 |

### 5.3 Q-IM 에러

| 코드 | HTTP | 의미 |
|------|------|------|
| `E-IM-201`  | 404 | 사용자 없음 |
| `E-IM-202`  | 403 | 사용자 정지 (SUSPENDED) |
| `E-IM-203`  | 410 | 사용자 탈퇴 (WITHDRAWN) |

### 5.4 IdO / Handoff 에러

| 코드 | HTTP | 의미 |
|------|------|------|
| `E-IDO-101` | 410 | Handoff Ticket 만료 |
| `E-IDO-102` | 409 | Ticket 이미 소비됨 |
| `E-IDO-103` | 409 | Ticket 재사용 시도 (보안 이벤트) |
| `E-IDO-104` | 403 | Ticket 기관 불일치 |

### 5.5 기관(Agency) 에러

| 코드 | HTTP | 의미 |
|------|------|------|
| `E-AGENCY-301` | 403 | 비활성 기관 |
| `E-AGENCY-302` | 403 | 콜백 URL 화이트리스트 위반 |
| `E-AGENCY-303` | 403 | 기관 코드 불일치 |
| `E-AGENCY-304` | 403 | 최소 인증 수준 미달 |
| `E-AGENCY-305` | 503 | 기관 점검 시간 |
| `E-AGENCY-306` | 429 | Rate Limit 초과 (AGENCY_RATE_LIMIT_EXCEEDED) |

### 5.6 운영(OPS) 에러

| 코드 | HTTP | 의미 |
|------|------|------|
| `E-OPS-901` | 503 | 외부 서비스 일시 오류 (`Retry-After` 포함 예정) |

---

## 6. 이벤트 타입 정의

### 6.1 AuthEvent (Q-Sign → `qsign.auth.events`)

```java
public class AuthEvent extends DomainEvent {
    public static final String AUTH_COMPLETED = "AUTH_COMPLETED";
    public static final String AUTH_FAILED    = "AUTH_FAILED";
    public static final String AUTH_LOCKED    = "AUTH_LOCKED";

    private String authResultId;
    private String correlationId;
    private String providerCode;
    private String identifierHash;
    private String authLevel;
    private String authMethod;
    private String sourceSystem;   // "q-sign" | "ido-nonoidc"
    private Instant authenticatedAt;
}
```

### 6.2 HandoffEvent (IdO → `ido.handoff.events`)

```java
public class HandoffEvent extends DomainEvent {
    public static final String HANDOFF_ISSUED   = "HANDOFF_ISSUED";
    public static final String HANDOFF_CONSUMED = "HANDOFF_CONSUMED";
    public static final String HANDOFF_REVOKED  = "HANDOFF_REVOKED";
    public static final String HANDOFF_REUSE    = "HANDOFF_REUSE_ATTEMPT";

    private String ticketId;
    private String correlationId;
    private String agencyCode;
    private String identifierHash;
    private String authLevel;
}
```

### 6.3 AuditLogEvent (IdO → `platform.audit.log`)

2년 보존. PII 원문 금지.

```java
public class AuditLogEvent extends DomainEvent {
    // 카테고리 상수
    public static final String CATEGORY_AUTH    = "AUTH";
    public static final String CATEGORY_HANDOFF = "HANDOFF";
    public static final String CATEGORY_MEMBER  = "MEMBER";
    public static final String CATEGORY_SESSION = "SESSION";
    public static final String CATEGORY_WEBHOOK = "WEBHOOK";
    public static final String CATEGORY_SYSTEM  = "SYSTEM";

    // 결과 상수
    public static final String OUTCOME_SUCCESS  = "SUCCESS";
    public static final String OUTCOME_FAILURE  = "FAILURE";
    public static final String OUTCOME_PARTIAL  = "PARTIAL";

    // 행위자 상수
    public static final String ACTOR_USER   = "USER";
    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_AGENCY = "AGENCY";

    private String eventCategory;
    private String eventAction;
    private String actorType;
    private String actorId;
    private String resourceType;
    private String resourceId;
    private String agencyCode;
    private String sourceSystem;
    private String sourceIp;
    private String outcome;
    private String outcomeDetail;
    private String metadataJson;   // PII 불포함 JSON
}
```

---

## 7. PlatformException

```java
public class PlatformException extends RuntimeException {
    private final PlatformErrorCode errorCode;
    private final String correlationId;
    
    public PlatformException(PlatformErrorCode errorCode, String correlationId) { ... }
    public HttpStatus getHttpStatus() { return errorCode.getHttpStatus(); }
    public String getCode()          { return errorCode.getCode(); }
}
```

---

*다음 문서: [03b-module-qsign.md](03b-module-qsign.md)*
