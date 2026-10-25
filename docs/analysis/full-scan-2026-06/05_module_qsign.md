# 05 · Module Deep Dive — Q-Sign (신원증명 / OIDC 브로커)

> 원천: `/home/user/webapp/q-sign/` · 정본 `docs/internal/spec/03b-module-qsign.md`.
> 파일 수 53 · Postgres `qsign` 스키마 · V1~V5 · Keycloak 연동.

---

## 1. 삼각 요약

| 항목 | 값 |
|---|---|
| 포트 | 8081 |
| DB | PostgreSQL 16 (schema: `qsign`) |
| 마이그레이션 | V1~V5 (최신 `V5__add_auth_method.sql`) |
| 파일 수 | 53 |
| SoR 정본 | **인증 사실(Authentication Fact)** — "누가 언제 어떻게 인증했는가" |
| 프로토콜 | OIDC (Keycloak Brokering) + PKCE |

---

## 2. 서브패키지 (10개)

```
q-sign/src/main/java/kr/go/smes/qsign/
├── QSignApplication.java
├── api/             — REST 컨트롤러 (AuthController 등)
├── application/     — Use case (AuthServiceImpl)
├── config/          — Spring Security, Keycloak, Kafka
├── domain/          — AuthResult, OidcSession, AuthMethod
├── infrastructure/  — 외부 클라이언트 (NICE, KMC, 통합인증 등)
├── kafka/           — 이벤트 발행 (auth.events)
├── keycloak/        — Keycloak 클라이언트 (Brokering)
├── metrics/         — Prometheus 지표
├── outbox/          — Transactional Outbox
└── pkce/            — PKCE code_verifier/challenge 생성·검증
```

---

## 3. 마이그레이션 V1~V5

| V | 파일 | 요지 |
|---|---|---|
| V1 | `create_schema.sql` | auth_result, oidc_session 기본 |
| V2 | `add_audit_log.sql` | 감사 로그 |
| V3 | `add_oidc_session.sql` | OIDC 세션 확장 (state/nonce) |
| V4 | `add_processed_event.sql` | 멱등 컨슈머 원장 |
| V5 | `add_auth_method.sql` | **auth_method** 컬럼 (인증 수단 확장) |

`auth_method` (V5) 예: `PASSWORD`, `NICE_OACX`, `KMC_MOBILE`, `IPIN`, `SOCIAL_NAVER`, `SOCIAL_KAKAO`, ...

---

## 4. OIDC Brokering 아키텍처

### 4.1 흐름 요약
```
FE(SPA) ─→ IdO(P) ─→ Q-Sign ─(state, nonce, code_challenge)→ Keycloak
                                                          │
                                              (외부 IdP: NICE / KMC / 소셜 / ...)
                                                          │
Keycloak ─(code)→ Q-Sign (broker/callback) ─(code_verifier)→ token endpoint
                                                          │
Q-Sign 이 AuthResult 확립 ─(Outbox)→ Kafka(qsign.auth.events) ─→ IdO
                                                          │
                                        IdO 가 세션/토큰 발급 (P/T 역할)
```

### 4.2 정본 근거
- `07-security.md` §PKCE: `code_verifier` 최소 43자, S256 challenge.
- `06-kafka-event-catalog.md` §qsign.auth.events: `AuthEvent` 스키마.

---

## 5. 핵심 클래스

| 클래스 | 역할 |
|---|---|
| `AuthController` | `/api/v1/auth` 진입점, PKCE 생성/challenge 발급 |
| `AuthServiceImpl` | OIDC 흐름 오케스트레이션 |
| `AuthMethodResolver` | V5 auth_method 결정 로직 |
| `KeycloakClient` | Keycloak Admin/Token API |
| `PkceGenerator` | code_verifier/challenge |
| `AuthResultPublisher` | Outbox → `qsign.auth.events` |
| `OidcSessionRepository` | oidc_session 테이블 CRUD |
| `AuditLogPublisher` | `platform.audit.log` 발행 |

---

## 6. `AuthResult` 계약 (platform-common)

```java
public record AuthResult(
    String qimUserId,           // Q-IM 회원 ID (없으면 null → guest 등록 대상)
    String authMethod,          // V5 auth_method 값
    String assuranceLevel,      // AAL1/AAL2/AAL3 (V12)
    Instant authenticatedAt,
    String provider,            // Keycloak IdP alias
    Map<String, Object> extras
) {}
```

이 계약은 `AuthEvent` (Kafka payload) 의 core.

---

## 7. AAL/MFA (V12 IdO 마이그레이션과 짝)

- Q-Sign 이 인증 수단으로 결정한 `assurance_level` 이 IdO 로 전달.
- IdO 는 `V12__add_mfa_aal_schema.sql` 로 사용자별 AAL 등급 저장.
- 특정 SP 는 최소 AAL2 이상만 요구 (Handoff 검증 시 확인).

---

## 8. Outbox 상세

- 테이블: `qsign.qsign_outbox`
- 발행자: `AuthResultPublisher` (트랜잭션 내 INSERT)
- 릴레이: `outbox-relay-batch/job/qsign` (ShedLock 락 획득)
- Poll interval: 500ms (F-13 관련)
- 실패 시: `next_retry_at` 증가 (V17 IdO 와 동일 패턴)

---

## 9. 신뢰 루트 (Trust Root)

정본 `07-security.md` §신뢰 루트: **Q-Sign 이 인증한 사실만 IdO 가 신뢰한다**.
- `InternalSigVerifier` (IdO 측) — Q-Sign 발행 이벤트의 HMAC 서명 검증.
- 키 회전: `V9__add_crypto_key_registry_and_rate_limit.sql` (IdO) 의 `crypto_key_registry` 사용.

---

## 10. 관찰된 리스크

| # | 항목 | 위험 | 근거 |
|---|---|---|---|
| L1 | Keycloak 다운 시 인증 전면 불가 (SPOF) | 🔴 HIGH | Keycloak 브로커화 채택 |
| L2 | V5 auth_method 값 확장 시 SP 리버스 매핑 필요 | 🟡 MED | V5 |
| L3 | 외부 IdP 회로 차단기 (F-06 NICE, F-07 통합인증) | 🟢 LOW | Resilience4j 이미 존재 |
| L4 | 신뢰 루트 회전 절차 정본화 필요 | 🟡 MED | crypto_key_registry |
| L5 | q-authz 편입 이후 AuthEvent 에 roles[] 포함 여부 결정 | 🟡 MED | 현재는 IdO 가 별도 조회 |

---

## 11. 참조
- 소스: `/home/user/webapp/q-sign/`
- 스펙: `docs/internal/spec/03b-module-qsign.md`
- 보안: `docs/internal/spec/07-security.md`
- 이벤트: `docs/internal/spec/06-kafka-event-catalog.md`
