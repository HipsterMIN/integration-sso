# Phase 6 — End-to-End 시나리오 통합 분석

**분석 일자**: 2026-05-22
**분석 범위**: Phase 2~5 에서 발견된 모든 결함을 실제 사용자 흐름에 대입하여 발현 양상 시뮬레이션
**기준 브랜치**: `shipster` @ `90956a7`

---

## 0. 분석 방식

본 문서는 **단위 결함 → 사용자 경험으로 번역** 한다.
각 시나리오는 다음 구조:
1. **트리거** : 어떤 사용자 행위/시스템 상태가 시나리오 시작
2. **흐름** : Q-Sign / IdO / Q-IM / 기관 4축에서 일어나는 일
3. **결함 활성** : 관여하는 F-Findings (Phase 2-5)
4. **사용자 결과** : 끝에서 사용자는 무엇을 보는가
5. **운영자 감지** : 알람/메트릭/로그로 감지되는가
6. **차단 효과 (Blast radius)** : 1명? 100명? 전체?

---

## 1. 시나리오 분류

| 분류 | 시나리오 수 | 차단 효과 |
|------|------------|----------|
| **S-Normal** (정상 흐름) | 4개 | — |
| **S-Degraded** (부분 장애) | 6개 | 1~수백명 |
| **S-Catastrophic** (대규모 영향) | 5개 | 1000명+ / 전체 |
| **S-Security** (보안 침해 시나리오) | 4개 | 잠재적 무한 |

---

## 2. S-Normal — 정상 흐름

### S-N1: 신규 사용자 최초 회원 가입 + 첫 SSO 진입

**트리거**: 사용자가 기관 A 로그인 → "PASS/카카오 등 본인인증" 선택 → OnePass 진입

**흐름**:
1. **Q-Sign** : Keycloak callback 수신 → Q-IM `/api/v1/internal/users/registerOrLookup` 호출
2. **Q-IM** : `identifierHash = SHA-256(sub)` 계산 → `qim_user` UPSERT → `qimUserId` 반환
3. **Q-Sign** : auth_result Redis 저장 → IdO 로 콜백 redirect
4. **IdO** : `/api/v1/fe-session` 생성 → feSession 쿠키 발급 → 기관 A 의 callback URL 로 redirect
5. **기관 A SDK** : `/api/v1/ido/handoff/issue` 호출 → ticketId 수신
6. **기관 A 백엔드** : `/api/v1/ido/handoff/verify(ticketId)` → HandoffPayload(state=APPROVED, agencySubjectId=DI) 수신
7. **기관 A** : 자체 회원 DB 에 `agencySubjectId` 매핑 저장 → 로그인 완료

**소요 시간 예상**: 800ms ~ 2s (Keycloak 본인인증 외)

**확인된 정상 동작**:
- V3.x (Q-IM identity 매핑 정상)
- V4.x (Handoff Issue 의 rate limit + 화이트리스트)
- V4.6 (Virtual Thread 병렬 프로비저닝)
- V4.13 (Feature Flag 점진 도입)

**현재 위험**: F2.1 (Keycloak default secret) 가 안 잡혀 있으면 S-Security-2 로 전환.

---

### S-N2: 기존 사용자 재로그인 (FE Session 유효)

**트리거**: 사용자가 기관 A 페이지 클릭 → 이전 FE Session 쿠키 (sliding TTL=30분 이내) 보유

**흐름**:
1. **IdO** : `/api/v1/fe-session/check` → feSession 유효 → sliding TTL refresh + 즉시 handoff issue 가능 응답
2. **기관 A SDK** : `/api/v1/ido/handoff/issue` 호출 → ticketId
3. **기관 A 백엔드** : `/verify` → APPROVED
4. **결과**: Q-Sign 재인증 없이 즉시 로그인

**소요 시간**: 200ms

**확인된 정상 동작**: V4.8 (sliding + absolute timeout), V4.9 (returnUrl 화이트리스트)

---

### S-N3: Cross-Agency SSO (기관 A → 기관 B)

**트리거**: 사용자가 기관 A 에서 로그인 상태 → 기관 B 링크 클릭

