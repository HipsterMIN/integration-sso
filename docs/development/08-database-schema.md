# 08. 데이터베이스 스키마 (DB Schema)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09

---

## 1. DB 구성 개요

| DB | 버전 | 스키마 | 담당 모듈 | Flyway 버전 |
|----|------|--------|----------|------------|
| PostgreSQL | 16 | `qsign` | Q-Sign | V1~V5 |
| PostgreSQL | 16 | `ido` | IdO | V1~V10 |
| MariaDB | 11.4 | `qim` | Q-IM | V1~V3 |

---

## 2. Q-Sign 스키마 (PostgreSQL / qsign)

### V1 — 기본 스키마 (`V1__create_schema.sql`)

#### qsign.auth_result

| 컬럼 | 타입 | 설명 |
|------|------|------|
| auth_result_id | VARCHAR(36) PK | UUID |
| correlation_id | VARCHAR(36) | 상관관계 ID |
| provider_code | VARCHAR(50) | IdP 코드 (kakao, naver 등) |
| identifier_hash | VARCHAR(64) | SHA-256(CI/sub) |
| sub | VARCHAR(200) | IdP Subject |
| auth_level | VARCHAR(20) | 인증 수준 |
| status | VARCHAR(20) | COMPLETED, FAILED |
| signature | VARCHAR(512) | 내부 서명 (미적용 상태) |
| created_at | TIMESTAMPTZ | 생성 시각 |
| expired_at | TIMESTAMPTZ | 만료 시각 |

#### qsign.auth_lock

| 컬럼 | 타입 | 설명 |
|------|------|------|
| lock_id | VARCHAR(36) PK | UUID |
| identifier_hash | VARCHAR(64) | 잠금 대상 식별자 |
| lock_reason | VARCHAR(50) | 잠금 사유 |
| failed_count | SMALLINT | 실패 횟수 |
| locked_until | TIMESTAMPTZ | 잠금 해제 시각 |
| created_at | TIMESTAMPTZ | 잠금 생성 시각 |

#### qsign.outbox_record

| 컬럼 | 타입 | 설명 |
|------|------|------|
| outbox_id | VARCHAR(36) PK | UUID |
| event_type | VARCHAR(80) | 이벤트 타입 |
| topic | VARCHAR(100) | Kafka 토픽 |
| payload | TEXT | JSON 페이로드 |
| status | VARCHAR(20) | PENDING, PUBLISHED, FAILED |
| retry_count | SMALLINT | 재시도 횟수 |
| created_at | TIMESTAMPTZ | 생성 시각 |

### V2 — 감사 로그 (`V2__add_audit_log.sql`)

#### qsign.audit_log

| 컬럼 | 타입 | 설명 |
|------|------|------|
| audit_id | BIGSERIAL PK | 자동 증가 ID |
| correlation_id | VARCHAR(36) | 상관관계 ID |
| action | VARCHAR(50) | 액션 유형 |
| actor | VARCHAR(100) | 행위자 |
| result | VARCHAR(20) | 결과 (SUCCESS, FAIL) |
| message | TEXT | 상세 메시지 |
| created_at | TIMESTAMPTZ | 생성 시각 |

#### qsign.used_nonce

| 컬럼 | 타입 | 설명 |
|------|------|------|
| nonce | VARCHAR(200) PK | Nonce 값 |
| created_at | TIMESTAMPTZ | 사용 시각 |
| expires_at | TIMESTAMPTZ | 만료 시각 |

### V3 — OIDC 세션 (`V3__add_oidc_session.sql`)

#### qsign.keycloak_state

| 컬럼 | 타입 | 설명 |
|------|------|------|
| state | VARCHAR(100) PK | CSRF State |
| provider_code | VARCHAR(50) | IdP 코드 |
| nonce | VARCHAR(200) | Nonce |
| code_verifier | VARCHAR(256) | PKCE code_verifier |
| created_at | TIMESTAMPTZ | 생성 시각 |
| expires_at | TIMESTAMPTZ | 만료 시각 |

### V4 — 멱등 컨슈머 (`V4__add_processed_event.sql`)

#### qsign.processed_event

