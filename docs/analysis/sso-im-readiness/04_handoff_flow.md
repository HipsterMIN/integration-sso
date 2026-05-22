# Phase 4 — IdO Handoff Flow 심층 분석

**분석 일자**: 2026-05-22
**분석 범위**: IdO (Identity Orchestrator) 모듈 — Handoff Issue/Verify/Revoke, Webhook Dispatch, Cross-Agency SSO(CAST), Gateway, Provisioning, FE Session
**기준 브랜치**: `shipster` @ `90956a7`
**분석 원칙**: 거짓 안심 검증 — "이 기능은 구현되어 있다"가 아니라 **"이 코드가 실패 케이스를 처리하는가"**

---

## 0. 분석 대상 코드 (실측 기반)

| # | 파일 | 라인 | 핵심 책임 |
|---|------|------|-----------|
| 1 | `ido/.../api/HandoffController.java` | 201 | Issue / Verify / Revoke 엔드포인트, Fe-Session-Id 쿠키 추출 |
| 2 | `ido/.../handoff/HandoffServiceImpl.java` | 318 | Issue/Verify/Revoke 트랜잭션 본체 |
| 3 | `ido/.../handoff/crypto/HandoffCryptoService.java` | 262 | encrypt/sign/verify (AES-GCM + HMAC) |
| 4 | `ido/.../infrastructure/TicketRepositoryImpl.java` | 219 | Redis 기반 ticket 저장소 |
| 5 | `ido/.../policy/PolicyEngineImpl.java` | 244 | buildHandoffPayload, GUEST 분기 |
| 6 | `ido/.../gateway/HmacSignatureFilter.java` | 257 | F-26 인바운드 HMAC 검증 필터 |
| 7 | `ido/.../webhook/WebhookDispatcherService.java` | 564 | Webhook Outbox 적재 + HMAC 서명 |
| 8 | `ido/.../webhook/WebhookDispatchOutboxRelay.java` | 510 | HTTPS POST 폴링 발송, 지수 백오프 |
| 9 | `ido/.../gateway/AgencyGatewayServiceImpl.java` | 334 | 인/아웃바운드 게이트웨이, HMAC 서명 |
| 10 | `ido/.../sso/CrossAgencySsoController.java` | 279 | CAST 토큰 발급/검증, 기관간 SSO |
| 11 | `ido/.../fe/api/FeSessionController.java` | 226 | FE 세션 CRUD (BFF) |
| 12 | `ido/.../fe/session/FeSessionServiceImpl.java` | 234 | Redis 기반 FE 세션 |
| 13 | `ido/.../provision/ProvisioningServiceImpl.java` | 463+ | 전 기관 프로비저닝 (Virtual Thread) |

---

## 1. Findings (심각도 분류)

### Severity Rubric
- **Critical** : 운영 즉시 실 사용자 차단/보안 침해/데이터 무결성 파괴 가능
- **High**    : 특정 부하/공격/장애 조건에서 사용자 차단 또는 보안 약화
- **Medium**  : 운영 시 컴플라이언스/운영 효율성 저하, 우회 가능
- **Low**     : 코드 품질/명명/문서화 (기능 영향 없음)

---

### F4.1 [**Critical**] `HandoffCryptoService.verify()` 는 dead code — Handoff Verify 가 서명/암호화를 검증하지 않는다

**증거**:
- `ido/.../handoff/crypto/HandoffCryptoService.java:161-170` — `verify(String ticketId, String agencyCode, String encryptedPayload, String signature)` 메서드 정의됨.
- `grep -rn "handoffCryptoService\." ido/src/main/java/` 결과 (call site 전체):
  ```
  HandoffServiceImpl.java:111: String encrypted = handoffCryptoService.encrypt(plain, ticketId);
  HandoffServiceImpl.java:112: String signature = handoffCryptoService.sign(ticketId, cmd.getAgencyCode(), encrypted);
  ```
  → **`verify()` 호출 0건**. 컴파일은 되지만 **실행 경로상 도달 불가능**.

- `HandoffServiceImpl.java:174-212` `verify()` 구현:
  ```java
  HandoffTicket ticket = ticketRepository.findById(ticketId).orElseThrow(...);
  // 상태/만료/agencyCode 검증
  ticketRepository.consume(ticketId);
  publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_CONSUMED, ticket, null);
  HandoffPayload payload = policyEngine.buildHandoffPayload(ticket, correlationId);
  ```
  → ticket 객체의 `encryptedPayload`, `signature` 필드는 **읽지도, 검증하지도 않는다**.

**실패 시나리오**:
1. Redis에 침입/조작이 가능한 공격자가 `handoff:ticket:{ticketId}` 키의 JSON을 변조 (예: `qimUserId`를 다른 사용자로 swap).
2. 기관이 `POST /api/v1/ido/handoff/verify` 호출 → `findById()`는 변조된 ticket 반환.
3. 상태/만료/agencyCode는 일치하므로 통과.
4. `buildHandoffPayload`로 **변조된 qimUserId**가 그대로 payload에 담겨 기관에 전달됨.

**결정적 점**: encrypt/sign의 목적은 정확히 이 변조를 막기 위함인데, **verify 경로가 이 방어 로직을 호출하지 않으므로** ticket 발급 시 들어가는 모든 암호학적 보호가 **장식적**으로 전락.

**근본 원인 가설**: Sprint 13/14 리팩토링 시 `ticketRepository`에 ticket을 통째로 저장하는 구조로 바뀌면서 (Redis JSON 직렬화), encrypt/sign이 별도 transport 계층에서만 의미 있게 되었고, in-process verify 호출이 누락. 하지만 Redis 자체가 신뢰 경계가 아니라면 (managed Redis가 아니라 self-hosted거나 다른 서비스도 같은 Redis를 공유) 이 가정이 무너진다.

