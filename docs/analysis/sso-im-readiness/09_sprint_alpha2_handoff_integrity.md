# Sprint α-2 진행 보고 — Handoff 무결성 (F4.1 + F4.5 + F4.2)

**진행 일자**: 2026-05-22
**브랜치**: `shipster` (개발 브랜치, PR shipster → main)
**해결 결함**: F4.1 (Critical / 위험도 10), F4.5 (Critical / 위험도 9), F4.2 (Critical / 위험도 8)
**관련 분석**: `04_handoff_flow.md §F4.1, §F4.2, §F4.5` / `07_risk_matrix_roadmap.md`

---

## 1. 작업 목적

옵션 1 (점진 수정 / 안전) 로드맵의 두 번째 스프린트.
Handoff Verify 경로의 **무결성·원자성·재시도 가능성** 세 가지 본질 속성을 모두 깨뜨리던
세 개의 Critical 결함을 한 번에 봉합한다.

| 결함 | 본질 속성 침해 | 운영 영향 |
|------|--------------|----------|
| **F4.1** | 무결성 (Integrity) | 서명 검증을 거치지 않아 Redis 침해/직렬화 변조 시 변조된 payload 가 기관으로 전달 |
| **F4.5** | 재시도 가능성 (Idempotency) | Q-IM 일시 장애 시 ticket 이 영구 CONSUMED → 사용자 차단 |
| **F4.2** | 원자성 (Atomicity) | consume 이 GET→SET 2단계 → 동시 verify race → 동일 ticket 으로 두 번 페이로드 발급 |

세 결함은 **연쇄적**으로 동작했음에 주목해야 함:
- F4.1 로 변조 차단이 없는 상태에서
- F4.5 로 인해 race window 가 길고 (consume 이 buildPayload 보다 먼저라 race window 가 짧지만 정상경로에선 영구 차단)
- F4.2 로 동시 소비가 가능하면
- **변조된 payload 가 두 번 발급 + 사용자는 영구 차단** 이 동시에 가능했음

---

## 2. F4.1 — `HandoffServiceImpl.verify()` 서명 검증 미실행 (dead code)

### 2.1 변경 전 상태

```java
// idem-hub/src/main/java/kr/go/smes/idem-hub/handoff/HandoffServiceImpl.java:198~202
ticketRepository.consume(ticketId);
publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_CONSUMED, ticket, null);
HandoffPayload payload = policyEngine.buildHandoffPayload(ticket, correlationId);
// ↑ handoffCryptoService.verify() 호출 없음
```

`HandoffCryptoService.verify(ticketId, agencyCode, encryptedPayload, signature)` 는 정의되어 있으나
`HandoffServiceImpl.verify()` 에서 한 번도 호출되지 않아 **dead code** 상태.

**공격 시나리오** — `04_handoff_flow.md §F4.1`:
1. Redis 침해 또는 ObjectMapper 직렬화 결함으로 ticket 의 `qimUserId` 가 변조됨.
2. `findById` 가 변조된 ticket 을 그대로 반환.
3. 사전 상태 검증 (state/expiry/agencyCode) 만 통과하면 `buildHandoffPayload` 에 그대로 진입.
4. 변조된 qimUserId 로 Q-IM 조회 → 다른 사용자의 DI 가 payload 에 담겨 기관으로 전달.

### 2.2 변경 후 상태

```java
// HandoffServiceImpl.verify() — ② 단계로 HMAC 검증 추가
if (ticket.getEncryptedPayload() == null || ticket.getSignature() == null
        || !handoffCryptoService.verify(
                ticketId, agencyCode,
                ticket.getEncryptedPayload(), ticket.getSignature())) {
    publishSignatureInvalidEvent(ticket, correlationId);
    auditVerify(ticketId, agencyCode, correlationId,
            AuditLogEvent.OUTCOME_FAILURE, "SIGNATURE_INVALID");
    log.error("[IdO] Handoff Verify 서명 검증 실패 ticketId={} agency={} corr={}",
            ticketId, agencyCode, correlationId);
    throw new PlatformException(PlatformErrorCode.IDO_TICKET_SIGNATURE_INVALID, correlationId);
}
```