| 컬럼 | 타입 | 설명 |
|------|------|------|
| event_id | VARCHAR(36) | 이벤트 UUID |
| consumer_group | VARCHAR(100) | 컨슈머 그룹 |
| processed_at | TIMESTAMPTZ | 처리 시각 |
| PK | (event_id, consumer_group) | 복합 키 |

### V5 — auth_method 컬럼 (`V5__add_auth_method.sql`)

```sql
ALTER TABLE qsign.auth_result
    ADD COLUMN IF NOT EXISTS auth_method VARCHAR(30);
```

---

## 3. IdO 스키마 (PostgreSQL / ido)

### V1 — 기본 스키마 (`V1__create_schema.sql`)

#### ido.agency_meta

| 컬럼 | 타입 | 설명 |
|------|------|------|
| agency_code | VARCHAR(50) PK | 기관 코드 |
| agency_name | VARCHAR(200) | 기관명 |
| integration_type | VARCHAR(30) | DIRECT, BRIDGE, INTERNAL_SSO, APACHE_GATE |
| allowed_attributes | JSONB | 허용 속성 목록 |
| callback_whitelist | JSONB | Callback URL 화이트리스트 |
| api_key_hash | VARCHAR(256) | API Key 해시 (PBKDF2) |
| policy_version | VARCHAR(20) | 정책 버전 |
| active | BOOLEAN | 활성화 여부 |
| webhook_enabled | BOOLEAN | Webhook 활성화 여부 |
| webhook_endpoint | VARCHAR(500) | Webhook URL |
| daily_lookup_limit | INT | 일별 조회 한도 |
| created_at | TIMESTAMPTZ | 생성 시각 |

#### ido.provider_config

| 컬럼 | 타입 | 설명 |
|------|------|------|
| provider_code | VARCHAR(50) PK | 사업자 코드 (kakao, naver, pass 등) |
| display_name | VARCHAR(100) | 표시명 |
| auth_level | VARCHAR(20) | 인증 수준 |
| broker_mode | VARCHAR(30) | 브로커 모드 |
| idp_hint | VARCHAR(50) | Keycloak IdP hint |
| provider_type | VARCHAR(30) | STANDARD_OIDC, SEMI_STANDARD_OIDC, NON_STANDARD |
| active | BOOLEAN | 활성화 여부 |

#### ido.handoff_audit

| 컬럼 | 타입 | 설명 |
|------|------|------|
| ticket_id | VARCHAR(36) PK | Ticket UUID |
| agency_code | VARCHAR(50) | 기관 코드 |
| qim_user_id | VARCHAR(36) | Q-IM 사용자 ID |
| agency_subject_id | VARCHAR(256) | 기관별 Subject ID (HMAC) |
| issued_at | TIMESTAMPTZ | 발급 시각 |
| expired_at | TIMESTAMPTZ | 만료 시각 |
| status | VARCHAR(20) | ISSUED, CONSUMED, REVOKED |

### V2 — FE 세션 (`V2__add_fe_session.sql`)

#### ido.fe_session

| 컬럼 | 타입 | 설명 |
|------|------|------|
| session_id | VARCHAR(36) PK | 세션 UUID |
| qim_user_id | VARCHAR(36) | Q-IM 사용자 ID |
| provider_code | VARCHAR(50) | IdP 코드 |
| auth_level | VARCHAR(20) | 인증 수준 |
| status | VARCHAR(20) | ACTIVE, EXPIRED |
| created_at | TIMESTAMPTZ | 생성 시각 |
| last_accessed_at | TIMESTAMPTZ | 마지막 접근 시각 |
| expires_at | TIMESTAMPTZ | 만료 시각 |

### V3 — Keycloak 인증 (`V3__add_keycloak_auth.sql`)

#### ido.auth_result

