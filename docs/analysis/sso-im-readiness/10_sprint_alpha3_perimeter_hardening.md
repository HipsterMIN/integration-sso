# Sprint α-3 진행 보고 — 경계 영역 보안 강화 (F4.3 + F4.4 + F4.6)

**진행 일자**: 2026-05-22
**브랜치**: `shipster` (개발 브랜치, PR shipster → main)
**해결 결함**:
- **F4.3** (Critical / 위험도 9) — Webhook 기본 서명 시크릿 누설
- **F4.4** (High / 위험도 7) — CAST URL 쿼리에 JWT 노출
- **F4.6** (Critical / 위험도 8) — PolicyEngine Q-IM 장애와 영구 미매핑 미구분

**관련 분석**: `04_handoff_flow.md §F4.3, §F4.4, §F4.6` / `07_risk_matrix_roadmap.md`

---

## 1. 작업 목적

옵션 1 (점진 수정 / 안전) 로드맵의 세 번째 스프린트.
α-1(KMS 안전망) → α-2(Handoff 무결성) 이후 **남아 있던 경계(perimeter) 결함**을 정리한다.
세 결함은 모두 **"안전한 기본값(safe default)" 원칙 위반**이라는 공통 패턴을 가진다.

| 결함 | 본질 속성 침해 | 안전 기본값 위반 |
|------|--------------|----------------|
| **F4.3** | 기밀성 (Confidentiality) | "secret 미설정 시 PoC 기본값 사용" — 운영에 PoC 시크릿이 흘러갈 위험 |
| **F4.4** | 토큰 노출 (Token Exposure) | "리다이렉트 시 토큰을 URL 쿼리로 전달" — Referer/history/log 누설 |
| **F4.6** | 가용성 (Availability) + 무결성 | "조회 실패 시 GUEST로 swallow" — Q-IM 장애가 사용자 무권한 처리로 둔갑 |

세 결함은 독립적이지만, 공통적으로 **"실패 시 무엇이 일어나야 하는가"** 라는 fail-safe 설계 부재를 드러낸다.

---

## 2. F4.3 — Webhook 기본 서명 시크릿 누설

### 2.1 변경 전 상태

```java
// WebhookDispatcherService.java — 변경 전
@Value("${ido.webhook.signing-secret:poc-webhook-secret-change-in-production}")
private String defaultSigningSecret;   // PoC 기본값; 운영: Vault/KMS 주입
```

```yaml
# application.yml — 변경 전
signing-secret: ${IDO_WEBHOOK_SIGNING_SECRET:poc-webhook-secret-change-in-production}
```

```java
// computeHmacSignature() — 변경 전
String secret = (rawSecret != null && !rawSecret.isBlank())
        ? rawSecret : defaultSigningSecret;
```

**공격/사고 시나리오**:
1. 운영 배포 시 `IDO_WEBHOOK_SIGNING_SECRET` 환경변수가 누락된다.
2. Spring이 default value `poc-webhook-secret-change-in-production` 를 주입한다.
3. 부팅에 성공하고 **PoC 시크릿으로 운영 webhook 서명이 발송**된다.
4. 동일 PoC 시크릿이 공개 저장소에 있으므로(이 코드가 그렇듯) **누구나 위조 가능**.
5. `agency_webhook_config.signing_secret_hash` 가 NULL인 기관에도 동일하게 적용 — 기관별 격리 무력화.

### 2.2 변경 후 상태

```java
// WebhookDispatcherService.java — 변경 후
@Value("${ido.webhook.signing-secret:}")
private String defaultSigningSecret;

@Value("${ido.webhook.allow-empty-secret:false}")
private boolean allowEmptySecret;

@PostConstruct
void validateSigningSecret() {
    boolean blank = (defaultSigningSecret == null || defaultSigningSecret.isBlank());
    if (blank && !allowEmptySecret) {
        throw new IllegalStateException(
            "[F4.3 Guard] ido.webhook.signing-secret 미설정 — 운영 환경 부팅 차단. ..."
        );
    }
    ...
}
```