**보호 메커니즘**:
1. HMAC-SHA256 으로 `ticketId | agencyCode | encryptedPayload` 의 무결성 검증
2. `null` payload/signature 는 short-circuit 으로 즉시 실패 (방어적)
3. 검증 실패 시 **새 에러 코드** `IDO_TICKET_SIGNATURE_INVALID` (E-IDO-108, HTTP 401) 반환
4. 별도 Kafka 이벤트 `SIGNATURE_INVALID` 발행 → SIEM 에서 다회 발생 시 기관 차단/조사 트리거 가능

### 2.3 신규 에러 코드

| 코드 | HTTP | 메시지 |
|------|------|--------|
| `IDO_TICKET_SIGNATURE_INVALID` (E-IDO-108) | 401 Unauthorized | Handoff Ticket 서명 검증 실패. |

### 2.4 신규 Kafka 이벤트 타입

| 타입 | 토픽 | 용도 |
|------|------|------|
| `SIGNATURE_INVALID` | `ido.handoff.events` | 변조 의심 / Redis 침해 시그널, SIEM 연계 |

---

## 3. F4.5 — verify() 비-원자 다단계 → 부분 실패 시 사용자 영구 차단

### 3.1 변경 전 상태 (문제의 순서)

```
① Ticket 조회 + 사전 상태 검증
② ticketRepository.consume()                   ← ticket이 CONSUMED 로 전이됨
③ publishHandoffEvent(HANDOFF_CONSUMED)        ← Kafka publish
④ policyEngine.buildHandoffPayload()           ← Q-IM HTTP 호출 가능 (실패 가능)
⑤ auditVerify(SUCCESS) + return payload
```

**문제 시나리오**:
- ④ 단계의 `qimClient.getDi()` HTTP 호출이 timeout/실패 → 예외 throw.
- 메서드는 비정상 종료되지만 ②의 consume 은 이미 commit 됨.
- ticket 상태는 `CONSUMED` 로 영구 고정 → 사용자가 새로고침/재시도해도 `IDO_TICKET_CONSUMED` (409) 만 받음.
- 운영자가 수동으로 ticket 을 revoke 하고 사용자가 처음부터 재인증해야 함.

이 silent failure 는 **본질 기능(SSO 1회성 연계)을 깨뜨리지 않으면서 운영 가시성만 낮추는** 가장 위험한 종류의 결함.

### 3.2 변경 후 상태 (재시도 가능한 순서)

```
① Ticket 조회 + 사전 상태 검증
② HMAC 서명 검증                                ← F4.1 신규 (signature 검증)
③ policyEngine.buildHandoffPayload()           ← Q-IM HTTP 호출 (실패 시 ticket 유지)
④ ticketRepository.consume()                   ← Atomic CAS (F4.2 신규)
⑤ publishHandoffEvent(HANDOFF_CONSUMED)
⑥ auditVerify(SUCCESS) + return payload
```

**보호 메커니즘**:
- ③ 실패 시 ticket 은 ISSUED 그대로 → 사용자 재시도 가능 (60초 TTL 내).
- ③↔④ 사이의 race window 는 **F4.2 의 atomic CAS** 가 차단:
  - 동시 두 verify 요청이 ③을 모두 통과해도 ④에서 한쪽만 성공.
  - 패배한 요청은 `IDO_TICKET_CONSUMED` 받음.
  - **변조 차단(F4.1) + race 차단(F4.2)** 두 가드가 동시에 작동하여 ④에서 중복 발급 불가.

---

## 4. F4.2 — `TicketRepositoryImpl.consume()` 비-원자 CAS

### 4.1 변경 전 상태

```java
// TicketRepositoryImpl.consume() — GET-then-SET 패턴
Object value = redisTemplate.opsForValue().get(key);
if (value == null) throw IDO_TICKET_EXPIRED;
HandoffTicket existing = objectMapper.readValue(...);
HandoffTicket consumed = HandoffTicket.builder()
        .state(HandoffTicket.TicketState.CONSUMED)
        ... .build();
redisTemplate.opsForValue().set(key, consumed_json, ttl);  // ← GET 과 비-원자
```

