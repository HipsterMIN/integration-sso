# 중기원패스 회원 전환 — 프로젝트 반영 구현 플랜

> **문서 분류**: 구현 계획서 (Implementation Plan)  
> **버전**: v1.2.0  
> **최종 수정**: 2026-05-07  
> **근거 문서**: 중기원패스 프로세스 설계서 v0.9 (44슬라이드), PoC 실행문서  
> **대상 독자**: 백엔드 개발자, 아키텍트, PM  
> **관련 모듈**: `q-im`, `ido`, `q-sign`, `onepass-fe`, `agency-stub`

---

## 목차

1. [분석 개요](#1-분석-개요)
2. [PPTX 프로세스 → 현재 코드베이스 GAP 분석](#2-gap-분석)
3. [Phase별 구현 플랜](#3-phase별-구현-플랜)
4. [Phase 1 — Q-IM 회원 전환 도메인 완성](#4-phase-1--q-im-회원-전환-도메인-완성)
5. [Phase 2 — IdO 회원 가입·전환 정책 엔진 연동](#5-phase-2--ido-회원-가입전환-정책-엔진-연동)
6. [Phase 3 — 회원 탈퇴·삭제 프로세스](#6-phase-3--회원-탈퇴삭제-프로세스)
7. [Phase 4 — 장애 Fallback 및 IM 장애 모드](#7-phase-4--장애-fallback-및-im-장애-모드)
8. [DB 마이그레이션 계획](#8-db-마이그레이션-계획)
9. [API 신규 명세](#9-api-신규-명세)
10. [이벤트 토픽 설계](#10-이벤트-토픽-설계)
11. [테스트 전략](#11-테스트-전략)
12. [우선순위 매트릭스](#12-우선순위-매트릭스)

---

## 1. 분석 개요

### 1.1 PPTX 설계서 요약 (44슬라이드)

| 챕터 | 프로세스 수 | 핵심 내용 |
|------|------------|-----------|
| **1. 회원가입** | 3개 | 1.1 유관시스템 진입, 1.2 중기원패스 진입, 1.3 최초 기업회원 |
| **2. 통합계정 전환** | 6개 | 2.1~2.4 기존회원 전환, 2.5~2.6 통합회원 추가 |
| **3. 회원관리** | 9개 | ID/PW 찾기, 정보 삭제(4종), 정보 수정(2종) |
| **4. 기타** | 2개 | 중기원패스 장애, IM 장애 시 Fallback |

### 1.2 현재 코드베이스 구현 현황

```
현재 구현된 영역 (GREEN) — v1.2.0 기준 (2026-05-07)
├── OIDC 브로커링 — q-sign Keycloak 어댑터 (v1.1.0 완료) ✅
│   ├── KeycloakAuthUrlController (POST /api/v1/oidc/{provider}/auth-url) ✅
│   ├── KeycloakCallbackController (GET /api/v1/oidc/keycloak/callback) ✅
│   ├── KeycloakCallbackService (state→token→JWKS→identifierHash→AuthResult) ✅
│   ├── KeycloakStateStore (Redis, qsign:oidc:state:{state}, TTL 300s) ✅
│   ├── KeycloakJwksVerifier (Java 표준 라이브러리, @Cacheable keycloakJwks) ✅
│   ├── KeycloakProperties (idp-hint-mapping: kakao/naver/pass/gpki) ✅
│   └── realm-export.json (onepass, q-sign-client, ido-client, social-kakao IdP) ✅
│
├── OIDC 브로커링 — ido Keycloak 모드 (IDO_BROKER_MODE=keycloak) ✅
├── 비OIDC 브로커링 (PASS/GPKI/금융인증서/공동인증서 — PoC 플레이스홀더) ✅
├── AuthResult 생성 + Outbox 발행 (qsign.auth_result, ido.auth_result) ✅
├── FeSession 발급·관리 (Redis, 슬라이딩 TTL 30분, 절대만료 8시간) ✅
├── Handoff Ticket Issue/Verify/Revoke ✅
├── Q-IM qimUserId 등록 (registerUser) ✅
├── Q-IM 상태 전이 (ACTIVE→SUSPENDED→WITHDRAWN) ✅
├── 인증수단 매핑 (auth_mean_mapping) 구조 ✅
├── 기관 정책 (AgencyMeta, PolicyEngine) ✅
│
└── [v1.2.0 신규] Q-IM SP 수신 API — IdO 완전 중재 패턴 ✅
    ├── QimSpReceiverController  (POST /api/qim/sp/v1/member/{query|register|withdraw}) ✅
    ├── QimSpReceiverService     (멱등성·AES 복호화·instMbrId 매핑·Outbox 발행) ✅
    ├── InstMbrIdMappingRepository (ido.inst_mbr_id_mapping CRUD) ✅
    ├── SpReceiverIdempotencyStore (ido.sp_receiver_idempotency TTL=7일) ✅
    ├── AesSharedKeyDecryptor    (AES-256-CBC, identifierHash SHA-256) ✅
    ├── InstMbrIdMapping         (도메인: PERSONAL|CORPORATE, ACTIVE|WITHDRAWN) ✅
    ├── QimSpMemberEventConsumer (Kafka: qim.sp.member.events 구독) ✅
    ├── QimSpMemberEventHandler  (REGISTERED/TRANSFERRED/WITHDRAWN 이벤트 처리) ✅
    ├── DB V4 마이그레이션        (inst_mbr_id_mapping, sp_receiver_idempotency, qim_sp_receiver_log) ✅
    └── application.yml          (ido.qim.inbound-api-key-hash, aes-shared-key 설정 추가) ✅

미구현 영역 (RED) — PPTX와 대조하여 도출 (v1.2.0 기준)
├── [P1] CI값 기반 68개 유관시스템 회원정보 조회 ❌
├── [P1] 통합계정 UUID 생성 및 연결 대상 선택 로직 ❌
├── [P1] 인증수단 추가 (addAuthMeanMapping) 실제 구현 ❌
├── [P1] 기업회원 전환 (사업자등록번호 기반) ❌
├── [P1] 14세 미만 보호자 인증 분기 ❌
├── [P2] 개인정보 동의 기록 (제3자 정보제공 동의) ❌
├── [P2] 개인회원 ID/PW 찾기 (CI 기반 조회) ❌
├── [P2] 회원정보 수정 + 유관시스템 동기화 ❌
├── [P2] 유관기관 어댑터 알림 (AgencyAdapterService — QimSpMemberEventHandler Phase 2) ❌
├── [P3] 회원 탈퇴 — 기본, 삭제불가, 부분탈퇴, 개인정보포털 4종 ❌
├── [P3] 논리적 삭제 + 보존기간 만료 시 영구파기 ❌
├── [P3] 개인정보 파기 스케줄링 (QimSpMemberEventHandler.onMemberWithdrawn Phase 3) ❌
├── [P4] 중기원패스 장애 시 유관시스템 임시 로그인 Fallback ❌
└── [P4] IM 서버 장애 모드 Circuit Breaker 완성 ❌
```

> **v1.2.0 주요 변경 사항**: Q-IM SP 수신 API(MEMBER_QUERY / MEMBER_REGISTER / MEMBER_WITHDRAW) 전체를
> IdO가 대리 구현 완료. Q-IM 명세서 v1.52 기반 완전 중재 패턴 적용.
> 상세 설계는 [qim-ido-integration-architecture.md](qim-ido-integration-architecture.md) 참조.

---

## 2. GAP 분석

### 2.1 회원 식별 체계 GAP

PPTX는 **CI값(연계정보)** 기반으로 68개 유관시스템에서 기존 회원을 조회하고, 매칭된 계정이 있으면 **통합계정 UUID**에 연결하는 구조를 정의한다.

현재 코드베이스에서:
- `qim.auth_mean_mapping.identifier_hash` = `SHA-256(CI 또는 idToken.sub)` 로 저장
- `qim.user_profile.ci` = AES-256-GCM 암호화된 CI 저장 컬럼 존재
- **그러나** CI값을 받아 68개 유관시스템을 조회하는 **외부 연동 서비스(AgencyMemberLookup)가 없음**

```
PPTX Flow:                   현재 구현:
본인인증(CI확보)              본인인증(OIDC/비OIDC) ✅
   ↓                              ↓
CI → 68개 유관시스템 조회 ❌  identifierHash로 Q-IM 조회만
   ↓                              ↓
매칭 계정 선택               qimUserId 등록/반환 ✅
   ↓                              ↓
UUID(qimUserId) 생성          UUID 생성 ✅
```

### 2.2 통합계정 전환 프로세스 GAP

| PPTX 단계 | 현재 구현 | 상태 |
|-----------|----------|------|
| 유관시스템 ID/PW 로그인 | 없음 (OIDC만) | ❌ |
| 통합계정 전환 팝업 표시 | FE 영역, 미구현 | ❌ |
| 개인정보 동의 (제3자 제공) | 없음 | ❌ |
| CI값 획득 | nonOIDC 브로커 일부 | △ |
| CI → 68개 유관시스템 조회 | 없음 | ❌ |
| 연결할 통합대상 선택 | 없음 | ❌ |
| 신규 ID 입력 + 중복 검토 | 없음 | ❌ |
| 통합계정 생성 (UUID) | `registerUser()` | ✅ |
| 통합회원 테이블 저장 | `qim.qim_user` | ✅ |
| DB 동기화 (유관시스템으로) | 없음 | ❌ |

### 2.3 회원관리 GAP

| PPTX 프로세스 | 현재 구현 | 상태 |
|--------------|----------|------|
| 개인회원 ID 찾기 | 없음 | ❌ |
| 개인회원 PW 찾기 | 없음 | ❌ |
| 기업회원 PW 찾기 | 없음 | ❌ |
| 회원정보 삭제 (기본) | `withdrawUser()` 일부 | △ |
| 회원정보 삭제 (삭제불가) | 없음 | ❌ |
| 회원정보 삭제 (부분탈퇴) | 없음 | ❌ |
| 개인정보포털 탈퇴 | 없음 | ❌ |
| 회원정보 수정 (중기원패스) | 없음 | ❌ |
| 회원정보 수정 (유관시스템) | 없음 | ❌ |

### 2.4 장애 모드 GAP

| 장애 유형 | PPTX 정의 | 현재 구현 |
|----------|----------|----------|
| 중기원패스 장애 | 유관시스템 임시 로그인 화면 | Circuit Breaker 구조만 |
| IM 서버 장애 | 임시 로그인 → IM 복구 후 동기화 | `IDO_QIM_UNREACHABLE` 에러코드만 |

---

## 3. Phase별 구현 플랜

```
타임라인 (예상)
──────────────────────────────────────────────────────────
Sprint 1~2  Phase 1  Q-IM 회원 전환 도메인 완성       (2주)
Sprint 3    Phase 2  IdO 정책 엔진 + 전환 API 연동    (1주)
Sprint 4    Phase 3  탈퇴·삭제 프로세스               (1주)
Sprint 5    Phase 4  장애 Fallback + IM 장애 모드      (1주)
Sprint 6    통합 테스트 + PoC 검증                    (1주)
──────────────────────────────────────────────────────────
총 6 Sprint (약 6주)
```

---

## 4. Phase 1 — Q-IM 회원 전환 도메인 완성

### 4.1 신규 도메인 클래스

#### 4.1.1 `AgencyMemberLookupResult` (유관시스템 회원 조회 결과)

```java
// q-im/src/main/java/kr/go/smes/qim/domain/AgencyMemberLookupResult.java
@Getter @Builder
public class AgencyMemberLookupResult {
    private final String  agencyCode;       // 유관시스템 코드
    private final String  agencyName;       // 유관시스템 명칭
    private final String  localUserId;      // 유관시스템 내 사용자 ID
    private final boolean isRegistered;     // 가입 여부
    private final String  memberType;       // PERSONAL / CORPORATE
    private final String  businessRegNo;    // 사업자등록번호 (기업회원)
}
```

#### 4.1.2 `ConversionSession` (전환 세션 — Redis 임시 저장)

```java
// q-im/src/main/java/kr/go/smes/qim/domain/ConversionSession.java
@Getter @Builder
public class ConversionSession {
    private final String sessionId;           // UUID
    private final String ciHash;              // SHA-256(CI) — 복호화 불가
    private final String encryptedCi;         // AES-256-GCM 암호화된 CI
    private final String memberType;          // PERSONAL / CORPORATE
    private final String guardianCiHash;      // 14세 미만인 경우 보호자 CI 해시
    private final boolean isMinor;            // 14세 미만 여부
    private final String businessRegNo;       // 기업회원인 경우
    private final List<AgencyMemberLookupResult> candidateAccounts; // 매칭 계정 목록
    private final List<String> selectedAgencyCodes; // 연결 선택된 유관시스템
    private final ConversionStep currentStep; // 현재 단계 (CONSENT → CI → LOOKUP → SELECT → COMPLETE)
    private final String correlationId;
    private final Instant expiresAt;          // TTL 10분
    
    public enum ConversionStep {
        CONSENT, CI_VERIFIED, LOOKUP_DONE, AGENCY_SELECTED, COMPLETED
    }
}
```

#### 4.1.3 `ConsentRecord` (개인정보 동의 기록)

```java
// q-im/src/main/java/kr/go/smes/qim/domain/ConsentRecord.java
@Getter @Builder
public class ConsentRecord {
    private final String  consentId;       // UUID
    private final String  qimUserId;
    private final String  consentType;     // THIRD_PARTY_PROVISION / SERVICE_TERMS
    private final String  consentedAt;
    private final String  consentVersion;  // 동의서 버전
    private final String  ipAddress;
    private final boolean agreed;
}
```

### 4.2 신규 서비스

#### 4.2.1 `AgencyMemberLookupService` (68개 유관시스템 조회)

```
책임:
  - CI 해시를 받아 등록된 유관시스템 목록 전체 조회
  - 병렬 호출 (CompletableFuture) + 타임아웃 3초
  - 조회 실패한 기관은 스킵 (부분 성공 허용)
  - 결과를 ConversionSession에 저장

구현 위치: q-im/src/main/java/kr/go/smes/qim/agency/AgencyMemberLookupService.java
의존성: AgencyStub REST API (확장 가능하도록 인터페이스 분리)

주요 메서드:
  List<AgencyMemberLookupResult> lookupByIdentifierHash(
      String identifierHash, String correlationId)
  
  List<AgencyMemberLookupResult> lookupByCorporateRegNo(
      String businessRegNo, String correlationId)
```

#### 4.2.2 `MemberConversionService` (통합계정 전환 오케스트레이터)

```
책임:
  - 전환 프로세스 단계별 상태머신 관리 (Redis ConversionSession)
  - CI 기반 회원 조회 결과 취합
  - 연결 대상 유관시스템 선택 처리
  - 신규 ID 중복 검토
  - 통합계정 UUID 생성 (registerUser 호출)
  - 완료 후 ConversionSession 삭제

구현 위치: q-im/src/main/java/kr/go/smes/qim/conversion/MemberConversionService.java

주요 메서드:
  ConversionSession initiateConversion(InitiateConversionCommand cmd)
  ConversionSession recordCiVerified(String sessionId, String ciHash, String encryptedCi)
  ConversionSession selectTargetAgencies(String sessionId, List<String> agencyCodes)
  QimUser completeConversion(String sessionId, String newUserId, String correlationId)
```

#### 4.2.3 `MinorGuardianService` (14세 미만 보호자 인증 — PPTX 2.3)

```
책임:
  - 생년월일로 14세 미만 여부 판단
  - 보호자 CI 획득 분기 처리
  - 보호자 ConversionSession 연결

구현 위치: q-im/src/main/java/kr/go/smes/qim/conversion/MinorGuardianService.java
```

### 4.3 `addAuthMeanMapping` 실제 구현 완성

현재 `UserServiceImpl.addAuthMeanMapping()`에 `TODO`가 있음. 아래를 구현해야 함:

```java
// 현재 (미구현)
// TODO: 실제 구현 시 매핑 목록에 추가 후 저장

// 구현 목표
@Override
@Transactional
public QimUser addAuthMeanMapping(String qimUserId, String identifierHash,
                                   String providerCode, String correlationId) {
    QimUser user = findById(qimUserId, correlationId);

    // 중복 매핑 방지
    userRepository.findByIdentifierHash(identifierHash).ifPresent(existing -> {
        if (!existing.getQimUserId().equals(qimUserId)) {
            throw new PlatformException(PlatformErrorCode.IM_IDENTIFIER_CONFLICT, correlationId);
        }
    });

    // ① 새 매핑 생성
    AuthMeanMapping newMapping = AuthMeanMapping.builder()
            .mappingId(UUID.randomUUID().toString())
            .providerCode(providerCode)
            .identifierHash(identifierHash)
            .status(AuthMeanMapping.MappingStatus.ACTIVE)
            .build();

    // ② 사용자 버전 증가
    long nextVersion = user.getEventVersion() + 1;
    QimUser updated = user.toBuilder()
            .authMeanMapping(newMapping)   // 기존 목록에 추가
            .eventVersion(nextVersion)
            .build();

    // ③ 저장 + Outbox (단일 트랜잭션)
    userRepository.save(updated);
    outboxService.publishInTx(buildUserEvent(
            UserEvent.TYPE_MAPPING_ADDED, updated, correlationId, nextVersion,
            "인증수단 추가: " + providerCode, true));

    log.info("[Q-IM] 인증수단 추가 완료 qimUserId={} provider={}", qimUserId, providerCode);
    return updated;
}
```

### 4.4 ConversionSession Redis 저장소

```
키 패턴: conversion:session:{sessionId}
TTL: 600초 (10분)
직렬화: Jackson JSON
저장 시점:
  - initiate → CONSENT 단계로 생성
  - CI 인증 완료 → CI_VERIFIED 단계로 업데이트
  - 유관시스템 조회 완료 → LOOKUP_DONE
  - 연결 대상 선택 → AGENCY_SELECTED
  - 완료 → COMPLETED 후 즉시 삭제 (보안)
```

---

## 5. Phase 2 — IdO 회원 가입·전환 정책 엔진 연동

### 5.1 신규 API 엔드포인트 (IdO)

#### 5.1.1 회원 전환 개시 API

```
POST /api/v1/conversion/initiate
요청 주체: onepass-fe (FE BFF)
목적: 전환 세션 개시 + 개인정보 동의 기록

Request:
{
  "memberType": "PERSONAL",       // PERSONAL / CORPORATE
  "returnUrl": "https://...",
  "correlationId": "uuid"
}

Response:
{
  "conversionSessionId": "uuid",
  "nextStep": "CONSENT",
  "consentRequired": true
}
```

#### 5.1.2 CI 인증 완료 콜백 (비OIDC 브로커 연동)

```
POST /api/v1/conversion/{sessionId}/ci-verified
요청 주체: IdO nonOIDC 브로커 (내부)
목적: CI 획득 완료 → 유관시스템 조회 트리거

Request:
{
  "identifierHash": "sha256hex...",
  "authLevel": "L1",
  "providerCode": "PASS",
  "isMinor": false
}

Response:
{
  "nextStep": "LOOKUP_DONE",
  "candidateCount": 5,
  "lookupSessionId": "uuid"
}
```

#### 5.1.3 유관시스템 후보 목록 조회

```
GET /api/v1/conversion/{sessionId}/candidates

Response:
{
  "candidates": [
    {
      "agencyCode": "SBIZ24",
      "agencyName": "중소벤처24",
      "localUserId": "hong***@...",
      "memberType": "PERSONAL"
    }
  ],
  "totalCount": 3
}
```

#### 5.1.4 통합 대상 선택 + 완료

```
POST /api/v1/conversion/{sessionId}/complete

Request:
{
  "selectedAgencyCodes": ["SBIZ24", "BIZ_PLAZA"],
  "newUserId": "hong2024",
  "memberInfo": {
    "name": "홍길동",
    "email": "hong@example.com",
    "mobile": "010-****-5678"
  }
}

Response:
{
  "qimUserId": "uuid",
  "redirectUrl": "https://...",
  "feSessionId": "base64url..."
}
```

### 5.2 PolicyEngine 확장

```java
// 현재 PolicyEngine에 추가할 메서드

/**
 * 회원 전환 가능 여부 판단 (PPTX 2.1 ~ 2.4)
 * - 이미 통합계정이 있으면 전환 불필요 (추가 연결로 분기)
 * - CI 중복 확인
 */
boolean canInitiateConversion(String identifierHash, String correlationId);

/**
 * 기업회원 전환 허용 여부
 * - 사업자등록번호 유효성 검증
 * - 이미 등록된 법인인지 확인
 */
boolean isCorporateConversionAllowed(String businessRegNo, String correlationId);

/**
 * 14세 미만 분기 판단 (PPTX 2.3)
 */
boolean isMinorAccount(int birthYear, int birthMonth, int birthDay);
```

### 5.3 기업회원 전환 (PPTX 2.4)

```
기업회원 전환은 개인 CI 대신 사업자등록번호 기반으로 동작

신규 필요 클래스:
  CorporateConversionCommand — 사업자등록번호, 기업명, 대표자명 포함
  CorporateAuthMeanMapping — providerCode = "CORPORATE_CERT" or "CORPORATE_GPKI"

q-im.qim_user에 memberType 컬럼 추가 (PERSONAL / CORPORATE)
q-im.user_profile에 business_reg_no, company_name, ceo_name 컬럼 추가
```

---

## 6. Phase 3 — 회원 탈퇴·삭제 프로세스

### 6.1 4종 탈퇴 프로세스 구현

#### 6.1.1 기본 탈퇴 (PPTX 3.4)

```
흐름: 탈퇴 요청 → 본인인증 → 탈퇴 제한 조건 체크 → 공통정보 삭제 → 유관시스템 전파
구현: UserServiceImpl.withdrawUser() 완성

추가 구현:
  - 탈퇴 제한 조건 체크 (미납 서비스, 진행중 신청 등)
  - 유관시스템 탈퇴 정보 전송 (Kafka 이벤트: USER_WITHDRAWN)
  - 개인정보 파기 (user_profile 마스킹)
```

#### 6.1.2 삭제 불가 시 논리적 삭제 (PPTX 3.5)

```java
// q-im/src/main/java/kr/go/smes/qim/domain/RetentionPolicy.java
@Getter @Builder
public class RetentionPolicy {
    private final String agencyCode;
    private final String retentionReason;    // 법적 보존 근거
    private final Instant retentionUntil;    // 보존 기간 만료일
    private final List<String> retainedFields; // 보존할 필드 목록
}

// 논리적 삭제 상태 추가
// qim.qim_user.status에 'LOGICALLY_DELETED' 추가
// qim.user_profile에 logical_deletion_at, retention_until 컬럼 추가
```

```
DB 마이그레이션 필요:
  V4__add_retention_policy.sql
  - qim.retention_policy 테이블 신규 생성
  - qim.qim_user.status CHECK에 'LOGICALLY_DELETED' 추가
  - 보존기간 만료 시 영구파기 스케줄러 (별도 배치 또는 Spring @Scheduled)
```

#### 6.1.3 부분 탈퇴 (PPTX 3.6 — 일부 유관시스템만)

```java
// q-im/src/main/java/kr/go/smes/qim/application/UserService.java에 추가
/**
 * 특정 유관시스템에 대한 인증수단 매핑 해제 (부분 탈퇴)
 * PPTX 3.6: 탈퇴 대상 시스템 선택 → 영향도 고지 → 본인인증 → 해제
 */
QimUser revokeAuthMeanMapping(String qimUserId, List<String> agencyCodes, 
                               String reason, String correlationId);
```

#### 6.1.4 개인정보포털 탈퇴 (PPTX 3.7)

```
특수 프로세스: privacy.go.kr 에서 탈퇴 요청이 오는 배치/관리 콘솔 경로

필요 구현:
  PrivacyPortalWithdrawalCommand:
    - 로컬 ID → UUID 변환 추출
    - 수동 로그인 식별정보 확인
    - 관리 콘솔 엑셀 업로드 지원
    - 수동 로그인 차단 Flag 전파
    - SSO 세션 강제 종료 (FeSession 전체 무효화)
    - 탈퇴 요청 Upsert
    - UUID 유효성 검증
    - 개인정보 파기/비식별화

  신규 API:
    POST /api/admin/v1/users/privacy-portal-withdrawal
    (관리 콘솔 전용, 별도 인증 필요)
  
  기존 코드 연동:
    FeSessionService.invalidateByQimUserId() ← 이미 구현됨 ✅
    FeSessionService.markAdvisoryFlag() ← 이미 구현됨 ✅
```

### 6.2 탈퇴 제한 조건 체크 서비스

```java
// ido/src/main/java/kr/go/smes/ido/policy/WithdrawalPolicyEngine.java
public interface WithdrawalPolicyEngine {
    /**
     * 탈퇴 가능 여부 판단
     * 제한 조건 예시:
     *   - 미납 대출 잔액 존재
     *   - 진행 중인 보조금 신청
     *   - 계류 중인 분쟁 처리
     */
    WithdrawalCheckResult check(String qimUserId, String correlationId);
    
    record WithdrawalCheckResult(
        boolean allowed,
        List<String> blockedReasons,    // 제한 사유 목록
        List<String> retentionRequired  // 법적 보존 필요 유관시스템
    ) {}
}
```

---

## 7. Phase 4 — 장애 Fallback 및 IM 장애 모드

### 7.1 중기원패스 장애 시 (PPTX 4.1)

```
현재 상태:
  - Resilience4j Circuit Breaker 설정 있음 (application.yml)
  - IDO_QIM_UNREACHABLE 에러코드 있음
  - 그러나 Fallback 응답이 미구현

구현 목표:
  - Circuit OPEN 시 → 유관시스템별 임시 로그인 URL 반환
  - BrokerController에 Fallback 응답 추가

```

```java
// ido/src/main/java/kr/go/smes/ido/broker/BrokerController.java 수정

@GetMapping("/{provider}/authorize")
public ResponseEntity<?> authorize(...) {
    try {
        String authUrl = brokerService.buildAuthorizationUrl(...);
        return ResponseEntity.status(302).header("Location", authUrl).build();
    } catch (CallNotPermittedException e) {
        // Circuit OPEN → 유관시스템 임시 로그인 URL로 리다이렉트 (PPTX 4.1)
        log.warn("[IdO] Circuit OPEN — Fallback to agency direct login");
        String fallbackUrl = agencyMetaService.getFallbackLoginUrl(provider);
        return ResponseEntity.status(302).header("Location", fallbackUrl).build();
    }
}
```

```
AgencyMeta에 추가 필드:
  - fallbackLoginUrl: String (유관시스템 자체 임시 로그인 URL)
  - fallbackEnabled: boolean

DB 마이그레이션:
  V5__add_fallback_config.sql
  ido.agency_meta에 fallback_login_url, fallback_enabled 컬럼 추가
```

### 7.2 IM 서버 장애 모드 (PPTX 4.2)

```
현재:
  - UserStatusCache (Redis) 존재 — 캐시 우선 조회 구조
  - PolicyEngine.resolveUserStatus()에서 Q-IM 직접 조회
  - 장애 시 IDO_QIM_UNREACHABLE 에러만 반환

구현 목표:
  1. Q-IM 장애 시 최종 캐시 기반 임시 허용 (Stale Cache Mode)
  2. 임시 로그인 허용 후 Q-IM 복구 시 동기화 이벤트 발행
  3. 임시 허용 기간: 최대 30분 (설정 가능)
```

```java
// ido/src/main/java/kr/go/smes/ido/policy/PolicyEngineImpl.java 수정

@Override
public UserStatus resolveUserStatus(String qimUserId, String correlationId) {
    // 1. Redis 캐시 우선 조회
    Optional<UserStatus> cached = userStatusCache.get(qimUserId);
    if (cached.isPresent()) return cached.get();
    
    // 2. Q-IM 직접 조회 (Circuit Breaker)
    try {
        return qimClient.getUserStatus(qimUserId, correlationId);
    } catch (CallNotPermittedException | QimUnavailableException e) {
        // 3. Circuit OPEN — Stale Cache에서 마지막 알려진 상태 반환
        log.warn("[IdO] Q-IM 장애 — Stale Cache 모드 진입 qimUserId={}", qimUserId);
        return userStatusCache.getStale(qimUserId)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId));
    }
}
```

```
UserStatusCache에 추가:
  - getStale(qimUserId): 만료된 캐시도 허용하여 반환
  - isStaleMode(): Circuit OPEN 여부 확인
  - Stale TTL: 별도 설정 (기본 1800초)
```

---

## 8. DB 마이그레이션 계획

### 8.1 Q-IM 마이그레이션 (신규)

```sql
-- V2__add_conversion_tables.sql (q-im)

-- 1. 회원 타입 구분 (개인/기업)
ALTER TABLE qim.qim_user ADD COLUMN member_type VARCHAR(20) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE qim.qim_user ADD CONSTRAINT chk_qim_member_type 
    CHECK (member_type IN ('PERSONAL', 'CORPORATE'));

-- 2. 개인정보 보존 정책 테이블
CREATE TABLE qim.retention_policy (
    policy_id           VARCHAR(36)   NOT NULL,
    qim_user_id         VARCHAR(36)   NOT NULL,
    agency_code         VARCHAR(50)   NOT NULL,
    retention_reason    VARCHAR(200)  NOT NULL,  -- 법적 근거
    retention_until     TIMESTAMPTZ   NOT NULL,
    retained_fields     JSONB,                   -- 보존 항목
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_retention_policy PRIMARY KEY (policy_id),
    CONSTRAINT fk_retention_user 
        FOREIGN KEY (qim_user_id) REFERENCES qim.qim_user(qim_user_id)
);
CREATE INDEX idx_retention_expiry ON qim.retention_policy (retention_until)
    WHERE retention_until > NOW();

-- 3. 개인정보 동의 기록
CREATE TABLE qim.consent_record (
    consent_id          VARCHAR(36)   NOT NULL,
    qim_user_id         VARCHAR(36)   NOT NULL,
    consent_type        VARCHAR(50)   NOT NULL,  -- THIRD_PARTY_PROVISION / SERVICE_TERMS
    consent_version     VARCHAR(20)   NOT NULL,
    agreed              BOOLEAN       NOT NULL,
    ip_address          VARCHAR(45),
    consented_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_consent_record PRIMARY KEY (consent_id),
    CONSTRAINT fk_consent_user 
        FOREIGN KEY (qim_user_id) REFERENCES qim.qim_user(qim_user_id),
    CONSTRAINT chk_consent_type 
        CHECK (consent_type IN ('THIRD_PARTY_PROVISION','SERVICE_TERMS','PRIVACY_POLICY'))
);
CREATE INDEX idx_consent_user ON qim.consent_record (qim_user_id, consented_at DESC);

-- 4. 기업회원 프로필 확장
ALTER TABLE qim.user_profile ADD COLUMN business_reg_no VARCHAR(20);
ALTER TABLE qim.user_profile ADD COLUMN company_name    VARCHAR(200);
ALTER TABLE qim.user_profile ADD COLUMN ceo_name_masked VARCHAR(100);

-- 5. 논리적 삭제 상태 추가
ALTER TABLE qim.qim_user DROP CONSTRAINT chk_qim_user_status;
ALTER TABLE qim.qim_user ADD CONSTRAINT chk_qim_user_status
    CHECK (status IN ('ACTIVE','SUSPENDED','WITHDRAWN','LOGICALLY_DELETED'));

ALTER TABLE qim.user_profile ADD COLUMN logical_deletion_at TIMESTAMPTZ;
ALTER TABLE qim.user_profile ADD COLUMN retention_until     TIMESTAMPTZ;
```

### 8.2 IdO 마이그레이션 (신규)

```sql
-- V4__add_withdrawal_and_fallback.sql (ido)

-- 1. 기관 메타 — 장애 Fallback 설정
ALTER TABLE ido.agency_meta ADD COLUMN fallback_login_url VARCHAR(500);
ALTER TABLE ido.agency_meta ADD COLUMN fallback_enabled   BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. 탈퇴 제한 조건 로그
CREATE TABLE ido.withdrawal_check_log (
    log_id              VARCHAR(36)   NOT NULL,
    qim_user_id         VARCHAR(36)   NOT NULL,
    correlation_id      VARCHAR(36),
    check_result        VARCHAR(20)   NOT NULL,  -- ALLOWED / BLOCKED
    blocked_reasons     JSONB,
    retention_required  JSONB,                   -- 법적 보존 필요 기관 목록
    checked_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_withdrawal_check_log PRIMARY KEY (log_id)
);
CREATE INDEX idx_withdrawal_check_user ON ido.withdrawal_check_log (qim_user_id, checked_at DESC);

-- 3. 개인정보포털 탈퇴 요청 추적
CREATE TABLE ido.privacy_portal_withdrawal (
    request_id          VARCHAR(36)   NOT NULL,
    qim_user_id         VARCHAR(36),             -- 로컬ID → UUID 변환 후 채움
    local_user_id       VARCHAR(200),            -- 원본 로컬 ID (암호화)
    status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    received_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ,
    admin_id            VARCHAR(100),
    CONSTRAINT pk_privacy_portal_withdrawal PRIMARY KEY (request_id),
    CONSTRAINT chk_pp_withdrawal_status 
        CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED'))
);
```

---

## 9. API 신규 명세

### 9.1 회원 전환 API (IdO)

| Method | Path | 설명 | PPTX 참조 |
|--------|------|------|-----------|
| `POST` | `/api/v1/conversion/initiate` | 전환 세션 개시 | 2.1~2.4 |
| `POST` | `/api/v1/conversion/{sid}/consent` | 개인정보 동의 기록 | 2.1.4 |
| `POST` | `/api/v1/conversion/{sid}/ci-verified` | CI 인증 완료 콜백 | 2.1.5 |
| `GET`  | `/api/v1/conversion/{sid}/candidates` | 유관시스템 후보 목록 | 2.1.6 |
| `POST` | `/api/v1/conversion/{sid}/select` | 연결 대상 선택 | 2.1.7 |
| `POST` | `/api/v1/conversion/{sid}/complete` | 통합계정 생성 완료 | 2.1.9~2.1.12 |

### 9.2 회원관리 API (Q-IM)

| Method | Path | 설명 | PPTX 참조 |
|--------|------|------|-----------|
| `POST` | `/api/v1/users/find-id` | 개인회원 ID 찾기 | 3.1 |
| `POST` | `/api/v1/users/find-password` | 개인회원 PW 찾기 | 3.2 |
| `POST` | `/api/v1/users/corporate/find-password` | 기업회원 PW 찾기 | 3.3 |
| `POST` | `/api/v1/users/{id}/withdraw` | 기본 탈퇴 | 3.4 |
| `POST` | `/api/v1/users/{id}/revoke-agency` | 유관시스템별 부분 탈퇴 | 3.6 |
| `PUT`  | `/api/v1/users/{id}/profile` | 회원정보 수정 | 3.8 |

### 9.3 관리 콘솔 API (IdO)

| Method | Path | 설명 | PPTX 참조 |
|--------|------|------|-----------|
| `POST` | `/api/admin/v1/users/privacy-portal-withdrawal` | 개인정보포털 탈퇴 처리 | 3.7 |
| `GET`  | `/api/admin/v1/users/{id}/withdrawal-check` | 탈퇴 제한 조건 확인 | 3.4~3.6 |

### 9.4 에러 코드 신규 추가

```java
// platform-common/src/main/java/kr/go/smes/common/error/PlatformErrorCode.java 추가

// ── 회원 전환 오류 (E-CONV-5xx) ──────────────────────────────────────────
CONV_SESSION_EXPIRED      ("E-CONV-501", HttpStatus.GONE,          "전환 세션이 만료되었습니다."),
CONV_SESSION_NOT_FOUND    ("E-CONV-502", HttpStatus.NOT_FOUND,     "전환 세션을 찾을 수 없습니다."),
CONV_INVALID_STEP         ("E-CONV-503", HttpStatus.CONFLICT,      "잘못된 전환 단계입니다."),
CONV_USERID_DUPLICATE     ("E-CONV-504", HttpStatus.CONFLICT,      "이미 사용 중인 아이디입니다."),
CONV_AGENCY_LOOKUP_FAILED ("E-CONV-505", HttpStatus.BAD_GATEWAY,   "유관시스템 조회에 실패했습니다."),

// ── 탈퇴 오류 (E-WDRL-6xx) ───────────────────────────────────────────────
WDRL_BLOCKED              ("E-WDRL-601", HttpStatus.FORBIDDEN,     "탈퇴 제한 조건이 있습니다."),
WDRL_RETENTION_REQUIRED   ("E-WDRL-602", HttpStatus.ACCEPTED,      "일부 데이터는 법적 보존 후 파기됩니다."),
WDRL_MINOR_NOT_ALLOWED    ("E-WDRL-603", HttpStatus.FORBIDDEN,     "미성년자 탈퇴는 보호자 인증이 필요합니다."),
```

---

## 10. 이벤트 토픽 설계

### 10.1 신규 Kafka 토픽

| 토픽 | 생산자 | 소비자 | 용도 |
|------|-------|-------|------|
| `qim.sp.member.events` | IdO (Outbox) | IdO (QimSpMemberEventConsumer) | **[v1.2.0 구현완료]** SP 수신 회원 이벤트 내부 전파 |
| `qim.conversion.events` | Q-IM | IdO, agency-stub | 전환 완료/취소 이벤트 |
| `qim.consent.events` | Q-IM | 법무/감사 시스템 | 개인정보 동의 기록 |
| `qim.withdrawal.events` | Q-IM | IdO, 유관시스템 | 탈퇴/삭제 전파 |
| `ido.fallback.events` | IdO | 모니터링 | Circuit Breaker 전환 감사 |

> **`qim.sp.member.events` 이벤트 타입** (v1.2.0 구현):
> - `QIM_MEMBER_REGISTERED`  — Q-IM이 SP(IdO)에 신규 회원 등록 통보
> - `QIM_MEMBER_TRANSFERRED` — Q-IM이 SP(IdO)에 전환 회원 등록 통보
> - `QIM_MEMBER_WITHDRAWN`   — Q-IM이 SP(IdO)에 회원 탈퇴 통보

### 10.2 이벤트 페이로드

```json
// qim.withdrawal.events 예시
{
  "eventId": "uuid",
  "eventType": "USER_WITHDRAWN",         // USER_WITHDRAWN / LOGICALLY_DELETED / MAPPING_REVOKED
  "sourceSystem": "q-im",
  "correlationId": "uuid",
  "qimUserId": "uuid",
  "version": 5,
  "withdrawalType": "FULL",              // FULL / PARTIAL / LOGICAL
  "affectedAgencyCodes": ["SBIZ24"],
  "retentionRequired": {
    "SBIZ24": "2028-12-31"              // 유관시스템별 보존 만료일
  },
  "occurredAt": "2026-05-07T10:00:00Z"
}
```

```json
// qim.conversion.events 예시
{
  "eventId": "uuid",
  "eventType": "CONVERSION_COMPLETED",
  "sourceSystem": "q-im",
  "correlationId": "uuid",
  "qimUserId": "uuid",
  "memberType": "PERSONAL",
  "linkedAgencyCodes": ["SBIZ24", "BIZ_PLAZA"],
  "occurredAt": "2026-05-07T10:00:00Z"
}
```

---

## 11. 테스트 전략

### 11.1 단위 테스트 우선순위

| 클래스 | 테스트 케이스 | 우선순위 |
|--------|-------------|---------|
| `MemberConversionService` | 전환 단계별 상태머신 전이 검증 | P0 |
| `AgencyMemberLookupService` | 병렬 조회 + 부분 실패 허용 | P0 |
| `WithdrawalPolicyEngine` | 제한 조건 체크 | P0 |
| `UserServiceImpl.addAuthMeanMapping` | 중복 매핑 방지 | P0 |
| `MinorGuardianService` | 14세 미만 분기 | P1 |
| `PolicyEngineImpl.resolveUserStatus` | Stale Cache 모드 | P1 |

### 11.2 통합 테스트 시나리오 (PoC 검증)

```
시나리오 1: 개인회원 신규 가입 (PPTX 1.2)
  1. /api/v1/conversion/initiate (memberType=PERSONAL)
  2. 개인정보 동의 기록
  3. 본인인증 (PASS 비OIDC) → CI 획득
  4. 유관시스템 후보 조회 (agency-stub 목 사용)
  5. 연결 대상 선택 (중소벤처24 선택)
  6. 통합계정 생성 완료
  7. FeSession 발급 → feSessionId 쿠키 확인

시나리오 2: 기존 회원 통합계정 전환 (PPTX 2.1)
  1. 유관시스템 ID/PW 로그인 (agency-stub 시뮬레이션)
  2. 통합계정 전환 팝업 → 동의
  3. CI 기반 68개 유관시스템 조회
  4. 매칭 계정 3개 중 2개 선택
  5. 통합계정 UUID 생성
  6. 동기화 이벤트 발행 확인

시나리오 3: 기업회원 전환 (PPTX 2.4)
  1. 사업자등록번호 입력
  2. 기업 인증 (GPKI)
  3. 사업자번호 기반 유관시스템 조회
  4. 기업회원 통합계정 생성

시나리오 4: 중기원패스 장애 Fallback (PPTX 4.1)
  1. Resilience4j Circuit Breaker를 강제 OPEN
  2. /api/v1/broker/kakao/authorize 호출
  3. fallbackLoginUrl로 리다이렉트 확인

시나리오 5: 기본 탈퇴 (PPTX 3.4)
  1. 탈퇴 제한 조건 체크 (통과)
  2. 본인인증 완료
  3. USER_WITHDRAWN 이벤트 발행 확인
  4. FeSession 전체 무효화 확인
  5. 유관시스템 탈퇴 정보 전송 확인
```

---

## 12. 우선순위 매트릭스

### 12.1 구현 우선순위 (PoC 기준)

| 우선순위 | 항목 | 담당 모듈 | Phase | 예상 공수 |
|---------|------|----------|-------|---------|
| **P0** | `addAuthMeanMapping` 실제 구현 완성 | q-im | 1 | 0.5일 |
| **P0** | `ConversionSession` Redis 상태머신 | q-im | 1 | 2일 |
| **P0** | `AgencyMemberLookupService` (목 기반) | q-im | 1 | 1일 |
| **P0** | 회원 전환 API 4종 (initiate→complete) | ido | 2 | 3일 |
| **P0** | 개인정보 동의 기록 | q-im | 1 | 1일 |
| **P0** | 기본 탈퇴 완성 (이벤트 전파 포함) | q-im/ido | 3 | 2일 |
| **P1** | 기업회원 전환 (사업자번호 기반) | q-im | 1 | 2일 |
| **P1** | 논리적 삭제 + 보존정책 | q-im | 3 | 1.5일 |
| **P1** | Circuit Breaker Fallback (PPTX 4.1) | ido | 4 | 1일 |
| **P1** | Stale Cache 장애 모드 (PPTX 4.2) | ido | 4 | 1일 |
| **P2** | 14세 미만 보호자 인증 분기 | q-im | 1 | 1일 |
| **P2** | ID/PW 찾기 (개인/기업) | q-im | 2 | 1.5일 |
| **P2** | 개인정보포털 탈퇴 (PPTX 3.7) | ido | 3 | 2일 |
| **P2** | 회원정보 수정 + 유관시스템 동기화 | q-im/ido | 2 | 2일 |
| **P3** | 부분 탈퇴 (일부 유관시스템) | q-im | 3 | 1.5일 |
| **P3** | 보존기간 만료 영구파기 스케줄러 | q-im | 3 | 1일 |

### 12.2 PoC MVP 범위

```
PoC에서 반드시 확인해야 할 최소 기능 (P0 전부 + P1 일부):

✅ 신규 회원 통합계정 생성 (UUID 발급)
✅ 기존 회원 통합계정 전환 (CI → 유관시스템 조회 → 선택 → 완료)
✅ 개인정보 동의 기록
✅ 기본 탈퇴 + 이벤트 전파
✅ Circuit Breaker Fallback
⬜ 기업회원 전환 (P1, 데모 가능 수준)
⬜ 14세 미만 분기 (P2, 시간 허용 시)
```

---

## 부록 A. 현재 코드베이스 활용 가능 자산

| 자산 | 위치 | Phase 연동 방법 |
|-----|------|---------------|
| `FeSessionService.invalidateByQimUserId()` | ido | Phase 3 탈퇴 시 세션 무효화 |
| `FeSessionService.markAdvisoryFlag()` | ido | Phase 4 장애 모드 Advisory |
| `UserStatusCache` | ido | Phase 4 Stale Cache 모드 확장 |
| `NonOidcBrokerAdapter` | ido | Phase 1 CI 획득 경로 (PASS 연동) |
| `IdoOidcStateStore` | ido | Phase 1 ConversionSession 패턴 참고 |
| `QimEventConsumer` | ido | Phase 3 탈퇴 이벤트 소비 확장 |
| `OutboxService` | q-im | Phase 1~3 전 이벤트 발행 |
| `AgencyMeta.callbackWhitelist` | ido | Phase 2 전환 완료 returnUrl 검증 |

## 부록 B. agency-stub 확장 필요 항목

```
PoC용 agency-stub에 추가해야 할 Mock API:

  GET  /stub/api/member/by-ci-hash          → 유관시스템 회원 조회 (CI 해시 기반)
  GET  /stub/api/member/by-business-reg     → 기업회원 조회 (사업자번호 기반)
  POST /stub/api/member/withdrawal-notify   → 탈퇴 알림 수신
  POST /stub/api/member/sync                → 회원정보 동기화
  GET  /stub/api/health                     → 장애 시뮬레이션 (강제 503 반환)
```
