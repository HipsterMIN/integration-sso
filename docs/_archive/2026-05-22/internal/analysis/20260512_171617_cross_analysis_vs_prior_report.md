# OnePass 플랫폼 — 독립 심층 분석 vs. 이전 보고서 교차 검증 보고서

- **작성일**: 2026-05-12
- **대상 코드베이스**: v2.3.0 (Sprint 10, 브랜치: `genspark_ai_developer`)
- **비교 대상 문서**: `20260511_153022_451_production_readiness_analysis.md` (v2.0, 2026-05-11 작성)
- **분석 방법**: 코드베이스 독립 심층 분석 → 이전 보고서 열람 → 항목별 교차 검증

---

## 1. 분석 방법론

본 보고서는 다음 순서로 작성되었다.

1. **독립 심층 분석 (Blind)**: 이전 보고서를 열람하지 않은 상태에서 소스 코드, 설정 파일, 인프라, CI/CD를 직접 분석
2. **이전 보고서 열람**: `20260511_153022_451_production_readiness_analysis.md` 열람
3. **항목별 교차 검증**: 이전 보고서의 각 `Critical`/`P0` 주장을 현재 코드베이스에서 직접 확인
4. **비교 결론 도출**: 일치·불일치·신규 발견 항목 분류

---

## 2. 이전 보고서 주요 주장 vs. 현재 코드베이스 실제 상태

### 2.1 [이전 보고서 C-01] qimUserId 미연동 — `OidcCompleteController`에서 `identifierHash`를 영구 식별자처럼 사용

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **Critical** — SSO/IM 시스템의 존재 이유를 부정하는 결함 |
| **현재 코드 확인 결과** | **해결됨** |

**코드 근거**:

```
파일: ido/src/main/java/kr/go/smes/ido/broker/OidcCompleteController.java
라인: 55-58 (Javadoc 변경 이력)
```

`resolveQimUserId()` 메서드(라인 195–231)는 `QimClient.findByCi()`를 통해 실제 `qimUserId`를 Q-IM에서 조회한다. CI 없는 경우에만 `identifierHash`로 폴백하며, 이 경우 `ERROR` 레벨 경고 로그를 출력한다. 코드 주석에 명시적으로 "P0 수정 (v0.8.7): identifierHash를 qimUserId 대용으로 사용하던 PoC 코드 제거"라고 기록되어 있다.

**판정**: 이전 보고서가 식별한 시점에는 존재했던 결함이나, **현재 코드베이스에서는 수정 완료**. 이전 보고서는 수정 전 버전을 대상으로 작성된 것으로 추정됨.

---

### 2.2 [이전 보고서 C-02] X-Internal-Sig 검증 우회 — `strict-mode: false`로 인증 우회 가능

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **Critical** — 내부망 침투 시 모든 사용자 위장 가능 |
| **현재 코드 확인 결과** | **부분 일치 (설계 의도적 PoC 모드)** |

**코드 근거**:

```
파일: q-sign/src/main/java/kr/go/smes/qsign/api/InternalSigVerifier.java
라인: 102-157
```

- 프로덕션 기본값: `qsign.ido.internal-sig-strict-mode: true` (서명 검증 강제)
- non-strict 모드(라인 148–157): PoC 환경에서만 사용하는 설계 의도적 옵션. 서명 불일치 시 `WARN` 로그 출력 후 통과
- IdO `InternalSigVerifier.java`(라인 87–126): 항상 HMAC-SHA256 검증 수행

**판정**: 이전 보고서의 지적 자체는 유효했으나, 현재 코드는 `strict-mode=true`가 프로덕션 기본값이다. 다만 **`strict-mode=false`가 설정 파일에 남아 있다는 사실**은 운영 배포 시 해당 값이 의도치 않게 적용될 위험이 있으므로 여전히 주의가 필요하다.

---

### 2.3 [이전 보고서 C-03] PKCE 미지원

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **Critical** — 인가 코드 가로채기 공격 방어 불가 |
| **현재 코드 확인 결과** | **해결됨 (완전 구현)** |

**코드 근거**:

```
파일: q-sign/src/main/java/kr/go/smes/qsign/pkce/PkceService.java
라인: 15-175
```