**Race scenario** — `04_handoff_flow.md §F4.2`:
| t | 요청 A | 요청 B |
|---|--------|--------|
| t1 | `GET key` → ISSUED |  |
| t2 |  | `GET key` → ISSUED |
| t3 | state check 통과 | state check 통과 |
| t4 | `SET key consumed_json` |  |
| t5 |  | `SET key consumed_json` (덮어쓰기) |
| t6 | `buildPayload` → A 에게 발급 | `buildPayload` → B 에게 발급 |

두 요청 모두 동일 ticket 으로 페이로드를 받음 — 1회성 보장 위반.

### 4.2 변경 후 상태 (Lua atomic CAS)

```lua
-- TicketRepositoryImpl.CONSUME_LUA
local cur = redis.call('GET', KEYS[1]);
if cur == false then return 0; end;                        -- 키 없음
if string.find(cur, ARGV[4], 1, true) == nil then          -- state != ISSUED
  local s = string.match(cur, '"state":"([^"]+)"');
  if s == nil then return 'PARSE_ERROR'; end;
  return s;
end;
redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]);        -- atomic 전이
return 1;
```

```java
Object result = redisTemplate.execute(
        CONSUME_SCRIPT,
        Collections.singletonList(key),
        "ISSUED", consumedJson, String.valueOf(remainTtl), "\"state\":\"ISSUED\""
);
handleConsumeResult(ticketId, result);
```

**Redis Lua 보장**:
- Redis 는 single-threaded 실행 모델 → Lua 스크립트 전체가 atomic.
- 동시 두 verify 요청이 들어와도 한쪽만 `1` 반환, 다른 쪽은 mismatch state 반환.
- 패배한 요청은 `IDO_TICKET_CONSUMED` (혹은 `IDO_TICKET_REVOKED`) PlatformException 으로 전환.

### 4.3 반환값 처리 매트릭스

| Lua 반환 | 의미 | Java 측 처리 |
|----------|------|-------------|
| `1` (Long) | CAS 성공 | 정상 완료 + 감사 이력 갱신 |
| `0` (Long) | 키 없음 (만료) | `PlatformException(IDO_TICKET_EXPIRED)` |
| `"CONSUMED"` | race 패배 (이미 소비) | `PlatformException(IDO_TICKET_CONSUMED)` |
| `"REVOKED"` | race 중 revoke | `PlatformException(IDO_TICKET_REVOKED)` |
| `"PARSE_ERROR"` | JSON 파싱 실패 (손상) | `RuntimeException` (운영 알람) |

### 4.4 사전 빠른 거부 경로

Lua CAS 가 어차피 race 를 막지만, 명백한 이미 CONSUMED/REVOKED 상태는 Lua 호출 없이 빠르게 차단:
- 사전 `GET` 1회 + state 검사 → CONSUMED/REVOKED 면 즉시 PlatformException.
- Lua 호출은 ISSUED 상태가 확인된 경우에만 수행.
- 통상 99% 의 verify 는 사전 검사에서 race 없이 ISSUED 통과 → Lua 1회 호출로 완료.

---

## 5. 3중 안전망 — verify 전체 경로 시퀀스

```
┌──────────────────────────────────────────────────────────────┐
│ HandoffServiceImpl.verify(ticketId, agency, corr)            │
├──────────────────────────────────────────────────────────────┤
│  ① ticketRepository.findById()                               │
│     ├─ state == CONSUMED → REUSE_ATTEMPT 이벤트 + 409        │
│     ├─ state == REVOKED  → 410                              │
│     ├─ isExpired()       → 410                              │
│     └─ agencyCode mismatch → 403                            │
│                                                              │
│  ② handoffCryptoService.verify()        ← F4.1 신규          │
│     ├─ payload/signature null            ─┐                  │
│     ├─ HMAC mismatch                     ├─→ SIGNATURE_INVALID│
│     └─ AAD 검증 실패                       ─┘     이벤트 + 401  │
│                                                              │
│  ③ policyEngine.buildHandoffPayload()    ← F4.5 순서 변경     │
│     └─ Q-IM HTTP 호출 가능                                    │
│        실패 시: ticket 은 ISSUED 그대로 → 재시도 가능          │
│                                                              │
│  ④ ticketRepository.consume()            ← F4.2 Lua atomic   │
│     └─ ISSUED→CONSUMED CAS                                  │
│        race 패배 시: IDO_TICKET_CONSUMED throw               │
│                                                              │
│  ⑤ publishHandoffEvent(HANDOFF_CONSUMED)                    │
│  ⑥ audit log SUCCESS + return payload                       │
└──────────────────────────────────────────────────────────────┘
```