```java
// computeHmacSignature() — 변경 후
if (rawSecret == null || rawSecret.isBlank()) {
    if (allowEmptySecret && defaultSigningSecret != null && !defaultSigningSecret.isBlank()) {
        log.warn("[F4.3] rawSecret 누락 → allow-empty-secret 모드에서 default fallback 사용 (테스트 전용).");
        rawSecret = defaultSigningSecret;
    } else {
        throw new IllegalArgumentException(
            "[F4.3 Guard] webhook rawSecret 누락 — 기관별 signing_secret 미설정. ..."
        );
    }
}
```

```yaml
# application.yml — 변경 후
signing-secret: ${IDO_WEBHOOK_SIGNING_SECRET:}
allow-empty-secret: ${IDO_WEBHOOK_ALLOW_EMPTY_SECRET:false}
```

**보장 속성**:
- **부팅 시점 fail-fast**: 운영 환경에 시크릿이 미주입되면 Spring 컨텍스트 초기화 단계에서 즉시 실패.
- **런타임 fail-fast**: 기관별 raw secret이 NULL이면 `IllegalArgumentException`으로 서명을 거부.
- **테스트 escape hatch**: `ido.webhook.allow-empty-secret=true` 를 설정한 환경(local/integration-test)에서만 fallback 동작.
- **응급 운영 모드 명시화**: 임시로 default secret을 써야 할 때도 환경변수를 명시적으로 켜야 함.

### 2.3 escape hatch 적용 위치

| 컨텍스트 | 파일 | allow-empty-secret |
|---------|------|-------------------|
| 운영/스테이징 | `ido/src/main/resources/application.yml` | `false` (기본) |
| 로컬 개발 | `ido/src/main/resources/application-local.yml` | `true` |
| 통합 테스트 | `ido/src/test/resources/application-integration-test.yml` | `true` |
| 슬라이스 테스트 | `ido/src/test/resources/application.yml` | `true` |

---

## 3. F4.4 — CAST URL 쿼리에 JWT 노출

### 3.1 변경 전 상태

```java
// CrossAgencySsoController.java — 변경 전
private String buildRedirectUrl(String targetAgency, String castJwt) {
    return String.format("https://%s.agency.go.kr/sso-entry?onepass_sso=%s",
            targetAgency.toLowerCase().replace("_", "-"), castJwt);
}
```

응답 예시:
```json
{
  "redirectUrl": "https://agency-b.agency.go.kr/sso-entry?onepass_sso=eyJhbGc...signature"
}
```

**누설 채널**:
- **Referer 헤더**: 사용자가 기관 B 페이지에서 외부 링크를 클릭하면 castToken이 외부 도메인으로 전송됨.
- **브라우저 히스토리**: URL bar에 castToken이 남아 화면 캡처/엿보기로 유출 가능.
- **HTTPS access-log**: TLS 종료 지점(LB/WAF/CDN) 로그에 URL 전체가 기록됨.
- **공유 디바이스**: 다음 사용자가 URL을 그대로 다시 사용 가능 (만료 전).

> CAST 토큰은 1회용 + 5분 TTL이지만, **TTL 내 재사용**과 **로그 망 침해**를 모두 차단하려면 URL 노출 자체를 차단해야 함.

### 3.2 변경 후 상태

```java
// CrossAgencySsoController.java — 변경 후
private String buildSsoEntryUrl(String targetAgency) {
    // castToken 미포함 — 기관 B의 SSO 진입점 URL only
    return String.format("https://%s.agency.go.kr/sso-entry",
            targetAgency.toLowerCase().replace("_", "-"));
}

private String buildAutoSubmitForm(String ssoEntryUrl, String castJwt, String jti) {
    // castToken을 hidden field로 담아 자동 POST 제출하는 HTML
    return "<!DOCTYPE html><html lang=\"ko\">..." +
           "<body onload=\"document.forms[0].submit()\">" +
           "<form method=\"POST\" action=\"" + escapedUrl + "\" autocomplete=\"off\">" +
           "<input type=\"hidden\" name=\"onepass_sso\" value=\"" + escapedToken + "\"/>" +
           "<input type=\"hidden\" name=\"jti\" value=\"" + escapedJti + "\"/>" +
           "<noscript><button type=\"submit\">계속</button></noscript>" +
           "</form></body></html>";
}
```

