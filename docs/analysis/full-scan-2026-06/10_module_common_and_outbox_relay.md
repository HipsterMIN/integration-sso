# 10 · Module Deep Dive — platform-common + outbox-relay-batch

> 두 모듈은 도메인 없이 **횡단 관심사(cross-cut)** 를 담당하므로 하나의 문서로 결합.

---

## Part A. platform-common (M1)

### A.1 삼각 요약
| 항목 | 값 |
|---|---|
| 파일 수 | 23 |
| DB | 없음 (라이브러리 JAR) |
| 정본 스펙 | `docs/internal/spec/03a-module-platform-common.md` (270 lines) |
| 의존 모듈 | 모든 서비스 모듈이 platform-common 을 의존 |

### A.2 서브패키지
```
platform-common/src/main/java/kr/go/smes/common/
├── domain/    — AuthResult, HandoffPayload
├── error/     — PlatformErrorCode (E-QS-*, E-IDP-*, E-IM-*, E-IDO-*, E-AGENCY-*, E-OPS-*)
├── event/     — AuthEvent, HandoffEvent, AuditLogEvent, AdvisoryEvent
└── util/      — 유틸 함수
```

### A.3 핵심 계약

#### `AuthResult`
```java
public record AuthResult(
    String qimUserId, String authMethod, String assuranceLevel,
    Instant authenticatedAt, String provider, Map<String,Object> extras
) {}
```

#### `HandoffPayload` (★PR #205 이후 roles[] 추가)
```java
public record HandoffPayload(
    String qimUserId, String agencyCode, List<String> roles,
    Instant issuedAt, Instant expiresAt, String issuer,
    Map<String,Object> extras
) {}
```

#### `PlatformErrorCode` 접두어 규약
| 접두 | 원천 |
|---|---|
| `E-QS-*` | Q-Sign |
| `E-IDP-*` | 외부 IdP |
| `E-IM-*` | Q-IM |
| `E-IDO-*` | IdO |
| `E-AGENCY-*` | agency-stub / SDK |
| `E-OPS-*` | 운영/인프라 |

**관찰**: `E-AUTHZ-*` 접두는 **아직 정의되지 않음** — q-authz 편입 후 필요.

#### 이벤트 타입 4종
| 이벤트 | 발행자 | 소비자 |
|---|---|---|
| `AuthEvent` | Q-Sign | IdO |
| `HandoffEvent` | IdO | 감사 |
| `AuditLogEvent` | 전 계열 | 감사 스토어 |
| `AdvisoryEvent` | IdO | SSO/SLO 조언 (SP) |

### A.4 관찰된 결여
| # | 항목 | 위험 |
|---|---|---|
| L1 | `E-AUTHZ-*` 에러코드 미정의 | 🟡 MED |
| L2 | `AuthzEvent` (grant/revoke 이벤트) 미정의 | 🔴 HIGH |
| L3 | `HandoffPayload.roles[]` 필드 정본 문서 미갱신 (03a) | 🟡 MED |

---

## Part B. outbox-relay-batch (M10)

### B.1 삼각 요약
| 항목 | 값 |
|---|---|
| 포트 | 8088 |
| 파일 수 | 20 |
| 역할 | 3개 Outbox → Kafka 릴레이 배치 |
| 락 | ShedLock (분산 락) |
| Spring | Spring Batch + Scheduling |
| ADR | ADR-003 (Outbox 채택), ADR-2026-005 (스케줄러 독립화 PROPOSED) |

### B.2 서브패키지
```
outbox-relay-batch/src/main/java/kr/go/smes/batch/
├── alert/       — 알림 (Slack/Email)
├── config/      — Bean 설정, ShedLock, Kafka
├── job/
│   ├── ido/     — IdO Outbox 릴레이 (ProvisioningOutbox + Webhook)
│   ├── qim/     — Q-IM Outbox 릴레이
│   └── qsign/   — Q-Sign Outbox 릴레이
└── metrics/     — Micrometer 지표
```

### B.3 릴레이 파이프라인
```
Origin DB (transaction) ─→ *_outbox table (same tx)
                              │
                              ▼
                     ShedLock 획득 (분산 락)
                              │
                              ▼
             SELECT ... WHERE published=false ORDER BY created_at LIMIT N
                              │
                              ▼
                     Kafka.send(topic, payload)
                              │
                              ▼
                     UPDATE outbox SET published=true (in tx)
                              │
                              ▼
             실패 시: UPDATE next_retry_at = NOW() + backoff (V17 IdO)
```

### B.4 ShedLock 정책
- 락 스토어: 별도 테이블 (관찰 필요)
- 락 TTL: 대략 5분(관찰 필요)
- 여러 인스턴스에서 동시 실행 → 오직 1개가 성공

### B.5 관찰된 결여
| # | 항목 | 위험 |
|---|---|---|
| L1 | **q-authz Outbox 릴레이 잡 미착수** | 🔴 HIGH |
| L2 | 지표 대시보드 정본화 | 🟡 MED |
| L3 | 실패 알림 채널 (Slack) 운영 계약 | 🟡 MED |
| L4 | ADR-2026-005 스케줄러 독립화 PROPOSED → 아직 결정 대기 | 🟢 LOW |

### B.6 배치 잡 3개 (관찰)
| 잡 | 원천 DB | 발행 토픽 |
|---|---|---|
| `qsign-outbox-relay` | qsign schema | `qsign.auth.events`, `platform.audit.log` |
| `qim-outbox-relay` | qim DB (MariaDB) | `qim.user.events`, `qim.user.snapshot` |
| `ido-outbox-relay` | ido schema | `ido.handoff.events`, `platform.session.advisory`, Webhook |

---

## Part C. 두 모듈의 결합 관계

`outbox-relay-batch` 는 `platform-common` 의 이벤트 스키마(`AuthEvent`/`HandoffEvent`/...) 를 직렬화하여 Kafka 로 발행한다. 즉 **platform-common 이 계약, outbox-relay-batch 가 전송**.

---

## 참조
- platform-common: `docs/internal/spec/03a-module-platform-common.md`
- Outbox 개발자 가이드: `Outbox패턴_개발자가이드.md` (120KB)
- Outbox 비개발자: `Outbox패턴_비개발자용.md` (23KB)
- ADR-003, ADR-2026-005
