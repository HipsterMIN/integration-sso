# 05. 데이터베이스 스키마 전체 명세

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09

---

## 1. DB 구성 개요

| DB 종류 | 버전 | 스키마 | 담당 모듈 | 최신 Flyway 버전 |
|--------|------|--------|----------|----------------|
| PostgreSQL | 16 | `qsign` | Q-Sign | **V5** |
| PostgreSQL | 16 | `ido` | IdO | **V10** |
| MariaDB | 11.4 | `qim` | Q-IM | **V3** |
| PostgreSQL | 16 | `agency_stub` | agency-stub | **V2** |

---

## 2. Q-Sign 스키마 (PostgreSQL / qsign)

### V1 — 기본 스키마

#### qsign.auth_result

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `auth_result_id` | VARCHAR(36) PK | UUID |
| `correlation_id` | VARCHAR(36) | 요청 추적 ID |
| `provider_code` | VARCHAR(50) | IdP 코드 (KAKAO_OIDC, PASS 등) |
| `identifier_hash` | VARCHAR(64) | SHA-256(CI/sub) |
| `sub` | VARCHAR(200) | IdP Subject |
| `auth_level` | VARCHAR(20) CHECK(L1/L2/L3) | 인증 수준 |
| `status` | VARCHAR(20) | COMPLETED / FAILED |
| `created_at` | TIMESTAMPTZ | 생성 시각 |
| `expired_at` | TIMESTAMPTZ | 만료 시각 |

#### qsign.auth_lock

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `lock_id` | VARCHAR(36) PK | UUID |
| `identifier_hash` | VARCHAR(64) | 잠금 대상 식별자 |
| `lock_reason` | VARCHAR(50) | 잠금 사유 |
| `failed_count` | SMALLINT | 실패 횟수 |
| `locked_until` | TIMESTAMPTZ | 잠금 해제 시각 |
| `created_at` | TIMESTAMPTZ | 잠금 생성 시각 |

#### qsign.outbox_record

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `outbox_id` | VARCHAR(36) PK | UUID |
| `event_type` | VARCHAR(80) | AUTH_COMPLETED / AUTH_FAILED / AUTH_LOCKED |
| `topic` | VARCHAR(100) | Kafka 토픽 |
| `payload` | TEXT | JSON 페이로드 |
| `status` | VARCHAR(20) | PENDING / PUBLISHED / FAILED |
| `retry_count` | SMALLINT | 재시도 횟수 |
| `created_at` | TIMESTAMPTZ | 생성 시각 |

#### qsign.provider_config

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `provider_code` | VARCHAR(50) PK | 공급자 코드 |
| `display_name` | VARCHAR(100) | 표시 이름 |
| `auth_level` | VARCHAR(10) | 기본 인증 수준 |
| `broker_mode` | VARCHAR(20) | keycloak / qsign |
| `active` | BOOLEAN | 활성 여부 |

### V2 — 감사 로그

#### qsign.audit_log, qsign.used_nonce

### V3 — Keycloak 세션

#### qsign.keycloak_session_log, qsign.oidc_nonce_used

### V4 — 멱등 처리 (GAP-QS-03)

#### qsign.last_event_version

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `topic` | VARCHAR(100) PK | Kafka 토픽 |
| `partition` | INT PK | 파티션 번호 |
| `last_offset` | BIGINT | 마지막 처리 오프셋 |

#### qsign.processed_event

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `event_id` | VARCHAR(36) PK | 이벤트 UUID |
| `processed_at` | TIMESTAMPTZ | 처리 시각 |

### V5 — auth_method 컬럼 추가

```sql
ALTER TABLE qsign.auth_result ADD COLUMN IF NOT EXISTS auth_method VARCHAR(30);
COMMENT ON COLUMN qsign.auth_result.auth_method IS '인증 수단 분류 코드 (§24.4.1)';
```

---

## 3. IdO 스키마 (PostgreSQL / ido)

### V1 — 기본 스키마