**흐름**:
1. **기관 B 페이지** : `POST /api/v1/agency/cast/issue?targetAgency=AGENCY_B` (브라우저, Fe-Session-Id 쿠키 자동 전송)
2. **IdO** : CAST JWT 발급 → `redirectUrl = "https://agency-b.agency.go.kr/sso-entry?onepass_sso=<JWT>"` 반환
3. **사용자** : redirect → 기관 B SSO entry
4. **기관 B 서버** : `POST /api/v1/agency/cast/verify` (CAST JWT + X-Agency-Api-Key)
5. **IdO** : CAST 검증 + Handoff 즉시 발급
6. **기관 B** : 로그인 완료

**현재 위험**:
- **F4.4** (CAST URL leak) — JWT 가 redirect URL 에 노출되어 브라우저 history/referer/server log 잔류
- **F4.19** (하드코딩 도메인 `*.agency.go.kr`) — 운영 기관 도메인 다양성 미반영
- **F4.12** (public-key endpoint placeholder) — SDK 키핀닝 불가

---

### S-N4: 사용자 로그아웃 → SLO 전 기관 전파

**트리거**: 사용자가 기관 A 또는 OnePass 에서 로그아웃 클릭

**흐름**:
1. **IdO** : `/api/v1/fe-session/logout` → feSession Redis 삭제 + 쿠키 만료
2. **IdO** : `FeSessionService.invalidateByQimUserId(qimUserId, "USER_LOGOUT")` → 모든 기관 세션 무효화
3. **WebhookDispatcher** : `enqueueForUserLogout()` → 모든 기관 webhook outbox 에 USER_LOGOUT 적재
4. **WebhookDispatchOutboxRelay** : 500ms 폴링 → 각 기관 endpoint 에 HTTPS POST
5. **기관들** : 각자의 세션 무효화

**소요 시간**: 즉시 (사용자) + 기관 webhook 도달 1~30초 (eventual consistency)

**현재 위험**:
- **F4.3** (default webhook secret) → 일부 기관이 default secret 으로 검증하면 위조 가능

---

## 3. S-Degraded — 부분 장애 / 사용자 차단

### S-D1: Q-IM 일시 5xx → 정상 회원이 GUEST 로 분류됨

**트리거**: Q-IM 인스턴스 재배포 중 30초 5xx 응답 (예: DB connection pool 일시 고갈)

**흐름**:
1. 사용자 정상 로그인 → IdO handoff issue 정상 → ticketId 수신
2. 기관 A 가 `/verify` 호출
3. IdO `verify()` → `policyEngine.buildHandoffPayload(ticket)` 진입
4. `PolicyEngineImpl.tryResolveDi()` → `qimClient.getDi()` HTTP 호출
5. Q-IM 5xx → IOException catch → **null 반환** (F4.6)
6. PolicyEngine : DI null → `HandoffPayload.state = GUEST`
7. 기관 A 가 GUEST 페이로드 수신 → "신규 회원가입 유도 페이지" 표시
8. 사용자 : 본인이 회원인데 "회원가입 해주세요" 받음

**결함 활성**:
- F4.6 (Q-IM 장애 vs 미매핑 미구분)

**사용자 결과**: 매우 혼란 — 본인 계정 있는데 가입 페이지로 유도됨. CS 문의 폭주.

**운영자 감지**: ❌ **즉시 감지 불가**
- handoff 성공률 메트릭: 100% (정상 응답)
- DI 조회 실패는 WARN 로그만 (Prometheus 카운터 없음)
- 30초 후 Q-IM 복구 → 신규 요청은 정상 → 그 30초 동안 verify 한 사용자만 영향

**Blast radius**: Q-IM 장애 30초 동안 verify 한 사용자 수 (TPS 기반 — 100 TPS 면 3000명).

**완화책 시급도**: ⚠️ **High** (Phase 4 F4.6 즉시 수정)

---

### S-D2: Verify 중 Q-IM 지연 → ticket 영구 consumed + 사용자 차단

**트리거**: Q-IM Read replica lag 으로 1회 verify 호출이 5초 지연