---

## 6. 호환성 영향 분석

| 호출 경로 | 변경 전 응답 | 변경 후 응답 | 비고 |
|-----------|-------------|-------------|------|
| 정상 verify | 200 + payload | 200 + payload | 동일 (서명 검증은 issue 시 set 된 정상 서명이므로 통과) |
| 변조된 ticket verify | 200 + 변조된 payload | **401 + IDO_TICKET_SIGNATURE_INVALID** | 새 차단 경로 |
| Q-IM 일시 장애 + 정상 verify | 5xx + ticket 영구 CONSUMED | 5xx + **ticket 유지 (재시도 가능)** | 차단성 응답 → 재시도 |
| 동시 verify (race) | 두 요청 모두 200 (중복 발급) | 한쪽 200, 한쪽 **409** | 1회성 보장 강화 |
| 미만료 ticket verify | 200 + payload | 200 + payload | 동일 |
| 만료된 ticket verify | 410 + IDO_TICKET_EXPIRED | 410 + IDO_TICKET_EXPIRED | 동일 |
| 이미 CONSUMED verify | 409 + REUSE_ATTEMPT 이벤트 | 409 + REUSE_ATTEMPT 이벤트 | 동일 |

**브레이킹 변경 평가**: 기관 측 클라이언트는 새 에러 코드 `E-IDO-108` 을 추가 처리해야 하지만,
HTTP status 401 + 표준 PlatformErrorResponse 형식이므로 기존 fallback 로직(`status >= 400` 처리)으로
별도 코드 변경 없이도 적절히 차단됨. 기관 onboarding 문서에 신규 코드 추가 안내 권장.

---

## 7. 회귀 테스트 목록

### 7.1 `HandoffServiceImplTest$SignatureVerification` (3 케이스, F4.1)
- `signatureMismatch_throwsSignatureInvalidAndDoesNotConsume`
  — HMAC 불일치 시 401 + consume 미호출 + Q-IM 미호출
- `nullEncryptedPayload_throwsSignatureInvalid`
  — payload null 시 short-circuit 401, verify() 호출 자체 안 됨
- `nullSignature_throwsSignatureInvalid`
  — signature null 시 short-circuit 401

### 7.2 `HandoffServiceImplTest$VerifyOrdering` (3 케이스, F4.5)
- `buildPayloadFails_doesNotConsumeTicket`
  — Q-IM 장애 → ticket ISSUED 유지, consume 미호출, Kafka publish 미발생
- `successPath_buildPayloadBeforeConsume`
  — Mockito InOrder 로 cryptoService.verify → buildPayload → consume → kafka.send 순서 검증
- `consumeRaceMismatch_propagatesPlatformException`
  — Lua CAS 가 IDO_TICKET_CONSUMED 던지면 HANDOFF_CONSUMED Kafka 미발행

### 7.3 `TicketRepositoryImplTest$CasBranchHandling` (9 케이스, F4.2)
- `casSuccess_completesAndUpdatesAudit` — Lua=1 → 정상 + 감사 이력 갱신
- `casKeyMissing_throwsExpired` — 사전 GET null → IDO_TICKET_EXPIRED, Lua 미호출
- `preCheckConsumed_throwsConsumedWithoutLua` — 사전 검사로 CONSUMED 발견 → Lua 미호출
- `preCheckRevoked_throwsRevokedWithoutLua` — 사전 검사로 REVOKED 발견 → Lua 미호출
- `casRaceMismatchConsumed_throwsConsumed` — Lua=CONSUMED → IDO_TICKET_CONSUMED
- `casRaceMismatchRevoked_throwsRevoked` — Lua=REVOKED → IDO_TICKET_REVOKED
- `casRaceMissingKey_throwsExpired` — Lua=0 → IDO_TICKET_EXPIRED
- `casParseError_throwsRuntimeException` — Lua=PARSE_ERROR → RuntimeException
- `luaScriptInvokedWithIssuedStateMarker` — ARGV 인자 정확성 검증