| 컬럼 | 타입 | 설명 |
|------|------|------|
| auth_result_id | VARCHAR(36) PK | UUID |
| correlation_id | VARCHAR(36) | 상관관계 ID |
| provider_code | VARCHAR(50) | IdP 코드 |
| provider_type | VARCHAR(30) | ProviderType |
| auth_level | VARCHAR(20) | 인증 수준 |
| identifier_hash | VARCHAR(64) | SHA-256(CI/sub) |
| sub | VARCHAR(200) | IdP Subject |
| qim_user_id | VARCHAR(36) | Q-IM 사용자 ID |
| auth_method | VARCHAR(80) | ★ V10 추가 |
| issued_at | TIMESTAMPTZ | ★ V10 추가 (JWT iat) |
| expires_at | TIMESTAMPTZ | ★ V10 추가 (JWT exp) |
| raw_id_token | TEXT | ★ V10 추가 (원본 id_token) |
| created_at | TIMESTAMPTZ | 생성 시각 |

### V4 — Q-IM SP 수신 (`V4__add_qim_sp_receiver.sql`)

#### ido.inst_mbr_id_mapping

| 컬럼 | 타입 | 설명 |
|------|------|------|
| inst_mbr_id | VARCHAR(36) PK | SP 내부 식별자 (= qimUserId) |
| qim_user_id | VARCHAR(36) | Q-IM 사용자 ID |
| mbrUuid | VARCHAR(36) | Q-IM 회원 UUID |
| identifier_hash | VARCHAR(64) | SHA-256(CI) |
| status | VARCHAR(20) | ACTIVE, WITHDRAWN |
| created_at | TIMESTAMPTZ | 생성 시각 |

#### ido.sp_receiver_idempotency

| 컬럼 | 타입 | 설명 |
|------|------|------|
| idempotency_key | VARCHAR(200) PK | Idempotency-Key 헤더값 |
| endpoint | VARCHAR(50) | QUERY, REGISTER, WITHDRAW |
| response_json | TEXT | 저장된 응답 JSON |
| created_at | TIMESTAMPTZ | 생성 시각 |
| expires_at | TIMESTAMPTZ | TTL 만료 시각 (7일) |

### V5 — 멱등 컨슈머 (`V5__add_processed_event.sql`)

#### ido.processed_event

| 컬럼 | 타입 | 설명 |
|------|------|------|
| event_id | VARCHAR(36) | 이벤트 UUID |
| consumer_group | VARCHAR(100) | 컨슈머 그룹 |
| event_type | VARCHAR(80) | 이벤트 타입 |
| result_code | VARCHAR(50) | 처리 결과 코드 |
| processed_at | TIMESTAMPTZ | 처리 시각 |
| PK | (event_id, consumer_group) | 복합 키 |

#### ido.last_event_version

| 컬럼 | 타입 | 설명 |
|------|------|------|
| entity_id | VARCHAR(36) | 엔티티 ID |
| entity_type | VARCHAR(50) | 엔티티 타입 |
| last_version | BIGINT | 마지막 처리된 이벤트 버전 |
| updated_at | TIMESTAMPTZ | 업데이트 시각 |

### V6 — 브로커 감사 로그 (`V6__add_broker_audit_log.sql`)

#### ido.broker_audit_log

| 컬럼 | 타입 | 설명 |
|------|------|------|
| log_id | BIGSERIAL PK | 자동 증가 ID |
| correlation_id | VARCHAR(36) | 상관관계 ID |
| provider_code | VARCHAR(50) | IdP 코드 |
| provider_tx_id | VARCHAR(100) | 사업자 트랜잭션 ID |
| action | VARCHAR(20) | REDIRECT, CALLBACK, COMPLETE, FAIL, TIMEOUT |
| provider_type | VARCHAR(30) | STANDARD_OIDC, NON_STANDARD 등 |
| sub | VARCHAR(200) | IdP Subject |
| identifier_hash | VARCHAR(64) | SHA-256(CI/sub) |
| auth_level | VARCHAR(20) | 인증 수준 |
| broker_mode | VARCHAR(30) | 브로커 모드 |
| error_code | VARCHAR(50) | 오류 코드 |
| client_ip | VARCHAR(45) | 클라이언트 IP |
| created_at | TIMESTAMPTZ | 생성 시각 |

### V7 — Webhook 및 감사 (`V7__add_webhook_and_audit.sql`)

#### ido.agency_webhook_config

