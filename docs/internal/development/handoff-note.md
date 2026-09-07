# integration-sso 인수인계 노트 (v1.9.0)

> **작성일**: 2026-05-09  
> **작성자**: AI Developer (genspark_ai_developer 브랜치)  
> **기준 커밋**: PR #23 이후 작업 + 현재 커밋  
> **마지막 빌드**: `./gradlew build -x test` → **BUILD SUCCESSFUL** (전 모듈)

---

## 1. 프로젝트 개요

`integration-sso`는 정부 중소기업지원 통합SSO(Single Sign-On) 플랫폼으로,  
Keycloak OIDC 브로커를 중심으로 다양한 인증 수단(PASS, 금융인증서, GPKI 등)을 통합합니다.

### 멀티모듈 구성

| 모듈 | 역할 | 포트 |
|------|------|------|
| `platform-common` | 공통 도메인·이벤트·에러코드 | — |
| `ido` | 인증 결과 생성·Handoff 발급·정책 엔진 | 8083 |
| `q-sign` | Q-Sign 서명·인증 검증 (레거시 호환) | 8081 |
| `q-im` | Q-IM 회원 정보 관리 | 8082 |
| `agency-stub` | 기관 시뮬레이터 (PoC) | 8084 |
| `idem-console` | React BFF (Vite) | 3000 |

---

## 2. 완료된 GAP 마감 현황 (v1.9.0)

### P0 — 필수 (전부 완료)

| 항목 | 내용 | 파일 |
|------|------|------|
| V10 마이그레이션 | `auth_result` 에 `auth_method`, `issued_at`, `expires_at`, `raw_id_token` 추가; `provider_circuit_config` 신규 테이블; `broker_audit_log` 인덱스 보강 | `V10__extend_auth_result_and_provider_routing.sql` |
| KeycloakOidcService | `saveAuthResult()` 에 4개 신규 컬럼 삽입; `AuthResult.resolveAuthMethod()` 연동 | `KeycloakOidcService.java` |
| NonOidcAuthService | `saveAuthResult()` 에 `auth_method` 삽입 | `NonOidcAuthService.java` |
| policyVersion 외부화 | 하드코딩 `"1.0"` → `${ido.policy.default-version:1.0}` (`@Value` 주입) | `application.yml`, `AgencyAdminService`, `AgencyMetaRepositoryImpl`, `PolicyEngineImpl` |
| platformVersion 외부화 | 하드코딩 `"1.0"` → `${ido.platform-version:1.0}` | `WebhookDispatcherService`, `WebhookDispatchOutboxRelay` |

### P1 — 핵심 (전부 완료)

| 항목 | 내용 | 파일 |
|------|------|------|
| BrokerAuditLogService | `broker_audit_log` 비동기 INSERT 서비스 구현 (`@Async auditExecutor`) | `broker/BrokerAuditLogService.java` (신규) |
| KeycloakCallbackController | CALLBACK / FAIL 액션 기록 연결; clientIp 추출 | `KeycloakCallbackController.java` |
| KeycloakOidcService | COMPLETE 액션 기록 연결 | `KeycloakOidcService.java` |
| NonOidcAuthService | COMPLETE 액션 기록 연결 | `NonOidcAuthService.java` |
| TraceparentFilter | W3C `traceparent` 헤더 전파 (이미 구현됨 — 확인) | `config/TraceparentFilter.java` |
| ProviderConfig 도메인 | `STANDARD_OIDC / SEMI_STANDARD_OIDC / NON_STANDARD` 분류 | `broker/provider/ProviderConfig.java` (신규) |
| ProviderConfigRepository | `ido.provider_config` 조회 + Redis `@Cacheable` (TTL 60분) | `broker/provider/ProviderConfigRepository.java` (신규) |
| ProviderRouter | provider_type 기반 `KEYCLOAK_RELAY / DIRECT_BROKER` 런타임 라우팅 | `broker/provider/ProviderRouter.java` (신규) |
| ProviderCircuitBreakerConfig | provider_code 단위 Resilience4j CB 동적 생성; DB(`provider_circuit_config`) 설정 로드 | `broker/provider/ProviderCircuitBreakerConfig.java` (신규) |
| RedisConfig | `provider-config` 캐시(TTL 60분) 추가 | `config/RedisConfig.java` |

### P2 — 권장 (문서 완료)

