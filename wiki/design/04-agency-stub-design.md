# Agency-Stub 서비스 상세 설계서

| 항목 | 내용 |
|------|------|
| **서비스명** | Agency-Stub (기관 시스템 PoC 스텁) |
| **포트** | 8090 |
| **역할** | 실제 기관 시스템을 모의(Mock)하는 PoC 스텁 서버 |
| **기술 스택** | Spring Boot 3.2, JDK 21, PostgreSQL |
| **최종 갱신** | 2026-05-15 (v0.8.8) |

---

## 1. 개요

Agency-Stub은 실제 68개 기관 시스템을 대체하는 **PoC(Proof of Concept) 테스트 서버**다. OnePass 플랫폼이 기관과 연동하는 모든 시나리오를 시뮬레이션한다.

```
OnePass Platform                Agency-Stub (:8090)
─────────────────               ───────────────────
IdO → HTTPS POST    ────────►  /api/agency/provision
IdO → Webhook       ────────►  /webhook/inbound
기관 → Handoff      ────────►  /api/entry/handoff
                    ◄────────  /api/agency/events (폴링)
                    ◄────────  /api/agency/member (조회)
```

---

## 2. 패키지 구조

```
io.github.hipstermin.idem.tenant
├── api/                        # REST API 컨트롤러
│   ├── AgencyEntryController.java      ← Handoff 수신 엔트리
│   ├── AgencyEventPollingController.java   ← IdO 이벤트 폴링
│   ├── AgencyHealthController.java     ← 헬스체크
│   └── AgencyMemberLookupController.java   ← 회원 조회 (전환용)
├── client/                     # IdO 클라이언트
│   ├── IdoTicketClient.java    ← Handoff 티켓 검증 (IdO 호출)
│   └── IdoVerifyClient.java    ← CAST Token 검증
├── config/                     # 설정
│   ├── AgencyApiKeyInterceptor.java    ← API Key 인터셉터
│   └── KafkaConsumerConfig.java        ← Handoff 이벤트 소비
├── kafka/                      # Kafka 소비
│   └── HandoffEventConsumer.java       ← ido.handoff.events
├── mock/                       # Mock 컨트롤러 (시나리오별)
│   ├── MockApacheGateController.java   ← Apache SSO 게이트 모의
│   ├── MockBridgeController.java       ← Bridge 방식 모의
│   └── MockSsoSessionController.java   ← SSO 세션 모의
├── session/                    # 기관 로컬 세션
│   ├── AgencyLocalSession.java
│   └── AgencySessionService.java
├── simulator/                  # 시나리오 시뮬레이터
│   └── AgencySimulatorController.java  ← E2E 테스트 트리거
├── webhook/                    # Webhook 수신
│   └── WebhookInboundController.java
└── init/
    └── AgencyDataInitializer.java      ← 초기 데이터 시드
```

---

## 3. 핵심 컴포넌트 상세

### 3.1 AgencyEntryController — Handoff 수신

기관 입장에서 OnePass로부터 사용자를 수신하는 엔드포인트.

```java
// POST /api/entry/handoff
// 1. Handoff Token 수신 (CAST Token or 티켓 코드)
// 2. IdoTicketClient → IdO에 티켓 검증 요청
// 3. 검증 성공 → AgencyLocalSession 생성
// 4. 기관 내부 서비스로 리다이렉트
```

**지원 Handoff 방식**:
| 방식 | 설명 | 엔드포인트 |
|------|------|-----------|
| `DIRECT` | 직접 리다이렉트 | `/api/entry/direct` |
| `CAST` | CAST Token 기반 | `/api/entry/cast` |
| `APACHE_GATE` | Apache SSO 게이트 | `/mock/apache-gate/auth` |
| `BRIDGE` | 브릿지 서버 경유 | `/mock/bridge/handoff` |

### 3.2 AgencyEventPollingController — IdO 이벤트 폴링

기관이 IdO로부터 회원 이벤트를 주기적으로 조회하는 방식 (Webhook 대안).

```java
// GET /api/agency/events?since={lastEventId}&limit=50
// → IdO AgencyEventController 호출
// → PERSONAL_MEMBER_REGISTERED, MEMBER_WITHDRAWN 등 수신
// → 기관 자체 DB 동기화
```

### 3.3 WebhookInboundController — Webhook 수신

```java
// POST /webhook/inbound
// 수신: IdO WebhookDispatchOutboxRelay가 발송한 HMAC-SHA256 서명 Webhook
// 검증: X-Webhook-Signature 헤더 검증
// 처리: 이벤트 타입별 분기 처리
```

### 3.4 AgencyMemberLookupController — 전환용 회원 조회

기관이 보유한 회원 목록을 Q-IM 전환 세션에 제공.

```java
// GET /api/member/lookup?ci={ciHash}
// → 기관 DB에서 해당 CI의 회원 정보 조회
// → CandidateMember 반환 (기관코드, 회원ID, 이름)
```

### 3.5 AgencySimulatorController — 시나리오 시뮬레이터

E2E 통합 테스트를 위한 시나리오 트리거.

```java
// POST /api/simulator/run
// Body: { "scenario": "MEMBER_REGISTER_AND_PROVISION" }
// → 가입 → Kafka → IdO 소비 → 프로비저닝 → Agency 수신 전 과정 시뮬레이션
```

---

## 4. DB 스키마 (Agency-Stub)

| 테이블 | 용도 |
|--------|------|
| `agency.agency_member` | 기관 자체 회원 정보 (시뮬레이션용) |
| `agency.agency_session` | 기관 로컬 세션 |
| `agency.webhook_log` | 수신된 Webhook 로그 |
| `agency.api_key` | 기관 API Key (IdO에서 발급) |

### Flyway 버전 현황 (Agency-Stub)

| 버전 | 내용 |
|------|------|
| V1 | 기본 스키마 |
| V2 | Webhook + API Key 테이블 |

---

## 5. 지원 시나리오

| 시나리오 | 설명 |
|---------|------|
| 신규 가입 수신 | `PERSONAL_MEMBER_REGISTERED` 이벤트 폴링/Webhook 수신 |
| 기업 가입 수신 | `BIZ_MEMBER_REGISTERED` 이벤트 수신 |
| 전환 회원 조회 | CI 기반 기관 계정 조회 → 전환 후보 반환 |
| Handoff 수신 | CAST Token or 티켓 기반 SSO 진입 처리 |
| 탈퇴 처리 | `MEMBER_WITHDRAWN` 이벤트 → 기관 계정 비활성화 |
| Webhook 수신 | HMAC 서명 검증 → 이벤트 처리 |

---

## 6. 개발·테스트 가이드

```bash
# Agency-Stub 단독 기동
cd agency-stub
./gradlew bootRun

# Simulator 트리거 (전체 E2E 흐름 테스트)
curl -X POST http://localhost:8090/api/simulator/run \
     -H "Content-Type: application/json" \
     -d '{"scenario": "MEMBER_REGISTER_FULL_FLOW"}'
```

> **주의**: Agency-Stub은 PoC 전용입니다. 실제 기관 연동 시에는 각 기관의 보안 요구사항에 맞게 별도 설계가 필요합니다.
