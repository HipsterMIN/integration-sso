# Operational Readiness Analysis — v2.2
**작성일**: 2026-05-08  
**대상 브랜치**: `genspark_ai_developer`  
**분석 범위**: q-sign · q-im · ido · onepass-fe · agency-stub  
**목적**: v1.4.2 아키텍처 원칙 재검토 — 유관기관 외부망 배치 원칙 보완  
**전 버전**: operational-readiness-analysis.md (2026-05-07)

---

## 변경 이력

| 버전 | 날짜 | 주요 변경 |
|------|------|-----------|
| v1.0 | 2026-05-07 | 최초 분석 |
| v2.0 | 2026-05-08 | v1.4.0/v1.4.1 버그 수정 반영 (9개 항목) |
| v2.1 | 2026-05-08 | P0~P1 보완 작업 반영: Dockerfile 생성, docker-compose 완성, identifierHash SHA-256 일관화, HMAC-SHA256 내부 서명 교체 |
| **v2.2** | **2026-05-08** | **아키텍처 원칙 재검토: 유관기관 외부망 배치 원칙 명확화, README 구성도 수정, 설계 보완 문서 신규 작성** |

---

## 1. 현재 서비스 기동 상태 (로컬 샌드박스 검증 완료)

| 모듈 | 포트 | Flyway | 상태 | 비고 |
|------|------|--------|------|------|
| Q-IM | 8082 | V1–V2 (MariaDB) | ✅ UP | `--profile app` 대상 |
| Q-Sign | 8081 | V1–V5 (PostgreSQL/qsign) | ✅ UP | Kafka TimeoutException = WARN 수준, 무시 가능 |
| IdO | 8083 | V1–V6 (PostgreSQL/ido) | ✅ UP | Kafka TimeoutException = WARN 수준, 무시 가능 |
| Keycloak | 8088 | — | ✅ `--profile keycloak` | realm-export.json 검증 완료 |

> Kafka TimeoutException은 `missing-topics-fatal: false` 설정으로 기동에는 영향 없음 (topics는 kafka-init 컨테이너가 초기화).

---

## 2. v1.4.1 완료된 버그 수정 목록 (9개)

### 2.1 Q-Sign

| # | 이슈 | 원인 | 해결 | 파일 |
|---|------|------|------|------|
| BUG-01 | V2 SQL IMMUTABLE 오류 | `idx_used_nonce_expires`의 `WHERE expires_at < NOW()` — PostgreSQL 부분 인덱스에 비결정적 함수 사용 불가 | 조건 제거 → 일반 인덱스로 교체 | `V2__add_audit_log.sql` |
| BUG-02 | `retryCount` 타입 불일치 | DB: `SMALLINT`, JPA entity: `int` → Hibernate 스키마 검증 실패 | `QSignOutboxRecord.java` 필드 타입 `int` → `short` 변경 | `QSignOutboxRecord.java` |
| BUG-03 | `AuthResultRepository` 빈 없음 | 인터페이스만 존재, 구현체 미작성 | `AuthResultRepositoryImpl` + `AuthResultJpaEntity` + `AuthResultJpaRepository` 신규 작성 | `infrastructure/jpa/` |
| BUG-04 | `LockRepository` 빈 없음 | 인터페이스만 존재, 구현체 미작성 | `LockRepositoryImpl` + `AuthLockJpaEntity` + `AuthLockJpaRepository` 신규 작성 | `infrastructure/jpa/` |
| BUG-05 | `auth_method` 컬럼 누락 | JPA entity에 `auth_method` 필드 추가, DB에 컬럼 없음 | V5 migration 추가: `ALTER TABLE qsign.auth_result ADD COLUMN IF NOT EXISTS auth_method VARCHAR(30)` | `V5__add_auth_method.sql` |

### 2.2 IdO