**개선안 (우선순위)**:
1. **HandoffServiceImpl.verify()** 에 다음 호출 추가 (라인 198 직전):
   ```java
   handoffCryptoService.verify(ticket.getTicketId(), ticket.getAgencyCode(),
                               ticket.getEncryptedPayload(), ticket.getSignature());
   ```
   서명 mismatch 시 `IDO_TICKET_SIGNATURE_INVALID` 에러 + 감사 로그(`SIGNATURE_INVALID` outcome) + Prometheus 카운터.
2. 단위 테스트: ticket 발급 후 Redis 키를 강제로 변조하고 verify가 `SIGNATURE_INVALID` 로 실패하는지 확인 (Testcontainers Redis).
3. 통합 테스트: `verify()` 정상 호출 후 metric `ido_handoff_signature_verified_total` 증가 확인.

---

### F4.2 [**Critical**] `TicketRepositoryImpl.consume()` 는 atomic CAS 가 아니다 — 동시 verify 시 ticket 이중 소비 가능

**증거** — `ido/.../infrastructure/TicketRepositoryImpl.java:91-131`:
```java
public void consume(String ticketId) {
    String key = "handoff:ticket:" + ticketId;
    Object value = redisTemplate.opsForValue().get(key);        // ① GET
    if (value == null) throw new RuntimeException(...);
    HandoffTicket existing = objectMapper.readValue(...);
    HandoffTicket consumed = HandoffTicket.builder()
            .ticketId(existing.getTicketId())
            // ... 전체 필드 복사
            .state(HandoffTicket.TicketState.CONSUMED)
            .build();
    redisTemplate.opsForValue().set(key, json, ...);            // ② SET — 무조건 덮어쓰기
}
```

**race condition** :
```
T1: GET → state=ISSUED
T2: GET → state=ISSUED   ← T1의 SET 이 아직
T1: SET(state=CONSUMED)
T2: SET(state=CONSUMED) ← T1이 이미 consume 했음에도 통과
```
- 두 verify 모두 `policyEngine.buildHandoffPayload()` 까지 도달 → **동일 ticket 으로 두 번 페이로드 발급**.

**HandoffServiceImpl 의 사전 상태 체크는 race window 를 막지 못함**:
- `if (ticket.getState() == CONSUMED) throw ...` 라인 181 — 이건 **findById** 결과 기준이지 atomic check-and-set 이 아님.

**실패 시나리오 (실 운영)**:
1. 기관 B의 verify 호출이 네트워크 retry 로 동시 2회 발사 (HTTP 클라이언트 idempotency 미적용).
2. 두 verify 모두 ISSUED 상태로 ticket 읽기 → 두 verify 모두 성공 → **기관 B 는 동일 사용자에 대해 두 번의 로그인 세션 생성** 또는 **중복 회원 가입 처리**.
3. F-24 (handoff replay defender) 가 있다고 가정해도, 그것은 ticketId 재사용 방지지 동시성 race 방지가 아님 (소스 코드상 추가 검증).

**개선안**:
1. Redis **Lua 스크립트** 로 atomic state transition:
   ```lua
   local current = redis.call('GET', KEYS[1])
   if current == false then return -1 end
   local ticket = cjson.decode(current)
   if ticket.state ~= 'ISSUED' then return -2 end
   ticket.state = 'CONSUMED'
   redis.call('SET', KEYS[1], cjson.encode(ticket), 'KEEPTTL')
   return 1
   ```
   - return 1 = 성공, -2 = 이미 consumed → `IDO_TICKET_CONSUMED` 즉시 throw.
2. 또는 **WATCH/MULTI/EXEC** 트랜잭션 사용.
3. 단위 테스트: 200개 가상 스레드로 같은 ticketId 동시 consume → 1개만 성공해야 함.

---

### F4.3 [**Critical**] Webhook signing secret default 가 평문 하드코딩 — `poc-webhook-secret-change-in-production`

**증거**:
- `WebhookDispatcherService.java:67-68`:
  ```java
  @Value("${ido.webhook.signing-secret:poc-webhook-secret-change-in-production}")
  private String defaultSigningSecret;
  ```
- `WebhookDispatcherService.java:260-272` `computeHmacSignature(payload, rawSecret)`:
  ```java
  String secret = (rawSecret != null && !rawSecret.isBlank()) ? rawSecret : defaultSigningSecret;
  ```
  → DB의 `signing_secret_hash` 가 null/blank 인 기관에 대해 **공개된 PoC 시크릿으로 HMAC 서명 발송**.

**보안 영향**:
- 공격자가 GitHub 코드를 보면 즉시 이 secret 을 알 수 있음.
- 공격자가 임의의 webhook payload 를 만들고 `sha256=HMAC(this-secret, payload)` 로 X-Webhook-Signature 헤더를 위조하여 기관 endpoint 에 직접 POST → 기관은 정상 OnePass webhook 으로 신뢰하고 사용자 상태 변경 (USER_WITHDRAWN, MEMBER_PROVISIONED 등) 처리.
- 기관 측 검증 코드가 default secret 을 모르고 자체 secret 만 검증한다면 영향 없지만, **OnePass 가 default secret 으로 발송한 webhook 을 기관이 받아 신뢰한다면** = 기관 측 secret 미설정 케이스에서 mutual 인증 0.