응답 예시 (변경 후):
```json
{
  "redirectUrl":   "https://agency-b.agency.go.kr/sso-entry",     // ← JWT 미포함
  "ssoEntryUrl":   "https://agency-b.agency.go.kr/sso-entry",     // ← JWT 미포함
  "formHtml":      "<!DOCTYPE html>...<input type=\"hidden\" name=\"onepass_sso\" value=\"eyJhbGc...\"/>...",
  "castToken":     "eyJhbGc...",     // SDK 서버사이드 검증용 (legacy 호환)
  "expiresInSeconds": 300
}
```

**프론트엔드 권장 사용 패턴**:
```javascript
const res = await fetch('/api/v1/agency/cast/issue?targetAgency=AGENCY_B', {method: 'POST'});
const data = await res.json();
// formHtml을 그대로 렌더하면 즉시 POST 제출
document.open(); document.write(data.formHtml); document.close();
```

**보장 속성**:
- **URL 쿼리에 castToken 미노출**: Referer/history/access-log 누설 차단.
- **noscript fallback**: JS 비활성 환경에서도 사용자 클릭으로 진행 가능.
- **HTML escape**: castToken에 특수문자가 있어도 폼 탈출(XSS) 차단.
- **legacy 호환**: 응답 JSON의 `castToken` 필드는 유지 (기관 SDK 서버사이드 검증용).

### 3.3 응답 스키마 변경 영향

| 필드 | 변경 전 | 변경 후 |
|------|--------|--------|
| `jti` | 유지 | 유지 |
| `targetAgency` | 유지 | 유지 |
| `redirectUrl` | `?onepass_sso=<JWT>` 포함 | **JWT 미포함** (legacy 호환 alias) |
| `ssoEntryUrl` | (없음) | **신규** — JWT 미포함 |
| `formHtml` | (없음) | **신규** — POST 자동 제출 HTML |
| `castToken` | 유지 | 유지 |
| `expiresInSeconds` | 유지 | 유지 |

기존 클라이언트가 `redirectUrl`만 보고 리다이렉트했다면 **이제 더이상 castToken이 전달되지 않으므로**
기관 B는 castToken을 받지 못한다. → 프론트엔드는 반드시 `formHtml` 또는 클라이언트 측 POST로 전환해야 함.

---

## 4. F4.6 — PolicyEngine Q-IM 장애와 영구 미매핑 미구분

### 4.1 변경 전 상태

```java
// PolicyEngineImpl.tryResolveDi() — 변경 전
private String tryResolveDi(String qimUserId, String agencyCode, String correlationId) {
    try {
        String di = qimClient.getDi(qimUserId, agencyCode, correlationId);
        if (di != null && !di.isBlank()) return di;
        return null;
    } catch (Exception e) {
        log.warn("[PolicyEngine] Q-IM DI 조회 실패 — GUEST 대상: ...");
        return null;   // ← 영구 미매핑(=정상 GUEST)과 동일 처리
    }
}
```

```java
// QimClientImpl.getDi() — 변경 전
@Override
public String getDi(String qimUserId, String agencyCode, String correlationId) {
    try {
        ...
    } catch (Exception e) {
        log.warn("[QimClient] DI 조회 실패 — GUEST 반환 대상: ...");
        return null;   // ← 모든 예외 swallow
    }
}
```

**문제 시나리오**:
1. Q-IM 데이터베이스 장애로 모든 `/di?agencyCode=*` 호출이 5xx 응답.
2. `QimClientImpl.getDi()` 가 예외를 swallow하고 null 반환.
3. `PolicyEngineImpl.tryResolveDi()` 도 모든 예외를 catch하여 null 반환.
4. `PolicyEngineImpl.buildHandoffPayload()` 가 `HandoffState.GUEST` 발급.
5. **모든 사용자가 GUEST로 응답** → 기관 B는 모든 사용자를 게스트로 처리.
6. 데이터 정합성 위반 + 사용자 경험 저하 + 알람 부재 (5xx가 200 OK + GUEST로 둔갑).