**흐름**:
1. 기관 A 가 `/verify` 호출
2. IdO 의 `HandoffServiceImpl.verify()` 라인 199 → `ticketRepository.consume(ticketId)` → Redis CONSUMED
3. 라인 200 → publishHandoffEvent 정상
4. 라인 202 → `policyEngine.buildHandoffPayload(ticket)` → `qimClient.getDi()` 호출
5. RestTemplate readTimeout=3초 → ReadTimeoutException → PolicyEngine 의 catch 에서 null 반환 → GUEST 페이로드 빌드 시도
6. **하지만** 다른 attribute 조회 (`collectUserAttributes`) 가 추가로 Q-IM 호출 → 또 timeout
7. 또는 단순히 `buildHandoffPayload` 실행 중 다른 예외 → verify() 메서드 전체 예외 throw
8. `@Transactional` rollback 시도 → 하지만 Redis 작업은 transaction 외부 → ticket 은 여전히 CONSUMED
9. 사용자 재시도 → `IDO_TICKET_CONSUMED` 에러

**결함 활성**:
- F4.5 (verify 비-원자적 다단계)
- F4.6 (Q-IM 장애 마스킹)

**사용자 결과**: "이미 사용된 ticket" 에러 → 재로그인 시도 → 새 ticket 발급 받으나 Q-IM 여전히 느림 → 또 차단 → 무한 loop. CS 문의.

**운영자 감지**: ⚠️ **부분 감지**
- `ido_handoff_consumed_total` 와 `ido_handoff_payload_build_success_total` 의 mismatch 가 있으면 감지 (현재 메트릭 존재 여부 미확인).
- Q-IM client latency p99 알람으로 간접 감지 가능.

**Blast radius**: Q-IM 지연 동안 verify 한 사용자 전체.

**완화책 시급도**: 🔴 **Critical** (Phase 4 F4.5)

---

### S-D3: Vault 토큰 만료 시점 동시 다발 호출 → 401 burst

**트리거**: AppRole 토큰 TTL=1h, 만료 시점에 정확히 100 TPS 의 verify 호출이 진입

**흐름**:
1. 모든 호출이 동시에 `VaultKmsClient.callTransit()` 진입 → 같은 만료된 clientToken 사용
2. 첫 호출이 401 받음 → `TokenExpiredException` → `acquireToken()` 호출 시작
3. 그 사이 다른 99개 호출도 401 받음 → 각자 `acquireToken()` 동시 호출 → **thundering herd**
4. Vault `/v1/auth/approle/login` 에 100개 동시 요청 → Vault rate limit 또는 5xx
5. 일부 토큰 획득 성공, 일부 실패 → 실패한 호출의 사용자는 즉시 차단
6. 토큰 획득한 호출도 그 사이 다른 호출이 `clientToken` volatile 변수를 덮어쓰므로 race condition

**결함 활성**:
- F5.3 (Vault 토큰 자동 갱신 부재)

**사용자 결과**: 매시 정시 (또는 토큰 TTL 만료 시점) 마다 수십~수백명이 KMS 에러로 차단.

**운영자 감지**: ✅ **감지 가능**
- KmsHealthMetrics (PR-B1) Gauge 변화.
- VaultKmsHealthIndicator 의 actuator 알람.

**Blast radius**: 토큰 만료 시점부터 모든 인스턴스가 토큰 재획득 완료할 때까지 (~수십초).

**완화책 시급도**: 🔴 **Critical** (Phase 5 F5.3)

---

### S-D4: Webhook outbox FAILED 적체 → 기관이 사용자 탈퇴 통보 못 받음

**트리거**: 기관 A 의 webhook endpoint 가 3회 retry 후 FAILED 처리

**흐름**:
1. 사용자가 OnePass 에서 탈퇴
2. WebhookDispatcher → outbox INSERT (status=PENDING)
3. OutboxRelay → HTTPS POST 기관 A endpoint → 5xx
4. retry 1 (2초 후) → 5xx, retry 2 (4초) → 5xx, retry 3 (8초) → 5xx
5. status=FAILED 마킹 + audit log FAILURE
6. **이후 후속 처리 없음** (F4.14)
7. Q-IM 의 사용자는 WITHDRAWN, 기관 A 의 사용자는 ACTIVE → 정합성 깨짐
8. 사용자가 기관 A 에 (이미 탈퇴한 OnePass 계정으로) 다시 진입 시도 → 가능하면 안 되는데 가능