**또한 컬럼명 거짓 라벨**:
- 컬럼 이름은 `signing_secret_hash` 이지만 실제로는 **plaintext signing secret** 이 저장됨 (라인 263 에서 그대로 Mac key 로 사용).
- → DB 침해 시 모든 기관의 webhook 서명 키가 즉시 노출.
- → 컬럼명을 `signing_secret_encrypted` 로 바꾸고 KMS 암호화 저장 또는 평문이라면 `signing_secret_plain` (명백한 명명) + Vault 격리 필요.

**개선안**:
1. **default secret 제거** — 환경변수 미주입 시 어플리케이션 startup 실패 (Spring `@PostConstruct` validation).
2. DB 저장 시 KMS DEK 로 암호화 → 발송 직전 복호화 (CiCryptoService 처럼).
3. 컬럼명을 `signing_secret_encrypted` 로 마이그레이션.
4. 운영 진입 전 모든 기관의 webhook secret 이 등록되어 있는지 startup health check.

---

### F4.4 [**Critical**] Cross-Agency CAST 토큰을 URL 쿼리스트링으로 전달 — referer leak / 브라우저 히스토리 / 서버 로그 노출

**증거** — `CrossAgencySsoController.java:245-251`:
```java
private String buildRedirectUrl(String targetAgency, String castJwt) {
    return String.format("https://%s.agency.go.kr/sso-entry?onepass_sso=%s",
            targetAgency.toLowerCase().replace("_", "-"), castJwt);
}
```

**문제점 다중**:
1. **CAST JWT 가 URL 쿼리스트링에 노출** — 다음 위협에 모두 노출:
   - 브라우저 history 에 영구 보관 (사용자 PC 멀웨어/공유 PC).
   - HTTP Referer 헤더로 기관 B 페이지 내 모든 외부 리소스(이미지, 분석 스크립트)에 토큰 leak.
   - Nginx/ALB access log 에 토큰 기록 — 로그 접근 권한자가 토큰 입수.
   - 브라우저 확장 프로그램이 URL 을 읽음.
2. **하드코딩된 도메인 패턴** `*.agency.go.kr` — placeholder 주석에서 Sprint 14 까지 미해결로 명시. 운영 시 실제 기관 도메인은 천차만별인데 이 코드는 영원히 작동하지 않음 (예: smes.go.kr, korea.kr 등).
3. **TTL** : CAST 토큰 자체는 짧을 것으로 추정되지만 (코드상 `CastToken.TTL_SECONDS` 확인 필요), 토큰이 1회용이라도 leak 된 후 verify 호출까지 race window 존재.

**비교 — Handoff Ticket 의 경우**: `HandoffController` 는 ticketId 만 URL/Header 로 전달하고, ticket의 실제 페이로드는 서버측 Redis 에 보관 → verify 시 새 HTTP 요청으로 가져옴. **이게 올바른 패턴**.

**개선안**:
1. CAST 토큰을 **POST body 또는 hidden form field** 로 전달 (서버측 redirect 가 아닌 form auto-submit).
2. 또는 ticketId 를 짧은 opaque ID 로 발급하고 기관 B 는 서버 사이드 verify 호출로 실제 토큰 가져가게.
3. 도메인 화이트리스트는 `agency_endpoint_registry` 에서 조회 (이미 인프라 존재 — `endpointRegistry.findByAgencyAndType()`).
4. 라인 249 의 placeholder URL 은 **운영 진입 전 반드시 제거** — 현재 상태로 운영 배포하면 모든 cross-agency SSO 가 실패.

---

### F4.5 [**Critical**] Handoff Verify 의 비-원자적 다단계 — 부분 실패 시 사용자 영구 차단

**증거** — `HandoffServiceImpl.java:199-207`:
```java
ticketRepository.consume(ticketId);                                              // ① Redis SET (state=CONSUMED)
publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_CONSUMED, ticket, null);          // ② Kafka publish
HandoffPayload payload = policyEngine.buildHandoffPayload(ticket, correlationId); // ③ Q-IM HTTP 호출 가능
auditVerify(...);                                                                 // ④ 감사로그
return payload;
```

**문제**:
- 메서드에 `@Transactional` 이 있지만 Redis 작업과 Kafka 작업은 Spring transaction manager 의 통제를 받지 않음.
- ③ `buildHandoffPayload` 내부에서 `qimClient.getDi()` HTTP 호출이 timeout/실패 → 예외 throw → 메서드 빠져나옴.
- 이때 ticket은 이미 ① 에서 **CONSUMED 상태로 Redis 저장됨**.
- 사용자가 재시도하면 `if (ticket.getState() == CONSUMED)` 에 걸려 `IDO_TICKET_CONSUMED` 에러.
- → **단 한 번의 Q-IM 지연으로 사용자가 영구 차단**.

**실패 시나리오**:
- Q-IM 가 30초 지연 (DB connection pool 고갈) → verify 호출이 ReadTimeoutException → ticket consumed but no payload returned.
- 사용자가 새 ticket 으로 재시도 ⇨ Q-IM 여전히 느림 ⇨ 무한 루프 (각 시도마다 1개씩 ticket 소비).

**개선안**:
1. **payload 사전 빌드 후 consume**:
   ```java
   HandoffPayload payload = policyEngine.buildHandoffPayload(ticket, correlationId); // ① 먼저
   ticketRepository.consume(ticketId);                                                 // ② 성공 후 consume
   publishHandoffEvent(...);                                                           // ③
   ```
   - 단점: payload 빌드 후 consume 사이의 race window — 하지만 F4.2 Lua atomic CAS 와 결합하면 해결 가능.
2. 또는 **two-phase commit 패턴**: PENDING_CONSUMPTION 상태 → payload 성공 후 CONSUMED 로 commit, 실패 시 ISSUED 로 rollback.
3. circuit breaker (Resilience4j) 로 Q-IM 장애 빠른 감지 → 부분 실패 차단.

