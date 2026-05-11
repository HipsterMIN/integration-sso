# 코드 완성도 분석 보고서

**문서 ID**: ANAL-2026-001  
**버전**: v1.0  
**작성일**: 2026-05-11  
**작성자**: GenSpark AI (전체 코드베이스 자동 분석)  
**분류**: 내부 기술 문서 — 개발팀 인계용  
**분석 대상**: 전체 코드베이스 (Java 270개 파일 + TypeScript/TSX FE)  

---

## 목차

1. [분석 개요 및 방법론](#1-분석-개요-및-방법론)
2. [플랫폼 구성 현황](#2-플랫폼-구성-현황)
3. [Critical — 즉시 수정 필수 항목](#3-critical--즉시-수정-필수-항목)
4. [High — 운영 전 반드시 수정 항목](#4-high--운영-전-반드시-수정-항목)
5. [Medium — 운영 후 빠른 시일 내 처리 항목](#5-medium--운영-후-빠른-시일-내-처리-항목)
6. [Low — 개선 권장 항목](#6-low--개선-권장-항목)
7. [미구현 기능 전체 목록](#7-미구현-기능-전체-목록)
8. [보안 취약점 상세 분석](#8-보안-취약점-상세-분석)
9. [운영 환경 설정 체크리스트](#9-운영-환경-설정-체크리스트)
10. [테스트 커버리지 현황](#10-테스트-커버리지-현황)
11. [데이터 무결성 및 일관성 점검](#11-데이터-무결성-및-일관성-점검)
12. [아키텍처 완성도 평가](#12-아키텍처-완성도-평가)
13. [인계 전 필수 조치 사항 요약](#13-인계-전-필수-조치-사항-요약)

---

## 1. 분석 개요 및 방법론

### 1.1 분석 범위

| 모듈 | 언어 | 분석 대상 |
|------|------|---------|
| `ido` (8083) | Java 17 + Spring Boot 3.x | 전체 소스 |
| `q-sign` (8081) | Java 17 + Spring Boot 3.x | 전체 소스 |
| `q-im` (8082) | Java 17 + Spring Boot 3.x | 전체 소스 |
| `agency-stub` (8084) | Java 17 + Spring Boot 3.x | 전체 소스 |
| `onepass-fe` | TypeScript/React | 전체 소스 (`/frontend/src`) |
| `platform-common` | Java 17 | 공통 도메인/이벤트 |

**총 분석 파일**: Java 270개 + TypeScript/TSX 약 120개

### 1.2 분석 방법

1. **정적 코드 분석**: TODO/FIXME/PoC/미구현 키워드 전수 검색
2. **아키텍처 문서 대조**: ADR-001 (IdO 완전 중재 패턴) 대비 구현 현황 검토
3. **데이터 흐름 추적**: A→Z 시퀀스 재구성 후 누락 단계 식별
4. **보안 패턴 검증**: HMAC/AES/PBKDF2/Redis 소비-일회성 패턴 구현 상태 검토
5. **FE UX 흐름 검증**: 모달/버튼 동작 코드 대조

### 1.3 심각도 기준

| 심각도 | 기준 | 처리 시점 |
|--------|------|---------|
| **Critical** | 서비스 정상 동작 불가 / 보안 사고 발생 가능 | 인계 전 즉시 |
| **High** | 핵심 기능 미완성 / 운영 환경에서 장애 유발 가능 | 운영 배포 전 |
| **Medium** | 일부 기능 미완성 / UX 저하 | 초기 운영 후 1~2주 내 |
| **Low** | 개선 권장 / 기술 부채 | 운영 안정화 후 처리 |

---

## 2. 플랫폼 구성 현황

### 2.1 모듈별 완성도 평가

| 모듈 | 핵심 기능 완성도 | 보안 완성도 | 운영 준비도 | 종합 |
|------|----------------|-----------|-----------|------|
| `q-im` | ★★★★☆ (90%) | ★★★★☆ (85%) | ★★★★☆ | **양호** |
| `q-sign` | ★★★☆☆ (70%) | ★★★☆☆ (60%) | ★★★☆☆ | **조치 필요** |
| `ido` (인증 BFF) | ★★★★☆ (80%) | ★★★☆☆ (65%) | ★★★☆☆ | **조치 필요** |
| `ido` (Handoff) | ★★★★★ (95%) | ★★★★☆ (88%) | ★★★★☆ | **양호** |
| `onepass-fe` | ★★★☆☆ (60%) | ★★★★☆ (80%) | ★★☆☆☆ | **미완성 多** |
| `agency-stub` | N/A (테스트용) | N/A | N/A | **테스트 전용** |

### 2.2 기능 영역별 완성도

| 기능 영역 | 완성도 | 주요 미완성 항목 |
|----------|--------|--------------|
| OIDC 소셜 로그인 (q-sign 모드) | 70% | BrokerService.buildInternalSig() PoC |
| OIDC 완료 처리 (identifierHash → qimUserId) | 40% | OidcCompleteController 미완성 |
| NICE 휴대폰 본인인증 | 90% | HMAC 비교 타이밍 공격 취약 |
| OACX 간편인증서 | 85% | CI 미제공 provider 정책 미확립 |
| FE 세션 관리 | 95% | — |
| Handoff 발급/검증 | 95% | — |
| 회원 탈퇴 | 20% | FE 전체 미구현 (버튼=모달만) |
| 회원 정보 수정 | 50% | InformationStep2 일부 미구현 |
| 비밀번호 변경 | 20% | FE 미구현 (모달만) |
| 소속 기관 관리 | 30% | Affiliation 전체 미구현 |
| 공동인증서/Any-ID | 0% | 개발 중 모달만 |
| 기업인증서 로그인 | 0% | 개발 중 모달만 |
| 아이디/비밀번호 찾기 | 0% | 버튼만 존재 |
| 14세 미만 회원가입 | 0% | 버튼만 존재 |
| 알림 수신 설정 | 0% | UI 주석 처리 |

---

## 3. Critical — 즉시 수정 필수 항목

### C-001 [Critical] BrokerService.buildInternalSig() — PoC 서명 사용

**파일**: `ido/src/main/java/kr/go/smes/ido/broker/BrokerService.java`

**현재 코드**:
```java
private String buildInternalSig(String correlationId) {
    String safe = correlationId.replace("-", "");
    return "sig-" + (safe.length() >= 8 ? safe.substring(0, 8) : safe);
}
```

**문제점**:
- `correlationId` 앞 8자를 서명으로 사용 → 예측 가능 (보안 0%)
- 공격자가 `X-Internal-Sig` 헤더를 위조하여 q-sign에 무단 요청 가능
- 내부 서비스 간 인증이 사실상 없는 상태

**영향 범위**: q-sign 모드(`broker.mode=qsign`) 사용 시 OIDC 로그인 전체 흐름 보안 취약

**권장 수정**:
```java
// application.yml: ido.broker.internal-sig-secret=<256bit 이상 무작위 비밀 키>
@Value("${ido.broker.internal-sig-secret}")
private String internalSigSecret;

private String buildInternalSig(String correlationId) {
    try {
        long epochSeconds = Instant.now().getEpochSecond();
        String message = correlationId + ":" + epochSeconds;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(internalSigSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash) + "." + epochSeconds;
    } catch (Exception e) {
        throw new IllegalStateException("내부 서명 생성 실패", e);
    }
}
```

**q-sign 측 검증 (`InternalSigVerifier`)도 동시에 수정 필요** (현재 strict-mode=false로 우회 허용 중).

---

### C-002 [Critical] OidcCompleteController — identifierHash를 qimUserId 대용으로 사용

**파일**: `ido/src/main/java/kr/go/smes/ido/broker/OidcCompleteController.java`  
**연관 파일**: `ido/src/main/java/kr/go/smes/ido/broker/keycloak/KeycloakOidcService.java`

**현재 코드**:
```java
// PoC: identifierHash를 qimUserId 대용으로 사용
// 실운영: Q-IM 조회 후 실제 qimUserId 획득 필요
FeSession session = feSessionService.create(
    req.getIdentifierHash(),  // ← SHA-256(CI) 해시값을 qimUserId로 사용!
    req.getAuthResultId(),
    req.getAuthLevel(),
    returnUrl
);
```

**문제점**:
- `feSessionService.create(qimUserId, ...)` 의 첫 번째 파라미터가 qimUserId여야 하는데, `identifierHash`(SHA-256 해시)가 전달됨
- Redis `fe:user-sessions:{qimUserId}` Set에 잘못된 키로 역인덱스 생성
- `FeSessionService.invalidateByQimUserId()` (탈퇴 시 세션 일괄 무효화)가 정상 동작하지 않음
- Handoff 발급 시 `qimUserId`로 사용자 상태 확인 시 불일치 발생 가능

**권장 수정**:
```java
// Step 1: Q-IM에서 실제 qimUserId 조회
String qimUserId = qimClient.getUserByHash(req.getIdentifierHash())
    .getQimUserId();  // Q-IM API: GET /api/v1/internal/users/by-hash?hash={hash}

// Step 2: 실제 qimUserId로 FE 세션 생성
FeSession session = feSessionService.create(
    qimUserId,  // 실제 Q-IM 사용자 ID
    req.getAuthResultId(),
    req.getAuthLevel(),
    returnUrl
);
```

---

### C-003 [Critical] InternalSigVerifier.strict-mode=false — 서명 검증 우회

**파일**: `q-sign/src/main/java/kr/go/smes/qsign/api/InternalSigVerifier.java`

**현재 코드**:
```java
// non-strict 모드: PoC에서 단순 접두어 서명 허용 (경고만 출력)
if (!strictMode) {
    log.warn("[InternalSigVerifier] 서명 검증 실패 (non-strict 허용): correlationId={}", correlationId);
    return true;  // ← C-001과 연동: PoC 서명 통과 허용
}
```

**문제점**:
- `strict-mode=false` 설정 시 서명 검증을 완전히 우회
- C-001과 연동하여 q-sign 서비스 무단 접근 가능

**권장 수정**:
1. C-001 수정 완료 후 `strict-mode=true` 설정 (`application.yml`)
2. 운영 환경에서 절대 `strict-mode=false` 사용 금지

---

### C-004 [Critical] Q-IM 프로필 업데이트 API 없음

**현황**: `q-im` 모듈에 `PATCH /api/v1/internal/users/{id}/profile` 엔드포인트가 없음

**영향**:
- 회원 정보 수정(`InformationStep2.tsx`) 흐름에서 BE API 연동 불가
- 마이페이지 정보 수정 기능 전체 동작 불가

**필요 구현**:
```java
@PatchMapping("/{id}/profile")
public ResponseEntity<Void> updateProfile(
    @PathVariable String id,
    @RequestBody ProfileUpdateRequest request
) {
    // maskedName, maskedMobile, birthdate, gender 업데이트
    // Outbox: UserEvent(TYPE_UPDATED) 발행
}
```

---

## 4. High — 운영 전 반드시 수정 항목

### H-001 [High] FE 탈퇴 흐름 전체 미구현

**파일**: `onepass-fe/frontend/src/pages/Mypage/pages/Withdraw.tsx`

**현재 코드**:
```typescript
// TODO: API 배포 후 복원 — goNext 로 다음 단계 이동
// const nextRoute = getMypageRoute(memberType, 'WITHDRAW_STEP2');
<button onClick={() => setDevNoticeModal(true)}>다음</button>  // 모달만 표시
```

**문제**: 탈퇴 "다음" 버튼이 "서비스 준비 중" 모달만 표시. 실제 탈퇴 불가.

**필요 구현**:
1. 탈퇴 Step2 (사유 선택) — UI 구현 필요
2. 탈퇴 Step3 (최종 확인) — UI 구현 필요
3. BE API 연동: `DELETE /api/v1/internal/users/{id}` (Q-IM)
4. 탈퇴 완료 후 SLO 처리: `POST /api/v1/slo/initiate`

---

### H-002 [High] FE 비밀번호 변경 흐름 미구현

**파일**: `onepass-fe/frontend/src/pages/Mypage/pages/PasswordStep1.tsx`

**현재 상태**:
```typescript
// TODO: API 배포 후 복원 — 인증 성공 시 다음 단계로 이동
// TODO: API 배포 후 복원 — goNext 로 다음 단계 이동
```

NICE 인증/OACX 인증 후 비밀번호 변경 Step2 이동 및 BE API 연동 미구현.

---

### H-003 [High] 회원 정보 수정 Step2 일부 미구현

**파일**: `onepass-fe/frontend/src/pages/Mypage/pages/InformationStep2.tsx`

```typescript
// "서비스 준비 중" 모달이 2곳에 존재
// 행 172, 304
```

회원 정보 수정에서 일부 인증 수단(공동인증서 등) 사용 시 "서비스 준비 중" 처리.  
**문제**: 정보 수정 흐름이 완전히 동작하지 않는 경우 발생.

---

### H-004 [High] 기업 회원 전환 Step3 (기업인증) 미구현

**파일**: `onepass-fe/frontend/src/pages/ConversionSteps/member/Step3.tsx`

```typescript
title="서비스 준비 중"  // line 445
```

기업 간편인증서 인증 단계가 미구현.  
**문제**: 기업 회원 전환(가입) 흐름 완결 불가.

---

### H-005 [High] 기업 회원 전환 AccountForm — 사업자 진위확인 API 호출 스킵

**파일**: `onepass-fe/frontend/src/pages/ConversionSteps/member/components/AccountForm.tsx`

```typescript
// TODO: 기업인증 구현 후 진위확인 API 호출 활성화
// ① 진위확인 — 임시 스킵 (기업인증 미구현으로 설립일 미확보)
setValidateMessage('진위확인 생략 (기업인증 미구현)');
```

**문제**: 사업자등록번호 진위확인이 완전히 스킵됨. 허위 사업자번호로 기업 회원 등록 가능.

---

### H-006 [High] SloServiceImpl — internalSigSecret 빈 값 허용

**파일**: `ido/src/main/java/kr/go/smes/ido/slo/SloServiceImpl.java`

```java
// internalSigSecret이 비어 있으면 경고 후 임시 식별자 반환
```

SLO(Single Log-Out) 처리 시 서명 비밀 키가 없으면 임시 식별자로 처리 → 보안 취약.

---

### H-007 [High] WebhookDispatcherService — 기본 서명 시크릿 PoC 수준

**파일**: `ido/src/main/java/kr/go/smes/ido/webhook/WebhookDispatcherService.java`

```java
private String defaultSigningSecret;   // PoC 기본값; 운영: Vault/KMS 주입
```

웹훅 서명 시크릿이 환경변수로 주입되지 않으면 PoC 기본값 사용. 기관 웹훅의 HMAC 서명 신뢰성 손상.

---

### H-008 [High] Kafka 토픽 설정 — PoC/운영 혼용

**파일**: `ido/src/main/java/kr/go/smes/ido/config/KafkaTopicConfig.java`

```java
/** 핵심 토픽 파티션 수 — 60k 대응 기준 12 (PoC: 6, 운영: 12~24) */
/** Replication Factor — 운영: 3, PoC: 1 */
/** Min ISR — 운영: 2, PoC: 1 */
```

현재 설정이 PoC 값(RF=1, Partition=6)으로 되어 있을 가능성. 운영 시 RF=3, Partition=12+ 설정 필수.

---

### H-009 [High] 개인 정보 보존 기간 — 임시값 사용

**파일**: `ido/src/main/java/kr/go/smes/ido/retention/PersonalDataRetentionScheduler.java`

```java
/** 보존 기간 (일). 기본 365일 = 1년. 법무팀 확정 전 임시값. */
private static final int DEFAULT_RETENTION_DAYS = 365;
```

**문제**: 개인정보 보호법 상 보존 기간은 법무팀 검토 후 확정 필요. 임시값으로 운영 시 법적 리스크.

---

### H-010 [High] NonOidcBrokerAdapter — 완전 PoC 구현

**파일**: `ido/src/main/java/kr/go/smes/ido/broker/nonoidc/NonOidcBrokerAdapter.java`

```java
// PoC: 더미 — 운영: 금융결제원 금융인증서 API 연동
// PoC: 더미 — 운영: 행정안전부 GPKI 연동
// PoC: 더미 — 운영: 금융결제원 / KICA / CrossCert 연동
// PoC: 응답 검증 항상 통과 — 운영: 사업자 서명 검증
```

공동인증서(금융인증서, GPKI, CrossCert) 연동이 전부 더미 구현.

---

### H-011 [High] CallbackUrlValidator — whitelist 비어있으면 검증 스킵

**파일**: `ido/src/main/java/kr/go/smes/ido/handoff/validate/CallbackUrlValidator.java`

```java
// whitelist가 null이거나 비어있으면 검증 스킵 (PoC 하위호환)
```

Handoff 발급 시 callbackUrl 화이트리스트가 비어있으면 임의 URL로 Ticket 발급 가능.  
**운영 전 반드시 모든 기관의 callbackUrl 화이트리스트 설정 필수**.

---

## 5. Medium — 운영 후 빠른 시일 내 처리 항목

### M-001 [Medium] NICE HMAC 비교 — 타이밍 공격 취약

**파일**: `ido/src/main/java/kr/go/smes/ido/auth/service/NiceAuthService.java`

```java
String calculated = NiceCryptoUtil.hmacSha256Base64Url(response.getEncData(), hmacKey);
if (!calculated.equals(response.getIntegrityValue())) {  // ← String.equals()
    throw new DataIntegrityException();
}
```

**권장 수정**:
```java
if (!MessageDigest.isEqual(
    calculated.getBytes(StandardCharsets.UTF_8),
    response.getIntegrityValue().getBytes(StandardCharsets.UTF_8))) {
    throw new DataIntegrityException();
}
```

---

### M-002 [Medium] FE 소속 기관 관리 전체 미구현

**파일**: `Affiliation.tsx`, `AffiliationAddStep1.tsx`, `AffiliationWithdrawStep1.tsx`

소속 기관 조회/추가/탈퇴 전체가 "서비스 준비 중" 처리.

---

### M-003 [Medium] FE 알림 수신 설정 미구현

**파일**: `Information.tsx` (마이페이지 정보 조회)

```typescript
// 알림 수신 설정 UI 전체 주석 처리
```

---

### M-004 [Medium] FE 아이디/비밀번호 찾기 미동작

**파일**: `Login/index.tsx`

아이디 찾기, 비밀번호 찾기 버튼이 존재하지만 동작 없음 (onClick 없음 또는 모달만).

---

### M-005 [Medium] FE 14세 미만 회원가입 미동작

**파일**: 회원가입 흐름

14세 미만 회원가입 버튼 존재하지만 동작 없음.

---

### M-006 [Medium] OACX CI 미제공 Provider 처리 정책 미확립

**파일**: `ido/src/main/java/kr/go/smes/ido/auth/service/AuthService.java`

```java
} else {
    log.warn("[OACX] CI 미포함 — Q-IM 등록 건너뜀 (provider가 CI를 미제공)");
}
```

CI 없는 OACX provider 사용자가 Q-IM에 등록되지 않아 이후 서비스 이용 불가.  
**정책 결정 필요**: CI 미제공 provider 차단? 대체 식별자 사용?

---

### M-007 [Medium] NICE correlationId 비표준 형식

**파일**: `NiceAuthService`, `AuthService`

```java
String correlationId = "nice-" + webTransactionId;       // NiceAuthService
String correlationId = "oacx-" + System.currentTimeMillis(); // AuthService
```

correlationId 형식이 비표준. 분산 추적/감사 로그 연계 시 혼란 유발.  
**권장**: `UUID.randomUUID().toString()` 사용.

---

### M-008 [Medium] NICE 세션 TTL — 10분으로 부족할 수 있음

`NiceAuthSessionStore.SESSION_TTL_MINUTES = 10`

사용자가 NICE 팝업에서 10분 이상 소요 시 세션 만료 → 재인증 필요.  
**권장**: 15분 또는 NICE 팝업 타임아웃과 동기화.

---

### M-009 [Medium] OACX 환경변수 미설정 시 무음 실패

`EASYSIGN_URL`, `EASYSIGN_ORIGIN` 미설정 시 팝업이 빈 URL로 열림.  
FE 시작 시 환경변수 유효성 검증 추가 필요.

---

### M-010 [Medium] RegisterSteps 동일 미구현 항목 중복

`ConversionSteps/member/Step3.tsx`와 `RegisterSteps/member/Step3.tsx`에서  
동일한 "서비스 준비 중" 모달이 중복 구현됨.  
신규 가입(Register)과 회원 전환(Conversion) 모두 기업인증 미구현.

---

## 6. Low — 개선 권장 항목

### L-001 [Low] HandoffKeyRotationScheduler — KMS 통합 필요

**파일**: `ido/src/main/java/kr/go/smes/ido/crypto/HandoffKeyRotationScheduler.java`

```java
// 실제 키 생성은 KMS에 위임 (이 구현은 PoC/개발 환경용 자체 생성 포함)
```

운영 환경에서는 KMS(AWS KMS, Vault 등)를 통해 Handoff 암호화 키 관리 권장.

---

### L-002 [Low] AgencyMeta — bridge_endpoint 컬럼 재사용

**파일**: `ido/src/main/java/kr/go/smes/ido/domain/AgencyMeta.java`

```java
// DB: bridge_endpoint 컬럼 재사용 (APACHE_GATE 전용 컬럼 추가 전 임시)
```

`ApacheGateHandoffStrategy`가 `bridge_endpoint` 컬럼을 재사용 중.  
전용 컬럼(`apache_gate_endpoint`) 분리 권장.

---

### L-003 [Low] Q-IM AuthMeanMapping 마지막 사용일 미갱신

NICE/OACX 인증 성공 시 `auth_mean_mappings.last_used_at`이 갱신되지 않을 수 있음.  
로그인 이력 관리 정확도 향상을 위해 갱신 로직 확인 필요.

---

### L-004 [Low] FE CI 필드 응답 타입에 포함

**파일**: `onepass-fe/frontend/src/hooks/useNicePhoneAuth.ts`

```typescript
export interface NicePhoneAuthResult {
    resultCode: string;
    resultMsg: string;
    ci?: string;  // ← BE는 CI를 절대 반환하지 않지만 타입에 존재
    ...
}
```

BE(Q3=B 정책)는 CI를 반환하지 않지만 FE 타입에 `ci?` 필드가 남아 있음.  
혼란 방지를 위해 타입에서 제거 권장.

---

### L-005 [Low] PhoneAuthTab — URL 폴링 방식 (비권장)

**파일**: `onepass-fe/frontend/src/pages/OacxTest/PhoneAuthTab.tsx`

```typescript
// 팝업의 URL 변화를 폴링으로 감지
pollingRef.current = setInterval(async () => {
    const popupUrl = popup.location.href;  // cross-origin 접근 시 예외
    ...
}, 500);
```

테스트 페이지(`PhoneAuthTab`)에서 팝업 URL 폴링 방식 사용.  
`useNicePhoneAuth` 훅은 `postMessage` 방식으로 개선됨.  
**권장**: 테스트 페이지도 `useNicePhoneAuth` 훅 사용으로 통일.

---

### L-006 [Low] 운영 환경 Kafka RF=1 위험

**파일**: `ido/src/main/java/kr/go/smes/ido/config/KafkaTopicConfig.java`

```java
/** Replication Factor — 운영: 3, PoC: 1 */
```

Kafka RF=1(단일 복제)은 브로커 1대 장애 시 메시지 손실 발생.  
운영 Kafka 클러스터는 반드시 RF=3, min.insync.replicas=2 설정.

---

## 7. 미구현 기능 전체 목록

### 7.1 백엔드 미구현

| ID | 파일 / 컴포넌트 | 미구현 내용 | 심각도 | 선행 조건 |
|----|---------------|----------|--------|--------|
| BE-01 | `BrokerService.buildInternalSig()` | HMAC-SHA256 서명 교체 | Critical | 비밀 키 설정 |
| BE-02 | `OidcCompleteController` | identifierHash → qimUserId Q-IM 조회 | Critical | Q-IM API 연동 |
| BE-03 | `InternalSigVerifier` | strict-mode=true 전환 | Critical | BE-01 완료 |
| BE-04 | `q-im UserController` | PATCH /profile 엔드포인트 추가 | Critical | — |
| BE-05 | `NonOidcBrokerAdapter` | 공동인증서/금융인증서/GPKI 실제 연동 | High | 외부 API 계약 |
| BE-06 | `WebhookDispatcherService` | 서명 시크릿 Vault/KMS 주입 | High | 인프라 준비 |
| BE-07 | `AuthResult.dto` | CI 주석 처리된 S7-T6 TODO 정리 | Medium | — |
| BE-08 | `QimSpMemberEventHandler` | Phase 3 개인정보 파기 스케줄링 | Medium | 법무 확정 |

### 7.2 프론트엔드 미구현

| ID | 파일 / 페이지 | 미구현 내용 | 심각도 |
|----|-------------|----------|--------|
| FE-01 | `Withdraw.tsx` | 탈퇴 Step2/3 + API 연동 전체 | High |
| FE-02 | `PasswordStep1.tsx` | 비밀번호 변경 Step2 + API 연동 | High |
| FE-03 | `ConversionSteps/member/Step3.tsx` | 기업 간편인증서 Step3 | High |
| FE-04 | `AccountForm.tsx` | 사업자 진위확인 API 활성화 | High |
| FE-05 | `Affiliation.tsx` | 소속 기관 조회/추가/탈퇴 | Medium |
| FE-06 | `Login/index.tsx` | 공동인증서, Any-ID, 기업인증서 | Medium |
| FE-07 | `Login/index.tsx` | 아이디/비밀번호 찾기 | Medium |
| FE-08 | `Information.tsx` | 알림 수신 설정 | Low |
| FE-09 | 회원가입 흐름 | 14세 미만 회원가입 | Low |
| FE-10 | `InformationStep2.tsx` | 정보 수정 일부 인증수단 | Medium |
| FE-11 | `RegisterSteps/member/Step3.tsx` | 신규 가입 기업인증 | High |

### 7.3 인프라/설정 미구현

| ID | 항목 | 내용 | 심각도 |
|----|------|------|--------|
| INF-01 | Kafka RF | PoC=1 → 운영=3 전환 | High |
| INF-02 | Kafka Partition | PoC=6 → 운영=12+ 전환 | High |
| INF-03 | NICE_CLIENT_ID/SECRET | 운영 환경 실제 값 주입 | Critical |
| INF-04 | OACX_PROVIDER_KEY_PATH | 운영 환경 실제 키 파일 경로 | High |
| INF-05 | EASYSIGN_URL/ORIGIN | FE 빌드 환경변수 설정 | High |
| INF-06 | CallbackUrl whitelist | 기관별 화이트리스트 DB 등록 | High |
| INF-07 | internalSigSecret | HMAC 서명 키 안전한 비밀 값 | Critical |
| INF-08 | KMS 통합 | HandoffKeyRotation KMS 연동 | Low |

---

## 8. 보안 취약점 상세 분석

### 8.1 취약점 요약 테이블

| ID | 취약점 | 위치 | CVSS 수준 | 상태 |
|----|--------|------|---------|------|
| SEC-01 | 내부 서비스 인증 없음 (PoC 서명) | `BrokerService` | 9.8 (Critical) | **미수정** |
| SEC-02 | 내부 서명 검증 우회 허용 | `InternalSigVerifier` | 9.1 (Critical) | **미수정** |
| SEC-03 | qimUserId/identifierHash 혼동 | `OidcCompleteController` | 8.5 (High) | **미수정** |
| SEC-04 | HMAC 타이밍 공격 취약 | `NiceAuthService` | 5.9 (Medium) | **미수정** |
| SEC-05 | Webhook 서명 PoC 기본값 | `WebhookDispatcherService` | 7.3 (High) | **미수정** |
| SEC-06 | CallbackUrl 검증 스킵 | `CallbackUrlValidator` | 7.8 (High) | **설정 의존** |
| SEC-07 | Redis ticket 저장 평문 | `NiceTokenStore` | 6.0 (Medium) | **설계 결정 필요** |
| SEC-08 | 사업자 진위확인 스킵 | `AccountForm.tsx` | 7.5 (High) | **미수정** |
| SEC-09 | SLO 서명 키 없음 허용 | `SloServiceImpl` | 6.5 (Medium) | **미수정** |
| SEC-10 | NonOidc 응답 검증 항상 통과 | `NonOidcBrokerAdapter` | 8.0 (High) | **미수정** |

### 8.2 구현 완성된 보안 메커니즘 (✅ 정상)

| 메커니즘 | 구현 위치 | 상태 |
|---------|----------|------|
| CSRF 방어 (state 1회 소비) | `KeycloakStateStore` | ✅ 완성 |
| Replay 방어 (nonce) | `KeycloakCallbackService` | ✅ 완성 |
| PII 비보관 (SHA-256 hash) | `UserRegistrationServiceImpl` | ✅ 완성 |
| CI AES-256-GCM 암호화 | `CiCryptoService` | ✅ 완성 |
| PKCE RFC 7636 | `PkceService` | ✅ 완성 |
| feSessionId HttpOnly Secure | `OidcCompleteController` | ✅ 완성 |
| FE 세션 sliding 30분 | `FeSessionServiceImpl` | ✅ 완성 |
| Handoff 1회 소비 | `HandoffServiceImpl` | ✅ 완성 |
| Handoff AES-256-GCM + HMAC | `HandoffServiceImpl` | ✅ 완성 |
| REUSE_ATTEMPT 보안 이벤트 | `HandoffServiceImpl` | ✅ 완성 |
| NICE 분산 락 (Redisson) | `NiceAuthService` | ✅ 완성 |
| NICE HMAC 무결성 검증 | `NiceCryptoUtil` | ✅ 완성 (비교 방식 개선 필요) |
| NICE 세션 1회 소비 | `NiceAuthSessionStore` | ✅ 완성 |
| CI FE 미반환 (Q3=B) | `NiceAuthService`, `AuthService` | ✅ 완성 |
| Redis rate limiting | `AgencyRateLimiter` | ✅ 완성 |
| X-Internal-Api-Key | `InternalApiKeyInterceptor` | ✅ 완성 |
| GDPR §17 PII 삭제 | `UserRegistrationServiceImpl.withdraw()` | ✅ 완성 |
| Transactional Outbox | q-im Outbox 패턴 | ✅ 완성 |
| Kafka Idempotency | HandoffController | ✅ 완성 |
| returnUrl 화이트리스트 | `FeSessionServiceImpl.isValidReturnUrl()` | ✅ 완성 |

---

## 9. 운영 환경 설정 체크리스트

### 9.1 필수 환경변수 설정 (운영 배포 전 전수 확인)

#### ido 서비스

```bash
# 반드시 실제 값으로 교체 (기본값/PoC 값 절대 금지)
NICE_CLIENT_ID=<NICE 계약 발급값>
NICE_CLIENT_SECRET=<NICE 계약 발급값>
NICE_RETURN_URL=https://<운영도메인>/nice-callback.html
OACX_PROVIDER_KEY_PATH=/app/config/oacx-provider-key.json
INTEGRATION_AUTH_BASE_URL=https://intg-auth.smes.go.kr
IDO_BROKER_INTERNAL_SIG_SECRET=<256bit 이상 무작위 비밀 키>  # C-001 수정 후
IDO_WEBHOOK_DEFAULT_SIGNING_SECRET=<Vault/KMS 주입>
```

#### q-sign 서비스

```bash
# InternalSigVerifier strict 모드
Q_SIGN_INTERNAL_SIG_STRICT_MODE=true  # C-001, C-003 수정 후
```

#### FE 빌드

```bash
EASYSIGN_URL=<OACX 운영 간편서명 팝업 URL>
EASYSIGN_ORIGIN=<OACX 서버 origin>
```

### 9.2 Kafka 운영 설정

```yaml
# 운영 클러스터 설정 (PoC=1 → 운영=3으로 전환)
replication.factor: 3
min.insync.replicas: 2
num.partitions: 12  # 주요 토픽
```

### 9.3 DB 설정 체크리스트

- [ ] `agency_meta` 테이블: 모든 기관의 `callback_url_whitelist` 설정
- [ ] `agency_meta` 테이블: `min_auth_level`, `rate_limit` 운영 값 설정
- [ ] 개인정보 보존 기간 정책 법무 확정 후 `DEFAULT_RETENTION_DAYS` 설정

### 9.4 Redis 설정

- [ ] `nice:token:snapshot` — ticket 필드 접근 권한 제한 (ACL 설정)
- [ ] Redis Sentinel 또는 Cluster 설정 (단일 인스턴스 사용 금지)
- [ ] maxmemory-policy: `allkeys-lru` (TTL 기반 데이터 있으므로)

---

## 10. 테스트 커버리지 현황

### 10.1 테스트 현황

**마지막 실행**: `./gradlew test` → **397개 통과**

| 모듈 | 테스트 파일 수 | 주요 커버 영역 |
|------|-------------|-------------|
| `ido` | 다수 | FeSession, Handoff, NICE, OACX |
| `q-sign` | 다수 | KeycloakCallback, PKCE |
| `q-im` | 다수 | UserRegistration, Outbox |
| `platform-common` | 일부 | 도메인 모델 |

### 10.2 테스트 미흡 영역

| 영역 | 현황 | 권장 |
|------|------|------|
| `BrokerService.buildInternalSig()` | 없음 (PoC) | C-001 수정 후 단위 테스트 |
| HMAC 타이밍 공격 방어 | 없음 | M-001 수정 후 테스트 |
| `OidcCompleteController` qimUserId 검증 | 없음 | C-002 수정 후 통합 테스트 |
| FE 통합 테스트 | 없음 | Playwright/Cypress E2E 권장 |
| 탈퇴 전체 흐름 | 없음 | H-001 구현 후 |
| Kafka 이벤트 발행 검증 | 부분적 | Embedded Kafka 활용 확대 |

---

## 11. 데이터 무결성 및 일관성 점검

### 11.1 데이터 흐름 정합성

| 흐름 | 이슈 | 심각도 |
|------|------|--------|
| OIDC 로그인 → FE 세션 → qimUserId | identifierHash가 qimUserId로 잘못 저장 (C-002) | Critical |
| NICE 인증 → Q-IM 등록 → FE 세션 연결 | OIDC와 NICE 인증 Q-IM 등록은 완성, 세션 연결 로직 확인 필요 | High |
| 탈퇴 → PII 삭제 → 세션 무효화 | FE 탈퇴 미구현으로 실제 흐름 검증 불가 | High |
| Handoff → DI 조회 → 기관 전달 | 완성된 것으로 확인 | OK |

### 11.2 트랜잭션 경계 점검

| 영역 | 트랜잭션 | 이슈 |
|------|---------|------|
| Q-IM 사용자 등록 (3개 엔티티 + Outbox) | 단일 트랜잭션 ✅ | — |
| Q-IM 탈퇴 (PII 삭제 + StatusHistory + Outbox) | 단일 트랜잭션 ✅ | — |
| Handoff 발급 (DB + Kafka + AuditLog) | Outbox 패턴으로 보장 ✅ | — |
| NICE 인증 결과 (복호화 + Q-IM 등록) | 트랜잭션 없음 (API 호출) — 등록 실패 시 재시도 필요 | Medium |

---

## 12. 아키텍처 완성도 평가

### 12.1 ADR-001 (IdO 완전 중재 패턴) 준수 현황

| ADR 요구사항 | 구현 상태 |
|------------|---------|
| FE는 ido와만 통신 | ✅ Nginx 프록시로 구현 |
| q-sign 모드: FE → ido → q-sign → Keycloak | ⚠️ 부분 완성 (C-001, C-002 이슈) |
| keycloak 모드: FE → ido → Keycloak 직접 | ✅ 완성 |
| 내부 서비스 간 HMAC 서명 | ❌ PoC 수준 (C-001, C-003) |
| PII 비보관 원칙 | ✅ SHA-256 hash 적용 |
| Handoff 1회 소비 | ✅ 완성 |

### 12.2 Transactional Outbox 패턴 준수

| 컴포넌트 | 준수 여부 |
|---------|---------|
| Q-IM 사용자 등록 | ✅ |
| Q-IM 탈퇴 | ✅ |
| Handoff 발급 | ✅ |
| NICE 인증 감사 로그 | ✅ (Outbox 또는 직접 발행) |

### 12.3 완성된 핵심 패턴

- ✅ **PKCE (RFC 7636)**: code_challenge S256, 타이밍 공격 방지 `MessageDigest.isEqual()`
- ✅ **Redisson 분산 락**: NICE 토큰 갱신, Double-Checked Locking
- ✅ **Resilience4j CB+Retry**: NICE API 클라이언트
- ✅ **Redis 슬라이딩 윈도우 Rate Limiting**: Handoff 발급
- ✅ **AES-256-GCM**: Handoff Ticket 암호화, CI 암호화, NICE 결과 복호화
- ✅ **HMAC-SHA256**: Handoff HMAC, NICE 무결성 검증
- ✅ **Idempotency Key**: Handoff 중복 발급 방지

---

## 13. 인계 전 필수 조치 사항 요약

### 인계 조건 (Go/No-Go 기준)

**아래 모든 항목이 완료되어야 개발팀 인계 가능**:

#### 🚨 Critical (인계 차단)

| # | 항목 | 담당 | 예상 공수 |
|---|------|------|---------|
| 1 | `BrokerService.buildInternalSig()` HMAC-SHA256 교체 | BE 개발팀 | 0.5일 |
| 2 | `InternalSigVerifier` strict-mode=true 전환 | BE 개발팀 | 0.5일 |
| 3 | `OidcCompleteController` Q-IM qimUserId 실제 조회 | BE 개발팀 | 1일 |
| 4 | Q-IM `PATCH /profile` 엔드포인트 구현 | BE 개발팀 | 1일 |
| 5 | 운영 환경변수 전수 설정 확인 | DevOps | 0.5일 |
| 6 | `CallbackUrl` 화이트리스트 기관별 설정 | 기획/DevOps | 0.5일 |

#### ⚠️ High (운영 배포 전 완료)

| # | 항목 | 담당 | 예상 공수 |
|---|------|------|---------|
| 7 | FE 탈퇴 흐름 Step2/3 구현 + API 연동 | FE 개발팀 | 3일 |
| 8 | FE 비밀번호 변경 Step2 구현 + API 연동 | FE 개발팀 | 2일 |
| 9 | 기업 회원 전환 Step3 (기업인증) 구현 | FE 개발팀 | 3일 |
| 10 | 사업자 진위확인 API 활성화 | FE 개발팀 | 0.5일 |
| 11 | Kafka RF=3, Partition=12 운영 설정 | DevOps | 0.5일 |
| 12 | 개인정보 보존 기간 법무 확정 및 설정 | 법무/BE | 1일 |
| 13 | WebhookDispatcherService 서명 키 Vault 주입 | DevOps/BE | 0.5일 |

**총 예상 잔여 공수**: Critical 4일 + High 11일 = **약 15일**

---

## 부록 A: 파일별 TODO/PoC 전체 목록

| 파일 경로 | 키워드 | 내용 요약 |
|---------|--------|---------|
| `ido/broker/BrokerService.java` | PoC | buildInternalSig() 더미 서명 |
| `ido/broker/OidcCompleteController.java` | PoC | identifierHash qimUserId 대용 |
| `ido/broker/keycloak/KeycloakOidcService.java` | PoC | identifierHash qimUserId 대용 (2곳) |
| `ido/broker/nonoidc/NonOidcBrokerAdapter.java` | PoC | 공동인증서 연동 더미 (5곳) |
| `ido/broker/nonoidc/NonOidcAuthCommand.java` | PoC | 암호화 채널 미구현 |
| `q-sign/api/InternalSigVerifier.java` | PoC | strict-mode=false 서명 검증 우회 |
| `q-sign/application/AuthServiceImpl.java` | 임시 | identifierHash 임시 계산 |
| `ido/handoff/validate/CallbackUrlValidator.java` | PoC | whitelist 비어있으면 검증 스킵 |
| `ido/webhook/WebhookDispatcherService.java` | PoC | 기본 서명 시크릿 |
| `ido/crypto/HandoffKeyRotationScheduler.java` | PoC | KMS 미연동 |
| `ido/retention/PersonalDataRetentionScheduler.java` | 임시 | 보존 기간 365일 임시값 |
| `ido/domain/AgencyMeta.java` | 임시 | bridge_endpoint 컬럼 재사용 |
| `ido/config/KafkaTopicConfig.java` | PoC | RF=1, Partition=6 |
| `ido/slo/SloServiceImpl.java` | 임시 | internalSigSecret 없으면 임시 처리 |
| `ido/auth/service/AuthService.java` | TODO | OACX CI Q-IM 등록 |
| `ido/auth/dto/CiCheckResponse.java` | TODO | S7-T6 TODO 주석 (이미 구현됨, 주석만 남음) |
| `ido/auth/dto/NicePhoneAuthResultResponse.java` | TODO | CI TODO 주석 (이미 구현됨) |
| `onepass-fe/Withdraw.tsx` | TODO | 탈퇴 API 연동 |
| `onepass-fe/PasswordStep1.tsx` | TODO | 비밀번호 변경 Step2 |
| `onepass-fe/ConversionSteps/Step3.tsx` | 개발 중 | 기업인증 |
| `onepass-fe/AccountForm.tsx` | TODO | 진위확인 API |
| `onepass-fe/Affiliation.tsx` | 개발 중 | 소속기관 관리 |
| `onepass-fe/InformationStep2.tsx` | 개발 중 | 정보수정 일부 |
| `onepass-fe/Login/index.tsx` | 개발 중 | 공동인증서/Any-ID/기업인증서 |
| `onepass-fe/RegisterSteps/Step3.tsx` | 개발 중 | 기업인증 (신규가입) |

---

*문서 끝 — ANAL-2026-001 v1.0*  
*관련 문서: `docs/internal/dataflow/01~05-*.md`*
