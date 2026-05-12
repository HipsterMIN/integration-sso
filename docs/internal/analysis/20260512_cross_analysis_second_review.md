# 교차 검증 보고서 2차 심층 재검토 — 보수적 관점 재분석

- **작성일**: 2026-05-12
- **검토 대상**: `20260512_171617_cross_analysis_vs_prior_report.md`
- **분석 방법**: 보고서의 각 주장을 코드베이스에서 직접 재검증, 이견 없는 것도 반박 가능성 탐색
- **관점**: "보고서가 틀렸거나 과장된 부분이 있다"는 전제 하에 깐깐하게 재검토

---

## 요약 판정

| 구분 | 건수 | 내용 |
|------|------|------|
| 보고서 주장 **정확** | 5건 | 코드와 일치, 이의 없음 |
| 보고서 주장 **부정확/오진** | 3건 | 코드를 잘못 읽었거나 과소 평가 |
| 보고서가 **놓친 신규 결함** | 4건 | 코드에 실재하나 보고서에 없음 |
| 심각도 **재분류 필요** | 2건 | 보고서 등급과 실제 위험이 다름 |

---

## 1. 보고서 주장 재검증 — 항목별 판정

---

### [C-01] qimUserId 미연동 — "해결됨" 판정

**보고서 주장**: OidcCompleteController에서 identifierHash 대신 실제 qimUserId를 Q-IM에서 조회하므로 해결됨.

**재검증 결과**: ⚠️ **부분 정확 — Keycloak 모드에서는 여전히 identifierHash 사용 중**

```
파일: ido/src/main/java/kr/go/smes/ido/broker/keycloak/KeycloakOidcService.java
라인 144: identifierHash,   // 소셜 로그인 경로: CI 없음 → identifierHash 사용 (Q-IM 팀 협의 필요)
라인 352: identifierHash,   // 소셜 로그인: CI 없음 → identifierHash 사용 (Q-IM 팀 협의 필요)
```

**실제 상황**:
- qsign 모드 + CI 있음 → ✅ qimUserId 정상 조회 (보고서 맞음)
- qsign 모드 + CI 없음 → ⚠️ identifierHash 폴백 (보고서가 언급하지 않음)
- **keycloak 모드** → ❌ identifierHash를 영구 식별자로 사용 (보고서 놓침)

코드 주석에 "Q-IM 팀 협의 필요"가 두 곳에 명시되어 있다. Keycloak 모드는 운영 배포 예정 모드(`broker.mode=keycloak`)이므로 이 경로에서의 identifierHash 사용은 수정 전과 동일한 구조적 결함이 남아 있는 것이다.

**재판정**: ~~해결됨~~ → **부분 해결 (Keycloak 모드 경로 미해결)**

---

### [C-02] X-Internal-Sig 검증 우회 — "부분 일치" 판정

**보고서 주장**: `strict-mode=true`가 프로덕션 기본값이나 non-strict 코드 잔존이 위험.

**재검증 결과**: ✅ **정확, 단 위험 경로를 추가 발견**

```
파일: q-sign/src/main/java/kr/go/smes/qsign/api/InternalSigVerifier.java
라인 115: return !strictMode; // strict=false → PoC 허용, strict=true → 거부
라인 119: return !strictMode;
```

보고서의 분석 자체는 맞다. 그러나 추가로 발견된 위험:

**ido 측 InternalSigVerifier.java와 q-sign 측 InternalSigVerifier.java가 별개 파일**이다.

```
ido 측 (라인 87-126):  strict-mode 없음 → 항상 강제 검증, 미설정 시 즉시 거부(false)
q-sign 측 (라인 102-157): strict-mode 있음 → non-strict 우회 경로 존재
```

즉 보고서가 지적한 위험은 **q-sign → ido 방향** 내부 API에 해당한다. ido → q-sign 방향은 항상 엄격하게 검증된다. 보고서가 방향을 혼용해서 기술한 점은 아쉽다.

**재판정**: 보고서 판정 유지, 단 방향 명시 필요

---

### [M-A] Rate Limiter 단일 JVM 문제 — 보고서의 **심각한 오진**