### 7.4 기존 verify 테스트 보정
- `validVerify_consumesTicketAndReturnsPayload` —
  서명 검증 mock(`true` 반환) 추가 + cryptoService.verify() 호출 1회 검증 추가.

**총 신규/보정 테스트**: 16건 (신규 15 + 보정 1).

---

## 8. 후속 작업 (이번 스프린트에서 다루지 않은 것)

| 항목 | 사유 | 다음 위치 |
|------|------|-----------|
| Testcontainers(Redis) 기반 통합 테스트로 Lua atomic 성 실증 | 단위 테스트로 분기 처리는 검증했으나, 실 Redis 상의 동시성은 별도 PR | Sprint α-3 또는 별도 통합 테스트 PR |
| `TicketRepositoryImpl.revoke()` 도 Lua atomic 으로 (F4.13 Medium) | 위험도 Medium, 별도 스프린트 | Sprint β-2 또는 β-3 |
| Webhook secret 평문 저장 (F4.10), Identity Hash 결함 (F4.11) | 동일 Phase 4 결함이지만 위험도/난이도 별개 | Sprint α-3 (F4.3 + F4.4 + F4.6) |
| 기관 onboarding 문서에 E-IDO-108 에러 코드 추가 안내 | 운영 문서 변경 | 별도 docs PR |

---

## 9. DoD (Definition of Done) 체크

- [x] F4.1: `HandoffServiceImpl.verify()` 가 `cryptoService.verify()` 를 호출하도록 변경
- [x] F4.1: 서명 검증 실패 시 `IDO_TICKET_SIGNATURE_INVALID` (E-IDO-108) 응답
- [x] F4.1: `SIGNATURE_INVALID` Kafka 이벤트 타입 추가 + 발행
- [x] F4.5: verify 단계 순서를 `signature → payload → consume → publish` 로 재배치
- [x] F4.5: Q-IM 장애 시 ticket ISSUED 유지 검증 (회귀 테스트)
- [x] F4.2: `consume()` 을 Redis Lua atomic CAS 로 교체
- [x] F4.2: race condition 분기 처리 (`CONSUMED`/`REVOKED`/`0`/`PARSE_ERROR`)
- [x] 회귀 테스트 16건 (Signature 3 + Ordering 3 + CAS 9 + 보정 1)
- [x] 분석 docs: `09_sprint_alpha2_handoff_integrity.md` 작성
- [x] roadmap docs: `07_risk_matrix_roadmap.md` 진행 상태 갱신
- [x] index: `00_INDEX.md` 갱신
- [ ] (별도 PR) Testcontainers 기반 Lua atomic 성 통합 테스트
- [ ] (별도 PR) 기관 onboarding 문서에 E-IDO-108 에러 안내

---

## 10. 변경 파일 요약

```
idem-hub/src/main/java/kr/go/smes/idem-hub/handoff/HandoffServiceImpl.java         (F4.1+F4.5)
idem-hub/src/main/java/kr/go/smes/idem-hub/infrastructure/TicketRepositoryImpl.java (F4.2)
idem-common/src/main/java/kr/go/smes/common/error/PlatformErrorCode.java  (E-IDO-108 추가)
idem-common/src/main/java/kr/go/smes/common/event/HandoffEvent.java        (SIGNATURE_INVALID 추가)
idem-hub/src/test/java/kr/go/smes/idem-hub/handoff/HandoffServiceImplTest.java           (회귀 9건)
idem-hub/src/test/java/kr/go/smes/idem-hub/infrastructure/TicketRepositoryImplTest.java  (신규, 회귀 9건)
docs/analysis/sso-im-readiness/09_sprint_alpha2_handoff_integrity.md           (신규)
docs/analysis/sso-im-readiness/07_risk_matrix_roadmap.md                       (진행 상태 갱신)
docs/analysis/sso-im-readiness/00_INDEX.md                                     (인덱스 갱신)
```
