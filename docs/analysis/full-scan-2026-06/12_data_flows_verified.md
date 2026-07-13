# 12 · Data Flows — 검증된 6+1 데이터 흐름

> 정본 `docs/internal/dataflow/01~06` + PR #205 신규 흐름(연합 인가) 을 통합 재구성.
> 모든 흐름은 소스 코드 참조 확인 완료.

---

## 흐름 1 · 로그인 (01-login-flow.md)

### 5가지 로그인 유형
| # | 유형 | 진입 |
|---|---|---|
| 1 | PW 로그인 | `POST /api/v1/auth/login` (IdO → Q-Sign 표준) |
| 2 | 소셜 SSO (Naver/Kakao) | `POST /api/v1/auth/social/{provider}` |
| 3 | NICE OACX | `POST /api/v1/auth/nice` (V19 anyid_provider_config) |
| 4 | KMC 모바일 | `POST /api/v1/auth/kmc` |
| 5 | 통합인증 (범정부) | `POST /api/v1/auth/integration` |

### 공통 시퀀스
```
FE ──(IDO_AUTH_URL, 쿠키 fe-session-id)──▶ IdO (P)
                                              │
                                              ▼
                                        IdO ──▶ Q-Sign (OIDC via Keycloak)
                                              │
                                              ▼
                                        Keycloak ──▶ 외부 IdP
                                              │
                                              ◀── code
                                              ▼
                                    Q-Sign ──(Outbox)──▶ Kafka(qsign.auth.events)
                                              │
                                              ▼
                                        IdO Kafka Consumer 수신
                                              │
                                              ▼
                                    ★ IdO ──▶ q-authz.getEffectiveRoles()   [PR #205]
                                              │
                                              ▼
                                        IdO 세션 확립 + Cast Token(roles) 발급
                                              │
                                              ▼
                                        FE 쿠키 갱신 + 리디렉트
```

---

## 흐름 2 · 회원 전환 (02-member-conversion-flow.md)

```
FE ──▶ IdO (ConversionInitController)
         │
         ▼
       IdO ──▶ Q-IM (신규 회원 등록 요청, HMAC 서명)
         │
         ▼
       Q-IM ──▶ Outbox → Kafka(qim.user.events, kind=CREATE)
         │
         ▼
   SP 컨슈머 수신, 자체 시스템 회원 생성
         │
         ▼
   Q-IM ──▶ IdO 응답 (qim_user_id)
         │
         ▼
   IdO ConversionSessionController 완료 → 세션 확립
```

**멱등성**: `external_key` 로 중복 요청 차단.

---

## 흐름 3 · 회원 변경·탈퇴 (03-member-update-withdraw-flow.md)

```
FE ──▶ IdO
         │
         ▼
       IdO ──▶ Q-IM (변경/탈퇴 요청)
         │
         ├─ 변경: qim_member UPDATE + status_history 추가
         └─ 탈퇴: withdrawal_request INSERT + 지연 파기 (F-11 스케줄러)
         │
         ▼
       Q-IM Outbox → qim.user.events (kind=UPDATE / DELETE)
         │
         ▼
   SP 컨슈머 수신, 자체 회원 반영
```

---

## 흐름 4 · Handoff (04-handoff-flow.md) — 최신

### 4.1 발급
```
FE ──(POST /api/v1/handoff, Idempotency-Key)──▶ IdO HandoffController
         │
         ▼
       HandoffService.issue()
         ├─ resolve qim_user_id from fe-session-id
         ├─ ★ q-authz.getEffectiveRoles(qim_user_id, "PLATFORM", agencyCode)   [PR #205]
         ├─ buildPlainPayload with roles[]
         ├─ HandoffCrypto.encrypt (AES-256-GCM)
         ├─ Idempotency-Key TTL 1day (Redis)
         └─ audit → Kafka(ido.handoff.events)
         │
         ▼
       FE ──▶ SP (Handoff 토큰 첨부, 리디렉트)
```

### 4.2 검증
```
SP ──(Handoff 토큰)──▶ IdO HandoffController.verify()
         │
         ▼
       HandoffCrypto.decrypt
         │
         ▼
       validate issuer / expiry / nonce
         │
         ▼
       ✓ HandoffPayload.roles[] 를 SP 에 반환
         │
         ▼
       SP 는 roles 로 세션 확립 (자체 인가 판단)
```

---