**보고서 주장**: `ConcurrentHashMap` 기반 인메모리 카운터 사용 → K8s 멀티 Pod 시 TPS 한도 우회.

**재검증 결과**: ❌ **보고서가 완전히 틀렸다**

```
파일: ido/src/main/java/kr/go/smes/ido/ratelimit/AgencyRateLimiter.java
라인 41-70: Redis Lua 스크립트 기반 분산 카운터
라인 37: TPS_KEY_PREFIX = "ido:rl:tps:"  ← Redis 키
라인 38: DAILY_KEY_PREFIX = "ido:rl:daily:"  ← Redis 키
```

실제 구현은 **Redis Lua 스크립트**를 이용한 원자적 분산 카운터다. Lua `INCR + EXPIRE` 방식이므로 K8s 멀티 Pod 환경에서도 모든 Pod가 동일한 Redis 키를 공유하여 정확한 TPS/일별 제한이 적용된다. `ConcurrentHashMap`은 코드 어디에도 없다.

보고서가 코드를 직접 읽지 않고 이전 버전을 기준으로 추정한 것으로 판단된다.

**재판정**: ~~Medium~~ → **해결된 항목 (보고서 오진)**

단, 새로운 실제 위험 발견:
```
라인 152-154: fail-open 정책
  log.error("Redis 오류 — 허용 처리 (fail-open)")
  return true; // fail-open: Redis 장애 시 허용
```
Redis 장애 시 Rate Limit이 완전히 우회된다. 이것이 진짜 위험이다. 보고서가 지적한 JVM 문제는 없고, 보고서가 놓친 fail-open 문제는 실재한다.

---

### [M-B] JWKS 캐시 TTL 1시간 — 판정 유지, 단 과장됨

**보고서 주장**: 키 유출 시 1시간 동안 침해 창 존재.

**재검증 결과**: ⚠️ **사실이나 실질 위험 과장**

```
q-sign/src/main/resources/application.yml
라인 167: jwks-cache-ttl-seconds: 3600
```

이는 사실이다. 그러나 실제 Keycloak 키 교체 시나리오에서 JWKS는 새 키가 추가되는 방식으로 교체되므로(키 삭제가 아닌 키 추가), 1시간 TTL이 반드시 "기존 토큰 유효" 문제를 야기하지는 않는다. 진짜 위험은 **키가 유출된 후 즉시 폐기할 수 없다**는 것이며, 이는 심각도 Medium이 아닌 Low-Medium이 더 적절하다.

또한 캐시 강제 갱신 API나 키 교체 이벤트 Hook이 없다는 점은 보고서가 놓쳤다.

**재판정**: Medium → **Low-Medium (과장됨)**

---

### [L-A] OWASP `|| true` 우회 — 판정 부정확

**보고서 주장**: CVE 발견 시에도 CI 빌드가 성공으로 처리된다.

**재검증 결과**: ⚠️ **사실이나 맥락 오해**

```
.github/workflows/ci.yml
라인 184: || true  # CVSS 임계값 초과해도 워크플로우 계속 (리포트 업로드 목적)
라인 184 주석: "리포트 업로드 목적"
```

`|| true`의 목적은 **취약점 발견 시에도 리포트를 반드시 업로드**하기 위함이다. 이어서:

```
라인 195-200: SARIF 결과를 GitHub Security에 업로드
라인 473-482: Trivy 보안 스캔 (Docker 이미지)
  exit-code: '0'  # CRITICAL 취약점도 빌드 중단 안함 (리포트만)
  continue-on-error: true
```

OWASP, Trivy 모두 `exit-code=0` 또는 `|| true`로 빌드를 계속한다. 보고서의 지적은 사실이지만, 이것이 **의도적 설계 선택**인지 **실수**인지를 판단하지 않았다. 실제로 이 패턴은 "스캔 결과를 수집하되 빌드는 차단하지 않는" 정책으로, Trivy 주석에도 명시되어 있다.