**결함 활성**:
- F4.14 (FAILED 후 DLQ 부재)

**사용자 결과**: 사용자 본인은 탈퇴했다고 인식하나, 기관 A 가 여전히 회원으로 분류 → 마케팅 메시지 발송 등 GDPR 위반 가능.

**운영자 감지**: ⚠️ **부분 감지**
- PR-B2 의 8개 알람 중 webhook 발송 성공률 알람 있음 (재확인 필요).
- 개별 FAILED 레코드 추적은 manual SQL 필요.

**Blast radius**: 탈퇴 통보가 기관 A 에 도달 못한 사용자 전체.

**완화책 시급도**: ⚠️ **High** (F4.14)

---

### S-D5: Redis 일시 장애 → ticket consume race + 이중 verify

**트리거**: Redis cluster 장애 복구 중 1초의 split-brain 상황 또는 단순한 동시 verify 호출 (네트워크 retry)

**흐름**:
1. 기관 A 의 HTTP 클라이언트 retry 정책으로 `/verify` 동시 2회 호출 (idempotency key 미사용)
2. 두 verify 모두 `findById()` → state=ISSUED 읽음
3. 두 verify 모두 상태 검증 통과
4. 두 verify 모두 `consume()` 호출 → 비-atomic GET/SET → 둘 다 CONSUMED 로 SET (마지막 SET 이 이김, 하지만 둘 다 "consume 성공" 판단)
5. 두 verify 모두 `buildHandoffPayload` 호출 → **동일 페이로드 2회 발급**
6. 기관 A : 동일 사용자에 대해 두 번의 로그인 세션 생성 (또는 중복 회원가입 처리)

**결함 활성**:
- F4.2 (consume non-atomic CAS)

**사용자 결과**: 사용자 본인은 1회 로그인 시도했는데 기관 A 에 2개 세션 — 추적 시스템에 동일 사용자 중복 로그인 기록.

**운영자 감지**: ❌ **즉시 감지 매우 어려움**
- 두 verify 모두 OUTCOME_SUCCESS 감사 로그.
- 사후 SIEM 분석으로 동일 ticketId 의 verify 2회 발견 가능하나 실시간 알람 부재.

**Blast radius**: 동시 verify 시도하는 사용자 (드물지만 0 아님).

**완화책 시급도**: 🔴 **Critical** (F4.2 Lua atomic CAS)

---

### S-D6: CI 암호화 키 default 미설정 → 등록 vs 조회 키 불일치

**트리거**: Q-IM 운영 환경 변수 `CI_AES_KEY` 미주입 → CiCryptoServiceImpl 라인 40 default `AAA...=` 활성

**흐름**:
1. 신규 사용자 등록 시 CI 를 default 키로 암호화 후 DB 저장
2. 운영자가 누락을 인지하고 환경변수 추가 → 재배포
3. 기존 사용자 (default 키로 암호화된 CI) 조회 시 → 새 키로 복호화 시도 → AES-GCM 인증 태그 실패 → 사용자 조회 불가
4. 신규 등록은 새 키로 정상

**결함 활성**:
- F3.3 (CI AES key default `AAA...=`)

**사용자 결과**: 기존 사용자 전체 차단. 신규 가입은 가능하나 기존 계정 접근 불가.

**운영자 감지**: ⚠️ **부분 감지**
- decrypt 실패 카운터가 있다면 (확인 필요) 즉시 감지.
- 없다면 CS 문의 폭주로만 감지.

**Blast radius**: default 키로 암호화된 모든 기존 사용자.

**완화책 시급도**: 🔴 **Critical** (Phase 3 F3.3 + F5.1 fail-safe 강화)

---

## 4. S-Catastrophic — 대규모 영향

### S-C1: Q-IM `findByIdentifierHash` Optional 모호 + 두 종류 hash 인코딩 = 회원 조회 영구 실패

**트리거**: 신규 사용자가 PASS CI 로 가입 후 다른 회원 조회 API 통해 자신을 검색