| 항목 | 상태 | 비고 |
|------|------|------|
| Kafka DLQ/compaction | 설정 존재 (DLQ 파티션 설정 `ido.kafka.partition-count-dlq`) | 운영 배포 시 Kafka 토픽 수동 생성 필요 |
| Docker Compose | `docker-compose.yml` + profile 분리 이미 구성 | `tools`, `keycloak`, `monitoring`, `app`, `optionB` profile |
| 최종 인수 패키지 | ✅ 본 문서 | `docs/handoff-note.md` |

---

## 3. 핵심 아키텍처 흐름

### 3.1 Keycloak OIDC 인증 흐름 (Strategy B)

```
FE → GET /api/v1/broker/login?provider=kakao
   → IdO Redis에 state/nonce 저장
   → Keycloak /authorize?... redirect

Keycloak → GET /api/v1/broker/callback?code=...&state=...
   → KeycloakCallbackController
       ↓ broker_audit_log CALLBACK 기록
   → KeycloakOidcService.handleCallback()
       ↓ state 검증 → token 교환 → JWT 검증
       ↓ auth_result INSERT (V10: auth_method, issued_at, expires_at, raw_id_token)
       ↓ outbox INSERT → Kafka qsign.auth.events
       ↓ broker_audit_log COMPLETE 기록
   → FE 세션 생성 → feSessionId 쿠키
   → 302 → returnUrl
```

### 3.2 비OIDC 인증 흐름 (Strategy B)

```
FE/기관 → POST /api/v1/broker/nonoidc/{provider}/callback
   → NonOidcBrokerController
   → NonOidcBrokerAdapter.normalizeResponse()
   → NonOidcAuthService.processAuth()
       ↓ auth_result INSERT (V10: auth_method)
       ↓ outbox INSERT → Kafka qsign.auth.events
       ↓ broker_audit_log COMPLETE 기록
```

### 3.3 provider_type 기반 라우팅

```
ProviderRouter.resolve(providerCode)
   → ProviderConfigRepository.findByCode() [Redis 캐시, DB fallback]
   → ProviderConfig.ProviderType
      STANDARD_OIDC / SEMI_STANDARD_OIDC → BrokerRoute.KEYCLOAK_RELAY
      NON_STANDARD                        → BrokerRoute.DIRECT_BROKER
```

---

## 4. 마이그레이션 이력 (ido 모듈)

| 버전 | 파일 | 주요 변경 |
|------|------|----------|
| V1 | `V1__create_schema.sql` | ido 기본 스키마 (agency_meta, handoff_audit 등) |
| V2 | `V2__add_oidc_state.sql` | OIDC state store |
| V3 | `V3__add_keycloak_auth.sql` | `auth_result`, `provider_config`, `oidc_session_log` |
| V4 | `V4__add_fe_session.sql` | FE 세션 테이블 |
| V5 | `V5__add_outbox.sql` | Transactional Outbox |
| V6 | `V6__add_broker_audit_log.sql` | `broker_audit_log`; `provider_config`에 `provider_type` 추가 |
| V7 | `V7__add_nonoidc_support.sql` | 비OIDC 지원 (auth_lock 등) |
| V8 | `V8__add_webhook.sql` | Webhook outbox + 설정 테이블 |
| V9 | `V9__add_crypto_key_registry_and_rate_limit.sql` | AES 키 레지스트리, Rate Limit 설정, Member Lookup 로그 |
| **V10** | `V10__extend_auth_result_and_provider_routing.sql` | **auth_result 컬럼 4개 추가; provider_circuit_config 신규; broker_audit_log 인덱스** |

---

## 5. 설정 변경 이력 (application.yml)

### v1.9.0 신규 설정 키

| 키 | 기본값 | 설명 |
|----|--------|------|
| `ido.platform-version` | `1.0` | Webhook 헤더·페이로드 platformVersion (하드코딩 제거) |
| `ido.policy.default-version` | `1.0` | 신규 기관 policyVersion 기본값 (하드코딩 제거) |
| `ido.provider.circuit-cache-ttl-seconds` | `3600` | provider_circuit_config 캐시 TTL |

### 환경변수 오버라이드 (운영 배포 필수)

```bash
IDO_DEFAULT_POLICY_VERSION=2.0        # 정책 버전 업그레이드 시
IDO_PLATFORM_VERSION=1.1              # API 버전 변경 시
IDO_PROVIDER_CIRCUIT_CACHE_TTL=7200   # CB 설정 캐시 연장 시
```

---

## 6. Redis 캐시 키 전체 목록