RFC 7636 S256 방식 완전 구현:
- `generateCodeVerifier()`: 64바이트 SecureRandom → Base64URL (라인 47–55)
- `generateCodeChallenge()`: SHA-256 + Base64URL (라인 69–89)
- `verifyCodeVerifier()`: Redis 조회 + 상수 시간 비교 (라인 134–175)
- PKCE 파라미터 존재 여부 확인 메서드 (`hasPkceParams()`) 포함

**판정**: 이전 보고서 작성 시점에는 미구현 상태였으나, **현재 코드베이스에서 완전 구현**.

---

### 2.4 [이전 보고서 C-03] API Key 검증 — 단순 문자열 비교 + `CHANGEME` 우회

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **Critical** — SP 연동 보안 전무 |
| **현재 코드 확인 결과** | **해결됨** |

**코드 근거**:

```
파일: ido/src/main/java/kr/go/smes/ido/qim/sp/service/QimSpReceiverService.java
라인: 283-317
```

- PBKDF2-HMAC-SHA256 검증 (`ApiKeyHashUtil.verify()`, 라인 312)
- `CHANGEME` 입력 시 즉시 `false` 반환 및 `ERROR` 로그 출력 (라인 305–309)
- 상수 시간 비교로 타이밍 어택 방어

**판정**: **현재 코드베이스에서 해결됨**.

---

### 2.5 [이전 보고서 C-04] PII 암호화 미구현 — CI 암호화 서비스 부재

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **Critical** — CI가 DB에 평문 저장 |
| **현재 코드 확인 결과** | **해결됨** |

**코드 근거**:

```
파일: q-im/src/main/java/kr/go/smes/qim/crypto/CiCryptoServiceImpl.java
라인: 54-107
```

- AES-256-GCM, 12바이트 SecureRandom IV, 128비트 인증 태그 사용
- 버전 포맷: `v{n}.{base64url(IV)}.{base64url(ciphertext+tag)}`
- 미암호화 값 감지 시 경고 로그 후 암호화 강제 (라인 85–88)
- TODO/FIXME 없음

**판정**: **현재 코드베이스에서 완전 구현**.

---

### 2.6 [이전 보고서 C-04] Handoff Ticket 암호화 TODO 상태

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **Critical** — 핸드오프 페이로드 평문 전송 |
| **현재 코드 확인 결과** | **해결됨** |

**코드 근거**:

```
파일: ido/src/main/java/kr/go/smes/ido/handoff/crypto/HandoffCryptoService.java
라인: 62-240
```

- `encrypt()`: AES-256-GCM + AAD 바인딩 (라인 62–90)
- `decrypt()`: 버전 분기 + 레거시 호환 (라인 102–113)
- `sign()`/`verify()`: HMAC-SHA256 + 상수 시간 비교 (라인 137–170)
- TODO 없음

**판정**: **현재 코드베이스에서 완전 구현**.

---

### 2.7 [이전 보고서 C-04] Attribute 필터링 미구현

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **Critical** — 외부 기관에 모든 속성 과잉 노출 |
| **현재 코드 확인 결과** | **해결됨** |

**코드 근거**:

```
파일: ido/src/main/java/kr/go/smes/ido/policy/PolicyEngineImpl.java
라인: 168-174
```

```java
private Map<String, Object> filterAttributes(Map<String, Object> rawAttributes, List<String> allowedKeys) {
    if (allowedKeys == null) return rawAttributes;     // null = 제한 없음
    if (allowedKeys.isEmpty()) return new HashMap<>(); // 빈 목록 = 전체 차단
    return rawAttributes.entrySet().stream()
        .filter(e -> allowedKeys.contains(e.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
}
```

DB 스키마: `agency_meta.allowed_attributes JSONB` 컬럼 존재 (`V1__create_schema.sql`, 라인 20).

**판정**: **현재 코드베이스에서 완전 구현**.

---

### 2.8 [이전 보고서 P0-A] DB 비밀번호 취약

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **P0** — 운영 민감 데이터 노출 |
| **현재 코드 확인 결과** | **일치 (PoC 기본값 잔존)** |

**코드 근거**:

```
파일: infra/docker/docker-compose.yml
라인 50: MARIADB_ROOT_PASSWORD: rootpass
라인 53: MARIADB_PASSWORD: qim
라인 90: POSTGRES_PASSWORD: onepass
```

PoC 기본값이며, 파일 헤더에 "운영 배포 시 외부에서 override 필수"라고 명시되어 있다. 그러나 이 값들이 K8s Secret 또는 환경 변수로 주입되지 않으면 그대로 사용될 위험이 있다.