**진짜 문제**: 보안 스캔이 빌드 게이트로 작동하지 않는다는 점 자체는 유효한 지적이다. 그러나 이것이 "CVE 발견 시 무시"가 아니라 "GitHub Security Advisories에 업로드하여 별도 추적"하는 구조다. CI 게이트 vs. 별도 추적 정책 중 어느 것이 맞는지는 팀 정책 결정 사항이다.

**재판정**: Low~Medium 유지, 단 맥락 설명 부족

---

### [C-A] Webhook/PolicyEngine Secret 기본값 — 판정 유지

**보고서 주장**: 두 곳에 PoC Secret 기본값 하드코딩.

**재검증 결과**: ✅ **정확**

```
application.yml 라인 325:
  signing-secret: ${IDO_WEBHOOK_SIGNING_SECRET:poc-webhook-secret-change-in-production}

PolicyEngineImpl.java 라인 44:
  @Value("${ido.agency-subject-secret:default-poc-secret-change-in-production}")
```

두 값 모두 확인됨. 특히 `agencySubjectId`는 기관 간 이동 시 사용자를 식별하는 핵심 식별자인데, 이 HMAC 키가 예측 가능하면 기관 간 사용자 위장이 이론적으로 가능하다. 보고서의 심각도 High 판정은 적절하다.

---

### [C-B] Git 이력 PoC Secret 잔존 — 판정 유지

**보고서 주장**: V8 마이그레이션 주석에 원문 API Key 기록.

**재검증 결과**: ✅ **정확, 단 심각도는 조정 필요**

```
V8__seed_agency_api_key_and_fix_webhook.sql
라인 11: -- rawApiKey  = "stub-api-key-dev-001"
라인 13:            = 8a5ad1ec5a18b326ed9ae616c9883e46bede28ed5b84d2912bb70263433749df
라인 15: -- rawWebhookSecret = "poc-webhook-secret-change-in-production"
```

원문 API Key와 그 SHA-256 해시 모두 주석에 있다. 이 정보가 소스 코드 접근 권한자에게 노출된다는 점은 보안 관행 위반이다.

단, 이것이 **PoC/개발용 시드 데이터**이고 실제 운영에서는 교체될 값이라는 점에서, 보고서의 "High" 등급은 과장될 수 있다. 운영 키가 아닌 PoC 키의 Git 잔존은 **Medium**이 더 적절하다. 운영 전 Git 이력 정리(`git filter-branch` 또는 BFG Repo Cleaner)가 필요하나 긴급도는 낮다.

**재판정**: High → **Medium (PoC 키이므로 운영 영향 없음, 단 관행 위반)**

---

## 2. 보고서가 놓친 실제 결함 — 신규 발견 4건

---

### [신규 X-1] Keycloak 모드 identifierHash 영구 사용 — 보고서 미포함

**심각도**: **High (C-01과 동급)**

위 1절에서 상술했다. 핵심만 재정리:

```
KeycloakOidcService.java 라인 144:
  feSessionService.create(
    identifierHash,  // 소셜 로그인 경로: CI 없음 → identifierHash 사용
    ...
  )
```

- Keycloak 모드(`broker.mode=keycloak`)는 운영 예정 모드
- 이 경로에서 `qimUserId` 대신 `identifierHash(SHA-256(sub))`가 FE 세션의 사용자 식별자가 됨
- Q-IM 팀 협의가 완료되지 않은 상태에서 운영 배포 시, SSO 핸드오프에서 agencySubjectId가 잘못 계산될 수 있음
- 보고서가 "C-01 해결됨"으로 처리했으나 Keycloak 경로는 여전히 동일 문제

---

### [신규 X-2] CiCheckResponse — S7-T6 구현 불완전 (항상 `true` 반환)

**심각도**: **Medium**

```
파일: ido/src/main/java/kr/go/smes/ido/auth/dto/CiCheckResponse.java
라인 62-63:
  // TODO(S7-T6): IM API 연동 후 실제 CI 매칭 로직 구현 필요.
  // 현재는 파라미터 검증 후 성공(true)만 반환.
```

실제 AuthService를 확인하면 현재 Q-IM `findByCi()` 호출은 구현되어 있다(라인 405). 그러나 DTO 주석은 아직 갱신되지 않아 혼란을 준다. DTO와 실제 구현 간의 불일치는 유지보수 혼란과 잘못된 테스트로 이어질 수 있다.