**흐름**:
1. **등록 경로** (UserController.registerOrLookup) — `computeSha256Hex(sub)` → **hex** identifier_hash 저장
2. **조회 경로** (MemberLookupController.sha256(ci)) — Base64URL withoutPadding → **Base64URL** identifierHash 로 조회
3. 두 인코딩이 다르므로 `findByIdentifierHash` 영구 empty 반환
4. 시스템은 "회원 없음" 으로 판단 → MemberLookupController 가 404 또는 신규 처리 흐름

**결함 활성**:
- F3.1 (SHA-256 hex vs Base64URL 불일치)
- F3.2 (`findByIdentifierHash` Optional 의미 모호)

**사용자 결과**: 회원 가입은 됐으나 어떤 회원 조회 API 도 본인을 못 찾음.

**Blast radius**: **모든 사용자** (단, V4 마이그레이션 이전 사용자는 다른 영향).

**완화책 시급도**: 🔴 **Critical** — Phase 3 F3.1 의 즉시 수정 필요.

---

### S-C2: Vault 1시간 down → 모든 SSO 차단

**트리거**: Vault cluster unsealed 상태 유지 작업 (HA 미구성 환경에서 단일 인스턴스 재시작)

**흐름**:
1. KmsClient.decrypt() 모든 호출이 KmsDecryptException
2. HandoffCryptoService.encrypt() / decrypt() 실패 (issue/verify 양쪽)
3. 모든 handoff issue 가 500 에러 → 사용자 로그인 차단
4. 신규 로그인 0, 진행 중 verify 도 차단
5. Q-IM 의 CI 암호화도 같은 KMS 의존 시 신규 가입도 차단

**결함 활성**:
- F5.1 (KMS off fail-safe)
- F5.2 (Vault 토큰 미획득 시 startup 차단 부재)
- F5.3 (자동 갱신 부재)

**사용자 결과**: 전체 SSO 서비스 중단.

**Blast radius**: **전 사용자** (Vault 장애 지속 시간 동안).

**완화책 시급도**: 🔴 **Critical** — Vault HA + 토큰 백그라운드 갱신 + KMS health gating Liveness.

---

### S-C3: Audit log Kafka 24h down → 백로그 적체 + 신규 audit 손실

S-D 의 시나리오 O 와 동일. 다만 영향이 외부 SIEM/규제 대응 측면.

**Blast radius**: 외부 컴플라이언스 감사 시점 보안 인시던트.

**완화책 시급도**: ⚠️ **High** — F5.8 burst 모드.

---

### S-C4: PolicyEngine GUEST 분기 + Q-IM 일시 장애 = 운영 시간 동안 GUEST 트래픽 폭증

S-D1 의 확대판. Q-IM 장애가 30초 가 아니라 30분 지속 시:
- 30분 × 100 TPS = **18만명** 이 GUEST 처리되어 기관 측에서 "신규 가입" 화면 마주함.
- CS 폭주, brand damage, 다음 정상화 후에도 사용자 신뢰 회복 어려움.

**완화책 시급도**: 🔴 **Critical** — F4.6 + Q-IM SLA 강화 (서킷 브레이커, 로컬 캐시).

---

### S-C5: WebhookDispatcher default secret + 기관 endpoint 공개 = 대량 위조 webhook 사고

**트리거**: 공격자가 GitHub 에서 default secret `poc-webhook-secret-change-in-production` 확인 + 운영 매뉴얼에서 기관 endpoint URL 확인

**흐름**:
1. 공격자가 자신의 서버에서 위조 USER_WITHDRAWN webhook payload 생성
2. `HMAC-SHA256(payload, default-secret)` 으로 X-Webhook-Signature 헤더 생성
3. 기관 endpoint 에 직접 HTTPS POST
4. 기관 측은 default secret 으로 검증 → 통과 → 사용자 강제 탈퇴 처리
5. 한 번에 수천명에 대한 위조 탈퇴 가능

**결함 활성**:
- F4.3 (default webhook secret 평문 하드코딩)

**사용자 결과**: 강제 탈퇴 → 데이터 손실 + 컴플라이언스 위반.

**Blast radius**: 기관별 default secret 으로 검증하는 모든 사용자.

**완화책 시급도**: 🔴 **Critical** — F4.3 default secret 제거 + KMS 암호화.

---

## 5. S-Security — 보안 침해 시나리오

### S-S1: Redis 침해 + signature verify dead code = 신분 swap