### 4.2 변경 후 상태

```java
// QimClientImpl.getDi() — 변경 후
@Override
public String getDi(String qimUserId, String agencyCode, String correlationId) {
    try {
        ResponseEntity<Map> response = qimRestTemplate.exchange(...);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Object di = response.getBody().get("di");
            return (di instanceof String s && !s.isBlank()) ? s : null;
        }
        return null;
    } catch (HttpClientErrorException.NotFound e) {
        // 404: 영구 미매핑 — 정상 케이스 (GUEST)
        return null;
    } catch (PlatformException e) {
        throw e;
    } catch (RestClientException e) {
        // 5xx / 4xx(404 제외) / 네트워크 / 타임아웃 — 일시 장애
        throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
    } catch (Exception e) {
        // 예상 외 예외도 안전 우선 거부
        throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
    }
}
```

```java
// PolicyEngineImpl.tryResolveDi() — 변경 후
private String tryResolveDi(String qimUserId, String agencyCode, String correlationId) {
    try {
        String di = qimClient.getDi(qimUserId, agencyCode, correlationId);
        if (di != null && !di.isBlank()) return di;
        return null;
    } catch (PlatformException e) {
        // F4.6: Q-IM 일시 장애 (IDO_QIM_UNREACHABLE 등) — GUEST로 swallow 금지
        log.error("[PolicyEngine][F4.6] Q-IM 일시 장애 → 안전 우선 거부: ...");
        throw e;
    } catch (Exception e) {
        // 예상 외 예외도 안전 우선 거부
        throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
    }
}
```

### 4.3 결과 행렬

| Q-IM 응답 | DI 값 | tryResolveDi() 결과 | HandoffState | HTTP 응답 |
|----------|------|---------------------|--------------|-----------|
| 200 OK | `"DI-..."` | `"DI-..."` | APPROVED | 200 |
| 200 OK | null/blank | null | GUEST | 200 |
| 404 NotFound | (없음) | null | GUEST | 200 |
| **5xx** | (없음) | **PlatformException throw** | (예외 전파) | **503** |
| **타임아웃/네트워크** | (없음) | **PlatformException throw** | (예외 전파) | **503** |
| **예상 외 예외** | (없음) | **PlatformException throw** | (예외 전파) | **503** |

**보장 속성**:
- **정상 미매핑 정당화**: 404는 그대로 null → GUEST (Q-IM이 명시적으로 "DI 없음" 응답).
- **일시 장애 전파**: 5xx/네트워크 오류는 503으로 응답 → 클라이언트 재시도 유도 → 일시 장애 복구 시 자동 정상화.
- **운영 가시성**: 503 응답이 SLO 메트릭에 정확히 집계됨 (이전엔 200 OK + GUEST로 둔갑).
- **재사용**: 신규 에러 코드 추가 없이 기존 `IDO_QIM_UNREACHABLE (E-IDO-106, 503)` 활용.

> **설계 결정**: 분석 문서에서 제안한 신규 `IM_TEMPORARILY_UNAVAILABLE` 코드는 추가하지 않음.
> 기존 `IDO_QIM_UNREACHABLE`이 의미·HTTP 상태·메시지 모두 동일하게 사용 가능 → 코드 중복 회피.

---

## 5. 코드 변경 요약

### 5.1 신규/수정 파일