**판정**: 이전 보고서의 지적 **유효**. PoC 맥락에서는 설계 의도적이나, 운영 전 반드시 교체 필요.

---

### 2.9 [이전 보고서 P0-A] AES/HMAC 키 기본값 (`change-me-*`, `CHANGEME_32BYTES`)

| 항목 | 내용 |
|------|------|
| **이전 보고서 판정** | **P0** — 암호화에 사용 불가한 기본값 |
| **현재 코드 확인 결과** | **일치 (여전히 잔존)** |

**코드 근거**:

```
파일: ido/src/main/resources/application.yml
라인 582: handoff-aes-key: ${IDO_HANDOFF_AES_KEY:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}
라인 583: handoff-hmac-key: ${IDO_HANDOFF_HMAC_KEY:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}
```

- 기본값이 `AAAAAA...` (Base64 전체 0바이트) = 암호화 무의미
- `QIM_CI_AES_KEY_V1` 미주입 시 동일 문제

**판정**: 이전 보고서의 지적 **유효**. 환경 변수 미주입 시 암호화가 사실상 동작하지 않는 상태.

---

## 3. 독립 분석에서 발견된 신규 항목 (이전 보고서 미포함)

이전 보고서가 식별하지 못했거나 별도로 언급하지 않은 항목들이다.

### 3.1 [신규 C-A] Webhook/PolicyEngine 하드코딩 기본 Secret

**심각도**: Critical (환경 변수 미주입 시 보안 무력화)

```
파일: ido/src/main/resources/application.yml
라인 325: signing-secret: ${IDO_WEBHOOK_SIGNING_SECRET:poc-webhook-secret-change-in-production}

파일: ido/src/main/java/kr/go/smes/ido/policy/PolicyEngineImpl.java
라인 44: @Value("${ido.agency-subject-secret:default-poc-secret-change-in-production}")
```

- Webhook HMAC-SHA256 서명이 `poc-webhook-secret-change-in-production`으로 생성됨 → 외부 기관이 webhook을 위조 가능
- AgencySubjectId HMAC이 예측 가능한 기본값 사용 → agencySubjectId 역산 가능

이전 보고서의 P0-A 항목에서 AES/HMAC 키만 언급했으나, webhook 서명 secret과 정책 엔진 HMAC 키도 동일하게 취약한 기본값을 가지고 있다.

---

### 3.2 [신규 C-B] Git 이력에 PoC Secret 잔존

**심각도**: High

```
파일: ido/src/main/resources/db/migration/V8__seed_agency_api_key_and_fix_webhook.sql
라인 9-16:
  -- rawApiKey  = "stub-api-key-dev-001"
  -- rawWebhookSecret = "poc-webhook-secret-change-in-production"
```

DB 마이그레이션 파일에 PoC 원문 키가 주석으로 기록되어 있다. 이 파일은 Git 이력에 영구 보존되므로, 코드 레포지토리 접근 권한을 가진 사람은 누구나 PoC 시스템의 API Key를 알 수 있다.

*운영 환경에서 이 값이 교체되지 않으면 의미가 없으나, 비밀 값이 소스 코드에 있다는 사실 자체가 보안 관행 위반이다.*

---

### 3.3 [신규 M-A] Rate Limiter 단일 JVM 문제

**심각도**: Medium (K8s 멀티 Pod 배포 시)

```
파일: ido/src/main/java/kr/go/smes/ido/ratelimit/AgencyRateLimiter.java
```

`ConcurrentHashMap` 기반 인메모리 카운터 사용. K8s 환경에서 Pod가 2개 이상 실행되면 각 Pod가 독립적인 카운터를 가져 실제 TPS 제한이 `설정값 × Pod 수`가 된다. Redisson 분산 락(F-08)은 적용되어 있으나, Rate Limiter 카운터 자체가 단일 JVM 범위다.

---

### 3.4 [신규 M-B] Keycloak JWKS 키 갱신 지연

**심각도**: Medium

```
파일: q-sign/src/main/resources/application.yml
라인 231: jwks-cache-ttl-seconds: 3600
```

Keycloak이 서명 키를 교체하면 최대 1시간 동안 기존 JWT가 유효하게 검증될 수 있다. 키 유출 시 침해 창이 1시간.

---

### 3.5 [신규 L-A] OWASP Dependency-Check `|| true` 우회