| 키 패턴 | 서비스 | TTL |
|---------|--------|-----|
| `ido:auth_result:{correlationId}` | AuthResultCacheService | 300s |
| `ido:auth_level:{correlationId}` | AuthResultCacheService | 300s |
| `ido:oidc_state:{state}` | IdoOidcStateStore | 5min |
| `ido:pkce:{correlationId}` | PkceService | 5min |
| `ido:fe_session:{feSessionId}` | FeSessionService | sliding 30min |
| `ido:rate:{agencyCode}:tps` | AgencyRateLimiter | 1s (슬라이딩) |
| `ido:rate:{agencyCode}:daily:{date}` | AgencyRateLimiter | 24h |
| Spring Cache: `provider-config` | ProviderConfigRepository | 60min |
| Spring Cache: `agencyMeta` | AgencyMetaRepositoryImpl | 60min |
| Spring Cache: `qimUserStatus` | UserStatusCache | 5min |
| Spring Cache: `keycloakJwks` | KeycloakJwksVerifier | 60min |

---

## 7. 운영 주의사항

### 7.1 V10 마이그레이션 시 유의

```sql
-- auth_result의 raw_id_token은 운영에서 암호화 저장 권장
-- AES-256-GCM 암호화 후 저장:
-- UPDATE ido.auth_result SET raw_id_token = encrypt(raw_id_token, key) WHERE ...
```

### 7.2 provider_circuit_config 설정

```sql
-- 특정 provider의 CB 임계값 조정 (재배포 없이 가능)
INSERT INTO ido.provider_circuit_config
    (provider_code, failure_rate_threshold, wait_duration_in_open_ms)
VALUES ('KAKAO_OIDC', 50, 60000)
ON CONFLICT (provider_code) DO UPDATE
    SET failure_rate_threshold = 50,
        wait_duration_in_open_ms = 60000;
-- 이후 애플리케이션 재시작 시 새 설정 로드 (캐시 만료 대기 또는 재시작)
```

### 7.3 broker_audit_log 모니터링

```sql
-- 최근 1시간 인증 실패 현황
SELECT provider_code, error_code, COUNT(*) as cnt
FROM ido.broker_audit_log
WHERE action = 'FAIL' AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY provider_code, error_code
ORDER BY cnt DESC;

-- provider별 COMPLETE/FAIL 비율
SELECT provider_code,
       SUM(CASE WHEN action = 'COMPLETE' THEN 1 ELSE 0 END) as success,
       SUM(CASE WHEN action = 'FAIL'     THEN 1 ELSE 0 END) as fail
FROM ido.broker_audit_log
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY provider_code;
```

---

## 8. 잔여 작업 (후속 스프린트)

| 우선순위 | 항목 | 설명 |
|----------|------|------|
| 권장 | `ProviderRouter` 실제 호출 지점 연결 | `HandoffController` 또는 `FeSessionController`에서 `ProviderRouter.resolve()` 호출하여 실제 라우팅에 활용 |
| 권장 | `ProviderCircuitBreakerConfig` 실제 wrapping | `KeycloakOidcService.exchangeCodeForToken()`, `NonOidcBrokerAdapter.initiateAuth()` 등에 CB 적용 |
| 운영 | `raw_id_token` 암호화 저장 | AES-256-GCM으로 저장 후 복호화 조회 |
| 운영 | Kafka DLQ 토픽 수동 생성 | `kafka-topics.sh --create --topic qsign.auth.events.dlq ...` |
| 운영 | `provider_circuit_config` 초기 데이터 적재 | PASS, KAKAO_OIDC 등 사용 provider에 CB 설정 삽입 |

---

## 9. 로컬 개발 환경 빠른 시작

```bash
# 전체 빌드
./gradlew build -x test

# ido 모듈만 빌드
./gradlew :idem-hub:build -x test

# 기본 인프라 시작 (PostgreSQL, Redis, Kafka, Keycloak)
docker-compose --profile tools --profile keycloak up -d

# 모니터링 스택 (Prometheus, Grafana, Loki)
docker-compose --profile monitoring up -d

# 앱 시작
docker-compose --profile app up -d
```

자세한 로컬 개발 가이드: [`docs/local-dev-guide.md`](local-dev-guide.md) (v1.2.0)

---

## 10. 담당자 연락처 및 참조

- **GitHub Repository**: `HipsterMIN/integration-sso`
- **기준 PR**: #23 (https://github.com/HipsterMIN/integration-sso/pull/23)
- **기술 설계서**: `docs/design/` 디렉토리
- **로컬 개발 가이드**: `docs/local-dev-guide.md`