| # | 이슈 | 원인 | 해결 | 파일 |
|---|------|------|------|------|
| BUG-06 | `QimEventConsumer` 빈 이름 충돌 | `infrastructure.QimEventConsumer`(stub)와 `kafka.QimEventConsumer`(완전 구현) 동시 존재 | `infrastructure/QimEventConsumer.java` 삭제 | — |
| BUG-07 | `cacheManager` 빈 중복 | `RedisConfig`(RedisCacheManager)와 `IdoWebConfig`(TtlConcurrentMapCacheManager) 동시 정의 | `IdoWebConfig` CacheManager 제거, `RedisConfig`에 `keycloakJwks` 캐시 추가, `@EnableCaching` 통합 | `RedisConfig.java`, `IdoWebConfig.java` |
| BUG-08 | V4 마이그레이션 IMMUTABLE 오류 | `sp_receiver_idempotency`의 부분 인덱스 `WHERE expires_at > NOW()` | 일반 인덱스로 교체 + 주석 보완 | `V4__add_qim_sp_receiver.sql` |
| BUG-09 | V5 마이그레이션 재실행 오류 | `processed_event`, `last_event_version` 테이블·인덱스 이미 존재 | `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`로 교체 | `V5__add_processed_event.sql` |
| BUG-10 | `AesSharedKeyDecryptor` Base64 오류 | `application-local.yml`의 `aes-shared-key` 값이 plain text — Base64 디코딩 실패 | 32바이트 Base64 인코딩 키(`bG9jYWwtdGVzdC1zZWNyZXQtMzJieXRlcy1wYWRkZWQ=`) 로 교체 | `application-local.yml` |

---

## 3. v2.1 추가 완료 항목

### 3.1 P0: Dockerfile 전 모듈 신규 작성 ✅

| 파일 | 설명 |
|------|------|
| `idem-gate/Dockerfile` | 멀티스테이지 빌드 (JDK 21 build → JRE 21 runtime), non-root 사용자 `qsign` |
| `idem-hub/Dockerfile` | 멀티스테이지 빌드, non-root 사용자 `ido` |
| `idem-registry/Dockerfile` | 멀티스테이지 빌드, non-root 사용자 `qim` |

**빌드 명령**:
```bash
# 루트에서 실행
docker build -f idem-gate/Dockerfile -t onepass-qsign:latest .
docker build -f idem-hub/Dockerfile     -t onepass-ido:latest .
docker build -f idem-registry/Dockerfile    -t onepass-qim:latest .
```

### 3.2 P0: docker-compose.yml onepass-qsign 서비스 추가 ✅

`infra/docker/docker-compose.yml`에 `onepass-qsign` 서비스 블록 추가:

```yaml
onepass-qsign:
  image: onepass-qsign:latest
  ports:
    - "8081:8081"
  environment:
    SPRING_PROFILES_ACTIVE: docker
    DB_HOST: postgres
    REDIS_HOST: redis
    KAFKA_SERVERS: kafka:29092
    IDO_BASE_URL: http://onepass-ido:8083
    KEYCLOAK_URL: http://keycloak:8080
    KEYCLOAK_REALM: onepass
    QSIGN_KEYCLOAK_CLIENT_ID: q-sign-client
    QSIGN_KEYCLOAK_CLIENT_SECRET: ${QSIGN_KEYCLOAK_CLIENT_SECRET:-change-me}
    QSIGN_KEYCLOAK_REDIRECT_URI: http://localhost:8081/api/v1/oidc/keycloak/callback
  networks:
    onepass-net:
      ipv4_address: 172.20.0.24
  profiles:
    - app
```

이로써 `--profile app` 기동 시 Q-IM + **Q-Sign** + IdO 모두 Docker 컨테이너로 구동 가능.

### 3.3 P1: `AuthServiceImpl` placeholder identifierHash → SHA-256 ✅

**수정 전** (이슈): `identifierHash(providerCode + "-" + correlationId)` — 평문 연결, 해시 없음  
**수정 후**: `computeIdentifierHash(providerCode + ":" + correlationId)` — SHA-256(UTF-8) HexFormat 출력

```java
// AuthServiceImpl.java — issueFromOidc 경로 (현 설계에서 실제 미사용)
String identifierHash = computeIdentifierHash(providerCode + ":" + correlationId);

private String computeIdentifierHash(String input) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
}
```