상세는 Phase 4 의 Scenario H 참조.

**트리거**: Redis managed service credential leak (예: 환경변수 git commit 사고) 또는 self-hosted Redis 의 misconfiguration.

**흐름**:
1. 공격자가 `handoff:ticket:T123` 키의 JSON 을 변조 (qimUserId swap)
2. 기관 X 가 `/verify(T123)` 호출
3. F4.1 (verify dead code) — signature 검증 미실행
4. PolicyEngine 이 변조된 qimUserId 로 페이로드 발급
5. 공격자가 victim 으로 로그인

**결함 활성**:
- F4.1 (verify dead code)
- F4.2 (consume race — 부가)

**Blast radius**: Redis 침해 지속 동안 임의 사용자.

**완화책 시급도**: 🔴 **Critical** — F4.1 즉시 수정 + Redis ACL/네트워크 격리.

---

### S-S2: K8s Secret leak → IDEM_HUB_HANDOFF_AES_KEY 평문 환경변수 입수 → 과거 ticket 전체 복호화

**트리거**: K8s RBAC 사고 (kubectl get secret 권한 leak) 또는 etcd 백업 파일 leak.

**흐름**:
1. 공격자가 base64 디코딩으로 환경변수 평문 입수
2. KMS Off 모드인 경우 (F5.1) — 이 환경변수가 실제 AES 키
3. 공격자가 과거 ticket 의 encryptedPayload (Redis snapshot, DB backup, 로그) 입수
4. AES-256-GCM 복호화 → payload 평문 (qimUserId, agencyCode 등)

**결함 활성**:
- F5.1 (KMS off fail-safe)
- F5.12 (환경변수 평문 키)

**완화책 시급도**: 🔴 **Critical** — KMS Vault 의무화 + K8s Secret 대신 Vault Agent.

---

### S-S3: `/api/v1/fe-session` 직접 호출 → 임의 사용자 가장

상세는 Phase 4 Scenario L 참조.

**트리거**: IdO 의 `/api/v1/fe-session` 경로가 외부 Ingress 노출 (Ingress 규칙 누락) 또는 내부 침해된 서비스가 호출.

**흐름**:
1. `POST /api/v1/fe-session { qimUserId: "victim-uuid" }` (인증 없이)
2. 응답으로 feSessionId 쿠키 발급받음
3. 그 쿠키로 모든 BFF 경로 호출 가능 (Cross-Agency SSO 포함)

**결함 활성**:
- F4.8 (FeSessionController 인증 부재)

**완화책 시급도**: 🔴 **Critical** — InternalApiKeyInterceptor 적용 + NetworkPolicy.

---

### S-S4: CAST JWT URL leak → 기관 B 가짜 페이지 또는 referer leak

상세는 Phase 4 F4.4 참조.

**트리거**: 사용자가 cross-agency SSO 사용 후 기관 B 페이지 내 외부 리소스 (이미지, 분석 스크립트, 광고) 가 referer 헤더로 CAST JWT 노출.

**흐름**:
1. CAST JWT 가 URL 쿼리스트링 (`?onepass_sso=<JWT>`) 에 포함
2. 기관 B 페이지 로드 시 모든 외부 리소스 요청에 Referer 헤더로 URL 전체 포함
3. 공격자 (외부 리소스 서버 운영자 또는 광고 네트워크) 가 JWT 수집
4. JWT TTL 이내에 (예: 60초) 자신의 IP 로 `/api/v1/agency/cast/verify` 호출 → 성공 → 다른 기관 로그인 페이로드 입수

**결함 활성**:
- F4.4 (CAST URL leak)

**완화책 시급도**: 🔴 **Critical** — POST body 또는 짧은 opaque ID 로 전환.

---

## 6. 시나리오 → 결함 매트릭스