---

### F4.6 [**Critical**] `PolicyEngineImpl.tryResolveDi()` 가 일시 장애와 영구 미매핑을 구분하지 않음 — Q-IM 장애 시 모든 사용자 GUEST 처리

**증거** — `PolicyEngineImpl.java:199-213`:
```java
private String tryResolveDi(String qimUserId, String agencyCode, String correlationId) {
    try {
        String di = qimClient.getDi(qimUserId, agencyCode, correlationId);
        if (di != null && !di.isBlank()) return di;
        log.info("[PolicyEngine] Q-IM DI 없음(null/blank) — GUEST 대상: ...");
        return null;
    } catch (Exception e) {
        log.warn("[PolicyEngine] Q-IM DI 조회 실패 — GUEST 대상: ...");
        return null;   // ← 영구 미매핑(=정상 GUEST)과 동일 처리
    }
}
```

**문제**:
- Q-IM HTTP 호출이 5xx, ConnectException, ReadTimeoutException 등으로 **예외 throw** → catch 에서 단순 `return null` → **GUEST 분기로 전환**.
- 호출자(`buildHandoffPayload`)는 null 이 "DI 없음(정상)" 인지 "Q-IM 장애" 인지 알 수 없음.
- 기관은 사용자가 등록된 회원임에도 **GUEST 로 전달받음** → 기관 측에서 "신규 가입 유도" 페이지 표시 또는 거절.

**실패 시나리오 — 대규모 영향**:
1. Q-IM 인스턴스 재배포 중 (1분) 5xx 발생.
2. 그 1분 동안 verify 한 모든 사용자가 GUEST 처리됨 (수백~수천 명).
3. 사용자는 "신규 회원가입" 화면을 보거나 거부됨 → 운영 incident.
4. 메트릭 측면: handoff 성공률은 100% (오류 throw 안 함) → 알람 미발동.

**개선안**:
1. **예외 타입 구분**:
   ```java
   try {
       String di = qimClient.getDi(...);
       if (di != null && !di.isBlank()) return di;
       return null;  // 정상 미매핑
   } catch (FeignClientException.NotFound e) {
       return null;  // 404 = 매핑 없음 = GUEST 정당
   } catch (Exception e) {
       // 5xx, timeout, network ⇒ 장애 — GUEST 로 fallback 하지 말고 throw
       throw new PlatformException(PlatformErrorCode.IM_TEMPORARILY_UNAVAILABLE, correlationId);
   }
   ```
2. `buildHandoffPayload` 호출부는 `IM_TEMPORARILY_UNAVAILABLE` 을 별도로 처리 → 503 Retry-After 응답.
3. 메트릭 신설: `ido_policy_guest_total{reason="not_mapped|qim_error"}` — GUEST 비율이 reason 별로 분리되어야 장애 감지 가능.

---

### F4.7 [**High**] HmacSignatureFilter soft mode (F-26=false) 가 missing signature 를 unconditional pass — 점진 도입 의도와 운영 위험 충돌

**증거** — `ido/.../gateway/HmacSignatureFilter.java:104` 인근 (요약):
- F-26 (`featureFlags.isHmacSigRequired()`) 가 false 이면 **시그니처 헤더 부재 시 그냥 통과**, true 이면 401.
- 즉 점진 도입(soft) 모드에서는 공격자가 X-Internal-Sig 헤더 없이 요청하면 통과.

**문제 (운영 진입 직전 관점)**:
- F-26 의 디폴트가 `false` (코드상 점진 도입) — 운영 배포 시 명시적 true 설정이 누락되면 전혀 검증되지 않음.
- "monitoring only" 의도라면 **missing sig 도 log + metric** 으로 기록해서 운영자가 강화 시점을 결정해야 하는데, 코드상 missing sig 도 통과시킨 후 metric 누락 (구체 확인 필요하나 라인 104 의 조건 분기에서 미기록).
- 결과: 운영자가 "현재 미준수 기관 비율"을 모르므로 강화(F-26=true) 시점을 영원히 미룸.

**개선안**:
1. soft mode 에서도 missing/invalid sig 카운터 신설:
   - `ido_inbound_hmac_total{result="missing|invalid|valid"}`
2. 일정 기간 missing 비율이 0% 면 자동으로 F-26=true 권고 알람.
3. soft mode 에서도 missing sig 는 audit log INFO 로 남기기.

---

### F4.8 [**High**] `FeSessionController.createSession()` 은 인증 없이 임의의 qimUserId 로 세션 발급 가능

**증거** — `FeSessionController.java:121-158`:
```java
@PostMapping
public ResponseEntity<Map<String, String>> createSession(
    @RequestHeader(value = "X-Internal-Caller", required = false) String caller,
    @RequestBody FeSessionCreateRequest req,
    HttpServletResponse response) {
    // X-Internal-Caller 는 로그만 찍고 검증 안 함
    FeSession session = feSessionService.create(req.getQimUserId(), ...);
    // feSessionId 쿠키 set + 반환
}
```

**문제**:
1. `InternalApiKeyInterceptor` 의 보호 경로는 `/api/v1/internal/**` 인데, 이 컨트롤러는 `/api/v1/fe-session` 으로 **interceptor 적용 대상이 아님**.
2. `X-Internal-Caller` 헤더는 로그용일 뿐 어떤 인증/인가 검증도 수행하지 않음.
3. 공격자가 IdO 서비스에 직접 접근 가능하면 (네트워크 격리만 의존) `POST /api/v1/fe-session { qimUserId: "victim-uuid" }` 로 **임의 사용자의 세션을 발급받음** → 그 쿠키로 모든 보호된 FE 페이지 접근.