> ⚠️ 이 코드 경로(`issueFromOidc`)는 현재 설계에서 실제로 호출되지 않는다.  
> Keycloak 흐름(흐름 A) → `KeycloakCallbackService.computeIdentifierHash(sub)` 사용  
> 비OIDC 흐름(흐름 B) → IdO `AesSharedKeyDecryptor.computeIdentifierHash(plainCi)` 값을 그대로 수신

### 3.4 P1: `buildInternalSig` PoC 서명 → HMAC-SHA256 ✅

**수정 전**: `"sig-" + correlationId.replace("-", "").substring(0, 8)` — 예측 가능한 PoC 서명  
**수정 후**: `HMAC-SHA256("{correlationId}:{epochSeconds}", internalSigSecret)` — 진짜 HMAC 서명

```java
// KeycloakCallbackService.java
private String buildInternalSig(String correlationId) {
    long epochSeconds = System.currentTimeMillis() / 1000L;
    String payload = correlationId + ":" + epochSeconds;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
            internalSigSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
}
```

**환경변수**: `IDO_INTERNAL_SIG_SECRET` (docker-compose.yml에 주입, 기본값 `change-me-32bytes-padding-secret`)  
**수신 측(IdO)**: `X-Internal-Sig` 헤더 HMAC 재계산 + `±60초` 타임스탬프 유효성 검사 적용 필요 (현재 미구현 — 아래 잔여 이슈 참조)

---

## 4. OIDC 흐름 시나리오 검증 매트릭스

| 시나리오 | 로컬 구동 | Docker(`--profile app`) | Docker + Keycloak | 조건 |
|----------|-----------|------------------------|-------------------|------|
| mode=keycloak (Kakao/Naver) | ✅ | ✅ (v2.1 Q-Sign 추가) | ✅ | Keycloak Client Secret 필요 |
| mode=qsign (직접) | ✅ | ✅ | ✅ | — |
| 비OIDC (PASS/GPKI) | ✅ | ✅ | ✅ | IdO NonOidcBroker → Q-Sign |
| Handoff Ticket 발급/검증 | ✅ | ✅ | ✅ | AES-256-GCM + HMAC 완전 구현 |

---

## 5. 파일 구조 (현재 확정)

```
webapp/
├── idem-gate/
│   ├── Dockerfile                               ★ 신규 (v2.1)
│   └── src/main/java/kr/go/smes/qsign/
│       ├── application/
│       │   └── AuthServiceImpl.java             ★ SHA-256 identifierHash, 미사용 경로 주석 강화
│       ├── keycloak/
│       │   └── KeycloakCallbackService.java     ★ HMAC-SHA256 buildInternalSig
│       └── infrastructure/
│           ├── AuthResultRepository.java        (인터페이스)
│           ├── AuthResultRepositoryImpl.java    ★ v1.4.1 신규
│           ├── LockRepository.java             (인터페이스)
│           ├── LockRepositoryImpl.java          ★ v1.4.1 신규
│           └── jpa/
│               ├── entity/AuthResultJpaEntity.java  ★ v1.4.1 신규
│               ├── entity/AuthLockJpaEntity.java    ★ v1.4.1 신규
│               ├── repository/AuthResultJpaRepository.java  ★ v1.4.1 신규
│               └── repository/AuthLockJpaRepository.java   ★ v1.4.1 신규
│   └── src/main/resources/
│       ├── application.yml
│       ├── application-local.yml                ★ v1.4.1 신규
│       └── db/migration/
│           ├── V1__create_schema.sql
│           ├── V2__add_audit_log.sql            ★ BUG-01 수정
│           ├── V3__add_oidc_session.sql
│           ├── V4__add_processed_event.sql
│           └── V5__add_auth_method.sql          ★ v1.4.1 신규
│
├── idem-hub/
│   ├── Dockerfile                               ★ 신규 (v2.1)
│   └── src/main/java/kr/go/smes/idem-hub/
│       └── config/
│           ├── RedisConfig.java                 ★ BUG-07: keycloakJwks 추가, @EnableCaching 통합
│           └── IdoWebConfig.java                ★ BUG-07: cacheManager 제거, @EnableScheduling 유지
│   └── src/main/resources/
│       ├── application-local.yml                ★ v1.4.1 신규
│       └── db/migration/
│           ├── V1~V3 (기존)
│           ├── V4__add_qim_sp_receiver.sql      ★ BUG-08 수정
│           ├── V5__add_processed_event.sql      ★ BUG-09 수정
│           └── V6__add_broker_audit_log.sql
│
├── idem-registry/
│   ├── Dockerfile                               ★ 신규 (v2.1)
│   └── src/main/resources/
│       └── application-local.yml                ★ v1.4.1 신규
│
└── infra/docker/
    └── docker-compose.yml                       ★ onepass-qsign 서비스 추가 (v2.1)
```

