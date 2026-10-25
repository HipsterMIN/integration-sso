# 06 · Module Deep Dive — q-authz (연합 인가 SoR) ★신규

> 이 문서는 정본 스펙 `03g-module-qauthz.md` 가 **부재**하는 공백을 소스 코드 실측으로 임시 보완한다.
> 원천: `q-authz/` 트리 전체 + settings.gradle.kts + PR #205 (5 commits).
> **정본 위계 없음** — 스펙 문서화 될 때까지 관찰 문서로만 활용할 것.

---

## 0. 삼각 요약 (TL;DR)

| 항목 | 값 |
|---|---|
| 신설 시점 | PR #205 (5 commits, 병합 `4f113e8`) |
| 도입 목적 | **부여는 중앙, 해석은 지역** — 기관별 역할 부여 SoR |
| 스택 | Spring Boot 3 (Web + JPA) · PostgreSQL RLS · Flyway V1 · Springdoc |
| 포트 | **8086** |
| 스키마 | `authz` (Postgres, `currentSchema=authz`) |
| 계층 | L1 = 코어(역할 CRUD/부여) · L2 = SCIM 2.0 Groups · Batch = JIT 만료 스케줄러 |
| 인증 | `X-Internal-Api-Key` 상수시간 비교 (fail-closed) |
| ADR 근거 | **미작성** (사후 ADR 필요) |
| 정본 스펙 | **미작성** (본 문서로 임시 대체) |

---

## 1. 모듈 파일 인벤토리

### 1.1 소스 트리 (39 files)
```
q-authz/
├── build.gradle.kts
└── src/main/
    ├── java/kr/go/smes/authz/
    │   ├── QAuthzApplication.java              — @EnableScheduling + 헌법 Javadoc
    │   ├── api/
    │   │   ├── AuthzInternalController.java    — /api/v1/internal/authz/**
    │   │   └── scim/
    │   │       └── ScimGroupController.java    — /scim/v2/Groups
    │   ├── application/
    │   │   ├── AuthzService.java               — grantRole / revokeRole / effectiveRoleCodes / expireOverdue
    │   │   ├── AuthzExpiryScheduler.java       — @Scheduled JIT 만료
    │   │   └── ScimGroupService.java           — reconcile (grant/revoke 재조정)
    │   ├── config/
    │   │   ├── InternalApiKeyInterceptor.java  — X-Internal-Api-Key 상수시간 비교
    │   │   ├── WebMvcConfig.java
    │   │   └── JpaAuditingConfig.java (예상)
    │   └── domain/
    │       ├── AuthzRole.java + Repository
    │       ├── AuthzUserRole.java + Repository
    │       └── AuthzGrantAudit.java + Repository
    └── resources/
        ├── application.yml
        └── db/migration/
            └── V1__create_authz_schema.sql     — 3 tables + RLS policies
```

### 1.2 빌드 의존 (`build.gradle.kts`)
- `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`
- `spring-boot-starter-actuator` (Health/Info)
- `postgresql` JDBC + `flyway-core` + `flyway-database-postgresql`
- `springdoc-openapi-starter-webmvc-ui` (Swagger UI)
- `platform-common` 프로젝트 의존 (AuthResult / PlatformErrorCode 사용 가능)

---

## 2. 데이터 모델 (`V1__create_authz_schema.sql`)

### 2.1 테이블 3종
```sql
-- 역할 카탈로그 (기관별 옵셔너리)
CREATE TABLE authz.authz_role (
  role_id        BIGSERIAL PRIMARY KEY,
  agency_code    VARCHAR(64) NOT NULL,       -- 멀티테넌트 파티션 키
  role_code      VARCHAR(128) NOT NULL,      -- 불투명 문자열 (의미 통일 금지)
  display_name   VARCHAR(255) NOT NULL,
  description    TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (agency_code, role_code)
);

-- 사용자 ↔ 역할 부여 (한시 만료 지원)
CREATE TABLE authz.authz_user_role (
  user_role_id   BIGSERIAL PRIMARY KEY,
  agency_code    VARCHAR(64) NOT NULL,
  qim_user_id    VARCHAR(64) NOT NULL,       -- Q-IM 회원 ID (외부 참조, FK 없음)
  role_code      VARCHAR(128) NOT NULL,
  granted_by     VARCHAR(128),
  granted_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at     TIMESTAMPTZ,                -- NULL = 무기한, 값 = JIT 만료
  revoked_at     TIMESTAMPTZ,                -- soft-delete
  UNIQUE (agency_code, qim_user_id, role_code)
);

-- 감사 로그 (grant / revoke / expire 모두 기록)
CREATE TABLE authz.authz_grant_audit (
  audit_id       BIGSERIAL PRIMARY KEY,
  agency_code    VARCHAR(64) NOT NULL,
  qim_user_id    VARCHAR(64) NOT NULL,
  role_code      VARCHAR(128) NOT NULL,
  action         VARCHAR(16)  NOT NULL,       -- 'GRANT' | 'REVOKE' | 'EXPIRE'
  actor          VARCHAR(128),
  reason         TEXT,
  event_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 2.2 RLS 정책 3개
```sql
ALTER TABLE authz.authz_role         ENABLE ROW LEVEL SECURITY;
ALTER TABLE authz.authz_user_role    ENABLE ROW LEVEL SECURITY;
ALTER TABLE authz.authz_grant_audit  ENABLE ROW LEVEL SECURITY;