**전제 검증 필요**:
- IdO 의 `/api/v1/fe-session` 경로가 외부 Ingress 에 노출되어 있는지? (Helm/Ingress YAML 확인 필요)
- 만약 외부 노출되어 있다면 즉시 운영 차단 사유.
- 내부 메시(mesh)만 호출 가능하다 해도, Q-Sign 외 다른 내부 서비스가 침해되면 즉시 전파.

**개선안**:
1. `/api/v1/fe-session` (POST/CONVERSION) 도 `InternalApiKeyInterceptor` 보호 경로에 포함.
2. `X-Internal-Caller` 와 함께 sender 별 API Key 검증.
3. NetworkPolicy 에서 q-sign 의 service account 만 호출 허용.

---

### F4.9 [**High**] `ProvisioningServiceImpl` 의 HMAC payload 비대칭 — 인바운드/아웃바운드 형식 다름

**증거** — `ProvisioningServiceImpl.java:438-462` 주석 + 구현:
- 아웃바운드: `payload = idempotencyKey + ":" + epochSeconds`
- 인바운드 (`HmacSignatureFilter`): `payload = agencyCode + ":" + idempotencyKey + ":" + epochSeconds`

**문제**:
1. 기관 SDK 가 OnePass 와 양방향 통신할 때 양쪽에서 같은 HMAC 검증 코드를 재사용하지 못함.
2. agencyCode 가 빠진 아웃바운드는 cross-agency replay 공격에 노출 (한 기관 secret 이 유출되면 다른 기관으로의 위조 가능 — 단, 서명 검증 키도 기관별이므로 영향 제한적이나 향후 키 단일화 시 위험).
3. 주석 (라인 443-447) 에서 "기관 연동 명세에서 다른 형식 요구 시 hmacPayloadTemplate 필드 추가" 라며 기술 부채를 명시 → 운영 진입 전에 통일하지 않으면 기관별 customization 폭증.

**개선안**:
1. 두 방향 모두 `{agencyCode}:{idempotencyKey}:{epochSeconds}` 로 통일.
2. 또는 SignaturePayloadBuilder 공통 유틸로 추출 → 양쪽이 같은 코드 사용.

---

### F4.10 [**High**] `WebhookDispatchOutboxRelay` 의 `signing_secret_hash` 컬럼이 실제로는 평문 secret — DB 침해 시 모든 기관 webhook 위조 가능

**증거** — `WebhookDispatchOutboxRelay.java:172`:
```java
String signingSecret = (String) row.get("signing_secret_hash");  // 실제로는 signing 원본 저장 위치
```
주석에 명시: "실제로는 signing 원본 저장 위치". F4.3 의 컬럼명 거짓 라벨과 동일 사안 — Outbox 측면에서 재확인.

**부가 문제**:
- DB JOIN 후 메모리에 secret 이 평문으로 잔류.
- 로그 출력 시 마스킹 누락 가능성 (확인 필요 — `payloadJson.length()` 만 로그 찍는 것 봐서 secret 자체는 안 찍는 듯하나, 디버그 모드 추가 시 위험).

**개선안 (F4.3 과 동일)**: KMS DEK 암호화 저장 + 발송 직전 복호화.

---

### F4.11 [**High**] `ProvisioningServiceImpl.buildIdentityHash()` 의 identity_hash 가 사용자 식별자가 아닌 이벤트 식별자

**증거** — `ProvisioningServiceImpl.java:425-430`:
```java
private String buildIdentityHash(String qimUserId, Instant registeredAt) {
    String input = qimUserId + ":" + registeredAt.toEpochMilli();
    byte[] hash  = digest.digest(input.getBytes(...));
    return "sha256:" + HexFormat.of().formatHex(hash);
}
```

**문제**:
1. 같은 qimUserId 라도 `registeredAt` 이 다르면 identity_hash 가 달라짐 — 기관 입장에서 "이 사용자가 누구인지" 식별 불가.
2. 동일 사용자가 여러 번 프로비저닝되면 매번 다른 identity_hash → 기관 측 dedup 불가.
3. PII 최소화 의도 ("실명/전화 평문 절대 포함 금지" — 라인 44) 는 좋지만, qimUserId 자체가 이미 UUID 라 PII 가 아님. **그냥 qimUserId 를 직접 보내면 됨**. epochMilli 를 섞는 것은 의도 불명.

**가능한 해석**: epochMilli 가 섞이면 동일 qimUserId 의 hash 가 매번 달라져 **추적 방지** 효과 → 하지만 동시에 idempotency 도 깨짐.

**개선안**:
1. `identity_hash = SHA-256(qimUserId)` (epochMilli 제거) — deterministic.
2. 또는 기관별 DI 와 동일 형태로 `HMAC(agencyCode + ":" + qimUserId, diSecret)` → 기관마다 다른 식별자, 동일 사용자는 동일 hash.
3. 현재 코드는 운영 진입 전 의도 명확화 필요.

---

### F4.12 [**High**] `CrossAgencySsoController.publicKey()` 가 Sprint 13 Phase 2 placeholder 응답 — SDK 키핀닝 불가

**증거** — `CrossAgencySsoController.java:202-209`:
```java
@GetMapping("/public-key")
public ResponseEntity<Map<String, String>> publicKey() {
    return ResponseEntity.ok(Map.of(
        "note", "공개키 엔드포인트 — Sprint 13 Phase 2에서 JWK 형식으로 구현 예정",
        "algorithm", "EdDSA",
        "curve", "Ed25519"
    ));
}
```