---

## 6. 잔여 이슈 (우선순위별)

### P0 — 실운용 전 필수 해결

| ID | 항목 | 위치 | 내용 |
|----|------|------|------|
| P0-01 | Kakao OAuth 실제 Client ID/Secret | `docker-compose.yml` `QSIGN_KEYCLOAK_CLIENT_SECRET` | Keycloak Admin Console에서 `q-sign-client` Client Secret 발급 후 `.env`에 저장 |
| P0-02 | IdO `X-Internal-Sig` 헤더 검증 미구현 | `OidcCompleteController.java` | Q-Sign이 HMAC-SHA256 서명 전송하나, IdO 수신 측에서 헤더를 검증하지 않음 (PoC 수용 가능, 운영 전 필수) |

### P1 — 다음 스프린트 우선 처리

| ID | 항목 | 위치 | 내용 |
|----|------|------|------|
| P1-01 | Resilience4j 어노테이션 실 적용 | `KeycloakCallbackService`, `QimClientImpl` | `application.yml`에 circuitbreaker/retry 설정 완비되어 있으나 실제 `@CircuitBreaker`, `@Retry` 어노테이션 미적용 |
| P1-02 | 기관 Attribute 필터링 | `PolicyEngineImpl` | `filterAttributes()` 빈 구현 — 기관 정책에 따른 속성 마스킹 미구현 |
| P1-03 | IdO `X-Internal-Sig` 검증 구현 | `OidcCompleteController` / Filter | HMAC-SHA256 재계산 + `±60초` 타임스탬프 유효성 검사 |
| P1-04 | `AgencyEntryController.callIdoVerify()` 실 구현 | `agency-stub` | 현재 Stub 반환 → RestTemplate으로 실제 IdO Verify API 호출, API Key 헤더 전송 구현 |
| P1-05 | IdO Webhook 디스패처 신규 구현 | `idem-hub/webhook/` 신규 패키지 | Handoff REVOKED/Advisory 이벤트 발생 시 기관 Webhook URL로 HTTPS POST (Resilience4j retry 포함) |
| P1-06 | agency-stub Docker 격리 | `docker-compose.yml` | agency-stub을 `onepass-net`에서 제거 → 별도 `agency-net` 또는 host 모드 |

### P2 — 중기 구현 대상

| ID | 항목 | 위치 | 내용 |
|----|------|------|------|
| P2-01 | 회원 전환(ConversionSession) 상태 기계 | `q-im` | `member-conversion-implementation-plan.md` 참조, 4단계 상태 전이 미구현 |
| P2-02 | 탈퇴 4가지 흐름 | `q-im`, `ido` | IMMEDIATE / SCHEDULED / AGENCY_REQUESTED / ADMIN_FORCED 미구현 |
| P2-03 | 동의 테이블 V3 migration | `ido` | `ido.consent_record`, `ido.consent_version` 스키마 추가 예정 |
| P2-04 | `AgencyMemberLookupService` | `ido` | 기관 회원 조회 API 스텁 상태 |
| P2-05 | Non-OIDC 흐름 `issueFromOidc` 교체 | `AuthServiceImpl` | PASS/GPKI 실 운용 시 idToken sub 파싱 후 `SHA-256(sub)` 기반 `identifierHash` 산출로 교체 필요 |
| P2-06 | 이벤트 폴링 API 구현 | `idem-hub/api/AgencyEventController` | 기관 Webhook 대안 — GET `/api/v1/agency/events?since={ts}` |
| P2-07 | agency-stub Kafka 직접 구독 제거 | `idem-tenant-sample/HandoffEventConsumer` | PoC 코드 정리 — 운영 전 Webhook/Polling 방식으로 교체 필수 |