**심각도**: Low~Medium

```
파일: .github/workflows/ci.yml
라인 184: || true   # OWASP 취약점 발견 시에도 빌드 성공
```

CVSS 7.0 이상 취약 라이브러리가 발견되어도 CI 빌드가 성공으로 처리된다.

---

## 4. 종합 비교 표

### 4.1 이전 보고서 항목별 현재 상태

| 이전 보고서 항목 | 등급 | 현재 코드 상태 | 비고 |
|----------------|------|--------------|------|
| C-01: qimUserId 미연동 | Critical | ✅ **해결됨** | v0.8.7에서 수정, Q-IM 조회 구현 완료 |
| C-02: X-Internal-Sig 우회 | Critical | ⚠️ **부분 해결** | 프로덕션 기본값=strict, PoC non-strict 코드 잔존 |
| C-03: PKCE 미구현 | Critical | ✅ **해결됨** | RFC 7636 S256 완전 구현 |
| C-03: API Key 단순 비교 | Critical | ✅ **해결됨** | PBKDF2 + CHANGEME 센티넬 보호 |
| C-04: CI 암호화 부재 | Critical | ✅ **해결됨** | AES-256-GCM 완전 구현 |
| C-04: Handoff Ticket 암호화 TODO | Critical | ✅ **해결됨** | 암호화/서명 완전 구현, TODO 없음 |
| C-04: Attribute 필터링 미구현 | Critical | ✅ **해결됨** | filterAttributes() 구현 완료 |
| P0-A: DB 비밀번호 취약 | P0 | ⚠️ **유효 (PoC 기본값)** | 운영 배포 전 환경 변수 override 필수 |
| P0-A: AES/HMAC 기본값 | P0 | ⚠️ **유효** | 0바이트 기본값 잔존, 환경 변수 미주입 시 위험 |

### 4.2 독립 분석 신규 발견 항목

| 신규 항목 | 등급 | 설명 |
|----------|------|------|
| C-A: Webhook/PolicyEngine Secret 기본값 | High | `poc-webhook-secret`, `default-poc-secret` 하드코딩 |
| C-B: Git 이력에 PoC API Key 잔존 | High | 마이그레이션 주석에 원문 키 기록 |
| M-A: Rate Limiter 단일 JVM | Medium | K8s 멀티 Pod 시 TPS 제한 우회 가능 |
| M-B: JWKS 캐시 TTL 1시간 | Medium | 키 교체 후 유출 창 1시간 |
| L-A: OWASP `|| true` 우회 | Low~Medium | CVE 발견 시에도 빌드 성공 처리 |

---

## 5. 이전 보고서와 현재 분석의 차이에 대한 해석

### 5.1 이전 보고서가 "Critical"로 분류한 항목 대부분이 현재 해결된 이유

이전 보고서(v2.0)는 2026-05-11 작성되었으나, 분석 대상은 현재 `v2.3.0 (Sprint 10)` 이전의 코드베이스로 추정된다. 코드 주석에 "P0 수정 (v0.8.7)"이라는 명시적 변경 이력이 있으며, PolicyEngineImpl.java의 Javadoc에도 "v2.0 — allowedAttributes 실제 필터링 구현"이라는 변경 기록이 있다.

**결론**: 이전 보고서는 일부 항목에 대해 **수정 전 버전**을 기준으로 작성되었거나, 코드 분석 범위가 제한적이었을 가능성이 있다. 현재 코드베이스는 이전 보고서의 Critical 항목 대부분을 이미 수정했다.

### 5.2 현재 코드베이스의 실제 배포 차단 요소

이전 보고서의 "Critical" 판정을 현재 코드 기준으로 재평가하면 다음과 같다.

**[즉시 해결 필요]**

1. **환경 변수 미주입 시 암호화 키 기본값 사용** — 운영 배포 전 필수
   - `IDO_HANDOFF_AES_KEY`, `IDO_HANDOFF_HMAC_KEY` (기본값: 0바이트)
   - `QIM_CI_AES_KEY_V1` (기본값: 0바이트)
   - `IDO_WEBHOOK_SIGNING_SECRET` (기본값: PoC 문자열)
   - `IDO_INTERNAL_SIG_SECRET`, `KEYCLOAK_CLIENT_SECRET` (기본값: 취약한 문자열)

2. **DB 비밀번호 기본값** — 운영 배포 전 환경 변수 override 필수