| 컬럼 | 타입 | 설명 |
|------|------|------|
| agency_code | VARCHAR(50) PK | 기관 코드 |
| endpoint_url | VARCHAR(500) | Webhook URL |
| hmac_secret | VARCHAR(256) | HMAC 서명 시크릿 |
| max_retry_count | INT | 최대 재시도 횟수 |
| retry_backoff_multiplier | INT | 재시도 백오프 배수 |

#### ido.webhook_dispatch_outbox

| 컬럼 | 타입 | 설명 |
|------|------|------|
| dispatch_id | BIGSERIAL PK | 자동 증가 ID |
| agency_code | VARCHAR(50) | 기관 코드 |
| event_type | VARCHAR(80) | 이벤트 타입 |
| payload | TEXT | JSON 페이로드 |
| status | VARCHAR(20) | PENDING, DISPATCHED, FAILED |
| retry_count | INT | 재시도 횟수 |
| next_retry_at | TIMESTAMPTZ | 다음 재시도 시각 |
| created_at | TIMESTAMPTZ | 생성 시각 |

#### ido.audit_log

| 컬럼 | 타입 | 설명 |
|------|------|------|
| log_id | BIGSERIAL PK | 자동 증가 ID |
| correlation_id | VARCHAR(36) | 상관관계 ID |
| action | VARCHAR(100) | 액션 유형 |
| actor | VARCHAR(100) | 행위자 (agencyCode) |
| resource | VARCHAR(200) | 자원 (ticketId 등) |
| outcome | VARCHAR(20) | SUCCESS, FAIL |
| metadata | JSONB | 추가 정보 |
| created_at | TIMESTAMPTZ | 생성 시각 |

#### ido.member_lookup_request

| 컬럼 | 타입 | 설명 |
|------|------|------|
| request_id | VARCHAR(36) PK | UUID |
| agency_code | VARCHAR(50) | 기관 코드 |
| identifier_hash | VARCHAR(64) | 조회 식별자 해시 |
| result | VARCHAR(20) | FOUND, NOT_FOUND |
| created_at | TIMESTAMPTZ | 요청 시각 |

### V8 — API Key 시드 데이터 (`V8__seed_agency_api_key_and_fix_webhook.sql`)

- 기본 기관 API Key 해시 시드 데이터 삽입
- Webhook 설정 컬럼 보정

### V9 — 키 버전 레지스트리 + Rate Limit (`V9__add_crypto_key_registry_and_rate_limit.sql`)

#### ido.handoff_key_registry

| 컬럼 | 타입 | 설명 |
|------|------|------|
| key_version | VARCHAR(10) PK | 키 버전 (v1, v2 ...) |
| encrypted_key | TEXT | 암호화된 AES 키 |
| is_active | BOOLEAN | 현재 활성 키 여부 |
| created_at | TIMESTAMPTZ | 생성 시각 |

### V10 — auth_result 확장 + Provider CB (`V10__extend_auth_result_and_provider_routing.sql`)

**추가 컬럼** (`ido.auth_result`):
```sql
ALTER TABLE ido.auth_result
    ADD COLUMN IF NOT EXISTS auth_method VARCHAR(80);
ALTER TABLE ido.auth_result
    ADD COLUMN IF NOT EXISTS issued_at TIMESTAMPTZ;
ALTER TABLE ido.auth_result
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE ido.auth_result
    ADD COLUMN IF NOT EXISTS raw_id_token TEXT;
```

#### ido.provider_circuit_config

| 컬럼 | 타입 | 설명 |
|------|------|------|
| provider_code | VARCHAR(50) PK | IdP 코드 |
| sliding_window_size | SMALLINT | 슬라이딩 윈도우 크기 |
| failure_rate_threshold | SMALLINT | 실패율 임계값 (%) |
| wait_duration_seconds | INT | OPEN 상태 유지 시간 |
| FOREIGN KEY | provider_code → ido.provider_config | 참조 무결성 |

**추가 인덱스** (`ido.broker_audit_log`):
```sql
CREATE INDEX IF NOT EXISTS idx_broker_audit_correlation
    ON ido.broker_audit_log (correlation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_broker_audit_provider_action
    ON ido.broker_audit_log (provider_code, action, created_at DESC);
```

