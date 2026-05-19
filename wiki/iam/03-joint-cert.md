# 공동인증서 (Joint Certificate / 구 공인인증서)

> **문서 분류**: IAM / 인증수단 상세  
> **버전**: v1.0.0  
> **작성일**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 보안 담당자  
> **상위 문서**: [00-overview.md](./00-overview.md)

---

## 목차

1. [개요](#1-개요)
2. [서비스 도메인 및 URL 구조](#2-서비스-도메인-및-url-구조)
3. [MagicLine4Web 아키텍처](#3-magicline4web-아키텍처)
4. [인증서 Subject DN 구조](#4-인증서-subject-dn-구조)
5. [DN → CI 변환 (브로커링)](#5-dn--ci-변환-브로커링)
6. [인증 흐름 (시퀀스 다이어그램)](#6-인증-흐름-시퀀스-다이어그램)
7. [저장 위치별 인증서 관리](#7-저장-위치별-인증서-관리)
8. [제공 데이터 (클레임)](#8-제공-데이터-클레임)
9. [개발 연동 방법](#9-개발-연동-방법)
10. [오류 코드 및 예외 처리](#10-오류-코드-및-예외-처리)

---

## 1. 개요

**공동인증서(Joint Certificate)**는 전자서명법 개정(2020)으로 기존 '공인인증서'가 폐지되고 도입된  
**민관 공동 전자서명 인증서**다. Any-ID에서는 **2등급** 인증수단으로 분류된다.

```
역사:
  1999: 공인인증서 도입 (전자서명법)
  2020.12: 전자서명법 전면 개정 → '공인'→'공동' 명칭 변경
           공인 독점 지위 폐지, 다양한 전자서명 인정
  2024.06: Any-ID를 통한 공동인증서 연동 표준화
```

### 1.1 핵심 특징

| 특징 | 내용 |
|------|------|
| **인증 등급** | **2등급** |
| **기술 기반** | X.509 PKI (공개키 기반구조) |
| **전자서명 알고리즘** | RSA-2048, ECDSA P-256 |
| **CI 출처** | Subject DN OID에서 직접 추출 또는 CA 제공 |
| **솔루션** | **MagicLine4Web v2.2** (DreamSecurity) |
| **저장 위치** | HDD, USB 토큰, 스마트카드, 클라우드 |

### 1.2 발급 기관 (CA, Certificate Authority)

| CA | 발급 대상 | 특이사항 |
|----|---------|---------|
| **한국전자인증 (CrossCert)** | 개인/기업 | 범용·전자세금계산서·코드서명 인증서 |
| **코스콤 (SignKorea)** | 기업 중심 | 증권·금융 특화 |
| **한국정보인증 (KICA)** | 개인/기업 | 행정 전자서명 특화 |
| **금융결제원 (KFTC)** | 금융권 | 은행·증권 공동 발급 |
| **yessign (금융결제원 자회사)** | 금융 개인 | 금융공동망 인증서 |

---

## 2. 서비스 도메인 및 URL 구조

### 2.1 도메인

| 환경 | URL |
|------|-----|
| **운영** | `https://crt.anyid.go.kr` |
| **데모/테스트** | `https://demo1.anyid.go.kr/crt/` |

### 2.2 실제 인증 URL (스크린샷 확인)

```
https://crt.anyid.go.kr/MagicLine4Web/v2.2/share.jsp
  ?instt=5000000082          ← 기관코드
  &certType=JOINT            ← 공동인증서 구분
```

### 2.3 URL 구조 분해

| 경로 | 의미 |
|------|------|
| `/MagicLine4Web/` | DreamSecurity MagicLine4Web 솔루션 경로 |
| `/v2.2/` | **MagicLine4Web 버전 2.2** (현재 운영) |
| `/share.jsp` | 공유 인증서 선택 화면 진입점 |

---

## 3. MagicLine4Web 아키텍처

**MagicLine4Web**은 드림시큐리티(DreamSecurity)가 개발한 웹 기반 공동인증서 처리 솔루션으로,  
ActiveX/NPAPI 없이 **순수 JavaScript + WebCrypto API**로 동작한다 (v2.0부터).

```
브라우저
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│               MagicLine4Web v2.2 (crt.anyid.go.kr)               │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │    인증서 검색 모듈                                          │  │
│  │    ├── HDD 검색      (file:// 권한 필요, 브라우저별 상이)    │  │
│  │    ├── USB 토큰     (Web USB API 또는 PKCS#11)             │  │
│  │    ├── 스마트카드   (Web Smart Card API)                   │  │
│  │    └── 클라우드     (CA별 클라우드 API 연동)               │  │
│  └────────────────────────────────────────────────────────────┘  │
│                              │                                    │
│  ┌────────────────────────────▼───────────────────────────────┐  │
│  │    인증서 목록 표시 UI                                       │  │
│  │    - Subject CN(이름), 유효기간, 발급기관 표시               │  │
│  │    - 인증서 선택                                             │  │
│  └────────────────────────────┬───────────────────────────────┘  │
│                              │                                    │
│  ┌────────────────────────────▼───────────────────────────────┐  │
│  │    PIN 입력 + 개인키 서명                                    │  │
│  │    - PIN(비밀번호) 입력                                      │  │
│  │    - 개인키로 challenge 서명 (RSA-SHA256 or ECDSA)          │  │
│  └────────────────────────────┬───────────────────────────────┘  │
│                              │ 서명값 + 인증서(DER)               │
│  ┌────────────────────────────▼───────────────────────────────┐  │
│  │    서명 검증 서버 (crt.anyid.go.kr 백엔드)                   │  │
│  │    - 인증서 유효성 검증 (CRL/OCSP)                          │  │
│  │    - Subject DN 파싱 → CI 추출                              │  │
│  └────────────────────────────┬───────────────────────────────┘  │
└────────────────────────────────┼──────────────────────────────────┘
                                 │ CI + DN 정보
                                 ▼
                    ptl.anyid.go.kr (OIDC IdP)
```

### 3.1 MagicLine4Web 버전 이력

| 버전 | 주요 변경 |
|------|---------|
| v1.x | ActiveX 기반 (IE 전용, 서비스 종료) |
| v2.0 | ActiveX 제거, 순수 JS + WebCrypto API |
| **v2.2** | 클라우드 인증서 지원, ECDSA P-256 추가, iOS Safari 지원 |

---

## 4. 인증서 Subject DN 구조

공동인증서의 **Subject Distinguished Name(DN)**은 인증서에 내장된 사용자 식별 정보다.

### 4.1 Subject DN 예시

```
Subject DN (DER/ASN.1):
  CN=홍길동(HONG_GIL_DONG)
  OU=personal
  O=yessign
  C=KR
  serialNumber=1234567890123456789    ← 16자리 이상 고유번호

또는 OID 기반:
  2.5.4.3=홍길동(HONG_GIL_DONG)       ← CN
  2.5.4.11=personal                   ← OU
  2.5.4.10=CrossCert                  ← O
  2.5.4.6=KR                          ← C
  2.5.4.5=홍길동761215M9367777        ← serialNumber (CA별 형식 상이)
```

### 4.2 CI 포함 OID (CA별 상이)

일부 CA는 Subject DN에 CI 값을 직접 OID로 포함시킨다:

| CA | CI 포함 OID | 형식 |
|----|-----------|------|
| **CrossCert** | `1.2.410.200004.10.1.1.3` | 88바이트 CI 직접 포함 |
| **KICA** | `1.2.410.200004.10.1.1.4` | CI 또는 DI 포함 |
| **yessign (금융결제원)** | `1.2.410.200005.1.2.1` | 금융 인증서 용도 |
| **SignKorea** | `1.2.410.200002.1.5.3` | serialNumber에 주민번호 일부 포함 |

> **⚠️ CA별 파싱 차이**: OID 형식이 CA마다 다르므로 범용 DN 파서가 필요하다.  
> Any-ID의 `crt.anyid.go.kr`이 파싱을 담당하므로 이용기관은 최종 CI만 수신하면 된다.

### 4.3 serialNumber 형식 비교

```
CrossCert (범용):  홍길동761215M1234567  (이름+생년월일+성별+임의번호)
yessign (금융):    19900115-1234567      (생년월일-임의번호)
KICA (행정):       KR123456789012345678  (국가코드+임의번호)
SignKorea (증권):  S123456789            (사업자등록번호 기반 가능)
```

---

## 5. DN → CI 변환 (브로커링)

공동인증서는 모바일 신분증·간편인증과 달리 **CI를 직접 제공하지 않을 수 있다**.  
이 경우 Any-ID가 두 가지 경로로 CI를 획득한다:

### 5.1 경로 1: OID에서 직접 추출 (CI 포함 인증서)

```
Subject DN 파싱
    │
    └── OID 1.2.410.200004.10.1.1.3 존재?
         ├── YES → 값 추출 = CI (88바이트)
         └── NO  → 경로 2로
```

### 5.2 경로 2: 행안부 CI 변환 API 호출 (CI 미포함 인증서)

```
Subject DN 파싱 → serialNumber 추출
    │
    ▼
행안부 CI 변환 API
  POST /api/ci/convert
  Body: { type: "JOINT_CERT", identifier: serialNumber, caCode: "CROSSCERT" }
    │
    ▼ CI (88바이트)
    └── ptl.anyid.go.kr로 전달
```

### 5.3 이용기관 관점

이용기관은 **어떤 경로를 거쳤는지 알 필요 없다**.  
최종 ID Token에는 항상 동일한 형식의 `ci` 클레임이 포함된다:

```jsonc
{
  "ci": "ABCdef123...총88바이트",  // 경로 1 또는 경로 2 결과 동일 형식
  "auth_method": "JOINT_CERT",
  "auth_level": 2
}
```

---

## 6. 인증 흐름 (시퀀스 다이어그램)

```
사용자          브라우저(ML4W)        crt.anyid.go.kr    CA OCSP서버    행안부CI서버    ptl.anyid.go.kr
  │                   │                    │                  │              │               │
  │── 공동인증서 선택 ──▶│                   │                  │              │               │
  │                   │── 인증서 목록 요청 ──▶│                  │              │               │
  │◀── 인증서 목록 표시 │◀── 인증서 목록 ─────│                  │              │               │
  │── 인증서 선택 ──────▶│                   │                  │              │               │
  │◀── PIN 입력 화면 ───│                   │                  │              │               │
  │── PIN 입력 ─────────▶│                  │                  │              │               │
  │                   │── 서명 요청 ────────▶│                  │              │               │
  │                   │   (서명값 + 인증서)   │                  │              │               │
  │                   │                    │── OCSP 검증 ───────▶│              │               │
  │                   │                    │◀── 유효 응답 ────────│              │               │
  │                   │                    │── CI 변환 요청 ──────────────────▶│               │
  │                   │                    │◀── CI(88바이트) ──────────────────│               │
  │                   │                    │── 인증 결과 전달 ─────────────────────────────────▶│
  │                   │◀── Auth Code ───────────────────────────────────────────────────────── │
  │                   │── /token ────────────────────────────────────────────────────────────▶│
  │                   │◀── ID Token(CI포함) ──────────────────────────────────────────────────│
  │◀── 로그인 완료 ──────│                   │                  │              │               │
```

---

## 7. 저장 위치별 인증서 관리

### 7.1 HDD (PC 하드디스크)

```
저장 경로 (Windows):
  C:\Users\{사용자명}\AppData\Roaming\NPKI\    (범용)
  C:\Program Files\NPKI\                        (프로그램 공용)

저장 경로 (macOS):
  ~/Library/Preferences/NPKI/                  (사용자)

저장 경로 (Linux):
  ~/.npki/                                      (사용자)
```

```
장점: 별도 장치 불필요, 이동 쉬움
단점: 악성코드 탈취 위험 (가장 취약)
MagicLine4Web v2.2: File System Access API 사용 (Chrome 86+)
```

### 7.2 USB 토큰

```
인증서가 USB 하드웨어 보안 모듈(HSM)에 저장
개인키: USB 내부에서만 서명 수행 (추출 불가)

지원 토큰: aToken, SecureToken, uToken (한국전자인증, KICA 등)
MagicLine4Web v2.2: Web USB API (Chrome/Edge), PKCS#11 래퍼
```

```
장점: 개인키 추출 불가 (높은 보안)
단점: USB 포트 필요, 모바일 불가, 별도 구매 필요
```

### 7.3 스마트카드

```
은행 OTP 카드 또는 전용 스마트카드에 저장
개인키: 카드 내부에서만 서명 수행

MagicLine4Web v2.2: Web Smart Card API (제한적 지원)
```

### 7.4 클라우드 (CA별 제공)

```
각 CA의 클라우드 서버에 인증서 저장
→ 기기 무관 접근 가능

CrossCert 클라우드: MySign Cloud
KICA 클라우드: KICA Cloud
금융결제원: 금융인증서(별도 문서 04-fin-cert.md 참조)
```

```
장점: 기기 독립, 스마트폰 가능
단점: CA 서버 의존, 클라우드 계정 필요
```

### 7.5 저장 위치별 보안 등급

| 저장 위치 | 보안 등급 | 권장 용도 |
|---------|---------|---------|
| 스마트카드 | ★★★★★ | 고보안 업무 (세금계산서, 전자서명 등) |
| USB 토큰 | ★★★★☆ | 기업 업무, 공공기관 |
| 클라우드 | ★★★☆☆ | 편의성 우선, 일반 민원 |
| HDD | ★★☆☆☆ | 비권장 (보안 약함) |

---

## 8. 제공 데이터 (클레임)

### 8.1 ID Token 클레임 (공동인증서 인증 시)

```jsonc
{
  "iss":          "https://ptl.anyid.go.kr",
  "sub":          "anyid-uuid-xxxx",
  "aud":          "YOUR_CLIENT_ID",
  "iat":          1716123456,
  "exp":          1716127056,
  "nonce":        "제출한_nonce값",

  // Any-ID 확장 클레임
  "ci":           "ABCdef123...총88바이트",  // ★ 연계정보
  "name":         "홍길동",
  "birthdate":    "19900115",

  // 공동인증서 전용 클레임
  "dn":           "CN=홍길동(HONG_GIL_DONG),OU=personal,O=yessign,C=KR",
  "cert_serial":  "0123456789ABCDEF",        // 인증서 일련번호 (HEX)
  "cert_issuer":  "yessign",                 // 발급 CA
  "cert_expire":  "20260115",               // 유효기간 YYYYMMDD
  "cert_type":    "PERSONAL",               // PERSONAL or CORPORATE

  // 인증 메타
  "auth_method":  "JOINT_CERT",
  "auth_level":   2,                         // 2등급
  "auth_time":    1716123456,
  "instt_cd":     "5000000082"
}
```

### 8.2 기업 인증서 추가 클레임

```jsonc
{
  "cert_type":         "CORPORATE",
  "org_name":          "주식회사 예시",
  "org_reg_no":        "1234567890",          // 사업자등록번호 (협약 시)
  "org_rep_name":      "홍길동",               // 대표자명
}
```

---

## 9. 개발 연동 방법

### 9.1 Authorization 요청 (공동인증서 지정)

```
GET https://ptl.anyid.go.kr/oidc/authorize
  ?response_type=code
  &client_id=YOUR_CLIENT_ID
  &redirect_uri=https%3A%2F%2Fyour-service.go.kr%2Fcallback
  &scope=openid+profile+ci+dn
  &state=RANDOM_STATE_VALUE
  &nonce=RANDOM_NONCE_VALUE
  &auth_method=JOINT_CERT                ← 공동인증서 직접 지정
  &instt=5000000082
  &code_challenge=BASE64URL_SHA256
  &code_challenge_method=S256
```

### 9.2 DN 파싱 유틸리티 (Spring Boot)

```java
// JointCertDnParser.java
@Component
public class JointCertDnParser {

    // Subject DN에서 CI 직접 추출 (CA가 OID에 포함한 경우)
    public Optional<String> extractCiFromDn(X509Certificate cert) {
        // CrossCert CI OID: 1.2.410.200004.10.1.1.3
        byte[] ciExtension = cert.getExtensionValue("1.2.410.200004.10.1.1.3");
        if (ciExtension != null) {
            return Optional.of(new String(parseDerOctetString(ciExtension)));
        }
        return Optional.empty();
    }

    // Subject DN에서 이름 추출
    public String extractCnName(String dn) {
        // CN=홍길동(HONG_GIL_DONG) 형식에서 한글 이름만 추출
        Pattern pattern = Pattern.compile("CN=([^(,]+)");
        Matcher matcher = pattern.matcher(dn);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    // Subject DN에서 serialNumber 추출
    public String extractSerialNumber(String dn) {
        Pattern pattern = Pattern.compile("serialNumber=([^,]+)");
        Matcher matcher = pattern.matcher(dn);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    // 기업 인증서 여부 확인 (OU 기준)
    public boolean isCorporateCert(String dn) {
        return dn.contains("OU=SMIME") ||
               dn.contains("OU=코드서명") ||
               dn.contains("OU=corporation");
    }
}
```

### 9.3 인증서 유효성 검증 (OCSP)

```java
// JointCertOcspValidator.java
@Component
public class JointCertOcspValidator {

    // OCSP 검증 (실시간 폐기 확인)
    public CertificateStatus validateOcsp(X509Certificate cert,
                                          X509Certificate issuerCert) throws Exception {
        // OCSP URL은 인증서 AIA 확장에서 추출
        String ocspUrl = extractOcspUrl(cert);

        OcspClient ocspClient = new JcaOCSPClientBuilder()
            .setProvider("BC")
            .build();

        BasicOCSPResp response = ocspClient.fetch(cert, issuerCert,
            new URL(ocspUrl));

        SingleResp[] responses = response.getResponses();
        for (SingleResp resp : responses) {
            if (resp.getCertStatus() == CertificateStatus.GOOD) {
                return CertificateStatus.GOOD;
            }
        }
        return CertificateStatus.REVOKED;
    }

    private String extractOcspUrl(X509Certificate cert) {
        // AIA (Authority Information Access) 확장에서 OCSP URL 추출
        byte[] aiaExtension = cert.getExtensionValue("1.3.6.1.5.5.7.1.1");
        // ... DER 파싱 로직
        return "http://ocsp.crosscert.com/"; // 예시
    }
}
```

### 9.4 기업 인증서 처리 예시

```java
// 기업 인증서 연동 시 추가 검증
@PostMapping("/auth/joint-cert/corporate")
public ResponseEntity<?> handleCorporateCert(
    @RequestBody CorporateCertRequest request,
    HttpSession session
) {
    AnyIdClaims claims = processOidcCallback(request.getCode(), session);

    if (!"CORPORATE".equals(claims.getCertType())) {
        return ResponseEntity.badRequest()
            .body("기업 인증서만 허용됩니다.");
    }

    // 사업자등록번호 검증
    String orgRegNo = claims.getOrgRegNo();
    if (!businessRegistry.isValid(orgRegNo)) {
        return ResponseEntity.badRequest()
            .body("유효하지 않은 사업자등록번호입니다.");
    }

    // 기업 회원 처리
    CorporateMember member = corporateMemberService
        .findOrCreateByOrgRegNo(orgRegNo, claims);

    return ResponseEntity.ok(CorporateLoginResponse.of(member));
}
```

---

## 10. 오류 코드 및 예외 처리

### 10.1 MagicLine4Web 오류 코드

| 오류 코드 | 의미 | 처리 방법 |
|---------|------|---------|
| `ML_001` | 인증서 없음 (저장소 비어있음) | "인증서를 발급하거나 다른 인증수단을 이용해주세요" |
| `ML_002` | PIN 오류 (5회 초과 잠김) | "PIN 오류 횟수 초과. 공동인증서 재발급 필요" |
| `ML_003` | 인증서 만료 | "인증서가 만료되었습니다. 갱신 후 이용해주세요" |
| `ML_004` | 인증서 폐기 (OCSP 폐기됨) | "폐기된 인증서입니다. 재발급 필요" |
| `ML_005` | USB 토큰 미연결 | "USB 토큰을 연결하고 다시 시도해주세요" |
| `ML_006` | 브라우저 미지원 (File API 제한) | "Chrome 또는 Edge 브라우저를 이용해주세요" |
| `ML_007` | 서명 실패 (개인키 오류) | "인증서 파일이 손상되었습니다. 재발급 필요" |
| `ML_010` | MagicLine4Web 서버 오류 | 기술지원 1566-2670 문의 |

### 10.2 브라우저별 지원 상황

| 브라우저 | HDD 인증서 | USB 토큰 | 클라우드 인증서 |
|---------|----------|---------|--------------|
| Chrome 86+ | ✅ File API | ✅ Web USB | ✅ |
| Edge 88+ | ✅ File API | ✅ Web USB | ✅ |
| Firefox | ❌ (File API 미지원) | 제한적 | ✅ |
| Safari (macOS) | ❌ | ❌ | ✅ |
| Safari (iOS) | ❌ | ❌ | ✅ (클라우드만) |

> **권장**: HDD/USB 인증서 사용자에게는 **Chrome 또는 Edge** 안내

### 10.3 인증서 만료 사전 안내

```java
// 인증서 만료 D-30 사전 안내 로직
public void checkCertExpiry(AnyIdClaims claims) {
    String certExpire = claims.getCertExpire(); // "20260115"
    LocalDate expireDate = LocalDate.parse(certExpire, 
        DateTimeFormatter.ofPattern("yyyyMMdd"));
    
    long daysUntilExpiry = ChronoUnit.DAYS.between(LocalDate.now(), expireDate);
    
    if (daysUntilExpiry <= 0) {
        throw new CertificateExpiredException("인증서가 만료되었습니다.");
    } else if (daysUntilExpiry <= 30) {
        // 경고 메시지 (로그인은 허용)
        log.warn("인증서 만료 {}일 남음. sub={}", daysUntilExpiry, claims.getSub());
        notificationService.sendCertExpiryWarning(claims, daysUntilExpiry);
    }
}
```

---

## 관련 문서

| 문서 | 링크 |
|------|------|
| Any-ID 전체 개요 | [00-overview.md](./00-overview.md) |
| 금융인증서 (관련 PKI 계열) | [04-fin-cert.md](./04-fin-cert.md) |
| CI/DN 브로커링 심층 분석 | [05-ci-dn-brokering.md](./05-ci-dn-brokering.md) |
| 설치형 연동 가이드 | [06-install-type-integration.md](./06-install-type-integration.md) |

---

*최종 수정: 2026-05-19 | 작성: OnePass 플랫폼 개발팀*