#### ido.agency_meta

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `agency_code` | VARCHAR(50) PK | 기관 코드 |
| `official_name` | VARCHAR(200) | 기관 공식명 |
| `api_key_hash` | VARCHAR(64) | SHA-256(rawKey) |
| `callback_whitelist` | JSONB | 허용 콜백 URL 목록 |
| `min_auth_level` | VARCHAR(10) | 최소 인증 수준 |
| `allowed_attributes` | JSONB | 허용 속성 목록 |
| `policy_version` | VARCHAR(20) | 정책 버전 |
| `integration_type` | VARCHAR(20) | DIRECT/BRIDGE/INTERNAL_SSO/APACHE_GATE |
| `bridge_endpoint` | VARCHAR(500) | BRIDGE 모드 엔드포인트 |
| `active` | BOOLEAN | 활성 여부 |
| `created_at` | TIMESTAMPTZ | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | 수정 시각 |

#### ido.handoff_ticket

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `ticket_id` | VARCHAR(36) PK | UUID |
| `agency_code` | VARCHAR(50) | 발급 기관 |
| `correlation_id` | VARCHAR(36) | 추적 ID |
| `encrypted_payload` | TEXT | AES-256-GCM 암호화 페이로드 |
| `signature` | VARCHAR(512) | HMAC-SHA256 서명 |
| `status` | VARCHAR(20) | ISSUED / CONSUMED / REVOKED / EXPIRED |
| `auth_level` | VARCHAR(10) | 인증 수준 |
| `issued_at` | TIMESTAMPTZ | 발급 시각 |
| `expires_at` | TIMESTAMPTZ | 만료 시각 |
| `consumed_at` | TIMESTAMPTZ | 소비 시각 |

### V2 — Outbox 패턴

#### ido.outbox_event

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `event_id` | VARCHAR(36) PK | UUID |
| `event_type` | VARCHAR(80) | 이벤트 타입 |
| `topic` | VARCHAR(100) | Kafka 토픽 |
| `payload` | JSONB | 이벤트 페이로드 |
| `status` | VARCHAR(20) | PENDING / PUBLISHED / FAILED |
| `retry_count` | SMALLINT | 재시도 횟수 |
| `created_at` | TIMESTAMPTZ | 생성 시각 |

### V3 — Keycloak 인증 + auth_result

#### ido.auth_result

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `auth_result_id` | VARCHAR(36) PK | UUID |
| `correlation_id` | VARCHAR(36) | 추적 ID |
| `auth_level` | VARCHAR(10) CHECK(L1/L2/L3) | 인증 수준 |
| `provider_code` | VARCHAR(50) | 공급자 코드 |
| `provider_tx_id` | VARCHAR(300) | 공급자 트랜잭션 ID |
| `identifier_hash` | VARCHAR(64) | SHA-256 해시 |
| `verification_result` | VARCHAR(20) | SUCCESS / FAIL |
| `source_system` | VARCHAR(50) | 출처 시스템 |
| `session_ref` | VARCHAR(36) | FE 세션 참조 |
| `requested_at` | TIMESTAMPTZ | 요청 시각 |
| `authenticated_at` | TIMESTAMPTZ | 인증 완료 시각 |
| `created_at` | TIMESTAMPTZ | 레코드 생성 시각 |

#### ido.auth_lock

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `lock_id` | VARCHAR(36) PK | UUID |
| `identifier_hash` | VARCHAR(64) | 잠금 식별자 |
| `provider_code` | VARCHAR(50) | 공급자 코드 |
| `failure_count` | SMALLINT | 실패 횟수 |
| `locked_until` | TIMESTAMPTZ | 잠금 해제 시각 |

#### ido.provider_config

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `provider_code` | VARCHAR(50) PK | 공급자 코드 |
| `display_name` | VARCHAR(100) | 표시 이름 |
| `auth_level` | VARCHAR(10) | 기본 인증 수준 (L1/L2/L3) |
| `broker_mode` | VARCHAR(20) | keycloak / direct |
| `provider_type` | VARCHAR(30) | STANDARD_OIDC / SEMI_STANDARD_OIDC / NON_STANDARD |
| `active` | BOOLEAN | 활성 여부 |

