# 03-E. agency-stub 모듈 상세 명세

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09  
> **모듈 경로**: `agency-stub/`  
> **포트**: 8084  
> **DB**: PostgreSQL (`agency_stub` 스키마, V1~V2)  
> **완성도**: 90%

---

## 1. 모듈 역할

`agency-stub`은 **실제 유관기관이 IdO와 연동하는 전체 흐름을 재현**하는 PoC 전용 Spring Boot 서비스다. 외부망에 위치하며, IdO의 공개 HTTPS API(Handoff 발급/검증, 이벤트 폴링)만 사용하여 유관기관 동작을 시뮬레이션한다.

> **중요**: 실제 운영 환경에서는 각 유관기관이 자체 애플리케이션을 구축하고 IdO API를 호출한다. agency-stub은 PoC 검증 전용이다.

---

## 2. 전체 API 엔드포인트 목록

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  E2E 시뮬레이터 (/api/v1/simulator/**)         ★ v1.7.0
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST   /api/v1/simulator/run               # 3단계 E2E 전체 흐름
POST   /api/v1/simulator/ticket            # Step 1 단독 (Ticket 발급)
POST   /api/v1/simulator/verify?ticketId=  # Step 2 단독 (Ticket 검증)
GET    /api/v1/simulator/status            # DB + IdO 연결 상태 + 세션 통계
GET    /api/v1/simulator/sessions          # 최근 세션 목록
DELETE /api/v1/simulator/sessions/{id}     # 세션 강제 무효화
GET    /api/v1/simulator/events            # 이벤트 큐 조회

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  헬스 / 진단 (/api/v1/health/**)               ★ v1.7.0
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GET    /api/v1/health                      # 전체 요약 (UP/DEGRADED/DOWN)
GET    /api/v1/health/db                   # DB 연결 + 테이블별 레코드 수
GET    /api/v1/health/ido                  # IdO actuator/health + 응답시간
GET    /api/v1/health/webhook              # 최근 1h Webhook 수신 통계
GET    /api/v1/health/queue                # 이벤트 큐 미배달 현황

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  기관 진입 흐름 (/agency/entry/**)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST   /agency/entry?ticketId=             # Verify → 세션 → AGSID 쿠키 발급
GET    /agency/entry/session               # 세션 유효성 확인 + Sliding 연장
DELETE /agency/entry/session               # 로그아웃 (세션 무효화)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Webhook 수신 · 이벤트 폴링
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST   /api/v1/webhook/inbound             # IdO → 기관 Push 수신 (HMAC 검증)
GET    /api/v1/events/poll                 # 미배달 이벤트 조회 (Pull)
GET    /api/v1/events/pending-count        # 미배달 이벤트 수 확인
```

---

## 3. 3단계 E2E 흐름

```
[STEP 1] Ticket 발급
  IdoTicketClient.issue(qimUserId, authResultId, authLevel, ...)
    POST {ido}/api/v1/handoff/issue
    Headers:
      X-Agency-Code:     AGENCY_STUB_001
      X-Agency-Key:      stub-api-key-dev-001  ← 원문 (로그 미기록)
      X-Correlation-Id:  {UUID}
      Idempotency-Key:   {UUID}  ← 멱등 처리 (Redis TTL 1일)
    Resilience4j: CircuitBreaker "ido-ticket" + Retry "ido-ticket"

    ▼ IdO 수신 (HandoffAgencyKeyInterceptor)
      ① X-Agency-Code, X-Agency-Key 헤더 존재 확인
      ② SHA-256(rawKey) 계산
      ③ SELECT api_key_hash FROM ido.agency_meta WHERE active = TRUE
      ④ MessageDigest.isEqual() — 상수시간 비교 (타이밍 공격 방지)
      ⑤ HandoffServiceImpl.issue() 실행

    ◀ 응답: { ticketId, expiresAt }

[STEP 2] Ticket 검증
  IdoVerifyClient.verify(ticketId, correlationId)
    POST {ido}/api/v1/handoff/verify
    Resilience4j:
      CircuitBreaker "ido-verify": 슬라이딩 윈도우 10회, 실패율 50% → OPEN 10s
      슬로우 콜 4s 초과 → 실패 (80% 이상 시 CB OPEN)
      Retry "ido-verify": 5xx → 최대 3회 (500ms → 1000ms exponential)
                          4xx → 재시도 없음, 즉시 REJECTED

    에러 처리:
      401/403 → REJECTED(AUTH_FAILED)
      404     → REJECTED(TICKET_NOT_FOUND)
      409     → REJECTED(TICKET_ALREADY_CONSUMED)
      410     → REJECTED(TICKET_EXPIRED)
      CB OPEN → HOLD (fallback: 일시 오류 안내)

    ◀ HandoffPayload { APPROVED | REJECTED | HOLD }

[STEP 3] 세션 생성
  Session Fixation 방지:
    기존 AGSID 쿠키 → DB 무효화 → 쿠키 삭제
  AgencySessionService.createSession()
    SecureRandom 192-bit → rawAGSID (Base64URL)
    DB 저장: SHA-256(rawAGSID)  ← 원문 미저장
    agency_local_session INSERT
    agency_user findOrCreate (agencySubjectId 기준)
    session_event_log INSERT (SESSION_CREATED)
  AGSID 쿠키 발급:
    Secure=true / HttpOnly=true / SameSite=Strict
    maxAge = idle-timeout-minutes (기본 30분)
```

---

## 4. Webhook 수신 흐름 (Push)

```
IdO WebhookDispatchOutboxRelay
  → POST /api/v1/webhook/inbound
    Headers:
      X-Webhook-Signature: HMAC-SHA256(payload, signingSecret)
      X-Webhook-Timestamp: {epoch-ms}  ← ±5분 타임스탬프 검증

WebhookInboundController:
  1. 타임스탬프 검증 (±300s)
  2. HMAC-SHA256 서명 재계산 + 상수시간 비교
  3. 이벤트 타입별 라우팅
     - HANDOFF_CONSUMED → 세션 발급 프로세스
     - USER_UPDATED     → 기관 사용자 정보 갱신
     - USER_SUSPENDED   → 세션 무효화
```

---

## 5. 테스트 Web UI

agency-stub 기동 후 브라우저에서 바로 접속.

```
http://localhost:8084/
```

| 탭 | 기능 |
|----|------|
| ⚡ **E2E 전체 흐름** | 3단계 스텝별 실시간 진행 시각화 + 결과 JSON 표시 |
| 🎟 **Ticket 발급** | Step 1 단독 실행 (결과가 검증 탭에 자동 입력) |
| ✅ **Ticket 검증** | Step 2 단독 실행 (ticketId 직접 입력 가능) |
| 🩺 **헬스 / 진단** | DB, IdO 연결 상태, 응답시간 실시간 조회 |
| 🗂 **세션 목록** | 최근 세션 현황 + 강제 무효화 버튼 |
| 📬 **이벤트 큐** | 미배달 이벤트 목록 |
| 📡 **Webhook 현황** | 최근 1h 수신 통계 |

---

## 6. DB 스키마 (agency_stub 스키마)

| Flyway 버전 | 파일 | 주요 내용 |
|------------|------|---------|
| V1 | `V1__create_schema.sql` | `agency_user`, `agency_local_session`, `session_event_log` |
| V2 | `V2__add_webhook_and_api_key.sql` | `webhook_event_log`, `event_queue` 추가 + API Key 시드 삽입 |

### agency_local_session 핵심 컬럼

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `session_id` | VARCHAR(36) PK | UUID (rawAGSID SHA-256 해시) |
| `agency_code` | VARCHAR(50) | 기관 코드 |
| `agency_subject_id` | VARCHAR(200) | HMAC-SHA256 식별자 |
| `auth_level` | VARCHAR(10) | L1/L2/L3 |
| `created_at` | TIMESTAMPTZ | 세션 생성 시각 |
| `expires_at` | TIMESTAMPTZ | 세션 만료 시각 |
| `invalidated` | BOOLEAN | 무효화 여부 |

---

## 7. 기동 초기화 (AgencyDataInitializer)

```java
@Component
public class AgencyDataInitializer implements CommandLineRunner {
    // 기동 시 자동 실행:
    // 1. API Key 자동 시드 (stub-api-key-dev-001 → SHA-256 → ido.agency_meta)
    // 2. 스키마 테이블 존재 여부 확인
    // 3. 기동 완료 로그 출력
}
```

---

## 8. 미구현 항목

| ID | 항목 | 우선순위 |
|----|------|---------|
| P2-07 | Kafka 직접 구독 제거 → Webhook/폴링 방식으로 교체 | P2 |
| P2-06 | Docker 별도 네트워크 격리 (현재 내부망 동일 네트워크) | P2 |
| P3 | mTLS 기관 인증 (Nginx/Gateway 레벨 클라이언트 인증서) | P3 |

---

*다음 문서: [04-api-reference.md](04-api-reference.md)*