**문제**:
- 실제 공개키 미반환 → 기관 SDK 가 CAST JWT 서명 독립 검증 불가.
- 기관은 OnePass 의 `/verify` 엔드포인트에 100% 의존 → OnePass 장애 시 모든 cross-agency SSO 차단.
- 명세는 "Sprint 13 Phase 2" 라고 미해결 표시 — 운영 진입 전 완성 필요.

**개선안**: Ed25519 공개키를 JWK 형식으로 반환 + JWKS endpoint (`/.well-known/jwks.json`) 표준화.

---

### F4.13 [**Medium**] `TicketRepositoryImpl` 의 모든 상태 변경이 Lua 스크립트 미사용 — consume 외에도 revoke 등 race 존재

**증거**: F4.2 에서 consume 만 확인했으나, `TicketRepositoryImpl` 전체 (라인 219) 가 동일 GET/SET 패턴 사용 가정 — revoke, save 등도 atomicity 부재.

**영향**:
- ticket save 와 revoke 가 동시에 진행되면 마지막 SET 이 이기는 last-write-wins → revoke 가 무시될 수 있음.
- revoke 의 의미는 "이 ticket 을 즉시 무효화" 인데 race 로 무효화 실패 시 보안 사고.

**개선안**: Lua 스크립트로 모든 상태 전이를 atomic CAS 화.

---

### F4.14 [**Medium**] `WebhookDispatchOutboxRelay` 의 max_retry=3 + 지수 백오프 후 FAILED → DLQ 처리 부재

**증거** — `WebhookDispatchOutboxRelay.java:279-287`:
```java
if (retryCount >= maxRetry) {
    markFailed(dispatchId, ...);
    publishAuditFailure(...);
    return DispatchResult.FAILED;
}
```

**문제**:
- FAILED 상태로 DB 에 기록만 되고 알람/DLQ 처리 없음.
- 운영자가 정기적으로 `SELECT * FROM webhook_dispatch_outbox WHERE status='FAILED'` 실행해야 발견 가능.
- Sprint 17 의 webhook 발송 성공률 알람은 있으나 (PR-B2 알람 정리에서 확인), 개별 FAILED 레코드 추적은 부재.

**개선안**:
1. FAILED 발생 시 `ido_webhook_dispatch_failed_total{agency_code, status}` 카운터 증가.
2. Grafana 대시보드에 FAILED 누적 추세 표시.
3. 수동 재발송 admin endpoint: `POST /api/v1/admin/webhook/dispatch/{dispatchId}/retry`.

---

### F4.15 [**Medium**] `WebhookDispatcherService` 의 webhook payload 에 sourceSystem="ido" 등 메타데이터가 평문 — 기관 측에서 신뢰 경계 불명확

**증거** — `WebhookDispatcherService.java:425-430` `buildHandoffWebhookPayload`:
```java
payload.put("platformVersion", platformVersion);
payload.put("sourceSystem",    "ido");
```

**문제**:
- HMAC 서명은 X-Webhook-Signature 헤더로 검증되지만, payload 자체에 들어가는 sourceSystem 은 **JSON 본문에 평문**.
- 공격자가 (default secret 알면) 임의 sourceSystem 값으로 위조 가능.
- 기관 측에서 sourceSystem 을 "신뢰 라벨" 로 사용하면 위조에 속음.

**개선안**: F4.3 default secret 제거 + sourceSystem 등은 서명에 포함되므로 위조 어려움 — 그러나 기관 SDK 검증 가이드 명확화 필요.

---

### F4.16 [**Medium**] `HandoffServiceImpl.issue()` 의 strategy postIssue 실패가 swallowed — DIRECT 외 전략 사용 시 silent failure

**증거** — `HandoffServiceImpl.java:147-156`:
```java
try {
    strategyFactory.getStrategy(integrationType)
            .postIssue(ticket, preBuiltPayload, cmd.getCorrelationId());
} catch (Exception strategyEx) {
    // 전략 후처리 실패는 Ticket 발급 자체를 롤백하지 않음
    log.error("[HandoffSvc] Strategy postIssue 실패 (비치명적, DIRECT 폴백): ...");
}
```

**문제**:
- BRIDGE 전략: Bridge 서버에 Payload pre-push 실패 시 → ticket 은 발급되었으나 Bridge 가 모름 → 사용자가 Bridge 경유 시 "ticket not found" 에러.
- INTERNAL_SSO 전략: SSO 쿠키 사전 등록 실패 시 → 사용자 자동 로그인 실패.
- "DIRECT 폴백" 주석이 있지만 실제 폴백 메커니즘은 부재 — 기관이 DIRECT verify 엔드포인트로 자동 재시도하지 않음.

**개선안**:
1. 전략 실패를 `ido_strategy_post_issue_failed_total{integrationType, agencyCode}` 카운터.
2. 전략 실패 시 사용자에게 "다시 시도" 안내 + Bridge/SSO 미사용 직접 verify URL 폴백 제공.
3. 또는 전략 실패 시 전체 발급 롤백 + 재시도 응답.

---

### F4.17 [**Low**] `WebhookDispatchOutboxRelay` 의 4xx 정책 — 404/410 은 즉시 FAILED 처리하지만 401/403 은 retry — 401/403 도 영구 실패일 가능성

**증거** — `WebhookDispatchOutboxRelay.java:221-228`:
```java
if (statusCode == 404 || statusCode == 410) {
    markFailed(dispatchId, statusCode, "endpoint not found: ...");
    return DispatchResult.FAILED;
}
return handleRetryOrFail(...);
```