---

## 4. Q-IM 스키마 (MariaDB / qim)

### V1 — 기본 스키마 (`V1__create_schema.sql`)

#### qim.qim_user

| 컬럼 | 타입 | 설명 |
|------|------|------|
| qim_user_id | VARCHAR(36) PK | UUID |
| status | VARCHAR(20) | ACTIVE, SUSPENDED, WITHDRAWN |
| member_type | VARCHAR(20) | PERSONAL, CORPORATE |
| created_at | DATETIME(6) | 생성 시각 |
| updated_at | DATETIME(6) | 수정 시각 |

#### qim.user_profile

| 컬럼 | 타입 | 설명 |
|------|------|------|
| profile_id | VARCHAR(36) PK | UUID |
| qim_user_id | VARCHAR(36) | Q-IM 사용자 ID (FK) |
| ci | TEXT | AES-256-GCM 암호화된 CI |
| ci_key_version | VARCHAR(10) | 암호화 키 버전 |
| masked_name | VARCHAR(50) | 마스킹된 이름 |
| masked_mobile | VARCHAR(20) | 마스킹된 전화번호 |
| masked_email | VARCHAR(100) | 마스킹된 이메일 |
| di_map | TEXT | JSON: {기관코드: DI값} |
| di_map_updated_at | DATETIME(6) | DI Map 최종 갱신 시각 |

#### qim.auth_mean_mapping

| 컬럼 | 타입 | 설명 |
|------|------|------|
| mapping_id | VARCHAR(36) PK | UUID |
| qim_user_id | VARCHAR(36) | Q-IM 사용자 ID (FK) |
| provider_code | VARCHAR(50) | IdP 코드 |
| identifier_hash | VARCHAR(64) | SHA-256(CI/sub) |
| created_at | DATETIME(6) | 생성 시각 |

#### qim.outbox_record

| 컬럼 | 타입 | 설명 |
|------|------|------|
| outbox_id | VARCHAR(36) PK | UUID |
| event_type | VARCHAR(80) | 이벤트 타입 |
| topic | VARCHAR(100) | Kafka 토픽 |
| payload | TEXT | JSON 페이로드 |
| status | VARCHAR(20) | PENDING, PUBLISHED, FAILED |
| retry_count | SMALLINT | 재시도 횟수 |
| created_at | DATETIME(6) | 생성 시각 |

### V2 — 멱등 컨슈머 (`V2__add_idempotent_consumer.sql`)

#### qim.snapshot_meta

| 컬럼 | 타입 | 설명 |
|------|------|------|
| snapshot_id | VARCHAR(36) PK | UUID |
| entity_type | VARCHAR(50) | 엔티티 타입 |
| last_snapshot_version | BIGINT | 마지막 스냅샷 버전 |
| created_at | DATETIME(6) | 생성 시각 |

#### qim.processed_event

| 컬럼 | 타입 | 설명 |
|------|------|------|
| event_id | VARCHAR(36) | 이벤트 UUID |
| consumer_group | VARCHAR(100) | 컨슈머 그룹 |
| processed_at | DATETIME(6) | 처리 시각 |
| PK | (event_id, consumer_group) | 복합 키 |

### V3 — CI 암호화 + 상태 이력 (`V3__add_ci_encryption_and_status_history.sql`)

```sql
ALTER TABLE qim.user_profile
    ADD COLUMN ci_key_version VARCHAR(10) DEFAULT 'v1' NOT NULL;
ALTER TABLE qim.user_profile
    ADD COLUMN di_map_updated_at DATETIME(6);
```

#### qim.user_status_history

| 컬럼 | 타입 | 설명 |
|------|------|------|
| history_id | BIGINT AUTO_INCREMENT PK | 자동 증가 ID |
| qim_user_id | VARCHAR(36) | Q-IM 사용자 ID |
| from_status | VARCHAR(20) | 이전 상태 |
| to_status | VARCHAR(20) | 변경 후 상태 |
| reason | VARCHAR(200) | 변경 사유 |
| changed_at | DATETIME(6) | 변경 시각 |

---

*다음 문서: [09-api-spec.md](09-api-spec.md)*
