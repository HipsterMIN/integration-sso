# F-04: 감사 로그 DB 저장

> **환경변수**: `IDO_AUDIT_DB_ENABLED`  
> **기본값**: `true`  
> **Spring 프로퍼티**: `ido.audit.db-save-enabled`  
> **소스**: `idem-hub/src/main/java/kr/go/smes/idem-hub/audit/AuditLogRepository.java`  
> **대상 테이블**: `ido.audit_log`

---

## ⚠️ 운영에서 false 금지 — 컴플라이언스 위반

```
IDO_AUDIT_DB_ENABLED=false  →  법적 감사 추적 불가  →  컴플라이언스 위반
```

**F-04를 비활성화하면**:
- 개인정보보호법 제29조 (안전조치 의무) 위반 위험
- 행정안전부 전자정부 보안 가이드라인 미준수
- 보안 사고 발생 시 사후 감사(Audit Trail) 불가

> 운영 환경에서 F-04=false가 감지되면 앱 기동 시 **`WARN` 로그가 출력**됩니다:  
> `[FeatureFlags] ⚠️ F-04 auditDb=OFF — 감사 로그 DB 저장 비활성. 운영 환경에서는 IDO_AUDIT_DB_ENABLED=true 필수!`

---

## 1. 이 기능은 무엇인가?

모든 API 요청·응답 감사 이벤트를 **`ido.audit_log` 테이블에 동기적으로 저장**합니다.  
트랜잭션 내부에서 동작하므로, 감사 로그 저장 실패 시 요청 처리도 롤백됩니다(무결성 보장).

```
API 요청 처리
  │
  ├─ 비즈니스 로직 실행
  │
  ├─→ [F-04=true] ido.audit_log INSERT (트랜잭션 내, 동기)
  │     실패 시 → 전체 트랜잭션 롤백 (요청 실패 처리)
  │
  └─→ [F-03=true] Kafka platform.audit.log 발행 (트랜잭션 외, 비동기)
```

---

## 2. 테이블 스키마

```sql
CREATE TABLE ido.audit_log (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    event_id       VARCHAR(36)  NOT NULL UNIQUE COMMENT 'UUID',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    event_type     VARCHAR(64)  NOT NULL COMMENT 'INBOUND_RECEIVED|AUTH_SUCCESS|...',
    agency_code    VARCHAR(20)  COMMENT '기관코드 (기관 요청 시)',
    user_id        VARCHAR(36)  COMMENT '사용자 UUID',
    endpoint       VARCHAR(200) NOT NULL COMMENT '요청 엔드포인트',
    http_method    VARCHAR(10)  NOT NULL,
    result_code    VARCHAR(10)  NOT NULL COMMENT 'HTTP 상태코드',
    result_message VARCHAR(500) COMMENT '결과 메시지',
    ip_address     VARCHAR(45)  COMMENT '요청 IP (IPv6 포함)',
    trace_id       VARCHAR(32)  COMMENT 'OTel Trace ID',
    extra_json     JSON         COMMENT '추가 메타데이터'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='IdO 서비스 감사 로그';

-- 주요 인덱스
CREATE INDEX idx_audit_log_agency_created ON ido.audit_log (agency_code, created_at);
CREATE INDEX idx_audit_log_event_type     ON ido.audit_log (event_type, created_at);
CREATE INDEX idx_audit_log_user_id        ON ido.audit_log (user_id, created_at);
```

---

## 3. 이벤트 타입 목록

| `event_type` | 설명 |
|-------------|------|
| `AUTH_SUCCESS` | 인증 성공 |
| `AUTH_FAILURE` | 인증 실패 |
| `AUTH_RATE_LIMIT` | F-01 Rate Limit 초과 |
| `AGENCY_RATE_LIMIT` | F-02 기관 Rate Limit 초과 |
| `INBOUND_RECEIVED` | Gateway 인바운드 이벤트 수신 |
| `OUTBOUND_SENT` | Gateway 아웃바운드 이벤트 발송 |
| `PROVISIONING_TRIGGERED` | 프로비저닝 실행 |
| `HMAC_SIG_INVALID` | HMAC 서명 검증 실패 |
| `AGENCY_KEY_AUTH_SUCCESS` | 기관 API Key 인증 성공 |
| `AGENCY_KEY_AUTH_FAILURE` | 기관 API Key 인증 실패 |
| `SP_RECEIVED` | Q-IM SP 수신 (F-18) |

---

## 4. false 설정 가능한 경우

**로컬 개발 환경**에서만, 그리고 DB 스키마 초기화 전 부트스트랩 단계에서만 허용:

```bash
# 로컬 개발 (audit_log 테이블 없이 구동 시)
IDO_AUDIT_DB_ENABLED=false
```

> **스테이징, UAT, 운영 환경에서는 절대 false 설정 금지**

---

## 5. 데이터 보존 정책

| 환경 | 보존 기간 | 파기 방법 |
|------|----------|----------|
| 운영 | 5년 (법적 의무) | F-11 파기 스케줄러 |
| 스테이징 | 90일 | 수동 정리 |
| 개발 | 30일 | 자동 Truncate |

---

## 6. 감사 쿼리 예시

```sql
-- 특정 기관의 오늘 감사 로그 전체 조회
SELECT event_type, COUNT(*) AS cnt, MAX(created_at) AS last_at
FROM ido.audit_log
WHERE agency_code = 'AGCY001'
  AND created_at >= CURDATE()
GROUP BY event_type
ORDER BY cnt DESC;

-- HMAC 서명 실패 이벤트 조회 (보안 모니터링)
SELECT created_at, agency_code, ip_address, extra_json
FROM ido.audit_log
WHERE event_type = 'HMAC_SIG_INVALID'
  AND created_at >= NOW() - INTERVAL 1 HOUR
ORDER BY created_at DESC
LIMIT 100;

-- 인증 실패 IP 랭킹 (브루트포스 탐지)
SELECT ip_address, COUNT(*) AS failure_count
FROM ido.audit_log
WHERE event_type = 'AUTH_FAILURE'
  AND created_at >= NOW() - INTERVAL 1 HOUR
GROUP BY ip_address
ORDER BY failure_count DESC
LIMIT 20;
```

---

## 7. 연관 기능

| 기능 | 관계 |
|------|------|
| [F-03 감사 Kafka](F-03-audit-kafka.md) | 동일 이벤트를 Kafka에도 발행 (독립) |
| [F-11 개인정보 파기](F-11-retention.md) | audit_log 파기 주기 제어 |
| [F-18 SP 수신 감사](F-18-sp-receiver-audit.md) | Q-IM 이벤트 → audit_log 기록 |
| [F-27 Agency Key 감사](F-27-agency-key-audit.md) | Agency 인증 이벤트 → audit_log 기록 |

---

## 연관 문서
- [FeatureFlags.java](../../idem-hub/src/main/java/kr/go/smes/idem-hub/config/FeatureFlags.java)
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