**문제**:
- 401 (인증 실패), 403 (권한 없음) 도 재시도해도 같은 결과 — secret 불일치/만료 → 영구 실패.
- 4번의 401 재시도는 기관 IDS/방화벽 에서 의심 트래픽으로 차단될 수 있음.

**개선안**: 401, 403 도 즉시 FAILED + 운영자 알람.

---

### F4.18 [**Low**] `HandoffServiceImpl.revoke()` 의 `findById().orElseThrow()` — 이미 revoke 된 ticket 재호출 시 NoSuchElementException

**증거** — `HandoffServiceImpl.java:221`:
```java
HandoffTicket ticket = ticketRepository.findById(ticketId).orElseThrow();
```
- `.orElseThrow()` (인자 없음) → `NoSuchElementException` → 500 응답 (PlatformException 이 아니므로 글로벌 핸들러에서 5xx).

**개선안**: `.orElseThrow(() -> new PlatformException(IDO_TICKET_NOT_FOUND, correlationId))` 로 정정.

---

### F4.19 [**Low**] `CrossAgencySsoController.buildRedirectUrl` 의 하드코딩 도메인 (placeholder) — Sprint 14 미해결

**증거**: F4.4 와 동일 라인. 별도 finding 으로 분리 (Sprint 14 backlog).

**개선안**: agency_endpoint_registry 의 entry_url 사용.

---

## 2. Catastrophic Scenarios — 운영 진입 시 실제 시나리오

### Scenario H: Redis 침해 + signature verify dead code = 신분 swap 공격

1. 내부망 침해 또는 Redis managed service 인증 leak.
2. 공격자 ↦ `redis-cli SET handoff:ticket:T123 '{"qimUserId":"victim-uuid","agencyCode":"AGENCY_X","state":"ISSUED",...}'`
3. 공격자가 기관 X 의 verify endpoint 를 사용자로 가장하여 호출 → ticketId=T123.
4. IdO 의 verify : 상태/만료/agencyCode 일치 → `policyEngine.buildHandoffPayload(ticket)` → **victim-uuid 의 페이로드 발급**.
5. 공격자는 victim 의 계정으로 기관 X 로그인 성공.

**방어선 부재**: F4.1 (signature verify dead code) + F4.2 (consume race) 가 모두 동시에 작동.

---

### Scenario I: Q-IM 일시 장애 → 전체 기관에 사용자 GUEST 처리

1. Q-IM DB connection pool 고갈 (예: long-running query, deadlock).
2. `qimClient.getDi()` 가 5xx/timeout 반환.
3. F4.6 — `tryResolveDi()` 가 예외를 catch 하고 `null` 반환.
4. IdO 는 정상적으로 GUEST 페이로드 발급.
5. 기관은 "이 사용자는 신규" 로 판단 → 회원가입 유도 페이지 표시.
6. 사용자는 본인이 이미 회원인데 새 가입 요구받음 → CS 폭주.

**감지 어려움**: handoff 성공률 메트릭은 100% (정상 응답), DI lookup 실패는 WARN 로그만.

---

### Scenario J: Verify 중 Q-IM 지연 → ticket 영구 consumed

F4.5 에 상세. 추가로:
- Q-IM 5초 지연 + IdO RestTemplate readTimeout 3초.
- 사용자 본인은 "잠시 후 다시 시도" 메시지를 받지만 ticket 이 이미 consumed.
- 재발급 받아야 하나 발급 측에서도 rate limit 에 걸리거나 idempotency 처리로 거부.

---

### Scenario K: Webhook default secret + 기관 mTLS 미설정 = 위조 webhook 수신

1. 기관 A 가 webhook secret 미등록 (`signing_secret_hash` NULL).
2. OnePass 는 default `poc-webhook-secret-change-in-production` 으로 서명하여 발송.
3. 공격자가 기관 A 의 endpoint URL 을 알고 (대부분 운영 매뉴얼/문서로 공개), default secret 로 임의 USER_WITHDRAWN webhook 위조 → 기관 A 의 endpoint 직접 POST.
4. 기관 A 가 default secret 로 검증 → 통과 → 사용자 강제 탈퇴 처리.

**연쇄 영향**: 한 번에 다수 사용자 강제 탈퇴 → 데이터 정합성 파괴 (Q-IM 의 사용자는 ACTIVE 인데 기관은 WITHDRAWN).

---

### Scenario L: FE Session 무인증 발급 + Redis 공유 = 임의 사용자 가장

1. 공격자가 IdO 의 `/api/v1/fe-session` (POST) 에 직접 도달 (내부망 침해 또는 잘못된 Ingress 설정).
2. `{ qimUserId: "any-uuid" }` 로 POST → feSessionId 쿠키 발급.
3. 그 쿠키로 모든 BFF 경로 호출 가능 (Cross-Agency SSO issue 포함).
4. 공격자는 `targetAgency=AGENCY_X` 로 CAST issue → 그 기관 로그인 성공.

**전제**: `/api/v1/fe-session` 외부 노출 여부 확인 필요. Ingress 가 `/api/v1/internal/**` 만 차단하면 이 경로는 그대로 노출.

---

## 3. Positive Verifications (잘 구현된 부분)