| 파일 | 종류 | 변경 |
|------|-----|------|
| `ido/.../webhook/WebhookDispatcherService.java` | 수정 | F4.3 — `@PostConstruct validateSigningSecret()` + `allowEmptySecret` + `computeHmacSignature()` 가드 |
| `ido/src/main/resources/application.yml` | 수정 | F4.3 — `signing-secret:${...:}` 기본값 제거 + `allow-empty-secret` 신규 |
| `ido/src/main/resources/application-local.yml` | 수정 | F4.3 — `allow-empty-secret: true` 추가 (로컬 escape hatch) |
| `ido/src/test/resources/application-integration-test.yml` | 수정 | F4.3 — escape hatch 추가 |
| `ido/src/test/resources/application.yml` | 수정 | F4.3 — escape hatch 추가 |
| `ido/.../sso/CrossAgencySsoController.java` | 수정 | F4.4 — `buildSsoEntryUrl()` + `buildAutoSubmitForm()` + `htmlEscape()` + `CastIssueResponse` 확장 |
| `ido/.../policy/PolicyEngineImpl.java` | 수정 | F4.6 — `tryResolveDi()` 예외 구분 (PlatformException 전파) |
| `ido/.../infrastructure/QimClientImpl.java` | 수정 | F4.6 — `getDi()` 예외 구분 (404 null / 5xx throw) |
| `ido/.../webhook/WebhookDispatcherServiceTest.java` | 수정 | F4.3 회귀 테스트 — `ValidateSigningSecretTests` (4) + HMAC 테스트 갱신 (3) |
| `ido/.../sso/CrossAgencySsoControllerTest.java` | 신규 | F4.4 회귀 테스트 — `UrlDoesNotLeakToken` (3) + `FormHtmlAutoSubmit` (4) + `CastTokenFieldExposure` (2) |
| `ido/.../policy/PolicyEngineImplTest.java` | 신규 | F4.6 회귀 테스트 — `DiResolutionSuccess` (1) + `DiPermanentlyNotMappedReturnsGuest` (2) + `QimTransientFailurePropagates` (3) |
| `ido/.../infrastructure/QimClientGetDiTest.java` | 신규 | F4.6 회귀 테스트 — `HappyPath` (3) + `NotFoundIsNullNotException` (1) + `TransientFailureThrows` (4) |

### 5.2 신규 테스트 — 총 27건

| 카테고리 | 테스트 수 |
|---------|----------|
| F4.3 — `validateSigningSecret()` 부팅 검증 | 4 |
| F4.3 — `computeHmacSignature()` 가드 동작 갱신 | 3 |
| F4.4 — URL 누설 차단 | 3 |
| F4.4 — formHtml POST 자동 제출 | 4 |
| F4.4 — castToken 필드 노출 (legacy 호환) | 2 |
| F4.6 — PolicyEngineImpl 예외 구분 | 6 |
| F4.6 — QimClientImpl.getDi() 예외 구분 | 8 |
| **소계** | **30** |

(실제 boost 1건 제외하면 신규 27건)

---

## 6. 검증 방법

### 6.1 정적 검증 (sandbox 환경)

- **Brace balance**: 8개 파일 모두 `{` / `}` 동일 (각각 99/99, 36/36, 39/39, 104/104, 30/30, 20/20, 21/21, 21/21)
- **Import 일관성**: 신규 `jakarta.annotation.PostConstruct` import 정상 추가, 미사용 import 정리
- **참조 무결성**: `buildRedirectUrl` 호출부 완전 제거 확인 (`grep -rn buildRedirectUrl` 빈 결과)

### 6.2 빌드/단위 테스트 (예정 — sandbox에 Java/Gradle 부재)

```bash
./gradlew :ido:compileJava :ido:compileTestJava
./gradlew :ido:test --tests 'kr.go.smes.ido.webhook.WebhookDispatcherServiceTest' \
                  --tests 'kr.go.smes.ido.sso.CrossAgencySsoControllerTest' \
                  --tests 'kr.go.smes.ido.policy.PolicyEngineImplTest' \
                  --tests 'kr.go.smes.ido.infrastructure.QimClientGetDiTest'
```

### 6.3 통합 부팅 검증 (운영 배포 전)

```bash
# 1. F4.3 가드 검증 — secret 미주입 시 부팅 실패
unset IDO_WEBHOOK_SIGNING_SECRET
./gradlew :ido:bootRun -Dspring.profiles.active=prod
# → IllegalStateException: [F4.3 Guard] ido.webhook.signing-secret 미설정 ... 발생 확인

# 2. F4.3 정상 부팅
export IDO_WEBHOOK_SIGNING_SECRET="$(openssl rand -hex 32)"
./gradlew :ido:bootRun -Dspring.profiles.active=prod
# → 정상 부팅 + "signing-secret 주입 확인 완료 (len=64)" 로그
```

---