CREATE POLICY authz_role_tenant       ON authz.authz_role
  USING (agency_code = current_setting('authz.agency_code', true));
CREATE POLICY authz_user_role_tenant  ON authz.authz_user_role
  USING (agency_code = current_setting('authz.agency_code', true));
CREATE POLICY authz_grant_audit_tenant ON authz.authz_grant_audit
  USING (agency_code = current_setting('authz.agency_code', true));
```

**관찰**:
- `current_setting(..., true)` 의 두 번째 인자 `true` = **missing_ok** — 세션 컨텍스트 미설정 시 NULL 반환 → RLS 정책상 매칭 실패 → 빈 결과.
- 즉, **agency_code 를 SET LOCAL 하지 않으면 조회 결과는 항상 빈 집합**이 된다.
- SET LOCAL 을 어디서 하는지는 소스 확인 필요 (일반 패턴: JPA 트랜잭션 인터셉터). 미확인 시 리스크.

### 2.3 인덱스·제약 요약
- `authz_role`: `UNIQUE(agency_code, role_code)` — 기관별 역할 코드는 유일.
- `authz_user_role`: `UNIQUE(agency_code, qim_user_id, role_code)` — 멱등 부여의 기반.
- `expires_at`, `revoked_at` 은 배치 스캔 대상 → 추가 인덱스 필요 여부 검토 (관찰 → 리스크로).

---

## 3. 애플리케이션 계층

### 3.1 `AuthzService` (핵심)

**Public API (관찰된 메서드 시그니처)**:
```java
UUID grantRole(String agencyCode, String qimUserId, String roleCode,
               Instant expiresAt, String actor, String reason);
void revokeRole(String agencyCode, String qimUserId, String roleCode,
                String actor, String reason);
List<String> effectiveRoleCodes(String agencyCode, String qimUserId);
int expireOverdue(int batchSize);   // 배치 만료 (배치 크기 제한)
```

**동작 정책**:
- `grantRole` = **UPSERT (멱등)**. UNIQUE 충돌 시 `revoked_at=NULL`, `expires_at=신규값` 갱신.
- `revokeRole` = soft-delete (`revoked_at=NOW()`).
- `effectiveRoleCodes` = `revoked_at IS NULL AND (expires_at IS NULL OR expires_at > NOW())` 필터.
- **모든 grant/revoke/expire 는 `authz_grant_audit` 로 이중 기록** (Outbox 아님, 감사 테이블 직접 쓰기).
- Kafka 이벤트 발행 **없음** — 관찰 리스크 (§17).

### 3.2 `AuthzExpiryScheduler`

```java
@Component
@ConditionalOnProperty(name = "authz.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class AuthzExpiryScheduler {

    private static final Duration FIXED_DELAY = Duration.ofSeconds(60);

    @Scheduled(fixedDelayString = "${authz.expiry.scan-interval-ms:60000}")
    public void scan() {
        int expired = authzService.expireOverdue(batchSize);
        if (expired > 0) log.info("[authz.expiry] expired={}", expired);
    }
}
```

**정책**:
- 기본 60초마다 스캔, batch-size 500 (application.yml).
- `@ConditionalOnProperty(matchIfMissing = true)` → **기본 ON**.
- 여러 인스턴스 동시 실행 시 **ShedLock 미사용** (관찰) → 중복 처리 위험. 리스크 등록.
- 단, `expireOverdue` 내부 UPDATE 는 `WHERE revoked_at IS NULL AND expires_at < NOW()` 조건이므로 이중 처리는 **결과 상 무해**하지만 감사 로그 이중 삽입 위험은 잔재.

### 3.3 `ScimGroupService` (L2)

**SCIM 2.0 매핑**:
```
SCIM Group (id, displayName, members[])
   ↕
