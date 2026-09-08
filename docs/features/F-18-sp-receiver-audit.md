# F-18: Q-IM SP 수신 감사 로그

> **환경변수**: `IDO_QIM_RECEIVER_AUDIT`  
> **기본값**: `true`  
> **Spring 프로퍼티**: `ido.qim.receiver-audit-enabled`  
> **소스**: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/qim/SpReceiverAuditInterceptor.java`

---

## 1. 이 기능은 무엇인가?

**Q-IM(Qualified Identity Manager) SP(Service Provider) 수신 API** 호출에 대한 감사 로그를 기록합니다.  
Q-IM 연동에서 SP로서 수신하는 이벤트(인증 결과, 속성 수신 등)를 `ido.audit_log` 및 Kafka(F-03)에 기록하여, Q-IM 측 감사 추적과 연결할 수 있도록 합니다.

```
Q-IM → IdO SP 수신 API 호출
  │
  ▼
SpReceiverAuditInterceptor.preHandle()
  │
  ├─ [F-18=false] → 감사 로그 스킵 (API 처리는 정상 진행)
  │
  └─ [F-18=true]
       ├─ Q-IM 요청 헤더 추출 (QIM-Session-ID, QIM-SP-Code 등)
       ├─ 감사 이벤트 생성 (event_type = 'SP_RECEIVED')
       ├─→ F-04: DB 저장 (ido.audit_log)
       └─→ F-03: Kafka 발행 (platform.audit.log)
```

---

## 2. 감사 대상 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `POST /api/v1/qim/sp/assertion` | SAML/OIDC 어설션 수신 |
| `POST /api/v1/qim/sp/attribute` | 사용자 속성 수신 |
| `POST /api/v1/qim/sp/session/end` | SP 세션 종료 알림 |

---

## 3. 감사 로그 필드

```json
{
  "eventId":       "550e8400-e29b-41d4-a716-446655440001",
  "eventType":     "SP_RECEIVED",
  "timestamp":     "2025-01-15T09:30:00.123Z",
  "endpoint":      "/api/v1/qim/sp/assertion",
  "qimSessionId":  "QIM-SESSION-abc123",
  "qimSpCode":     "ONEPASS-SP-01",
  "userId":        "user-uuid",
  "agencyCode":    null,
  "result":        "SUCCESS",
  "extraJson": {
    "assertionType": "SAML2",
    "attributeCount": 5,
    "sessionDuration": 3600
  }
}
```

---

## 4. Q-IM 연동 컨텍스트

```
IdP (Q-IM)  →  SP (IdO: OnePass)
     │
     ├─ POST /api/v1/qim/sp/assertion  → F-18 감사 로그
     ├─ POST /api/v1/qim/sp/attribute  → F-18 감사 로그
     └─ POST /api/v1/qim/sp/session/end → F-18 감사 로그
```

Q-IM 연동 감사 요구사항 (행정안전부 Q-IM 연동 가이드라인):
- 모든 SP 수신 이벤트는 5년간 감사 추적 가능해야 함
- `QIM-Session-ID` 기준으로 이벤트 연결 가능해야 함

---

## 5. false 설정 가능한 경우

**Q-IM 연동이 구성되지 않은 환경** (로컬, 일부 개발 환경):
```bash
IDO_QIM_RECEIVER_AUDIT=false  # Q-IM 미연동 환경
```

> ⚠️ **Q-IM 연동이 활성화된 운영 환경에서 false 설정 시 감사 추적 불가 — Q-IM 가이드라인 위반**

---

## 6. 모니터링

```sql
-- Q-IM SP 수신 이벤트 일별 통계
SELECT
    DATE(created_at)    AS dt,
    endpoint,
    COUNT(*)            AS request_count,
    SUM(CASE WHEN result_code = '200' THEN 1 ELSE 0 END) AS success_count,
    SUM(CASE WHEN result_code != '200' THEN 1 ELSE 0 END) AS error_count
FROM ido.audit_log
WHERE event_type = 'SP_RECEIVED'
  AND created_at >= NOW() - INTERVAL 7 DAY
GROUP BY DATE(created_at), endpoint
ORDER BY dt DESC, endpoint;

-- Q-IM 세션 ID로 이벤트 흐름 추적
SELECT created_at, event_type, endpoint, result_code,
       JSON_EXTRACT(extra_json, '$.qimSessionId') AS qim_session
FROM ido.audit_log
WHERE JSON_EXTRACT(extra_json, '$.qimSessionId') = 'QIM-SESSION-abc123'
ORDER BY created_at;
```

---

## 연관 기능

| 기능 | 관계 |
|------|------|
| [F-03 감사 Kafka](F-03-audit-kafka.md) | SP 수신 이벤트 Kafka 발행 |
| [F-04 감사 DB](F-04-audit-db.md) | SP 수신 이벤트 DB 저장 |
| [F-05 OTel 추적](F-05-auth-tracing.md) | QIM-Session-ID → TraceID 연결 |

---

## 연관 문서
- [FeatureFlags.java](../../idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/config/FeatureFlags.java)
- [Q-IM SP 연동 가이드](../qim-sp-integration-guide.md)