### V4~V9 — 점진적 확장

- V4: `oidc_session_log`, `oidc_nonce_used`
- V5: `broker_audit_log` (GAP-IDO 브로커 감사 로그)
- V6: `webhook_dispatch_outbox`, `fe_session`
- V7: `maintenance_window`
- V8: `agency_meta.api_key_hash` 보강, `event_queue`
- V9: `crypto_key_registry`, `agency_rate_limit_config`, `agency_meta_history`, `member_lookup_log`

### V10 — auth_result 확장 + Provider 라우팅 (★ v1.9.0, GAP-P0)

```sql
-- auth_result 확장 (4개 컬럼 추가)
ALTER TABLE ido.auth_result
  ADD COLUMN IF NOT EXISTS auth_method   VARCHAR(30),
  ADD COLUMN IF NOT EXISTS issued_at     TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS expires_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS raw_id_token  TEXT;

COMMENT ON COLUMN ido.auth_result.auth_method  IS '인증 수단 분류 코드 (§24.4.1)';
COMMENT ON COLUMN ido.auth_result.issued_at    IS 'IdP 토큰 발행 시각 (iat 클레임)';
COMMENT ON COLUMN ido.auth_result.expires_at   IS 'IdP 토큰 만료 시각 (exp 클레임)';
COMMENT ON COLUMN ido.auth_result.raw_id_token IS 'OIDC ID Token 원문 (감사·디버그용)';

-- provider_circuit_config 신규 테이블
CREATE TABLE IF NOT EXISTS ido.provider_circuit_config (
  provider_code         VARCHAR(50)  PRIMARY KEY,
  sliding_window_size   INT          NOT NULL DEFAULT 20,
  failure_rate_threshold DECIMAL(5,2) NOT NULL DEFAULT 50.0,
  wait_duration_open_ms  INT          NOT NULL DEFAULT 30000,
  slow_call_duration_ms  INT          NOT NULL DEFAULT 3000,
  min_calls             INT          NOT NULL DEFAULT 5,
  active                BOOLEAN      NOT NULL DEFAULT TRUE,
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- broker_audit_log 인덱스 보강
CREATE INDEX IF NOT EXISTS idx_broker_audit_correlation ON ido.broker_audit_log(correlation_id);
CREATE INDEX IF NOT EXISTS idx_broker_audit_provider    ON ido.broker_audit_log(provider_code, created_at DESC);
```

---

## 4. broker_audit_log 상세 (V5~V10)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `log_id` | VARCHAR(36) PK | UUID |
| `correlation_id` | VARCHAR(36) | 요청 추적 ID |
| `provider_code` | VARCHAR(50) | 공급자 코드 |
| `provider_type` | VARCHAR(30) | STANDARD_OIDC 등 |
| `provider_tx_id` | VARCHAR(300) | 공급자 트랜잭션 ID |
| `broker_mode` | VARCHAR(20) | keycloak / qsign / direct |
| `action` | VARCHAR(20) CHECK | REDIRECT/CALLBACK/COMPLETE/FAIL/TIMEOUT |
| `identifier_hash` | VARCHAR(64) | SHA-256 해시 |
| `auth_level` | VARCHAR(10) | 인증 수준 |
| `error_code` | VARCHAR(50) | 에러 코드 |
| `error_detail` | TEXT | 에러 상세 |
| `client_ip` | VARCHAR(50) | 클라이언트 IP |
| `fe_session_id` | VARCHAR(36) | FE 세션 ID |
| `created_at` | TIMESTAMPTZ | 생성 시각 |

---

## 5. Q-IM 스키마 (MariaDB / qim)

> **MariaDB 문법**: `TIMESTAMPTZ` → `DATETIME(6)`, `JSONB` → `JSON`(LONGTEXT alias), `CREATE SCHEMA` 사용 안 함, Partial Index 미지원