더 중요한 것은 `AuthService.handleOacxEasysign()`:

```
라인 230: TODO(S7-T6): IM API 연동 후 복호화된 CI를 IM API에 등록하는 로직 추가.
```

그러나 라인 292-310을 보면 이미 구현되어 있다:
```java
// S7-T6: CI → Q-IM 등록 (Q3=B: CI는 FE 미반환, Q-IM에만 전달)
if (ciForInternalUse != null && !ciForInternalUse.isBlank()) {
    QimRegisterResponse registerResult = imApiOutPort.register(authResult, correlationId);
```

**실제 문제**: 구현은 완료됐으나 TODO 주석이 제거되지 않았다. 코드와 주석이 불일치하여 다음 개발자가 혼란을 겪을 수 있다. 이것이 보고서가 이 항목을 신뢰도 있게 평가할 수 없었던 이유이기도 하다.

---

### [신규 X-3] PKCE `plain` 방식 허용 — 보안 약화

**심각도**: **Low-Medium**

```
파일: q-sign/src/main/java/kr/go/smes/qsign/api/OidcDiscoveryController.java
라인 141: metadata.put("code_challenge_methods_supported", List.of("plain", "S256"));

파일: q-sign/src/main/java/kr/go/smes/qsign/pkce/PkceService.java
라인 111: // plain 방식은 보안상 권장하지 않음 (RFC 7636 §4.2)
라인 158: // plain: code_verifier == code_challenge
```

Discovery 문서에 `plain`을 지원 method로 공표하고, 실제로 `plain`으로 저장된 challenge도 처리한다. RFC 7636은 `plain`을 `S256`를 지원할 수 없는 경우에만 허용하도록 권고한다. 브라우저 환경에서 `S256`을 지원하지 못할 이유가 없으므로, `plain` 지원은 의도치 않게 약한 PKCE를 허용하는 셈이다.

보고서는 "PKCE 완전 구현"으로 평가했으나, `plain` 허용은 불완전한 구현이다.

---

### [신규 X-4] Rate Limit fail-open — Redis 장애 시 무제한 허용

**심각도**: **Medium**

```
파일: ido/src/main/java/kr/go/smes/ido/ratelimit/AgencyRateLimiter.java
라인 152-154:
  log.error("Redis 오류 — 허용 처리 (fail-open): agencyCode={}")
  return true; // fail-open: Redis 장애 시 허용

파일: ido/src/main/java/kr/go/smes/ido/ratelimit/AuthRateLimitInterceptor.java
라인 164-165:
  log.error("Redis 오류 — fail-open 허용: key={}")
  return 0; // fail-open: 장애 시 허용
```

두 RateLimiter가 모두 fail-open이다. Redis 장애(네트워크 단절, OOM) 시 모든 기관의 Handoff Ticket 발급이 무제한으로 허용된다. 공격자가 Redis를 의도적으로 과부하시키면 Rate Limit를 우회할 수 있다.

보고서가 RateLimiter를 오진(JVM 단일 문제)한 결과로 이 실제 위험을 놓쳤다.

---

## 3. 보고서의 구조적 문제

### 3-1. 증거 없는 단정

보고서 섹션 3.1(신규 C-A)에서:

> "AgencySubjectId 역산 가능"

이 주장은 HMAC이 단방향 함수임을 무시한 과장이다. 기본값 `default-poc-secret-change-in-production`을 알고 있어도, agencySubjectId(= HMAC(qimUserId|agencyCode))로부터 qimUserId를 역산하는 것은 계산상 불가능하다. 위험은 "위조(forge)"가 아니라 "동일 입력이 동일 출력을 낳는다"는 예측 가능성이다.

정확한 위험 기술: "동일 qimUserId + agencyCode가 항상 동일 agencySubjectId를 생성하므로, PoC 환경에서 실제 사용된 agencySubjectId 값을 관찰하면 기관 간 사용자 추적이 가능하다." — 역산이 아닌 Cross-agency 사용자 추적이 진짜 위험이다.