| # | 항목 | 평가 |
|---|------|------|
| ✅ V4.1 | `WebhookDispatchOutboxRelay` 의 `FOR UPDATE SKIP LOCKED` | 다중 인스턴스 안전한 폴링 |
| ✅ V4.2 | 지수 백오프 (2^retry) + max_retry=3 | 합리적인 retry 정책 |
| ✅ V4.3 | 4xx/5xx 분리 + 404/410 즉시 FAILED | 영구 실패 빠른 종료 |
| ✅ V4.4 | `enqueueForHandoffEvent` 의 `ON CONFLICT DO NOTHING` | webhook outbox 중복 방어 |
| ✅ V4.5 | `AgencyGatewayServiceImpl.receiveInbound` 의 2단계 멱등성 (Redis SETNX + DB UNIQUE) | race condition 방어 |
| ✅ V4.6 | `ProvisioningServiceImpl` Virtual Thread Executor | 68개 기관 병렬 HTTP 안전 |
| ✅ V4.7 | `FeSessionServiceImpl` 의 invalidateByQimUserId | MANDATORY_SECURITY 일괄 무효화 지원 |
| ✅ V4.8 | `FeSessionServiceImpl` 의 sliding TTL + absolute timeout 분리 | 세션 보안 모범 사례 |
| ✅ V4.9 | `FeSessionController.checkSession` returnUrl 화이트리스트 | open redirect 방어 |
| ✅ V4.10 | `HandoffServiceImpl.issue()` 의 rate limit + callback whitelist + maintenance 분기 | 발급 전 사전 검증 정상 |
| ✅ V4.11 | 감사 로그 (auditIssue / auditVerify / WEBHOOK_DISPATCHED 등) 의 풍부함 | 사후 추적 가능 |
| ✅ V4.12 | `WebhookDispatcherService.buildHandoffWebhookPayload` 의 qimUserId 미포함 (ticketId, agencyCode 만) | PII 최소화 원칙 준수 |
| ✅ V4.13 | F-14 (Webhook Relay On/Off), F-20 (Provisioning enabled), F-22 (Provisioning dry-run) 등 Feature Flag 다단계 | 점진 도입 인프라 |
| ✅ V4.14 | `ProvisioningServiceImpl` 의 mTLS RestTemplate 별도 빈 | 기관별 인증 방식 분리 |
| ✅ V4.15 | `CrossAgencySsoController` 의 Fe-Session-Id 쿠키 server-side 추출 | qimUserId 클라이언트 변조 방지 |

---

## 4. 종합 평가

### Critical Path 무결성: ❌ 운영 진입 부적합
- **F4.1** (verify dead code) + **F4.2** (consume race) + **F4.3** (default webhook secret) + **F4.4** (CAST URL leak) + **F4.5** (verify non-atomic) + **F4.6** (qim error masking) — 6개 **Critical** 결함이 동시 존재.
- 어느 하나라도 실 운영에서 발현되면 사용자 차단 또는 보안 사고로 직결.

### Sprint 13/14 미완성: ⚠️ 명시적 placeholder 잔존
- `CrossAgencySsoController.publicKey()` — Sprint 13 Phase 2 명시.
- `CrossAgencySsoController.buildRedirectUrl()` — Sprint 14 명시 + 하드코딩 도메인.
- 운영 진입 전 둘 다 완성 필수.

### 점진 도입(Feature Flag) 인프라: ✅ 우수
- F-14, F-20, F-22, F-26 등 다단계 점진 도입 지원.
- 단, F-26 (HMAC 검증) 은 운영 진입 시 명시적 true 설정 필요 — 누락 위험.

### 외부 호환성: ⚠️ 비대칭 + 거짓 라벨
- HMAC payload 인바운드/아웃바운드 비대칭 (F4.9).
- `signing_secret_hash` 컬럼명이 실제로는 평문 secret (F4.3, F4.10).
- identity_hash 의 의미 모호 (F4.11).

---

## 5. 우선순위 정리 (다음 phase 의 입력)

### 즉시 수정 (운영 진입 차단)
1. **F4.1**: `HandoffServiceImpl.verify()` 에 `handoffCryptoService.verify()` 호출 추가.
2. **F4.2**: `TicketRepositoryImpl.consume()` Lua 스크립트로 atomic CAS.
3. **F4.3**: `defaultSigningSecret` 제거 + KMS 암호화 저장.
4. **F4.4**: CAST URL 토큰 노출 → POST body / 짧은 opaque ID 로 전환.
5. **F4.5**: verify 의 payload 빌드 → consume 순서 변경 (또는 two-phase commit).
6. **F4.6**: `tryResolveDi` 예외 타입 구분 — 장애와 미매핑 분리.

### 단기 (1 sprint 내)
7. **F4.7**: HmacSignatureFilter soft mode 의 missing sig 카운터.
8. **F4.8**: `/api/v1/fe-session` 인증 강화.
9. **F4.9**: HMAC payload 통일.
10. **F4.10**: webhook secret DB 암호화.
11. **F4.11**: identity_hash 정의 명확화.
12. **F4.12**: JWK endpoint 완성.

### 중기 (2-3 sprint)
13. F4.13 ~ F4.19 (Medium/Low)

---

## 6. 다음 단계 — Phase 5 입력

Phase 5 (`05_privacy_kms_audit.md`) 에서 확인할 항목:
- **KMS 통합**: CiCryptoService, HandoffCryptoService, 향후 webhook secret 의 키 관리 전체 흐름.
- **개인정보 비저장 원칙**: qimUserId, CI, DI, identifierHash 가 각 모듈 DB/로그/Kafka payload 에 노출되는지 전수 점검.
- **감사 로그 (audit log)**: 위 모든 Critical/High 시나리오에서 감사 로그가 충분한 정보를 남기는지 — 사후 추적 가능성.
- **GDPR/개인정보보호법 대응**: 삭제 요청 (right to erasure) 시 모든 모듈에서 일관 삭제되는지.

---

**Phase 4 분석 종료**
**결론**: IdO Handoff 본질 기능에서 **6개 Critical 결함** 확인. PR #173 머지 보류 사유 강화. 다음 Phase 5 에서 KMS/Privacy/Audit 보완 분석 진행.