---

## 7. Docker 전체 스택 기동 절차 (최소 full-stack)

### 7.1 사전 조건

```bash
# 1. 각 서비스 JAR 빌드
./gradlew :idem-gate:bootJar :idem-hub:bootJar :idem-registry:bootJar -x test

# 2. Docker 이미지 빌드 (루트에서)
docker build -f idem-gate/Dockerfile -t onepass-qsign:latest .
docker build -f idem-hub/Dockerfile     -t onepass-ido:latest .
docker build -f idem-registry/Dockerfile    -t onepass-qim:latest .

# 3. 환경변수 파일 생성 (.env)
cat > infra/docker/.env << 'EOF'
QSIGN_KEYCLOAK_CLIENT_SECRET=<Keycloak Admin에서 발급한 q-sign-client Secret>
IDO_INTERNAL_SIG_SECRET=<32바이트 이상의 랜덤 시크릿>
EOF
```

### 7.2 기동 순서

```bash
cd infra/docker

# ── 1단계: 인프라 (DB · Redis · Kafka · kafka-init) ───────────────
docker compose up -d postgres mariadb redis zookeeper kafka kafka-init

# ── 2단계: 애플리케이션 ──────────────────────────────────────────
docker compose --profile app up -d

# ── 3단계 (선택): Keycloak OIDC 브로커 ───────────────────────────
docker compose --profile keycloak up -d
```

### 7.3 헬스체크

```bash
curl http://localhost:8081/actuator/health  # Q-Sign
curl http://localhost:8082/actuator/health  # Q-IM
curl http://localhost:8083/actuator/health  # IdO
curl http://localhost:8088/health/ready     # Keycloak
```

### 7.4 기동 IP 할당표

| 서비스 | 컨테이너 IP | 포트 |
|--------|-----------|------|
| postgres | 172.20.0.10 | 5432 |
| redis | 172.20.0.11 | 6379 |
| zookeeper | 172.20.0.12 | 2181 |
| kafka | 172.20.0.13 | 9092 |
| schema-registry | 172.20.0.14 | 8085 |
| kafka-ui | 172.20.0.15 | 8090 |
| redis-insight | 172.20.0.16 | 5540 |
| pgadmin | 172.20.0.17 | 5050 |
| keycloak | 172.20.0.18 | 8088 |
| onepass-ido | 172.20.0.19 | 8083 |
| onepass-react | 172.20.0.20 | 3001 |
| mariadb | 172.20.0.21 | 3306 |
| onepass-qim | 172.20.0.22 | 8082 |
| adminer | 172.20.0.23 | 8091 |
| **onepass-qsign** | **172.20.0.24** | **8081** ★ 신규 |

---

## 8. 보안 체크리스트

| 항목 | 상태 | 비고 |
|------|------|------|
| PII 비보관 — identifierHash = SHA-256(CI/sub) | ✅ | KeycloakCallbackService, AesSharedKeyDecryptor |
| 내부 서명 HMAC-SHA256 | ✅ | KeycloakCallbackService.buildInternalSig (v2.1) |
| 내부 서명 수신 측 검증 | ⚠️ | IdO OidcCompleteController — P1-03 |
| Nonce 검증 (replay attack 방어) | ✅ | KeycloakCallbackService step 4 |
| CSRF state 검증 (1회 소비) | ✅ | KeycloakStateStore (Redis TTL 300s) |
| JWT RS256 서명 검증 | ✅ | KeycloakJwksVerifier |
| AES-256-CBC 복호화 | ✅ | AesSharedKeyDecryptor (PASS/GPKI CI 복호화) |
| AES-256-GCM Handoff 암호화 | ✅ | HandoffCryptoService |
| Kafka 멱등 컨슈머 | ✅ | IdempotentEventStore (processed_event 테이블) |
| DB Outbox 패턴 | ✅ | Q-Sign, IdO, Q-IM 전 모듈 |
| **기관 외부망 원칙 명문화** | ✅ | README 구성도 수정 + agency-external-arch-supplement.md 신규 작성 (v2.2) |
| **내부 Kafka 외부 노출 차단** | ⚠️ | agency-stub PoC 한정 직접 구독 중 — P1-06, P2-07에서 해결 |
| **기관 API 인증 (X-Agency-Key)** | ⚠️ | HandoffController에 API Key 검증 인터셉터 미구현 — P1-04와 연계 |