### 3-2. 버전 혼용 추정의 불완전함

보고서 5.1절:

> "이전 보고서는 수정 전 버전을 대상으로 작성된 것으로 추정됨"

이것은 추정이고 검증되지 않았다. 이전 보고서가 동일 코드베이스를 잘못 분석했을 가능성도 동일하게 존재한다. 보고서는 두 가능성 중 자신에게 유리한 해석만 채택했다.

### 3-3. 운영 준비도 종합 표의 과도한 낙관

보고서 섹션 7의 종합 표:

| 평가 영역 | 상태 |
|----------|------|
| 핵심 보안 기능 (PKCE, CI 암호화, Handoff 암호화) | ✅ 완전 구현 |
| 사용자 식별 체계 (qimUserId 연동) | ✅ 완전 구현 |

**PKCE**: plain 허용으로 "완전 구현" 아님 → ⚠️ 부분 구현
**사용자 식별 체계**: Keycloak 모드에서 identifierHash 사용 중 → ⚠️ 부분 구현

---

## 4. 재검토 후 최종 액션 아이템 (우선순위 조정)

### 배포 차단 (변경 없음)

| # | 항목 | 이유 |
|---|------|------|
| 1 | 암호화 키 환경 변수 미주입 | 기본값 0바이트 = 암호화 무의미 |
| 2 | Webhook/PolicyEngine Secret 환경 변수 미주입 | HMAC 서명 예측 가능 |
| 3 | DB 비밀번호 환경 변수 미주입 | PoC 기본값 사용 |

### 신규 추가 — 배포 전 처리 권고

| # | 항목 | 실제 위험 |
|---|------|-----------|
| 4 | Keycloak 모드 identifierHash 사용 | qimUserId 연동 미완 (SSO 핵심 결함) |
| 5 | PKCE plain 방식 지원 제거 | 약한 PKCE 허용 |
| 6 | Rate Limit fail-open → fail-secure 전환 검토 | Redis 장애 시 무제한 허용 |

### 단기 (배포 후 1주)

| # | 항목 | 보고서 평가 |
|---|------|-------------|
| 7 | non-strict 코드 제거 | 보고서 정확 |
| 8 | OWASP || true 제거 또는 정책 명문화 | 보고서 정확 (단 맥락 오해) |
| 9 | 완료된 TODO 주석 정리 | 보고서 미포함 |
| 10 | Git 이력 PoC Secret 제거 | 보고서 과장, 실제는 Medium |

### 중기 (배포 후 1개월)

| # | 항목 | 보고서 평가 |
|---|------|-------------|
| 11 | JWKS 캐시 TTL 단축 (300s) | 보고서 과장됨, 실제 Low-Medium |
| 12 | KMS 연동 | 보고서 정확 |

---

## 5. 최종 판정

### 보고서에 대한 평가

**전반적으로 신뢰도 있는 보고서**이나 두 가지 명확한 오류가 있다:

1. **RateLimiter 오진**: ConcurrentHashMap이라고 했으나 실제는 Redis Lua 스크립트. 이 오류로 인해 진짜 위험(fail-open)을 놓쳤다.
2. **C-01(qimUserId) 조기 종결**: qsign 모드만 검증하고 keycloak 모드를 검토하지 않았다. Keycloak 경로에서 identifierHash가 여전히 사용 중이다.

이 두 오류는 단순 실수가 아니라 **분석 범위 제한**에서 비롯된 것이다. 보고서가 특정 파일만 확인하고 동일 기능의 대체 경로를 검토하지 않은 결과다.

### 현재 코드베이스 운영 준비도 (재평가)

보고서의 "스테이징 배포 가능" 판정은 유지한다. 단 조건을 엄격화:

> 암호화 키 + Webhook Secret + DB 비밀번호 환경 변수 주입 **그리고** Keycloak 모드 identifierHash 문제 해결 후에야 스테이징 배포 적합.

PKCE plain 허용과 Rate Limit fail-open은 배포 후 단기 해결 가능하므로 배포 차단 조건으로 분류하지 않는다.
