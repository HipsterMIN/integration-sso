# OnePass 통합인증 플랫폼 — 단계적 배포 전략 (Phase-Gate Rollout)

> **문서 버전**: v1.0  
> **작성일**: 2026-05-14  
> **목적**: 개발팀이 새로운 기능을 안전하게 학습·검증하며 단계적으로 활성화할 수 있는 페이즈별 배포 전략  
> **핵심 원칙**: 모든 신규 기능은 기본값 OFF → 팀 준비 완료 후 ON

---

## 목차

1. [왜 단계적 배포인가](#1-왜-단계적-배포인가)
2. [Feature Flag 전체 목록](#2-feature-flag-전체-목록)
3. [Phase 구성](#3-phase-구성)
4. [Phase 1: 기반 안정화](#4-phase-1-기반-안정화-현재-운영-기능만)
5. [Phase 2: 프로비저닝 활성화](#5-phase-2-프로비저닝-활성화-sprint-14)
6. [Phase 3: 양방향 Gateway](#6-phase-3-양방향-gateway-활성화-sprint-15)
7. [Phase 4: SDK 공개 + 보안 강화](#7-phase-4-sdk-공개--보안-강화-sprint-16-17)
8. [Phase Gate 체크리스트](#8-phase-gate-체크리스트)
9. [기능별 On/Off 빠른 참조표](#9-기능별-onoff-빠른-참조표)
10. [ConfigMap 페이즈별 설정값](#10-configmap-페이즈별-설정값)
11. [롤백 판단 기준](#11-롤백-판단-기준)
12. [팀 학습 로드맵](#12-팀-학습-로드맵)

---

## 1. 왜 단계적 배포인가

### 현재 상황 진단

Sprint 14~16에서 구현된 기능들은 기술적으로 완성됐지만, 개발팀 입장에서는 **한 번에 이해하기 어려운 여러 새로운 패턴**이 동시에 도입됐습니다:

| 신규 기술/패턴 | 난이도 | 학습 필요 시간 |
|----------------|:------:|:----------:|
| Virtual Thread (JDK 21) + 68개 병렬 HTTP | 🔴 높음 | 1~2주 |
| Provisioning Outbox 패턴 (at-least-once) | 🔴 높음 | 1~2주 |
| FOR UPDATE SKIP LOCKED (분산 처리) | 🟡 중간 | 3~5일 |
| 이중 멱등성 방어 (Redis + DB) | 🟡 중간 | 3~5일 |
| HMAC-SHA256 서명 검증 | 🟡 중간 | 2~3일 |
| Agency Gateway 양방향 API | 🟡 중간 | 3~5일 |
| Java SDK JDK 8 호환 설계 | 🟡 중간 | 2~3일 |
| X-Api-Key 상수시간 비교 | 🟢 낮음 | 1~2일 |

### 단계적 배포의 장점

```
❌ 모든 기능 한 번에 활성화
   → 장애 발생 시 원인 파악 불가
   → 팀이 압도되어 인수인계 실패
   → 롤백 범위가 너무 넓어 위험

✅ Phase-Gate 단계적 활성화
   → 각 Phase에서 기능을 이해하고 검증
   → 장애 발생 시 해당 Phase 기능만 OFF
   → 팀이 새 기술을 점진적으로 습득
   → 실패 시 롤백 범위가 명확
```

### Feature Flag 철학

```
새 기능 배포 = 코드 배포 ≠ 기능 활성화

코드는 항상 배포됩니다.
기능은 팀이 준비됐을 때 환경변수 하나로 활성화합니다.
문제가 생기면 환경변수 하나로 즉시 비활성화합니다.
재배포 없이도 기능을 켜고 끌 수 있습니다.
```

---

## 2. Feature Flag 전체 목록

### 기존 플래그 (Sprint ~13, 안정 운영 중)

| ID | 환경변수 | 기본값 | 설명 | 상세 문서 |
|----|---------|:------:|------|-----------|
| F-01 | `IDO_AUTH_RL_ENABLED` | `true` | IP 기반 Auth Rate Limiting | [→](features/F-01-auth-rate-limit.md) |
| F-02 | `IDO_RATE_LIMIT_ENABLED` | `true` | 기관별 Rate Limiting | [→](features/F-02-agency-rate-limit.md) |
| F-03 | `IDO_AUDIT_KAFKA_ENABLED` | `true` | 감사 로그 Kafka 발행 | [→](features/F-03-audit-kafka.md) |
| F-04 | `IDO_AUDIT_DB_ENABLED` | `true` | 감사 로그 DB 저장 ⚠️OFF금지 | [→](features/F-04-audit-db.md) |
| F-05 | `IDO_AUTH_TRACING_ENABLED` | `true` | OTel 분산 추적 AOP | [→](features/F-05-auth-tracing.md) |
| F-08 | `IDO_REDISSON_ENABLED` | `true` | Redisson 분산 락 | [→](features/F-08-redisson-lock.md) |
| F-10 | `IDO_SECURITY_HEADERS_ENABLED` | `true` | 보안 응답 헤더 | [→](features/F-10-security-headers.md) |
| F-11 | `IDO_RETENTION_ENABLED` | `false` | 개인정보 파기 스케줄러 ⚠️ | [→](features/F-11-retention.md) |
| F-11b | `IDO_RETENTION_DRY_RUN` | `true` | 파기 dry-run 모드 | [→](features/F-11-retention.md) |
| F-12 | `IDO_CRYPTO_ROTATION_ENABLED` | `true` | Handoff 키 로테이션 | [→](features/F-12-crypto-rotation.md) |
| F-13 | `IDO_OUTBOX_RELAY_ENABLED` | `true` | IdO Outbox Relay | [→](features/F-13-outbox-relay.md) |
| F-14 | `IDO_WEBHOOK_RELAY_ENABLED` | `true` | Webhook Outbox Relay | [→](features/F-14-webhook-relay.md) |
| F-18 | `IDO_QIM_RECEIVER_AUDIT` | `true` | SP 수신 감사 로그 | [→](features/F-18-sp-receiver-audit.md) |

### 신규 플래그 (Sprint 14~16, Phase-Gate 관리)

| ID | 환경변수 | Phase 1 기본값 | Phase 2+ | 설명 | 상세 문서 |
|----|---------|:------:|:------:|------|-----------|
| **F-20** | `IDO_PROVISIONING_ENABLED` | **`false`** | `true` | 전 기관 프로비저닝 (Virtual Thread) | [→](features/F-20-provisioning.md) |
| **F-21** | `IDO_PROVISIONING_RELAY_ENABLED` | **`false`** | `true` | Provisioning Outbox 릴레이 | [→](features/F-21-provisioning-relay.md) |
| **F-22** | `IDO_PROVISIONING_DRY_RUN` | **`true`** | `false` | 프로비저닝 dry-run (로그만, HTTP 미발행) | [→](features/F-20-provisioning.md) |
| **F-23** | `IDO_GATEWAY_INBOUND_ENABLED` | **`false`** | `true` | Agency → OnePass 인바운드 API | [→](features/F-23-gateway-inbound.md) |
| **F-24** | `IDO_GATEWAY_OUTBOUND_ENABLED` | **`false`** | `true` | OnePass → Agency 아웃바운드 API | [→](features/F-24-gateway-outbound.md) |
| **F-25** | `IDO_GATEWAY_IDEMPOTENCY_ENABLED` | **`true`** | `true` | Redis 멱등성 중복 방어 | [→](features/F-25-gateway-idempotency.md) |
| **F-26** | `IDO_HMAC_SIG_REQUIRED` | **`false`** | `true` | X-Internal-Sig HMAC 필수 검증 | [→](features/F-26-hmac-sig.md) |
| **F-27** | `IDO_AGENCY_KEY_AUDIT_LOG` | **`true`** | `true` | Agency Key 인증 감사 로그 | [→](features/F-27-agency-key-audit.md) |

> **F-22 프로비저닝 dry-run 원칙**:  
> Phase 2 진입 시 `IDO_PROVISIONING_ENABLED=true` + `IDO_PROVISIONING_DRY_RUN=true`로 먼저 2주 운영.  
> 로그에서 "프로비저닝 dry-run: 대상 기관 N개" 확인 후 `IDO_PROVISIONING_DRY_RUN=false` 전환.

---

## 3. Phase 구성

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     OnePass Phase-Gate 배포 전략                         │
├──────────────┬──────────────────────────────────────────────────────────┤
│   Phase 1    │  기반 안정화 (지금 ~ Phase Gate 1 통과)                   │
│   "관찰"     │  기존 기능만 운영, 신규 기능 전부 OFF                      │
│              │  팀이 코드베이스와 인프라를 충분히 이해하는 기간             │
├──────────────┼──────────────────────────────────────────────────────────┤
│   Phase 2    │  프로비저닝 활성화 (Gate 1 통과 후)                        │
│   "학습"     │  F-20(dry-run ON) → 관찰 2주 → dry-run OFF               │
│              │  Virtual Thread, Outbox 패턴 팀 학습                      │
├──────────────┼──────────────────────────────────────────────────────────┤
│   Phase 3    │  양방향 Gateway 활성화 (Gate 2 통과 후)                    │
│   "확장"     │  F-23/F-24 인바운드/아웃바운드 API 개방                   │
│              │  멱등성, API Key 보안 팀 학습                              │
├──────────────┼──────────────────────────────────────────────────────────┤
│   Phase 4    │  SDK 공개 + 보안 강화 (Gate 3 통과 후)                     │
│   "완성"     │  Java SDK 배포, HMAC 필수화, mTLS 준비                    │
│              │  유관기관 연동 시작                                        │
└──────────────┴──────────────────────────────────────────────────────────┘

각 Phase 사이에 Gate 체크리스트가 있습니다.
Gate를 통과하지 못하면 다음 Phase로 진입하지 않습니다.
```

---

## 4. Phase 1: 기반 안정화 (현재 운영 기능만)

### 목표
- 기존 기능(Sprint ~13)을 완벽하게 이해하고 안정적으로 운영
- 신규 코드(Sprint 14~16)는 배포됐지만 **모두 OFF 상태**
- 팀이 코드베이스, 인프라, 모니터링에 익숙해지는 기간

### 기간
**최소 2주** (팀 역량에 따라 연장 가능)

### 활성화 기능

```yaml
# Phase 1 ConfigMap — 기존 기능만 운영
# Sprint 14~16 신규 기능은 전부 false

# ── 신규 기능 (모두 OFF) ──────────────────────────────────
IDO_PROVISIONING_ENABLED: "false"        # F-20: OFF ← 핵심
IDO_PROVISIONING_RELAY_ENABLED: "false"  # F-21: OFF
IDO_PROVISIONING_DRY_RUN: "true"         # F-22: dry-run 준비
IDO_GATEWAY_INBOUND_ENABLED: "false"     # F-23: OFF
IDO_GATEWAY_OUTBOUND_ENABLED: "false"    # F-24: OFF
IDO_GATEWAY_IDEMPOTENCY_ENABLED: "true"  # F-25: 항상 ON (방어)
IDO_HMAC_SIG_REQUIRED: "false"           # F-26: OFF (Sprint 17)
IDO_AGENCY_KEY_AUDIT_LOG: "true"         # F-27: 항상 ON (감사)

# ── 기존 기능 (변경 없음) ──────────────────────────────────
IDO_AUTH_RL_ENABLED: "true"
IDO_RATE_LIMIT_ENABLED: "true"
IDO_AUDIT_KAFKA_ENABLED: "true"
IDO_AUDIT_DB_ENABLED: "true"
IDO_AUTH_TRACING_ENABLED: "true"
IDO_REDISSON_ENABLED: "true"
IDO_SECURITY_HEADERS_ENABLED: "true"
IDO_RETENTION_ENABLED: "false"          # 법무팀 승인 전까지 OFF
IDO_RETENTION_DRY_RUN: "true"
IDO_CRYPTO_ROTATION_ENABLED: "true"
IDO_OUTBOX_RELAY_ENABLED: "true"
IDO_WEBHOOK_RELAY_ENABLED: "true"
IDO_QIM_RECEIVER_AUDIT: "true"
```

### Phase 1 팀 학습 과제

```
Week 1:
  □ 코드베이스 전체 구조 파악 (ido, q-im, q-sign, agency-stub)
  □ 로컬 환경 docker-compose 기동 성공
  □ Actuator 엔드포인트 확인 (http://localhost:8083/actuator/features)
  □ 기존 테스트 전체 실행 성공

Week 2:
  □ Sprint 14 코드 리뷰: ProvisioningService, ProvisioningOutboxRelay
  □ Sprint 15 코드 리뷰: AgencyGatewayController, AgencyGatewayServiceImpl
  □ Sprint 16 코드 리뷰: idem-sdk-java 전체
  □ Flyway V15/V16 마이그레이션 SQL 이해
  □ FeatureFlags 클래스 이해 (/actuator/features 응답 확인)
```

### Phase 1 검증 명령

```bash
# 1. 배포 후 기능 상태 확인 (신규 기능 모두 false여야 함)
curl -s http://localhost:8083/actuator/features | jq '.features | to_entries[] | select(.value.enabled == true) | .key'
# 예상 출력: F-01, F-02, F-03, F-04, F-05, F-08, F-10, F-12, F-13, F-14, F-18, F-25, F-27

# 2. 신규 기능 OFF 확인
curl -s http://localhost:8083/actuator/features | jq '.features["F-20_provisioning"]'
# 예상: { "enabled": false, "env": "IDO_PROVISIONING_ENABLED" }

# 3. 기존 기능 정상 작동 확인
curl -s http://localhost:8083/actuator/health | jq .status
# 예상: "UP"

# 4. 로그에서 FeatureFlags 출력 확인
kubectl logs -n smes deployment/ido | grep "FeatureFlags"
# 예상: F-20 provisioning = false, F-23 gatewayInbound = false ...
```

---

## 5. Phase 2: 프로비저닝 활성화 (Sprint 14)

### 목표
- Virtual Thread 기반 전 기관 프로비저닝 기능 활성화
- **2단계 전환**: dry-run(로그 확인) → 실제 HTTP 발행

### 사전 조건 (Gate 1)
- [ ] Phase 1 학습 과제 완료
- [ ] 팀 전원이 `ProvisioningService`, `ProvisioningOutboxRelay` 코드 이해
- [ ] `agency_endpoint_registry` 테이블에 테스트 기관 엔드포인트 등록 완료
- [ ] `provisioning_outbox` 테이블 존재 확인 (`SELECT count(*) FROM ido.provisioning_outbox`)
- [ ] agency-stub 서버 기동 및 `/provision` 엔드포인트 응답 확인

### Phase 2-A: dry-run 관찰 (2주)

```yaml
# Phase 2-A ConfigMap — dry-run 모드로 프로비저닝 로그 관찰
IDO_PROVISIONING_ENABLED: "true"         # F-20: ON (dry-run)
IDO_PROVISIONING_RELAY_ENABLED: "false"  # F-21: 릴레이는 아직 OFF
IDO_PROVISIONING_DRY_RUN: "true"         # F-22: dry-run (HTTP 미발행)
```

**dry-run 모드 동작**: 실제 HTTP POST를 보내지 않고 로그에만 기록합니다.
```
[Provisioning] DRY-RUN: 프로비저닝 트리거 qimUserId=xxx eventType=USER_REGISTERED
[Provisioning] DRY-RUN: 대상 기관 68개 — 실제 발행 안 함
[Provisioning] DRY-RUN: 기관별 페이로드 생성 완료 → HTTP POST 생략
```

**관찰 항목**:
```bash
# 프로비저닝 dry-run 로그 확인
kubectl logs -n smes deployment/ido | grep "DRY-RUN" | head -20

# dry-run 중 아웃박스 미생성 확인 (0이어야 함)
psql -h $DB_HOST -U onepass -d onepass -c \
  "SELECT count(*) FROM ido.provisioning_outbox WHERE created_at > now() - interval '1 hour';"
```

### Phase 2-B: 실제 발행 (dry-run 해제)

Gate 2-A 통과 기준:
- [ ] dry-run 로그에서 대상 기관 수가 예상과 일치 (예: 3개 테스트 기관)
- [ ] 페이로드 형식이 기관 API 스펙과 일치
- [ ] 팀이 Virtual Thread 동작 원리 이해

```yaml
# Phase 2-B ConfigMap — 실제 프로비저닝 활성화
IDO_PROVISIONING_ENABLED: "true"         # F-20: ON
IDO_PROVISIONING_RELAY_ENABLED: "true"   # F-21: 릴레이 ON
IDO_PROVISIONING_DRY_RUN: "false"        # F-22: 실제 발행
```

**검증**:
```bash
# 프로비저닝 성공 확인
psql -c "SELECT agency_code, status, count(*) 
         FROM ido.provisioning_outbox 
         GROUP BY agency_code, status 
         ORDER BY agency_code;"

# DEAD_LETTER 없음 확인 (1시간 기준)
psql -c "SELECT count(*) FROM ido.provisioning_outbox 
         WHERE status='DEAD_LETTER' AND created_at > now() - interval '1 hour';"
# 예상: 0
```

---

## 6. Phase 3: 양방향 Gateway 활성화 (Sprint 15)

### 목표
- 기관 ↔ OnePass 양방향 이벤트 API 개방
- Redis 멱등성 방어 팀 이해

### 사전 조건 (Gate 2)
- [ ] Phase 2-B 안정 운영 2주
- [ ] 팀 전원이 `AgencyGatewayController`, `AgencyGatewayServiceImpl` 코드 이해
- [ ] Redis 멱등성 저장소 (`GatewayIdempotencyStore`) 이해
- [ ] `gateway_inbound_audit`, `gateway_outbound_audit` 테이블 존재 확인
- [ ] 테스트 기관 API Key 발급 완료

### Phase 3-A: 인바운드 API 먼저

```yaml
# Phase 3-A ConfigMap — 인바운드만 먼저 활성화
IDO_GATEWAY_INBOUND_ENABLED: "true"      # F-23: ON (기관→OnePass)
IDO_GATEWAY_OUTBOUND_ENABLED: "false"    # F-24: 아직 OFF
IDO_GATEWAY_IDEMPOTENCY_ENABLED: "true"  # F-25: 항상 ON
```

**검증**:
```bash
# 테스트 기관에서 인바운드 이벤트 발송
curl -X POST https://onepass.go.kr/api/v1/agency/gateway/inbound/event \
  -H "X-Agency-Code: TEST_AGENCY" \
  -H "X-Agency-Key: {api-key}" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -H "X-Event-Type: USER_ACTION" \
  -H "Content-Type: application/json" \
  -d '{"action": "login", "userId": "test123"}'
# 예상: 202 Accepted

# 중복 전송 테스트 (같은 idempotency key)
# 예상: 409 Conflict (정상 동작)

# 감사 테이블 확인
psql -c "SELECT agency_code, event_type, status, created_at 
         FROM ido.gateway_inbound_audit 
         ORDER BY created_at DESC LIMIT 5;"
```

### Phase 3-B: 아웃바운드 API 활성화

Gate 3-A 통과 기준:
- [ ] 인바운드 이벤트 정상 수신 확인
- [ ] 멱등성 충돌(409) 처리 확인
- [ ] Redis 멱등성 키 TTL 24h 확인
- [ ] 팀이 이중 방어(Redis + DB ON CONFLICT) 원리 이해

```yaml
# Phase 3-B ConfigMap — 아웃바운드도 활성화
IDO_GATEWAY_INBOUND_ENABLED: "true"      # F-23: ON
IDO_GATEWAY_OUTBOUND_ENABLED: "true"     # F-24: ON
IDO_GATEWAY_IDEMPOTENCY_ENABLED: "true"  # F-25: ON
```

---

## 7. Phase 4: SDK 공개 + 보안 강화 (Sprint 16~17)

### 목표
- `idem-sdk-java` Maven/Gradle 배포
- HMAC-SHA256 서명 필수화
- 유관기관 연동 시작

### 사전 조건 (Gate 3)
- [ ] Phase 3 안정 운영 2주
- [ ] 최소 1개 기관과 Gateway API 실제 연동 완료
- [ ] SDK 배포 파이프라인 준비
- [ ] 팀이 `HmacSigner`, `IdempotencyKeyGenerator` 이해

### Phase 4 활성화

```yaml
# Phase 4 ConfigMap — HMAC 필수화
IDO_HMAC_SIG_REQUIRED: "true"            # F-26: HMAC 필수 검증 (Sprint 17)
```

> **주의**: `IDO_HMAC_SIG_REQUIRED=true` 전환 전에 **모든 연동 기관이 X-Internal-Sig 헤더를 포함**해야 합니다.  
> 기관 준비 완료 확인 없이 이 플래그를 true로 변경하면 기존 연동이 모두 401 오류가 됩니다.

---

## 8. Phase Gate 체크리스트

### Gate 1 (Phase 1 → Phase 2 진입 조건)

```
팀 이해도:
  □ FeatureFlags 클래스 역할 및 /actuator/features 사용법 이해
  □ ProvisioningService 코드 리뷰 완료 (Virtual Thread 설명 가능)
  □ ProvisioningOutboxRelay 지수 백오프 원리 설명 가능
  □ Flyway V15 마이그레이션 SQL 이해

인프라 준비:
  □ agency_endpoint_registry에 테스트 기관 1개 이상 등록
  □ provisioning_outbox 테이블 정상 생성 확인
  □ agency-stub 서버 기동 및 헬스체크 통과
  □ 모니터링 대시보드(Grafana/CloudWatch) 설정

승인:
  □ 팀 리드 게이트 리뷰 완료
  □ Phase 2-A 배포 계획 공유 완료
```

### Gate 2 (Phase 2 → Phase 3 진입 조건)

```
팀 이해도:
  □ 이중 멱등성 방어(Redis + DB) 원리 설명 가능
  □ AgencyGatewayServiceImpl.receiveInbound() 코드 리뷰 완료
  □ GatewayIdempotencyStore TTL 설정 이해
  □ ON CONFLICT DO NOTHING SQL 패턴 이해

운영 안정성:
  □ Phase 2-B 2주 이상 DEAD_LETTER 발생 0건
  □ provisioning_outbox PENDING 건수 < 10 유지
  □ 테스트 기관 API Key 발급 및 DB 등록 완료
  □ gateway_inbound_audit 테이블 정상 생성 확인

승인:
  □ 팀 리드 게이트 리뷰 완료
  □ 테스트 기관 담당자 연동 동의 완료
```

### Gate 3 (Phase 3 → Phase 4 진입 조건)

```
팀 이해도:
  □ HmacSigner 서명/검증 로직 설명 가능
  □ IdempotencyKeyGenerator 3종 전략 이해
  □ idem-sdk-java 빌드 및 로컬 테스트 실행 성공
  □ AgencyGatewayClient Builder 패턴 사용법 이해

운영 안정성:
  □ Phase 3 2주 이상 인바운드/아웃바운드 오류율 < 1%
  □ 실제 기관 1개 이상 연동 성공
  □ X-Internal-Sig 헤더 구현 기관 확인

승인:
  □ 보안팀 HMAC 필수화 일정 승인
  □ 유관기관 준비 완료 확인 (HMAC 헤더 포함 배포)
  □ SDK Maven 저장소 배포 완료
```

---

## 9. 기능별 On/Off 빠른 참조표

### 긴급 비활성화 (장애 발생 시)

```bash
# 프로비저닝 즉시 중단
kubectl set env deployment/ido -n smes \
  IDO_PROVISIONING_ENABLED=false \
  IDO_PROVISIONING_RELAY_ENABLED=false

# Gateway API 즉시 중단
kubectl set env deployment/ido -n smes \
  IDO_GATEWAY_INBOUND_ENABLED=false \
  IDO_GATEWAY_OUTBOUND_ENABLED=false

# HMAC 검증 완화 (기관 연동 장애 시)
kubectl set env deployment/ido -n smes IDO_HMAC_SIG_REQUIRED=false

# 전체 신규 기능 즉시 롤백 (Phase 1 상태로)
kubectl set env deployment/ido -n smes \
  IDO_PROVISIONING_ENABLED=false \
  IDO_PROVISIONING_RELAY_ENABLED=false \
  IDO_PROVISIONING_DRY_RUN=true \
  IDO_GATEWAY_INBOUND_ENABLED=false \
  IDO_GATEWAY_OUTBOUND_ENABLED=false \
  IDO_HMAC_SIG_REQUIRED=false
```

> **중요**: `kubectl set env`는 즉시 반영되며, Pod 재시작 없이 새 값이 적용됩니다.  
> 단, `@Value` 어노테이션으로 주입된 값은 **Pod 재시작 시**에 새 값으로 갱신됩니다.  
> 즉각 반영이 필요하면 `kubectl rollout restart deployment/ido -n smes`를 추가로 실행하세요.

### 기능 상태 확인

```bash
# 현재 모든 Feature Flag 상태 확인
curl -s http://ido-service:8083/actuator/features | jq '.features'

# Phase별 예상 상태 비교
# Phase 1: F-20~F-27 중 F-20, F-21, F-23, F-24, F-26 = false
# Phase 2: F-20, F-21 = true
# Phase 3: F-23, F-24 = true
# Phase 4: F-26 = true

# 실시간 로그로 기능 상태 확인
kubectl logs -n smes deployment/ido --follow | grep "\[FeatureFlags\]"
```

---

## 10. ConfigMap 페이즈별 설정값

페이즈별 ConfigMap(`infra/k8s/configmaps/ido-configmap-phase*.yml`)은 운영기관 전용 매니페스트라 **D3 에서 코어 저장소에서 제거**했다.
같은 값은 설치본의 환경변수(`infra/docker/install.env`, `docs/install.md`)로 준다. 운영기관의 k8s 매니페스트는 그 기관의 인프라 저장소가 관리한다.

```bash
# 현재 Phase 확인
kubectl get configmap ido-config -n smes -o yaml | grep "IDO_PROVISIONING_ENABLED"

# Phase 2 전환 (Gate 1 통과 후)
kubectl apply -f infra/k8s/configmaps/ido-configmap-phase2.yml
kubectl rollout restart deployment/ido -n smes
kubectl rollout status deployment/ido -n smes
```

---

## 11. 롤백 판단 기준

### 즉시 롤백 기준 (자동)

| 지표 | 임계값 | 롤백 대상 |
|------|--------|-----------|
| HTTP 5xx 오류율 | > 5% (5분) | 전체 배포 롤백 |
| DEAD_LETTER 급증 | > 50건/시간 | F-20, F-21 OFF |
| Redis 연결 실패 | > 10초 지속 | F-25 OFF (DB 2차 방어 유지) |
| Gateway 응답 지연 | p99 > 5초 | F-23, F-24 OFF |
| Memory OOM | Pod 재시작 반복 | 전체 배포 롤백 |

### 수동 롤백 기준 (팀 판단)

| 상황 | 권장 조치 |
|------|-----------|
| 프로비저닝 기관 오류 다수 | F-22 dry-run=true로 일시 전환 |
| 기관 API Key 인증 다수 실패 | F-27 감사 로그로 원인 파악 후 결정 |
| HMAC 검증 실패 급증 | F-26 false로 일시 완화 |
| 팀이 원인 파악 불가 | 해당 Phase 기능 전체 OFF → Phase 지원팀 연락 |

---

## 12. 팀 학습 로드맵

### Sprint 14 학습 순서 (프로비저닝)

```
1단계 (Day 1~2): 개념 이해
  → docs/features/F-20-provisioning.md 읽기
  → Virtual Thread 기본 개념 학습
  → Outbox 패턴 이해

2단계 (Day 3~5): 코드 읽기
  → ProvisioningService 인터페이스 읽기
  → ProvisioningServiceImpl.triggerProvisioning() 단계별 분석
  → ProvisioningOutboxRelay.relay() 분석

3단계 (Day 6~7): 테스트 실행
  → ./gradlew :idem-hub:test --tests "*ProvisioningServiceTest*"
  → 각 테스트가 무엇을 검증하는지 확인
  → dry-run 모드로 로컬 실행 후 로그 관찰

4단계 (Day 8~10): Gate 1 준비
  → 팀원에게 Virtual Thread 동작 설명해보기
  → DEAD_LETTER 발생 시나리오 토론
  → agency_endpoint_registry 데이터 직접 INSERT
```

### Sprint 15 학습 순서 (Gateway)

```
1단계: 이중 멱등성 방어 개념
  → Redis SET NX vs DB UNIQUE 역할 차이 이해
  → "Redis 장애 시 DB가 2차 방어" 원리 이해

2단계: 코드 읽기
  → GatewayIdempotencyStore.tryAcquireInbound() 분석
  → AgencyGatewayServiceImpl.receiveInbound() 4단계 흐름 분석
  → GatewayInboundRepository.insert() SQL 분석

3단계: 실제 테스트
  → curl로 인바운드 이벤트 전송
  → 같은 idempotency key로 재전송 → 409 확인
  → gateway_inbound_audit 테이블에서 레코드 확인
```

### Sprint 16 학습 순서 (SDK)

```
1단계: SDK 사용법 이해
  → docs/features/F-23-gateway-inbound.md의 SDK 예시 코드 실행
  → AgencyGatewayClient.builder() 패턴 이해

2단계: SDK 내부 구조
  → AgencyHttpAdapter 인터페이스 역할
  → HttpUrlConnectionAdapter vs OkHttpAgencyAdapter 차이
  → HmacSigner 서명 로직 이해

3단계: 기관 연동 시뮬레이션
  → agency-stub 서버에 SDK로 이벤트 전송
  → MockWebServer 테스트 실행 확인
```

---

> **문서 관리**: 이 문서는 각 Phase 전환 시 업데이트됩니다.  
> **담당자**: 개발팀 리드  
> **관련 문서**:  
> - [배포 가이드](_archive/2026-05-22/deployment-guide.md)  
> - [기능 문서 목록](features/)  
> - [Feature Flag 코드](../idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/config/FeatureFlags.java)