---

## 9. 로컬 개발 구동 (단일 명령)

```bash
# PostgreSQL/MariaDB/Redis가 이미 로컬에서 실행 중임을 가정

# Q-Sign
SPRING_PROFILES_ACTIVE=local \
  SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/onepass?currentSchema=qsign" \
  SPRING_DATASOURCE_USERNAME=onepass SPRING_DATASOURCE_PASSWORD=onepass \
  SPRING_DATA_REDIS_HOST=localhost \
  java -jar idem-gate/build/libs/q-sign-0.1.0-SNAPSHOT.jar &

# IdO
SPRING_PROFILES_ACTIVE=local \
  SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/onepass?currentSchema=ido" \
  SPRING_DATASOURCE_USERNAME=onepass SPRING_DATASOURCE_PASSWORD=onepass \
  SPRING_DATA_REDIS_HOST=localhost \
  java -jar idem-hub/build/libs/ido-0.1.0-SNAPSHOT.jar &

# Q-IM
SPRING_PROFILES_ACTIVE=local \
  java -jar idem-registry/build/libs/q-im-0.1.0-SNAPSHOT.jar &
```

---

## 10. 종합 평가

### 구현 완성도

| 영역 | 완성도 | 비고 |
|------|--------|------|
| Q-Sign 인증 결과 SoR (JPA 완전 구현) | **100%** | AuthResult + AuthLock 엔티티·Repository 완비 |
| Q-Sign Keycloak OIDC 흐름 | **100%** | HMAC-SHA256 내부 서명 포함 |
| Q-Sign DB 마이그레이션 | **100%** | V1–V5 완료 |
| IdO 정책 오케스트레이터 | **95%** | X-Internal-Sig 검증 미구현 |
| IdO DB 마이그레이션 | **100%** | V1–V6 완료 |
| Q-IM 사용자 SoR | **90%** | 전환·탈퇴 흐름 P2 |
| Docker 컨테이너화 | **100%** | Dockerfile 전 모듈 완비, docker-compose 완성 ★ |
| 보안 (서명/암호화/해시) | **95%** | 수신 측 서명 검증 P1 |
| **아키텍처 설계 원칙 (외부망 격리)** | **90%** | 원칙 명문화 완료, PoC 코드 경고 추가 / 실 격리는 P1-P2 |

### 최소 운용 가능(MVO) 판단

> **`--profile app` 기동 기준**: Q-IM · Q-Sign · IdO 3개 모듈이 Docker 컨테이너로 정상 기동되며,  
> Keycloak OIDC(Kakao/Naver) 흐름 및 비OIDC(PASS/GPKI) 흐름이 End-to-End로 동작한다.  
> **Kakao Client Secret 실 값 교체**가 유일한 P0 잔여 조건이며, 이것이 충족되면 PoC 데모 가능 상태.

### v2.2 신규 발견 — 아키텍처 원칙 보완 사항

| 항목 | 발견 경위 | 처리 결과 |
|------|-----------|----------|
| README 구성도에 agency-stub이 내부망으로 표시 | 아키텍처 다이어그램 재검토 | README 구성도 전면 수정 ✅ |
| agency-stub이 내부 Kafka 직접 구독 | 코드 분석 (`HandoffEventConsumer.java`) | PoC 경고 Javadoc 추가, P1-P2 이슈 등록 ✅ |
| agency-stub `application.yml` Kafka 설정 | 코드 분석 | PoC 경고 주석 추가 ✅ |
| `AgencyEntryController.callIdoVerify()` Stub 반환 | 코드 분석 | TODO 상세화 + P1-04 이슈 등록 ✅ |
| 설계 보완 문서 부재 | 문서 검토 | `docs/agency-external-arch-supplement.md` 신규 작성 ✅ |

---

*분석 작성: genspark_ai_developer / 브랜치: genspark_ai_developer → main PR #15*