## 흐름 5 · NICE OACX (05-nice-oacx-auth-flow.md)

NICE 는 대한민국 본인확인 3사 중 하나. OACX 는 NICE 인증센터의 OAuth-like 프로토콜.

```
FE ──▶ IdO ──▶ Q-Sign ──▶ NICE OACX (via Resilience4j CB F-06)
                              │
                              ◀── ci, name, birth, mobile
                              ▼
                   AuthResult with authMethod=NICE_OACX, AAL=AAL2
                              ▼
                   Kafka(qsign.auth.events) + Outbox
                              ▼
                        IdO 세션 확립
```

---

## 흐름 6 · Auth Providers 다중화 (06-auth-providers-flow.md)

V19 `anyid_provider_config` 테이블에 등록된 provider 를 IdO 가 라우팅:
```
anyid_provider_config
  provider_alias   VARCHAR(64) PK   -- e.g., NICE, KMC, INTEGRATION_AUTH
  provider_type    VARCHAR(32)      -- OIDC / NON_OIDC / SOCIAL
  base_url         VARCHAR(255)
  client_id        VARCHAR(128)
  client_secret    (KMS 참조)
  scopes           TEXT
  enabled          BOOLEAN
  ...
```
`AnyIdController` 가 이 설정을 조회하여 브로커 전략을 선택.

---

## 흐름 +1 · 연합 인가 (신설, PR #205)

### 부여 (SCIM PATCH)
```
관리자 시스템 ──(SCIM PATCH /scim/v2/Groups/{id})──▶ q-authz
         │
         ▼
       ScimGroupService.reconcile
         ├─ 파싱: members[value eq "user-x"] add
         ├─ AuthzService.grantRole (upsert, 감사)
         └─ 감사: authz_grant_audit INSERT
```

### 조회 (토큰/헤더 클레임)
```
IdO ──(GET /api/v1/internal/authz/users/{userId}/effective-roles)──▶ q-authz
         │
         ▼
       AuthzService.effectiveRoleCodes (RLS 필터 + 만료 필터)
         │
         ▼
       IdO 3중 주입:
         ① CastToken.claim("roles", [...])
         ② HandoffPayload.roles = [...]
         ③ ExtProxy X-Authz-Roles 헤더 재주입
```

### 만료 (배치)
```
AuthzExpiryScheduler (@Scheduled 60s)
   │
   ▼
 SELECT ... WHERE expires_at < NOW() AND revoked_at IS NULL LIMIT 500
   │
   ▼
 UPDATE revoked_at = NOW(), 감사 EXPIRE 기록
```

---

## 흐름 검증 매트릭스

| 흐름 | 정본 문서 | 소스 코드 검증 | q-authz 편입 | 관찰 |
|---|---|---|---|---|
| 1 로그인 | 01 | ✔ AuthController, QsignAuthEventConsumer | ✔ (Cast Token) | 정본 미갱신 |
| 2 전환 | 02 | ✔ ConversionInit/Session, Q-IM | 미영향 | — |
| 3 변경/탈퇴 | 03 | ✔ Q-IM withdrawal, F-11 | 미영향 | — |
| 4 Handoff | 04 | ✔ HandoffServiceImpl(340-360), roles 주입 | ✔ | ★정본 미갱신 |
| 5 NICE OACX | 05 | ✔ NiceApiClient, F-06 | 미영향 | — |
| 6 Providers | 06 | ✔ AnyIdController, V19 | 미영향 | 정본에 V19 반영 필요 |
| +1 인가 | **미정본** | ✔ q-authz + QAuthzClient | 신설 | ★신설, 정본 결여 |

---

## 관찰

1. **흐름 1과 4가 PR #205 로 확장** — 정본 문서(01/04) 는 미갱신.
2. **흐름 +1 (연합 인가) 은 정본 데이터플로 문서 없음** — `07-authz-flow.md` 신설 필요.
3. **6-필드 DLQ 표준**은 모든 흐름의 실패 경로에 적용됨 (spec 06 §DLQ).
4. **Idempotency-Key TTL 1day** 는 흐름 4 에만 적용. 흐름 2 는 `external_key` DB UNIQUE 로 대체.

---

## 참조
- 정본: `docs/internal/dataflow/01~06-*.md`
- 새 흐름 정본 요망: `docs/internal/dataflow/07-authz-flow.md`
- 관련 §03, §04, §06