**[배포는 가능하나 운영 위험]**

3. **X-Internal-Sig non-strict 코드 잔존** — `strict-mode=false` 설정이 실수로 적용될 경우 내부 인증 무력화

4. **OWASP `|| true` 우회** — CVE 발견 시 CI 게이트 무력화

**[K8s 배포 시 필요]**

5. **Rate Limiter 분산화** — Pod 수 × 설정값으로 실제 한도 초과 가능

---

## 6. 운영 배포 전 액션 아이템

이전 보고서와 독립 분석을 종합한 최종 액션 아이템이다.

### 즉시 처리 (배포 차단)

```bash
# 반드시 아래 환경 변수를 K8s Secret으로 주입해야 함
IDO_HANDOFF_AES_KEY=$(openssl rand -base64 32)
IDO_HANDOFF_HMAC_KEY=$(openssl rand -base64 32)
QIM_CI_AES_KEY_V1=$(openssl rand -base64 32)
IDO_WEBHOOK_SIGNING_SECRET=$(openssl rand -hex 32)
IDO_INTERNAL_SIG_SECRET=$(openssl rand -hex 32)
KEYCLOAK_CLIENT_SECRET=<Keycloak 생성 값>
DB_PASSWORD=<강력한 랜덤 값>
```

### 단기 처리 (배포 후 1주 이내)

| 항목 | 작업 | 담당 |
|------|------|------|
| non-strict 코드 제거 | `InternalSigVerifier`에서 non-strict 경로 삭제, `strict-mode` 설정 항목 제거 | Q-Sign BE |
| OWASP `|| true` 제거 | CI yml에서 `|| true` 제거 → CVE 발견 시 빌드 실패 | DevOps |
| Git 이력 정리 | V8 마이그레이션 주석의 PoC 원문 키 제거 | 공통 |
| Rate Limiter 분산화 | Redisson AtomicLong으로 카운터 교체 | IdO BE |

### 중기 처리 (배포 후 1개월)

- JWKS 캐시 TTL 단축 (3600s → 300s) 또는 키 교체 이벤트 강제 갱신 메커니즘 도입
- NHN KMS(SKM) 연동으로 키 관리 중앙화
- DLQ 재처리 관리 API 구현

---

## 7. 최종 결론

### 이전 보고서 평가

이전 보고서(v2.0)는 **분석 시점의 코드베이스**를 기준으로 올바른 Critical 결함을 식별했다. 다만 현재(v2.3.0) 코드베이스를 기준으로 하면, 보고서가 식별한 7개 Critical/P0 항목 중 **5개는 이미 수정 완료**되었고 **2개는 여전히 유효** (DB 기본값, AES/HMAC 기본값)하다.

### 현재 코드베이스 운영 준비도

| 평가 영역 | 상태 | 비고 |
|----------|------|------|
| 핵심 보안 기능 (PKCE, CI 암호화, Handoff 암호화) | ✅ 완전 구현 | |
| 사용자 식별 체계 (qimUserId 연동) | ✅ 완전 구현 | |
| API 인증 (X-Internal-Sig, PBKDF2 API Key) | ✅ 구현 (non-strict 모드 주의) | |
| 속성 필터링 (Attribute Filter) | ✅ 구현 | |
| 운영 Secret 관리 | ⚠️ **환경 변수 미주입 시 기본값 사용** | 배포 차단 |
| K8s 분산 Rate Limiting | ⚠️ 단일 JVM 구현 | Pod 2개 이상 시 취약 |
| CI 보안 게이트 | ⚠️ OWASP `|| true` 우회 | |
| 운영 관리 도구 (Admin, DLQ) | ❌ 미구현 | 운영 단계에서 필요 |

**종합 판정**:

> 이전 보고서가 제기한 "즉시 배포 중단" 권고는 당시 코드베이스 기준으로는 타당했다. 현재 코드베이스는 그 Critical 항목들이 대부분 해소되어 **스테이징 배포는 가능한 수준**에 도달했다. 다만, **암호화 키·DB 비밀번호 등 운영 Secret이 환경 변수로 반드시 주입되어야 하며**, 이것이 충족되지 않으면 현재 코드도 "암호화가 있는 것처럼 보이지만 실제로는 동작하지 않는" 상태가 된다. 이 조건이 충족된 후에야 **제한적 프로덕션 배포**가 가능하다.