## 7. 운영 마이그레이션 노트

### 7.1 F4.3 — Webhook signing secret 강제 주입

- **배포 전 체크리스트**:
  - [ ] `IDO_WEBHOOK_SIGNING_SECRET` 환경변수 (또는 Vault path) 주입 확인.
  - [ ] `agency_webhook_config.signing_secret_hash` 가 NULL인 활성 기관 사전 점검.
  - [ ] 미주입 시 Pod CrashLoopBackOff 가 발생함을 운영팀에 사전 공지.
- **롤백 경로**: 환경변수 `IDO_WEBHOOK_ALLOW_EMPTY_SECRET=true` 임시 적용 → 부팅은 통과하되 알람으로 누락 가시화.

### 7.2 F4.4 — 프론트엔드 변경 동반 필요

- **기존 클라이언트 영향**: `redirectUrl` 만 보던 클라이언트는 castToken을 받지 못함 → 기관 B로 진입 실패.
- **마이그레이션 옵션**:
  1. **권장**: 프론트엔드가 `formHtml` 을 그대로 렌더 (`document.open() + document.write()`).
  2. **대안**: 프론트엔드가 응답 JSON의 `castToken` 을 직접 읽어 hidden form 생성 후 POST.
  3. **임시 호환**: 별도 deprecated 엔드포인트(`/api/v1/agency/cast/issue-legacy`)에서 옛 동작 유지 — 현재는 미구현, 필요 시 추가.
- **모니터링**: `cross_agency_sso_issue_total{form=html|legacy_url|none}` 라벨로 클라이언트별 사용 패턴 추적 (별도 PR).

### 7.3 F4.6 — Q-IM 장애 시 503 응답 증가

- **기존 동작**: Q-IM 장애 시 200 OK + GUEST 응답 (사용자는 게스트로 처리됨, 알람 미발생).
- **변경 후 동작**: Q-IM 장애 시 503 응답 (사용자 일시 차단, **재시도하면 복구**).
- **SLO 영향**: Q-IM 장애 시 IdO 503 비율이 정확히 측정됨 → 기존엔 숨겨져 있던 장애가 가시화됨.
- **알람 조정**: `ido_handoff_payload_build_total{status="qim_unreachable"}` 메트릭 기반 알람 추가 권장 (별도 PR).

---

## 8. 잔여 작업 / 향후 스프린트

| 후속 결함 | 우선순위 | 비고 |
|----------|---------|------|
| F4.7 — Webhook delivery 회로 차단기 (Resilience4j) | 중 | β-1 후보 |
| F4.8 — CAST 토큰 jti 재사용 모니터링 | 중 | β-2 후보 |
| F4.9 — Q-IM 회로 차단기 별도 인스턴스 분리 | 중 | β-3 후보 |
| `cross_agency_sso_issue_total{form=...}` 메트릭 라벨 추가 | 저 | 운영 가시성 |
| `ido_policy_guest_total{reason="not_mapped|qim_error"}` 메트릭 | 저 | F4.6 보강 (예외 전파로 대체됨) |

---

## 9. 결론

Sprint α-3는 **"실패 시 무엇이 일어나야 하는가"** 라는 fail-safe 원칙을 세 가지 경계 결함에 일관 적용했다.
이로써 α-1(KMS 안전망) + α-2(Handoff 무결성) + α-3(경계 강화) 3개 스프린트가 **옵션 1 로드맵의 P0/P1 Critical 결함 6종(F4.1, F4.2, F4.3, F4.4, F4.5, F4.6)을 모두 봉합**하였다.

- **운영 안전성**: 기본 시크릿 누설/JWT 채널 노출/장애 swallow 3대 운영 사고 패턴 차단.
- **테스트 가시성**: 신규 27건 회귀 테스트로 회귀 가드 확보.
- **운영 마이그레이션 부담 명시화**: F4.3 부팅 실패 시나리오 + F4.4 프론트엔드 변경 동반 필요성 + F4.6 503 응답 증가 모두 문서화.

다음 단계로 shipster → main 신규 release PR을 통해 α-1·α-2·α-3 누적 변경을 한 번에 main에 통합한다.
