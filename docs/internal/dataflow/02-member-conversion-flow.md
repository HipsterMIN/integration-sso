# 회원 전환(신규 가입) 데이터 흐름 (A→Z 완전 추적)

**문서 ID**: FLOW-2026-002  
**버전**: v1.0  
**작성일**: 2026-05-11  
**작성자**: AI 분석 (GenSpark)  
**대상 독자**: 개발팀, 운영팀  

---

## 목차

1. [회원 전환 개요](#1-회원-전환-개요)
2. [FE 라우트 구조](#2-fe-라우트-구조)
3. [개인 회원 전환 흐름 (A→Z)](#3-개인-회원-전환-흐름-az)
4. [기업 회원 전환 흐름 (A→Z)](#4-기업-회원-전환-흐름-az)
5. [NICE 휴대폰 본인인증 상세 흐름](#5-nice-휴대폰-본인인증-상세-흐름)
6. [CI 처리 및 Q-IM 등록 흐름](#6-ci-처리-및-q-im-등록-흐름)
7. [DB 변경 전체 목록](#7-db-변경-전체-목록)
8. [Kafka 이벤트 발행 목록](#8-kafka-이벤트-발행-목록)
9. [미구현 항목 및 주의사항](#9-미구현-항목-및-주의사항)

---

## 1. 회원 전환 개요

**회원 전환**이란 기존 SMEP(중소기업 통합관리 플랫폼) 회원이 "중기원패스" 통합회원으로 전환하는 프로세스다.

```
전환 전: SMEP 기존 회원 (개별 서비스 계정)
전환 후: 중기원패스 통합회원 (feSessionId 발급, Q-IM 등록 완료)
```

**전환 흐름 진입점**:
- `redirect_uri` + `mbrId` 파라미터를 포함한 URL로 진입
- 파라미터 없으면 "잘못된 접근" 모달 표시

**핵심 기술**: NICE 휴대폰 본인인증 (CI 기반 신원 확인)

---

## 2. FE 라우트 구조

```
/conversion/step1         → ConversionStep1 (회원유형 선택)
/conversion/member/step2  → ConversionMemberStep2 (약관 동의)
/conversion/member/step3  → ConversionMemberStep3 (본인인증)
/conversion/member/step4  → ConversionMemberStep4 (기존 회원 확인)
/conversion/member/step5  → ConversionMemberStep5 (정보 입력)
/conversion/member/step6  → ConversionMemberStep6 (완료)
/conversion/member/step8  → ConversionMemberStep8 (추가 단계)

/conversion/business/step2 → ConversionBusinessStep2
/conversion/business/step3 → ConversionBusinessStep3
/conversion/business/step4 → ConversionBusinessStep4
/conversion/business/step5 → ConversionBusinessStep5
/conversion/business/step6 → ConversionBusinessStep6
```

**신규 가입도 동일 라우트 사용**:
```
/register/step1            → RegisterStep1 (회원유형 선택, type=member|business)
/register/member/step2 ~6  → 동일 컴포넌트 (ConversionMemberStep2~6)
/register/business/step2~6 → 동일 컴포넌트 (ConversionBusinessStep2~6)
```

**상태 관리**: `ConversionContext` Provider → useConversion() 훅

---

## 3. 개인 회원 전환 흐름 (A→Z)

```
사용자     FE(React)          ido(8083)              NICE 서버           q-im(8082)         외부 API(레거시)
  │            │                  │                      │                   │                   │
  │ [A] URL 진입│                  │                      │                   │                   │
  │ ?redirect_uri=...             │                      │                   │                   │
  │ &mbrId=...  │                  │                      │                   │                   │
  │────────────>│                  │                      │                   │                   │
  │             │ [B] Step1: 회원유형 선택               │                   │                   │
  │             │ (개인회원 라디오 선택)                  │                   │                   │
  │             │                  │                      │                   │                   │
  │             │ [C] Step2: 약관 동의                   │                   │                   │
  │             │ (필수 약관 모두 동의 체크)              │                   │                   │
  │             │                  │                      │                   │                   │
  │             │ [D] Step3: 본인인증 선택               │                   │                   │
  │             │ (휴대폰인증 / 간편인증)                │                   │                   │
  │             │                  │                      │                   │                   │
  │             │ [E-1] 휴대폰 인증 선택 시:             │                   │                   │
  │             │ GET /api/v1/auth/nice/phone/url        │                   │                   │
  │             │─────────────────>│                      │                   │                   │
  │             │                  │ [F] POST /auth/token │                   │                   │
  │             │                  │─────────────────────>│                   │                   │
  │             │                  │ Access Token 발급    │                   │                   │
  │             │                  │<─────────────────────│                   │                   │
  │             │                  │ [G] POST /auth/url   │                   │                   │
  │             │                  │─────────────────────>│                   │                   │
  │             │                  │ authUrl+transactionId│                   │                   │
  │             │                  │<─────────────────────│                   │                   │
  │             │                  │ Redis: save(requestNo│transactionId)     │                   │
  │             │ {authUrl, requestNo} 반환              │                   │                   │
  │             │<─────────────────│                      │                   │                   │
  │ [H] NICE 팝업 오픈                                   │                   │                   │
  │<────────────│                  │                      │                   │                   │
  │ [I] 사용자 휴대폰 인증 완료                          │                   │                   │
  │ NICE팝업→postMessage(web_transaction_id)             │                   │                   │
  │────────────>│                  │                      │                   │                   │
  │             │ [J] POST /api/v1/auth/nice/phone/result│                   │                   │
  │             │─────────────────>│                      │                   │                   │
  │             │                  │ [K] Redis: find      │                   │                   │
  │             │                  │ (requestNo→transId)  │                   │                   │
  │             │                  │ POST /auth/result    │                   │                   │
  │             │                  │─────────────────────>│                   │                   │
  │             │                  │ {encData, integrityValue}                │                   │
  │             │                  │<─────────────────────│                   │                   │
  │             │                  │ [L] HMAC 검증        │                   │                   │
  │             │                  │ AES-GCM 복호화       │                   │                   │
  │             │                  │ CI/DI/name/birth 추출│                   │                   │
  │             │                  │ [M] POST /api/v1/   │                   │                   │
  │             │                  │ internal/users      │                   │                   │
  │             │                  │─────────────────────────────────────────>│                   │
  │             │                  │                      │                   │ [N] identHash 조회│
  │             │                  │                      │                   │ 신규이면 INSERT   │
  │             │                  │                      │                   │ Outbox 적재       │
  │             │                  │ {qimUserId, isNew}  │                   │                   │
  │             │                  │<─────────────────────────────────────────│                   │
  │             │ {name,birth,gender,di} 반환 (CI 제외)  │                   │                   │
  │             │<─────────────────│                      │                   │                   │
  │             │ [O] Step4: 기존 회원 확인               │                   │                   │
  │             │ GET /api/v1/auth/nice/ci-check         │                   │                   │
  │             │─────────────────>│                      │                   │                   │
  │             │                  │ [P] 파라미터 검증    │                   │                   │
  │             │                  │ (현재 Q-IM 미연동    │                   │                   │
  │             │                  │ — S7-T6 완료 전)    │                   │                   │
  │             │ {result:true}    │                      │                   │                   │
  │             │<─────────────────│                      │                   │                   │
  │             │ [Q] Step5: 추가 정보 입력               │                   │                   │
  │             │ (이메일, 알림 설정 등)                  │                   │                   │
  │             │                  │                      │                   │                   │
  │             │ [R] Step6: 완료 화면                   │                   │                   │
  │             │ "회원 전환이 완료되었습니다"            │                   │                   │
  │             │                  │                      │                   │                   │
  │             │ [S] redirect_uri로 이동                │                   │                   │
  │<────────────│                  │                      │                   │                   │
```

### 3.1 Step1 — 회원유형 선택

**파일**: `onepass-fe/frontend/src/pages/ConversionSteps/member/Step1.tsx`

```typescript
// ConversionStep1.tsx
// URL 파라미터 파싱
const params = new URLSearchParams(window.location.search);
const redirectUri = params.get('redirect_uri');  // 완료 후 이동할 URL
const mbrId = params.get('mbrId');              // 기존 SMEP 회원 ID

// 검증: 두 파라미터 모두 필수
if (!redirectUri || !mbrId) {
    setMissingParams(true);  // "잘못된 접근" 모달 표시
    return;
}

// Context에 저장
updateData({ redirectUri, mbrId });
```

**상태 전이**: `ConversionContext`에 `{ redirectUri, mbrId, memberType }` 저장

### 3.2 Step2 — 약관 동의

**처리 내용**:
- 서비스 이용 약관 (필수)
- 개인정보 처리방침 (필수)
- 마케팅 수신 동의 (선택)

API 호출: 약관 목록 조회 (`GET /api/v1/terms-bundle` 또는 외부 API)

### 3.3 Step3 — 본인인증 (NICE 휴대폰 인증)

> 상세 흐름은 [5절 NICE 휴대폰 본인인증 상세 흐름](#5-nice-휴대폰-본인인증-상세-흐름) 참조

**핵심 데이터 흐름**:
```
FE → GET /api/v1/auth/nice/phone/url
   ← { authUrl, requestNo }
FE → 팝업 오픈 → 사용자 인증
   → postMessage 수신
FE → POST /api/v1/auth/nice/phone/result
   ← { name, birthdate, gender, nationalInfo, di, mobileCo, mobileNo }
   (* CI는 응답에 없음 — Q3=B 보안 정책)
```

### 3.4 Step4 — 기존 회원 확인 (ci-check)

**파일**: `onepass-fe/frontend/src/api/nice/ciCheck.ts`

```
POST /api/v1/auth/nice/ci-check
Body:
{
  "ci": {nice_result.ci},    ← NICE 결과에서 수신한 CI (FE가 임시 보관)
  "mbrDvsnCd": "A101",      ← 개인회원
  "indvlMbrNm": "홍길동",
  "indvlMbrId": "user123"
}
```

> ⚠️ **미완성**: 현재 파라미터 검증 후 `{ result: true }` 반환만 구현.  
> S7-T6 완료 후 Q-IM API 연동 필요 (CI로 기존 회원 조회 → 전환 가능 여부 확인).

### 3.5 Step5 — 추가 정보 입력

사용자가 이메일, 알림 수신 설정 등을 입력한다. NICE 인증으로 이미 획득한 정보(이름, 생년월일 등)는 자동 채워진다.

### 3.6 Step6 — 회원 전환 완료

전환 완료 화면 표시 후 `redirectUri`로 이동.

---

## 4. 기업 회원 전환 흐름 (A→Z)

```
Step1: 회원유형 선택 (기업회원)
  ↓ URL 파라미터: redirect_uri, mbrId
  
Step2: 약관 동의
  ↓
  
Step3: 기업 본인인증
  → OACX 전자서명 간편인증 (기업용)
  → POST /api/v1/auth/oacx/access-info (서버에서 accKey/accToken 발급)
  → OACXsdk.init() → OACXsdk.open()
  → 사용자 간편서명
  → POST /api/v1/auth/oacx/easysign (JWT 복호화, CI 추출)
  ↓
  
Step4: 기업 정보 확인
  → 사업자등록번호 기반 기업 정보 조회
  → GET /api/v1/ext/businessValidate?bizno={사업자등록번호}
  → 국세청 API 또는 레거시 SMEP API 연동
  ↓

Step5: 기업 세부 정보 입력
  → 대표자명, 연락처, 이메일 등
  ↓

Step6: 완료
  → redirect_uri로 이동
```

**기업인증 특이사항**:
- `POST /api/v1/auth/callback` (AuthController의 기업 간편인증 콜백) 사용
- 현재 FE Step3 기업인증 코드에 "미구현" 주석 다수 존재 (Q2=B 상태)
- 통합인증 서버에 auth-check 요청하는 흐름

---

## 5. NICE 휴대폰 본인인증 상세 흐름

**파일**: `ido/src/main/java/kr/go/smes/ido/auth/service/NiceAuthService.java`

### 5.1 Access Token 획득 (분산 락 적용)

```
Step 1: 빠른 경로 — 캐시 확인
  if (NiceTokenStore.isValid()) return token;  // 락 없이 즉시 반환

Step 2: Redisson 분산 락 획득
  lock.tryLock(waitSec=3, leaseSec=10, SECONDS)
  
  실패 시: 500ms 대기 후 캐시 재확인
           캐시 없으면 IllegalStateException
  
  성공 시: Double-Checked Locking (다른 Pod가 이미 갱신했을 수 있음)
           여전히 무효이면: NICE API 호출

Step 3: NICE Access Token API 호출
  POST https://nice-api/auth/v2/token
  Authorization: Basic {clientId:clientSecret base64}
  요청 번호: REQ_{yyyyMMddHHmmss}{UUID 12자}
  
  응답: { accessToken, expiresIn, ticket, iterators }

Step 4: Redis 저장 및 락 해제
  NiceTokenStore.save(accessToken, expiresIn, ticket, iterators)
  lock.unlock()
```

### 5.2 인증 URL 발급

```java
// NiceAuthService.getNicePhoneAuthUrl()
String requestNo = "REQ_" + yyyyMMddHHmmss + UUID.substring(0, 12);
String effectiveReturnUrl = returnUrl != null ? returnUrl : defaultReturnUrl;

// NICE URL 발급 API 호출
NiceUrlApiResponse urlResponse = niceApiClient.requestAuthUrl(
    token.accessToken(), requestNo, effectiveReturnUrl);

// Redis에 세션 저장 (requestNo → transactionId)
sessionStore.save(responseRequestNo, urlResponse.getTransactionId());
// 키: nice:session:{requestNo}
// 내용: { requestNo, transactionId }
// TTL: 단기 (사용 후 삭제)

// FE에 반환
{
  "resultCode": "2000",
  "authUrl": "https://nice-auth/...",
  "requestNo": "REQ_20260511090000abc123"
}
```

### 5.3 인증 결과 조회 및 복호화

```java
// NiceAuthService.getNicePhoneAuthResult()

// 1. requestNo로 Redis 세션 조회
NiceAuthSession session = sessionStore.find(requestNo);
// session: { requestNo, transactionId }

// 2. NICE 결과 조회 API 호출
NiceResultApiResponse result = niceApiClient.requestAuthResult(
    token.accessToken(),
    webTransactionId,      // FE가 postMessage로 전달한 값
    session.transactionId(),
    session.requestNo()
);
// 응답: { resultCode, encData, integrityValue }

// 3. HMAC + AES-GCM 복호화 (decryptAndVerify)
//   a) PBKDF2WithHmacSHA256으로 키 파생
//      keyString = NiceCryptoUtil.deriveKey(ticket, transactionId, iterators)
//   b) 키 분리
//      aesKey = keyString[0..31]   (32바이트 = AES-256)
//      hmacKey = keyString[48..79] (32바이트)
//   c) HMAC-SHA256 무결성 검증
//      calculated = HMAC-SHA256(encData, hmacKey) → Base64URL
//      calculated == integrityValue  (불일치 시 DataIntegrityException → 5003)
//   d) AES-256-GCM 복호화
//      decrypted = AES-GCM.decrypt(aesKey, encData)
//   e) JSON 파싱
//      { name, birthdate, gender, national_info, ci, di, mobile_co, mobile_no }

// 4. 세션 삭제 (1회성)
sessionStore.remove(session.requestNo());

// 5. CI → Q-IM 등록 (S7-T6)
String ci = resultMap.get("ci");
if (ci != null) {
    AuthResult authResult = AuthResult.builder()
        .ci(ci).di(di).name(name).birthday(birthday)
        .gender(gender).mobile(mobileNo).mobileCorp(mobileCo)
        .build();
    QimRegisterResponse reg = imApiOutPort.register(authResult, correlationId);
    // Q-IM: POST /api/v1/internal/users/register
    //   → SHA-256(ci) → identifierHash → Upsert
    log.info("Q-IM 등록 완료: qimUserId={} isNew={}", reg.getQimUserId(), reg.getIsNew());
}

// 6. FE에 응답 (CI 제외)
{
  "resultCode": "2000",
  "resultData": {
    "name": "홍길동",
    "birthdate": "19900101",
    "gender": "M",
    "nationalInfo": "내국인",
    "di": "DI값...",       // 연계정보 (CI와 달리 기관에 제공 가능)
    "mobileCo": "SKT",
    "mobileNo": "01012345678"
    // ci 없음 — Q3=B 보안 정책
  }
}
```

---

## 6. CI 처리 및 Q-IM 등록 흐름

### 6.1 CI란?

**CI (연계정보)**: 개인 동일인 확인을 위한 고유 식별자.  
주민등록번호 기반으로 NICE/행정안전부가 발급. 서비스 간 공유 불가(법적 규제).

**DI (중복 가입 확인 정보)**: 사이트(기관)별 고유 식별자. 동일인 확인용으로 기관에 제공 가능.

### 6.2 Q-IM에서의 CI 처리

```java
// QimClientImpl.registerUser() → q-im: POST /api/v1/internal/users/register
// (현재는 /api/v1/internal/users POST Upsert 경로 사용)

// q-im: UserRegistrationServiceImpl.registerOrGet()
// 1. identifierHash = SHA-256(ci)로 기존 사용자 조회
var existing = userRepository.findByIdentifierHash(identifierHash);

// 2. 있으면 반환 (isNew=false)
if (existing.isPresent()) return toResponse(existing.get(), false);

// 3. 없으면 신규 생성
String qimUserId = UuidV7.generate();

// CI 암호화 저장 (원문 불가 → AES-256-GCM)
String encryptedCi = ciCryptoService.encrypt(rawCi);

// PII 마스킹
String nameMasked = piiMaskingService.maskName(rawName);      // "홍*동"
String mobileMasked = piiMaskingService.maskMobile(rawMobile); // "010****5678"

// 엔티티 생성 및 저장
QimUserJpaEntity user = ...;
UserProfileJpaEntity profile = ...;  // nameMasked, mobileMasked, encryptedCi
AuthMeanMappingJpaEntity mapping = ...;  // identifierHash (SHA-256(ci))

userRepository.save(user);  // Cascade: profile + mapping 함께 저장
outboxService.publishInTx(UserEvent(TYPE_UPDATED, ...));
```

### 6.3 CI 보안 정책 (Q3=B)

| 데이터 | FE 반환 여부 | DB 저장 형태 | 목적 |
|---|---|---|---|
| CI (연계정보) | ❌ 미반환 | AES-256-GCM 암호화 | 본인 확인, 탈퇴 시 삭제 |
| identifierHash | ❌ 미반환 | SHA-256(CI) → hex | 동일인 조회 키 |
| DI (중복확인정보) | ✅ 반환 가능 | JSON 맵 (기관별) | 기관에 제공 |
| 이름 | ❌ 원문 미저장 | "홍*동" 마스킹 | 표시용 |
| 전화번호 | ❌ 원문 미저장 | "010****5678" 마스킹 | 표시용 |

---

## 7. DB 변경 전체 목록

### 회원 전환 완료 시 DB 변경 (q-im)

| 테이블 | 작업 | 내용 | 조건 |
|---|---|---|---|
| `qim_user` | INSERT | qimUserId, status=ACTIVE, eventVersion=1 | 신규 사용자만 |
| `user_profile` | INSERT | nameMasked, mobileMasked, ci(암호화), birthYear, gender, nationalityType | 신규 사용자만 |
| `auth_mean_mapping` | INSERT | mappingId, providerCode, identifierHash(SHA-256(CI)), status=ACTIVE | 신규 사용자만 |
| `outbox` | INSERT | eventType=USER_UPDATED, payload=JSON | 신규 사용자만 |

### NICE 인증 관련 DB 변경 (ido)

현재 NICE 인증 결과는 Redis 세션으로만 관리 (DB 저장 없음).  
감사 로그는 Kafka를 통해 별도 저장.

---

## 8. Kafka 이벤트 발행 목록

| 토픽 | 이벤트 타입 | 발행 시점 | 내용 |
|---|---|---|---|
| `qim.user.events` | `USER_UPDATED` | 신규 사용자 등록 완료 | qimUserId, status=ACTIVE, reason=USER_REGISTERED |
| `ido.audit.events` | `NICE_URL_ISSUED` | NICE 인증 URL 발급 | requestNo, resultCode |
| `ido.audit.events` | `NICE_RESULT_RETRIEVED` | NICE 결과 조회 완료 | requestNo, webTransactionId, resultCode |

### UserEvent 구조 (신규 등록)

```json
{
  "eventType": "USER_UPDATED",
  "sourceService": "q-im",
  "correlationId": null,
  "qimUserId": "uuid-v7",
  "eventVersion": 1,
  "newStatus": "ACTIVE",
  "reason": "USER_REGISTERED",
  "needsSync": false
}
```

---

## 9. 미구현 항목 및 주의사항

### 9.1 미완성 기능 (개발 필요)

| 항목 | 상태 | 파일 | 설명 |
|---|---|---|---|
| `POST /api/v1/auth/nice/ci-check` | PoC | `AuthService.checkNiceCi()` | 파라미터 검증만 구현. Q-IM 실제 연동 필요 |
| 기업 간편인증 (Step3) | Q2=B 미구현 | `ConversionBusinessStep3.tsx` | "미구현" 주석 다수 |
| `POST /api/v1/auth/callback` | 향후 구현 | `AuthController.callback()` | 기업 간편인증 콜백 (FE 미연동) |
| 14세 미만 회원가입 | 미구현 | `ConversionStep1.tsx` | 버튼은 있으나 동작 없음 |
| 아이디 찾기 / 비밀번호 찾기 | 미구현 | `Login/index.tsx` | 버튼은 있으나 동작 없음 |

### 9.2 PoC 수준 코드 (운영 전 필수 수정)

| 코드 위치 | 문제 | 수정 방향 |
|---|---|---|
| `OidcCompleteController` | "identifierHash를 qimUserId 대용으로 사용" | Q-IM Upsert API 실호출로 교체 |
| `BrokerService.buildInternalSig()` | `"sig-" + correlationId.substring(0,8)` PoC 서명 | HMAC-SHA256으로 교체 |
| `AuthServiceImpl.issueFromOidc()` | identifierHash 임시 계산 | 주석 경고 참고, 직접 OIDC 경로 사용 시 수정 |

### 9.3 회원 전환 흐름 갭

현재 **FE 회원 전환 완료 후 Q-IM feSession이 발급되지 않는다**.  
회원 전환은 NICE 인증으로 Q-IM 등록까지만 완료되고, 별도의 로그인 플로우(로그인 페이지 → 소셜 로그인)를 통해 feSessionId를 발급받아야 한다.

→ **운영 전 확인 필요**: 전환 완료 후 자동 로그인 처리 여부 (UX 관점에서 중요).

---

*문서 끝 — FLOW-2026-002 v1.0*