### V1 — 기본 스키마

#### qim_user

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `qim_user_id` | VARCHAR(36) PK | UUID 통합 식별자 |
| `ci_hash` | VARCHAR(64) UNIQUE | SHA-256(CI) |
| `enc_ci` | TEXT | AES-256-GCM 암호화 CI |
| `inst_mbr_id` | VARCHAR(36) | Q-IM instMbrId (SP 연동) |
| `status` | VARCHAR(20) | ACTIVE/SUSPENDED/WITHDRAWN |
| `primary_name` | VARCHAR(100) | 마스킹 처리된 이름 |
| `created_at` | DATETIME(6) | 등록 시각 |
| `updated_at` | DATETIME(6) | 최종 수정 시각 |

#### auth_mean_mapping

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `mapping_id` | VARCHAR(36) PK | UUID |
| `qim_user_id` | VARCHAR(36) FK | Q-IM 사용자 |
| `provider_code` | VARCHAR(50) | 인증 수단 |
| `identifier_hash` | VARCHAR(64) | SHA-256 해시 |
| `first_mapped_at` | DATETIME(6) | 최초 연결 시각 |

### V2 — 멱등 처리 + Snapshot (GAP-QIM-05)

#### last_event_version, processed_event, snapshot_meta

```sql
CREATE TABLE IF NOT EXISTS snapshot_meta (
  snapshot_id   VARCHAR(36)  PRIMARY KEY,
  qim_user_id   VARCHAR(36)  NOT NULL,
  event_count   INT          NOT NULL,
  status        VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',  -- PUBLISHED / FAILED
  published_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_snapshot_user (qim_user_id, published_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### V3 — CI 암호화 + 상태 이력

```sql
ALTER TABLE qim_user ADD COLUMN ci_encryption_key_version INT NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS user_status_history (
  history_id  VARCHAR(36)  PRIMARY KEY,
  qim_user_id VARCHAR(36)  NOT NULL,
  old_status  VARCHAR(20),
  new_status  VARCHAR(20)  NOT NULL,
  reason      VARCHAR(200),
  changed_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_ush_user (qim_user_id, changed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 6. agency_stub 스키마 (PostgreSQL / agency_stub)

### V1 — 기본 스키마

#### agency_local_session

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `session_id` | VARCHAR(36) PK | SHA-256(rawAGSID) |
| `agency_code` | VARCHAR(50) | 기관 코드 |
| `agency_subject_id` | VARCHAR(200) | HMAC-SHA256 식별자 |
| `auth_level` | VARCHAR(10) | 인증 수준 |
| `created_at` | TIMESTAMPTZ | 생성 시각 |
| `expires_at` | TIMESTAMPTZ | 만료 시각 |
| `invalidated` | BOOLEAN | 무효화 여부 |

### V2 — Webhook + API Key

#### webhook_event_log, event_queue

---

## 7. Redis 키 패턴

| 키 패턴 | TTL | 용도 |
|--------|-----|------|
| `fe:session:{feSessionId}` | 30분 (sliding) | FE 세션 |
| `ido:oidc:state:{state}` | 300s | OIDC state (CSRF) |
| `qsign:pkce:challenge:{state}` | 300s | PKCE 코드 챌린지 |
| `ido:rl:tps:{agencyCode}:{epochSec}` | 2s | Rate Limit TPS |
| `ido:rl:daily:{agencyCode}:{date}` | 25h | Rate Limit 일일 쿼터 |
| `ido:agency-meta:{agencyCode}` | 60분 | Agency 메타 캐시 |
| `ido:provider-config:{providerCode}` | 60분 | Provider 설정 캐시 |
| `ido:crypto:aes:current-version` | — | AES 현재 버전 |
| `ido:crypto:aes:version:{vN}` | — | AES 키 버전별 |
| `ido:idempotency:{key}` | 7일 | 멱등 처리 키 |

---

*다음 문서: [06-kafka-event-catalog.md](06-kafka-event-catalog.md)*