authz_role (role_code, display_name) + authz_user_role[]
```

**핵심 로직 (`reconcile`)**:
1. 요청 members[] 파싱 (직접 리스트 또는 `members[value eq "x"]` 필터).
2. 현재 `authz_user_role.qim_user_id` 집합과 비교.
3. **없는 건** grantRole 호출.
4. **초과분** revokeRole 호출.
5. 트랜잭션 원자성으로 감사 로그 삽입.

**엔드포인트**:
| 메서드 | 경로 | 목적 |
|---|---|---|
| GET | `/scim/v2/Groups` | 목록 (RLS 필터) |
| POST | `/scim/v2/Groups` | 신규 그룹 = 신규 role |
| PUT | `/scim/v2/Groups/{id}` | 그룹 전체 교체 (reconcile) |
| PATCH | `/scim/v2/Groups/{id}` | 부분 add/remove (reconcile) |
| GET | `/scim/v2/Groups/{id}` | 단건 조회 |

**관찰**:
- PATCH 요청의 `Operations[].value` 파싱: `parseValueEqFilter("members[value eq \"user-123\"]")` → `"user-123"` 추출.
- SCIM 표준 `Operations[].path` 형태의 필터를 정규식 없이 **문자열 트리밍**으로 파싱 (관찰 → 강건성 검토 대상).

---

## 4. API 컨트롤러

### 4.1 `AuthzInternalController` (L1)

Base path: `/api/v1/internal/authz`

| 메서드 | 경로 | 요청 헤더 | 목적 |
|---|---|---|---|
| POST | `/roles` | `X-Internal-Api-Key` | 역할 등록 |
| GET | `/roles?agency=XX` | 동일 | 역할 목록 |
| POST | `/grants` | 동일 | 사용자 역할 부여 |
| DELETE | `/grants/{userId}/{roleCode}` | 동일 | 부여 취소 |
| GET | `/users/{userId}/roles` | 동일 | 사용자 부여 목록 |
| **GET** | **`/users/{userId}/effective-roles?agency=XX`** | 동일 | **★토큰 클레임 원천** |

**응답 예시** (`effective-roles`):
```json
{
  "userId": "qim-user-abc",
  "agencyCode": "SMES-001",
  "roles": ["PLATFORM_ADMIN", "SUPPORT_AGENT"],
  "generatedAt": "2026-06-15T09:30:00Z"
}
```

### 4.2 `ScimGroupController` (L2)
- Base: `/scim/v2/Groups`
- Content-Type: `application/scim+json` (SCIM 2.0 표준)

---

## 5. 인증·인가 자체 보호

### 5.1 `InternalApiKeyInterceptor`

```java
public boolean preHandle(HttpServletRequest req, ...) {
    String actual = req.getHeader("X-Internal-Api-Key");
    if (actual == null || configuredKey == null) {
        res.setStatus(401);
        return false;   // fail-closed
    }
    // 상수시간 비교 (타이밍 공격 방지)
    byte[] a = actual.getBytes(StandardCharsets.UTF_8);
    byte[] b = configuredKey.getBytes(StandardCharsets.UTF_8);
    if (a.length != b.length || !MessageDigest.isEqual(a, b)) {
        res.setStatus(401);
        return false;
    }
    return true;
}
```

**결정적 성질**:
- **fail-closed**: 서버 설정 키가 NULL → 모든 요청 401.
- **타이밍 안전**: `MessageDigest.isEqual` = 상수시간.
- **적용 범위**: `/api/v1/internal/authz/**` + `/scim/v2/Groups/**` (WebMvcConfig 인터셉터 등록 기반).
- **주의**: 이는 서비스 간(내부) 인증만 담당. **엔드유저 인증은 담당하지 않음** — 호출자(IdO)가 이미 인증한 신원(`qimUserId`) 을 신뢰.

### 5.2 위임 신뢰 모델
```
End User ─(fe-session-id 쿠키)→ IdO ─(X-Internal-Api-Key)→ q-authz
                                 │
                        신원 결정: SessionRepository → qimUserId
```
- **q-authz 는 `qimUserId` 위조 여부를 검증하지 않는다** — IdO 를 신뢰한다.
- 이 신뢰의 대가로 q-authz 는 IdO 외부에서 호출 불가 (내부 네트워크·API Key 로 이중 격리).

---

## 6. IdO 통합점 (3개, 코드 관찰)

### 6.1 `QAuthzClient` (IdO 측)
```
POST/GET https://q-authz:8086/api/v1/internal/authz/users/{qimUserId}/effective-roles?agency={code}
Header:  X-Internal-Api-Key: <configured>
Timeout: (검증 필요)
Fail policy: fail-open — 예외/타임아웃 시 emptyList() 반환
```

### 6.2 3중 주입점 (§02 §8 재확인)
1. **CastToken** — `CastTokenServiceImpl.java:108` — `.claim(CLAIM_ROLES, roles)`
2. **HandoffPayload** — `HandoffServiceImpl.java:340-360` — `payload.put("roles", qAuthzClient.getEffectiveRoles(...))`
3. **X-Authz-\* Headers** — `ExtProxyController.java` — 요청 헤더 anti-spoofing → 재주입

**공통 원천**: `QAuthzClient.getEffectiveRoles(qimUserId, "PLATFORM", agencyCode)`.

### 6.3 실패 모드 매트릭스
| q-authz 상태 | IdO 동작 | 결과 |
|---|---|---|
| 정상 | 역할 클레임/헤더 정상 주입 | 인가 정상 |
| 타임아웃 | fail-open → `[]` 반환 | 인가 없이 통과 (**리스크**) |
| 401 (API Key 오류) | fail-open → `[]` 반환 | 인가 없이 통과 (**리스크**) |
| 5xx | fail-open → `[]` 반환 | 동일 |
| 회로 오픈 (Resilience4j 예정) | 회로 오픈 상태 로그 | 미착수 |

→ **정책 결정 필요**: 인가가 없는 상태에서 통과 vs 차단. 현재 = 가용성 우선(fail-open). §13 리스크 참조.

---

## 7. application.yml 정본 설정

```yaml
server:
  port: 8086

spring:
  application:
    name: q-authz
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres?currentSchema=authz
    username: authz
    password: ${AUTHZ_DB_PASSWORD:authz-pass}
  jpa:
    hibernate.ddl-auto: validate    # Flyway 정본, JPA 는 검증만
    properties:
      hibernate.default_schema: authz
  flyway:
    schemas: authz
    default-schema: authz

authz:
  internal-api-key: ${AUTHZ_INTERNAL_API_KEY:}       # ★반드시 설정, 미설정 시 fail-closed
  expiry:
    enabled: true
    scan-interval-ms: 60000
    batch-size: 500
```

**환경변수 표**:
| 이름 | 필수 | 기본 | 목적 |
|---|---|---|---|
| `AUTHZ_DB_PASSWORD` | 예 | authz-pass (dev) | Postgres 접속 |
| `AUTHZ_INTERNAL_API_KEY` | **예 (운영)** | 빈 문자열 (fail-closed) | 서비스 간 인증 |

---

## 8. 관찰된 결여 및 리스크

| # | 항목 | 위험도 | 근거 |
|---|---|---|---|
| L1 | 정본 스펙 `03g-module-qauthz.md` 부재 | 🟡 MED | `docs/internal/spec/` |
| L2 | ADR 부재 (설계 결정 근거) | 🟡 MED | `docs/internal/architecture/` |
| L3 | Kafka 이벤트 발행 없음 (grant/revoke → SP 통보 불가) | 🔴 HIGH | 소스 grep — Kafka 사용처 없음 |
| L4 | Outbox 테이블 부재 (§02 §5.3 참조) | 🔴 HIGH | V1 마이그레이션 |
| L5 | 만료 스케줄러 ShedLock 미사용 → 다중 노드 시 중복 실행 | 🟡 MED | AuthzExpiryScheduler |
| L6 | RLS 세션 컨텍스트 `SET LOCAL` 구현 위치 미확인 | 🟡 MED | JPA 인터셉터 코드 필요 |
| L7 | SCIM PATCH 필터 파싱 문자열 트리밍 방식 | 🟢 LOW | ScimGroupService.parseValueEqFilter |
| L8 | fail-open 정책 — 인가 정보 없이 통과 | 🔴 HIGH | QAuthzClient |
| L9 | 테스트 커버리지 (신설이라 얕음) | 🔴 HIGH | q-authz/src/test 미확인 (§16 참조) |
| L10 | Springdoc OpenAPI 노출 여부 (운영 배포 시 차단 필요) | 🟢 LOW | build.gradle.kts |

---

## 9. 향후 필요 작업 (§17 로 이관)

1. **ADR-2026-006 (가칭)**: 연합 인가 SoR 도입 근거 문서화.
2. **`03g-module-qauthz.md` 정본 스펙 작성**: 본 관찰 문서를 정본화.
3. **Outbox + Kafka 이벤트**: `authz.grant.events` / `authz.revoke.events` 발행.
4. **ShedLock 도입**: 만료 스케줄러 분산 안전화.
5. **fail-closed 모드 옵션**: 인가 정책이 엄격한 기관용.
6. **`SET LOCAL authz.agency_code` 검증 테스트**: RLS 정책 무결성.
7. **Resilience4j CB**: QAuthzClient 회로 차단기.
8. **SCIM 표준 준수 강화**: PATCH 표준 `path` 파서 (SCIM 스펙 §3.5.2).

---

## 10. 참조

- 소스: `/home/user/webapp/q-authz/`
- 통합점: `/home/user/webapp/ido/src/main/java/kr/go/smes/ido/infrastructure/QAuthzClient.java`
- 헌법 Javadoc: `QAuthzApplication.java`
- PR 계보: `aa375ed → 2329200 → 75289d7 → 7892fde → 8a8b355` (`git log` 참조)
- 관련 문서: §00_INDEX 관찰 C, §02 §2.2/§8, §13 (보안), §17 (로드맵)
