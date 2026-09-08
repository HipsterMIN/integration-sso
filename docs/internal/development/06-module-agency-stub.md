# 06. agency-stub 모듈 구현 상태 (v1.9.0)

> **명칭 안내 (2026-09-07)** — 이 문서의 `onepass.agent.*` 설정 키, `onepass-agent.properties`, `ONEPASS_*` 환경변수, `OnePass-*` 헤더, `OnePassAgent*` 클래스명은 개명 4단계(Java 패키지·런타임 식별자) 전까지 **구명을 그대로 사용**한다. 모듈·이미지·파일 이름만 Idem 신명이다. 대응표: [docs/naming.md](../../naming.md) §3.

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09  
> **모듈 경로**: `idem-tenant-sample/`  
> **포트**: 8084  
> **목적**: PoC 데모 및 유관기관 시뮬레이터

---

## 1. 모듈 개요

agency-stub은 **유관기관 정보시스템 시뮬레이터**로, 실제 유관기관이 구현해야 할 OnePass 연동 흐름을 PoC 수준에서 시연한다.

> ⚠️ **중요**: agency-stub은 PoC 시뮬레이터이며, 실 운영 유관기관 연동 코드가 아니다.  
> 내부 Kafka 직접 구독은 PoC 편의 코드 — 운영에서는 Webhook/Polling으로 대체해야 한다.  
> (참조: `docs/agency-external-arch-supplement.md`)

---

## 2. 패키지 구조

```
idem-tenant-sample/src/main/java/io/github/hipstermin/idem/tenant/
├── AgencyStubApplication.java
├── api/                           # 기관 공개 API
│   ├── AgencyEntryController.java     # 기관 진입점 (ticketId 수신 → Verify 호출)
│   ├── AgencyEventPollingController.java  # 이벤트 폴링 API (P1)
│   └── AgencyHealthController.java    # 헬스체크
├── client/                        # IdO 클라이언트
│   ├── IdoTicketClient.java           # Handoff Ticket 조회
│   └── IdoVerifyClient.java           # Handoff Verify API 호출
├── config/
│   ├── AgencyApiKeyInterceptor.java   # X-Agency-Key 헤더 검증
│   ├── AgencyWebConfig.java
│   └── KafkaConsumerConfig.java
├── init/
│   └── AgencyDataInitializer.java     # 초기 데이터 설정
├── kafka/
│   └── HandoffEventConsumer.java      # ⚠️ PoC 전용 — 내부 Kafka 직접 구독
├── session/
│   ├── AgencyLocalSession.java        # 기관 로컬 세션(AGSID)
│   └── AgencySessionService.java      # 세션 발급/관리
├── simulator/
│   └── AgencySimulatorController.java # 인증 흐름 시뮬레이션
└── webhook/
    └── WebhookInboundController.java  # Webhook 수신 처리
```

---

## 3. 핵심 기능별 구현 상태

### 3.1 Handoff Verify 흐름

**상태**: ✅ 완전 구현 (v1.6.0)

```java
// AgencyEntryController — 기관 진입점
// 1. ticketId 수신 (redirect parameter)
// 2. IdoVerifyClient.verify() → POST /api/v1/handoff/verify 실제 호출
// 3. HandoffPayload 수신
// 4. AgencySessionService.issue() → AGSID(기관 로컬 세션) 발급
```

```java
// IdoVerifyClient — RestTemplate 실제 구현 (v1.6.0 완성)
HandoffPayload verify(String ticketId, String agencyCode, String apiKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Agency-Code", agencyCode);
    headers.set("X-Agency-Key", apiKey);
    // POST to IdO /api/v1/handoff/verify
}
```

### 3.2 Webhook 수신 처리

**상태**: ✅ 완전 구현 (v1.6.0)

```java
// WebhookInboundController
// POST /agency/webhook/onepass
// - HMAC-SHA256 서명 검증 (X-OnePass-Signature 헤더)
// - 이벤트 타입별 처리 (TICKET_REVOKED, USER_ADVISORY)
// - 기관 로컬 세션 무효화
```

### 3.3 기관 API Key 인터셉터

**상태**: ✅ 완전 구현 (v1.6.0)

```java
// AgencyApiKeyInterceptor
// X-Agency-Key 헤더 검증 (PBKDF2 해시 비교)
```

### 3.4 이벤트 폴링 API

**상태**: ✅ 구조 완성 (v1.7.0)

```
GET /agency/events?since={timestamp}
- IdO GET /api/v1/agency/events 호출
- TICKET_REVOKED, USER_ADVISORY 이벤트 반환
```

### 3.5 Kafka 직접 구독 (PoC 전용)

**상태**: ⚠️ PoC 편의 코드

```java
/**
 * ⚠️ PoC 전용 — 실 운영에서는 사용 불가
 * 운영 대체 방안: Webhook(POST) 또는 폴링(GET)
 */
@KafkaListener(topics = "ido.handoff.events")
public void consumeHandoff(HandoffEvent event) {
    // REVOKED 수신 → 기관 세션 무효화 (PoC 시뮬레이션)
}
```

---

## 4. 구현 완성도

| 기능 | 상태 | 비고 |
|------|------|------|
| Handoff Verify API 실제 호출 | ✅ | v1.6.0 완성 |
| 기관 로컬 세션(AGSID) 관리 | ✅ | In-Memory |
| Webhook 수신 처리 | ✅ | HMAC 서명 검증 포함 |
| 기관 API Key 검증 | ✅ | X-Agency-Key 헤더 |
| 이벤트 폴링 API | ✅ | 구조 완성 |
| Kafka 내부 구독 | ⚠️ | PoC 전용 (운영 불가) |
| agency-stub Docker 격리 | ❌ | idem-net 포함 (P2) |
| mTLS 기관 인증 | ❌ | P3 (Nginx/Gateway 레벨) |

---

## 5. 운영 전환 시 변경 사항

| 항목 | 현재 PoC | 운영 목표 | 우선순위 |
|------|---------|----------|---------|
| Kafka 직접 구독 | ✅ (내부 Kafka) | ❌ 제거 → Webhook/폴링 | P2 |
| Docker 네트워크 | idem-net 포함 | 별도 네트워크 분리 | P2 |
| returnUrl 화이트리스트 | localhost:8084 포함 | 실제 기관 도메인만 | P2 |
| 인증 | API Key | mTLS (선택) | P3 |

---

## 6. API 엔드포인트

| Method | 경로 | 설명 |
|--------|------|------|
| GET | `/agency/entry` | 기관 서비스 진입점 (ticketId 수신) |
| GET | `/agency/events` | 이벤트 폴링 |
| GET | `/agency/health` | 헬스체크 |
| POST | `/agency/webhook/onepass` | OnePass Webhook 수신 |
| GET | `/agency/simulator` | 인증 시뮬레이션 UI |

---

*다음 문서: [07-module-frontend.md](07-module-frontend.md)*