| 시나리오 | F2.1 | F2.x | F3.1 | F3.2 | F3.3 | F3.4 | F4.1 | F4.2 | F4.3 | F4.4 | F4.5 | F4.6 | F4.8 | F5.1 | F5.2 | F5.3 | F5.4 |
|---------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| S-D1    |      |      |      |      |      |      |      |      |      |      |      | ✗    |      |      |      |      |      |
| S-D2    |      |      |      |      |      |      |      |      |      |      | ✗    | ✗    |      |      |      |      |      |
| S-D3    |      |      |      |      |      |      |      |      |      |      |      |      |      |      |      | ✗    |      |
| S-D4    |      |      |      |      |      |      |      |      |      |      |      |      |      |      |      |      |      |
| S-D5    |      |      |      |      |      |      |      | ✗    |      |      |      |      |      |      |      |      |      |
| S-D6    |      |      |      |      | ✗    |      |      |      |      |      |      |      |      | ✗    |      |      |      |
| S-C1    |      |      | ✗    | ✗    |      |      |      |      |      |      |      |      |      |      |      |      |      |
| S-C2    |      |      |      |      |      |      |      |      |      |      |      |      |      | ✗    | ✗    | ✗    |      |
| S-C3    |      |      |      |      |      |      |      |      |      |      |      |      |      |      |      |      |      |
| S-C4    |      |      |      |      |      |      |      |      |      |      |      | ✗    |      |      |      |      |      |
| S-C5    |      |      |      |      |      |      |      |      | ✗    |      |      |      |      |      |      |      |      |
| S-S1    |      |      |      |      |      |      | ✗    | ✗    |      |      |      |      |      |      |      |      |      |
| S-S2    |      |      |      |      |      |      |      |      |      |      |      |      |      | ✗    |      |      |      |
| S-S3    |      |      |      |      |      |      |      |      |      |      |      |      | ✗    |      |      |      |      |
| S-S4    |      |      |      |      |      |      |      |      |      | ✗    |      |      |      |      |      |      |      |

**관찰**:
- F4.6 (Q-IM 장애 마스킹) — 가장 많은 시나리오에 등장 (S-D1, S-D2, S-C4)
- F4.1 + F4.2 — 보안 침해 시나리오의 핵심
- F5.1 — fail-safe 부재는 단독으로도 catastrophic

---

## 7. 운영 진입 차단 시나리오 — 즉시 발현 가능성 추정

| 시나리오 | 운영 진입 직후 즉시 발현 가능성 | 조건 |
|---------|-----------------------------|------|
| S-D1 (Q-IM 일시 5xx) | **매우 높음** | Q-IM 재배포만으로 발현 |
| S-D2 (verify 중 Q-IM 지연) | **높음** | TPS 증가 + Q-IM 평균 응답 200ms 이상 |
| S-D5 (consume race) | **중간** | 기관 SDK 의 retry 정책 |
| S-D6 (CI 키 default) | **매우 높음** | 환경변수 누락 1개 |
| S-C1 (회원 조회 실패) | **확실** | 첫 사용자 가입 즉시 |
| S-C2 (Vault down) | **중간** | Vault HA 구성 여부 |
| S-S1 (Redis 침해 + dead code) | **낮음** (전제 조건 다수) | Redis 침해 후에만 |
| S-S3 (FeSession 직접 호출) | **확인 필요** | Ingress 규칙 |

---

## 8. 종합 평가

### 운영 진입 시 즉시 발현 가능성이 높은 시나리오: 5개
- S-C1 (회원 조회 영구 실패) — **첫 사용자부터 발현**
- S-D6 (CI 키 default) — 환경변수 1개 누락
- S-D1 (Q-IM 일시 장애) — Q-IM 재배포만으로
- S-D2 (verify 비-원자) — TPS 증가만으로
- S-C5 (default webhook secret 위조) — 공격자가 즉시 활용 가능

### 결정적 결함 동시 발현 시:
- F3.1 (회원 조회) × F3.3 (CI 키) × F4.5 (verify 비-원자) × F4.6 (Q-IM 마스킹) → **운영 진입 후 24시간 내 multi-incident 거의 확실**.

### 결론: ❌ 운영 진입 부적합
- 즉시 발현 가능 시나리오 5개 + 보안 침해 시나리오 4개 = 총 9개의 차단 사유.
- Phase 7 에서 우선순위 + roadmap 으로 정리.

---

**Phase 6 분석 종료**.
**다음 단계**: Phase 7 (`07_risk_matrix_roadmap.md`) — 모든 결함을 risk matrix 로 정리 + 운영 진입 전 필수 작업 roadmap.
