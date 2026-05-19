# CI/DN 브로커링 심층 분석

> **문서 분류**: IAM / 아키텍처 심층 분석  
> **버전**: v1.0.0  
> **작성일**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 아키텍트, 보안 담당자  
> **상위 문서**: [00-overview.md](./00-overview.md)

---

## 목차

1. [CI(연계정보)란 무엇인가](#1-ci연계정보란-무엇인가)
2. [DN(Distinguished Name)이란 무엇인가](#2-dndistinguished-name이란-무엇인가)
3. [인증수단별 CI 획득 경로](#3-인증수단별-ci-획득-경로)
4. [Any-ID CI 브로커링 상세](#4-any-id-ci-브로커링-상세)
5. [CI의 실제 사용 방법](#5-ci의-실제-사용-방법)
6. [CI 암호화 저장 전략](#6-ci-암호화-저장-전략)
7. [DN 파싱 심층 분석](#7-dn-파싱-심층-분석)
8. [CI 충돌 및 이전 처리](#8-ci-충돌-및-이전-처리)
9. [법적/개인정보 고려사항](#9-법적개인정보-고려사항)
10. [보안 위협 모델](#10-보안-위협-모델)

---

## 1. CI(연계정보)란 무엇인가

### 1.1 정의

**CI(Connecting Information, 연계정보)**는 주민등록번호를 **일방향 해시 변환**한 88바이트 문자열로,  
여러 서비스 간 동일 사용자를 식별하기 위한 **표준 연계 식별자**다.

```
법적 정의:
  정보통신망 이용촉진 및 정보보호 등에 관한 법률 제23조의2
  행정안전부 고시 2021-68호 (본인확인기관 기술 기준)
  → 주민등록번호를 직접 수집하지 않고 동일인 식별 가능
```

### 1.2 CI 생성 원리

```
입력:  주민등록번호 (13자리)
알고리즘: HMAC-SHA1 → Base64 인코딩 (행안부 지정 서비스 키 사용)

생성 공식 (단순화):
  rawCI = HMAC-SHA1(
    key   = "행안부_지정_서비스키(비공개)",
    value = "주민등록번호_13자리"
  )
  CI = Base64Encode(rawCI) → 28바이트(이진) → Base64 = 약 40자
  
※ 실제 CI는 88바이트 (Base64 ASCII 문자열)
```

```
예시:
  주민번호: 901215-1234567 (가상)
  CI 결과:  ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789AB==
             ↑ 88자 (Base64 문자열, 영문+숫자+==)
```

### 1.3 CI의 핵심 특성

| 특성 | 설명 |
|------|------|
| **일방향성** | CI → 주민번호 역산 불가 |
| **일관성** | 동일인 → 항상 동일한 CI 값 |
| **비교 가능성** | 서비스 간 CI 비교로 동일인 확인 |
| **행안부 독점** | CI 생성 키는 행안부만 보유 |
| **길이** | **88바이트 (ASCII 문자열)** |
| **용도 제한** | 본인확인·연계 목적 외 사용 금지 |

---

## 2. DN(Distinguished Name)이란 무엇인가

### 2.1 정의

**DN(Distinguished Name)**은 공동인증서(X.509 PKI) Subject 필드에 담긴  
**인증서 소유자 식별 정보**다.

### 2.2 DN 구조

```
Subject DN 예시 (공동인증서):
  CN=홍길동(HONG_GIL_DONG)    → Common Name (이름)
  OU=personal                  → Organizational Unit (개인/법인 구분)
  O=yessign                    → Organization (CA 이름)
  C=KR                         → Country (국가)
  serialNumber=홍길동761215M9367777  → 인증서 식별번호

ASN.1 OID 표현:
  2.5.4.3  = CN  (Common Name)
  2.5.4.11 = OU  (Organizational Unit)
  2.5.4.10 = O   (Organization)
  2.5.4.6  = C   (Country)
  2.5.4.5  = serialNumber
```

### 2.3 DN에서 CI 추출 (CA별 OID)

일부 CA는 Subject Extension OID에 CI를 직접 포함:

```
CrossCert (한국전자인증):
  OID: 1.2.410.200004.10.1.1.3
  형식: 88바이트 CI 직접 포함

KICA (한국정보인증):
  OID: 1.2.410.200004.10.1.1.4
  형식: CI 또는 DI 포함 (기관 설정에 따라)

yessign (금융결제원):
  OID: 1.2.410.200005.1.2.1
  형식: 금융결제원 자체 식별자 (CI 변환 필요)

SignKorea (코스콤):
  serialNumber 형식에서 CI 역산 필요
  → 행안부 CI 변환 API 호출
```

---

## 3. 인증수단별 CI 획득 경로

각 인증수단에서 Any-ID가 CI를 획득하는 경로를 비교한다:

### 3.1 전체 경로 비교표

| 인증수단 | CI 획득 주체 | 경로 단계 | CI 출처 |
|---------|-----------|---------|--------|
| **모바일 신분증** | 행안부 VRS | 1단계 | 행안부 주민등록 원장 → CI 직접 생성 |
| **간편인증 (카카오)** | 카카오 → 나이스 → 행안부 | 3단계 | 카카오 인증 → 본인확인기관 CI 변환 |
| **간편인증 (PASS)** | 통신사 → 한국정보인증 → 행안부 | 3단계 | 통신사 본인인증 → 본인확인기관 CI 변환 |
| **공동인증서 (CI OID)** | CA OID 직접 추출 | 1단계 | Subject OID에서 바로 추출 |
| **공동인증서 (OID 없음)** | CA → 행안부 CI 변환 | 2단계 | serialNumber → 행안부 CI 변환 API |
| **금융인증서** | KFTC → 행안부 | 2단계 | KFTC 인증 → 행안부 CI 변환 API |

### 3.2 모바일 신분증 CI 흐름 (1단계 — 최단)

```
사용자 모바일 신분증 앱
    │ VP(Verifiable Presentation) 제출
    ▼
행안부 VRS 검증 서버
    │ VC 서명 검증 → 주민등록 원장에서 DI 조회
    ▼
행안부 CI 생성 서버
    │ DI → CI 변환 (HMAC-SHA1, 행안부 서비스키)
    ▼
mid.anyid.go.kr → ptl.anyid.go.kr → ID Token의 ci 클레임
```

### 3.3 간편인증 CI 흐름 (3단계 — 카카오 예시)

```
카카오 인증서 검증 (카카오 서버)
    │ 카카오 사용자 정보 (이름, 생년월일, 전화번호)
    ▼
나이스평가정보 (방통위 지정 본인확인기관)
    │ 사용자 정보 → 주민등록번호 조회 (나이스 DB)
    ▼
행안부 CI 변환 API
    │ 주민등록번호 → CI 생성
    ▼
easysign.anyid.go.kr → ptl.anyid.go.kr → ID Token의 ci 클레임
```

### 3.4 공동인증서 CI 흐름 (2경로)

```
경로 A — OID 직접 추출 (CI 포함 인증서):
  인증서 Subject OID 1.2.410.200004.10.1.1.3
      │ CI 88바이트 직접 읽기
      ▼
  crt.anyid.go.kr → ptl.anyid.go.kr → ID Token

경로 B — 행안부 CI 변환 API (CI 미포함 인증서):
  인증서 Subject serialNumber 추출
      │
      ▼
  행안부 CI 변환 API
  POST /api/ci/convert
  { type: "JOINT_CERT", identifier: serialNumber, caCode: "CROSSCERT" }
      │ CI 88바이트
      ▼
  crt.anyid.go.kr → ptl.anyid.go.kr → ID Token
```

---

## 4. Any-ID CI 브로커링 상세

### 4.1 브로커링 레이어 구조

```
이용기관 (설치형)
    │
    │ OIDC Authorization Code Flow
    ▼
ptl.anyid.go.kr (Any-ID 통합플랫폼)
    │
    │ 인증수단별 라우팅 (instt 기반)
    ├──▶ mid.anyid.go.kr (모바일 신분증)
    ├──▶ easysign.anyid.go.kr (간편인증)
    └──▶ crt.anyid.go.kr (공동/금융인증서)
              │
              │ 각 인증수단에서 CI 획득
              ▼
    CI 정규화 모듈 (Any-ID 내부)
              │ 항상 88바이트 CI + 동일 클레임 형식
              ▼
    ID Token 발급 → 이용기관 서버
```

### 4.2 CI 정규화의 의미

**"CI 브로커링"의 핵심**: 이용기관은 인증수단에 무관하게 **항상 동일한 88바이트 CI**를 받는다.

```
Before (Any-ID 없을 때):
  카카오 로그인 → kakaoUserId: "12345678"
  공동인증서    → serialNumber: "홍길동761215M9367777"
  금융인증서    → kftcUserId: "kftc_9876543210"
  → 동일인이지만 서비스마다 다른 식별자 → 통합 불가

After (Any-ID 브로커링):
  카카오 로그인 → CI: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcde..."
  공동인증서    → CI: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcde..."  ← 동일!
  금융인증서    → CI: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcde..."  ← 동일!
  → 이용기관 DB에서 CI로 단일 회원 매핑 가능
```

---

## 5. CI의 실제 사용 방법

### 5.1 회원 식별 키 설계

```
DB 설계 원칙:
  - CI를 직접 저장하지 않고 ci_hash(SHA-256 + Salt) 형태로 저장
  - ci_hash를 회원 테이블의 unique key로 사용

  회원 테이블:
  ┌──────────────────────────────────────┐
  │  id        BIGINT PK                 │
  │  ci_hash   VARCHAR(64) UNIQUE        │ ← SHA-256(CI + salt)
  │  name      VARCHAR(50)               │
  │  birthdate VARCHAR(8)                │
  │  phone     VARCHAR(20)               │
  │  auth_level INT                      │ ← 마지막 인증 등급 저장
  │  created_at TIMESTAMP                │
  └──────────────────────────────────────┘
```

### 5.2 CI 기반 회원 조회·생성 로직

```java
@Service
@Transactional
public class CiBasedMemberService {

    private static final String CI_SALT = System.getenv("CI_HASH_SALT");

    public Member findOrCreateByCi(String ci, AnyIdClaims claims) {
        // 1. CI 해시 생성
        String ciHash = computeCiHash(ci);

        // 2. 기존 회원 조회
        return memberRepository.findByCiHash(ciHash)
            .map(existing -> {
                // 기존 회원 — 정보 갱신 (이름, 전화번호 등 최신화)
                existing.syncFromAnyId(claims);
                log.info("기존 회원 로그인. ciHash={}, authLevel={}",
                    ciHash.substring(0, 8) + "...", claims.getAuthLevel());
                return existing;
            })
            .orElseGet(() -> {
                // 신규 회원 생성
                Member newMember = Member.builder()
                    .ciHash(ciHash)
                    .name(claims.getName())
                    .birthdate(claims.getBirthdate())
                    .gender(claims.getGender())
                    .phone(claims.getPhoneNumber())
                    .authLevel(claims.getAuthLevel())
                    .authMethod(claims.getAuthMethod())
                    .build();
                log.info("신규 회원 생성. ciHash={}, method={}",
                    ciHash.substring(0, 8) + "...", claims.getAuthMethod());
                return memberRepository.save(newMember);
            });
    }

    // CI 해시 생성 (SHA-256 + 고정 Salt)
    private String computeCiHash(String ci) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(CI_SALT.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest(ci.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash); // 64자 HEX
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 지원 안 됨", e);
        }
    }
}
```

### 5.3 멀티 인증수단 연결 테이블

동일 회원이 여러 인증수단으로 로그인하는 경우를 관리:

```sql
-- 회원 인증수단 연결 테이블
CREATE TABLE member_auth_provider (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id   BIGINT NOT NULL REFERENCES member(id),
    ci_hash     VARCHAR(64) NOT NULL,          -- 인증수단별 CI는 동일 (정규화)
    auth_method VARCHAR(50) NOT NULL,          -- MOBILE_ID, EASY_SIGN 등
    provider    VARCHAR(50),                   -- KAKAO, NAVER 등
    last_used   TIMESTAMP NOT NULL,
    auth_level  INT NOT NULL,
    UNIQUE (ci_hash, auth_method)              -- 동일 CI + 동일 수단 중복 방지
);

-- 인덱스
CREATE INDEX idx_member_auth_ci_hash ON member_auth_provider(ci_hash);
```

---

## 6. CI 암호화 저장 전략

### 6.1 저장 방식 비교

| 방식 | 보안 | 조회 성능 | 권장 |
|------|------|---------|------|
| **평문 저장** | ❌ 매우 위험 | ✅ 빠름 | ❌ 절대 금지 |
| **SHA-256 해시** | ✅ 단방향 | ✅ 빠름 (인덱스 가능) | ✅ 권장 |
| **AES-256 암호화** | ✅✅ 양방향 가능 | ⚠️ 느림 (인덱스 불가) | 조건부 |
| **SHA-256 + Salt** | ✅✅ 레인보우테이블 방어 | ✅ 빠름 | ✅ 권장 |

### 6.2 권장 구현: SHA-256 + 고정 Salt

```java
// application.yml 설정
anyid:
  ci:
    hash-salt: ${CI_HASH_SALT}      # 환경변수에서 주입 (비밀값)
    hash-algorithm: SHA-256

// CiHashEncoder.java
@Component
public class CiHashEncoder {

    @Value("${anyid.ci.hash-salt}")
    private String salt;

    // 회원 조회용 해시 (빠른 인덱스 검색)
    public String encode(String ci) {
        return DigestUtils.sha256Hex(salt + ci); // 64자 HEX
    }

    // 절대 원문 CI를 로그에 출력하지 않음
    public String mask(String ci) {
        if (ci == null || ci.length() < 8) return "****";
        return ci.substring(0, 4) + "****" + ci.substring(ci.length() - 4);
    }
}
```

### 6.3 AES-256 암호화 (CI 원문이 필요한 경우)

```java
// 규제상 원문 CI 보관이 필요한 경우 (예: 행안부 감사 대응)
@Component
public class CiEncryptor {

    private final SecretKey aesKey;

    public CiEncryptor(@Value("${anyid.ci.aes-key}") String keyBase64) {
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        this.aesKey = new SecretKeySpec(keyBytes, "AES");
    }

    // AES-256-GCM 암호화
    public String encrypt(String ci) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = generateRandomIv(12); // GCM nonce 12 bytes
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec);

        byte[] ciphertext = cipher.doFinal(ci.getBytes(StandardCharsets.UTF_8));
        // IV + ciphertext를 함께 저장
        byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
            .put(iv).put(ciphertext).array();
        return Base64.getEncoder().encodeToString(combined);
    }

    // AES-256-GCM 복호화
    public String decrypt(String encryptedCi) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encryptedCi);
        byte[] iv = Arrays.copyOfRange(combined, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(combined, 12, combined.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec);
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
```

---

## 7. DN 파싱 심층 분석

### 7.1 CA별 DN 파싱 로직

```java
@Component
public class JointCertDnBroker {

    // Any-ID가 제공하는 dn 클레임에서 필요 정보 추출
    public DnParseResult parse(String dn, X509Certificate cert) {
        DnParseResult result = new DnParseResult();

        // 1. CI OID 직접 추출 시도
        Optional<String> ciFromOid = extractCiFromOid(cert);
        if (ciFromOid.isPresent()) {
            result.setCi(ciFromOid.get());
            result.setCiSource("OID_DIRECT");
            return result;
        }

        // 2. serialNumber 추출 → 행안부 API로 CI 변환
        String serialNumber = extractSerialNumber(dn);
        String caCode = extractCaCode(dn); // O 필드: yessign, CrossCert 등
        String ci = callCiConvertApi(serialNumber, caCode);
        result.setCi(ci);
        result.setCiSource("CA_CONVERT");
        return result;
    }

    private Optional<String> extractCiFromOid(X509Certificate cert) {
        // CrossCert CI OID
        String[] ciOids = {
            "1.2.410.200004.10.1.1.3",  // CrossCert
            "1.2.410.200004.10.1.1.4",  // KICA
        };
        for (String oid : ciOids) {
            byte[] ext = cert.getExtensionValue(oid);
            if (ext != null) {
                // DER OCTET STRING 파싱
                String ci = parseDerOctetString(ext);
                if (ci != null && ci.length() == 88) {
                    return Optional.of(ci);
                }
            }
        }
        return Optional.empty();
    }

    private String extractSerialNumber(String dn) {
        // DN에서 serialNumber 추출
        // 형식: "serialNumber=홍길동761215M9367777" 또는 "2.5.4.5=..."
        Pattern p = Pattern.compile("(?:serialNumber|2\\.5\\.4\\.5)=([^,]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(dn);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractCaCode(String dn) {
        // O 필드에서 CA 이름 추출
        Pattern p = Pattern.compile("O=([^,]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(dn);
        if (m.find()) {
            String org = m.group(1).trim().toLowerCase();
            if (org.contains("yessign"))    return "YESSIGN";
            if (org.contains("crosscert"))  return "CROSSCERT";
            if (org.contains("kica"))       return "KICA";
            if (org.contains("signkorea"))  return "SIGNKOREA";
        }
        return "UNKNOWN";
    }
}
```

### 7.2 DN에서 이름 추출 패턴

```java
// CN 필드에서 한글 이름 추출 (다양한 형식 처리)
public String extractKoreanName(String cn) {
    // 형식 1: "홍길동(HONG_GIL_DONG)" → "홍길동"
    Pattern p1 = Pattern.compile("^([가-힣]+)(?:\\([A-Z_]+\\))?$");
    Matcher m1 = p1.matcher(cn.trim());
    if (m1.find()) return m1.group(1);

    // 형식 2: "홍길동" (한글만)
    Pattern p2 = Pattern.compile("^([가-힣]+)$");
    Matcher m2 = p2.matcher(cn.trim());
    if (m2.find()) return m2.group(1);

    return cn.trim(); // 폴백
}
```

---

## 8. CI 충돌 및 이전 처리

### 8.1 CI 충돌 케이스

```
드문 케이스이지만 처리 필요:

케이스 1: 동명이인 (CI는 다름 — 문제 없음)
  홍길동A (CI: ABC...) vs 홍길동B (CI: XYZ...)
  → CI가 달라 자동으로 다른 회원

케이스 2: 주민번호 변경 (성전환/개명 후 재발급)
  → 새로운 주민번호 → 새로운 CI
  → 기존 회원과 연결 끊어짐 → 수동 매핑 필요
  처리: 고객센터에서 기존 ci_hash + 새 ci_hash 연결 처리

케이스 3: 외국인등록번호 (외국인)
  → CI 생성 가능하나 한국인 CI와 구분 불가
  → auth_method 클레임 참고하여 외국인 여부 보완 판단
```

### 8.2 인증수단 간 CI 동일성 검증

```java
// 같은 사람이 다른 인증수단으로 로그인 시 CI 동일성 검증
@Test
void testCiConsistencyAcrossAuthMethods() {
    // 모바일 신분증으로 로그인
    String ci1 = mobileIdAuth("홍길동", "901215-1234567").getCi();

    // 카카오 간편인증으로 로그인 (동일인)
    String ci2 = easySignAuth("KAKAO", "홍길동", "901215-1234567").getCi();

    // 공동인증서로 로그인 (동일인)
    String ci3 = jointCertAuth("홍길동", "901215-1234567").getCi();

    // 세 CI는 모두 동일해야 함 (Any-ID 브로커링 정확성 검증)
    assertThat(ci1).isEqualTo(ci2).isEqualTo(ci3);
}
```

---

## 9. 법적/개인정보 고려사항

### 9.1 CI 취급 법적 요건

```
개인정보보호법 제24조 (고유식별정보 처리 제한):
  CI는 "고유식별정보의 대체 수단"으로 준개인정보 수준 보호

의무사항:
  ✅ 수집 목적 외 사용 금지 (회원 식별 외 불가)
  ✅ 제3자 제공 시 별도 동의 필요
  ✅ 보유기간 경과 후 즉시 파기
  ✅ DB 저장 시 암호화 또는 해시 처리 (권장)
  ✅ 접근 권한 최소화 (need-to-know)
  ✅ 개인정보 처리방침에 CI 수집·이용 명시

금지사항:
  ❌ CI 원문을 로그에 기록
  ❌ CI로 주민등록번호 역산 시도
  ❌ CI를 행안부 허가 없이 제3자 판매/공유
  ❌ 마케팅·광고 프로파일링에 CI 사용
```

### 9.2 아동(만 14세 미만) 처리

```
아동·청소년 개인정보 보호법:
  만 14세 미만은 법정 대리인 동의 없이 CI 수집 금지

구현 방법:
  claims.getBirthdate() → 현재일 기준 만 나이 계산
  만 14세 미만 → Any-ID 레벨에서 이미 차단 + 이용기관에서 추가 검증

if (isUnder14(claims.getBirthdate())) {
    return ResponseEntity.status(403)
        .body("만 14세 미만은 법정대리인 동의가 필요합니다.");
}
```

### 9.3 보유기간 및 파기

```
권장 보유기간:
  현재 서비스 중인 회원: 서비스 이용 기간 + 5년 (민법 소멸시효)
  탈퇴 회원: 즉시 파기 또는 최대 1년 (전자상거래 소비자보호법)
  ci_hash: 회원 파기 시 함께 삭제 (null 처리 또는 DELETE)

파기 처리:
@Scheduled(cron = "0 0 2 * * *")  // 매일 새벽 2시
public void purgeExpiredCiHashes() {
    List<Member> toDelete = memberRepository.findWithdrawnBefore(
        LocalDateTime.now().minusYears(1)
    );
    toDelete.forEach(m -> {
        m.setCiHash(null);           // ci_hash NULL 처리
        m.setName("(삭제됨)");
        m.setPhone(null);
        auditLog.recordPurge(m.getId());
    });
    memberRepository.saveAll(toDelete);
}
```

---

## 10. 보안 위협 모델

### 10.1 CI 관련 주요 위협

| 위협 | 심각도 | 방어 방법 |
|------|--------|---------|
| **CI 탈취 (DB 해킹)** | 🔴 매우 높음 | ci_hash 저장, AES 암호화, DB 접근 제한 |
| **CI Replay Attack** | 🔴 높음 | nonce 1회 소비, 토큰 만료 확인 |
| **MITM (중간자 공격)** | 🟡 중간 | TLS 1.2+ 강제, Certificate Pinning |
| **로그 CI 노출** | 🟡 중간 | 로그에 CI 출력 금지, 마스킹 적용 |
| **CI 역산 시도** | 🔴 높음 | 일방향성으로 역산 불가, 소금값 비공개 |
| **인증서 위조 (공동인증서)** | 🟡 중간 | OCSP 실시간 검증, CA 루트 인증서 핀닝 |
| **ID Token 위조** | 🔴 높음 | JWK 서명 검증, iss/aud/exp 검증 |
| **CSRF** | 🟡 중간 | state 파라미터 32자+ 랜덤값 |
| **내부자 CI 조회** | 🟡 중간 | 최소 권한 정책, DB 접근 로그, 감사 |

### 10.2 ID Token 검증 체크리스트

```java
// AnyIdJwtVerifier.java
@Component
public class AnyIdJwtVerifier {

    public AnyIdClaims verify(String idToken, String expectedNonce) {
        JWTClaimsSet claims = parse(idToken);

        // ✅ 1. issuer 검증
        if (!"https://ptl.anyid.go.kr".equals(claims.getIssuer())) {
            throw new SecurityException("issuer 불일치");
        }

        // ✅ 2. audience 검증
        if (!claims.getAudience().contains(clientId)) {
            throw new SecurityException("audience 불일치 — 토큰 탈취 가능성");
        }

        // ✅ 3. 만료 검증
        if (new Date().after(claims.getExpirationTime())) {
            throw new SecurityException("만료된 ID Token");
        }

        // ✅ 4. nonce 검증 (Replay Attack 방어)
        if (!expectedNonce.equals(claims.getStringClaim("nonce"))) {
            throw new SecurityException("nonce 불일치 — Replay Attack 가능성");
        }

        // ✅ 5. 서명 검증 (JWK)
        verifySignatureWithJwks(idToken); // ptl.anyid.go.kr/.well-known/jwks.json

        // ✅ 6. CI 클레임 존재 및 길이 확인
        String ci = claims.getStringClaim("ci");
        if (ci == null || ci.length() != 88) {
            throw new SecurityException("CI 클레임 이상 — 88바이트 아님");
        }

        return AnyIdClaims.fromJwtClaims(claims);
    }
}
```

---

## 관련 문서

| 문서 | 링크 |
|------|------|
| Any-ID 전체 개요 | [00-overview.md](./00-overview.md) |
| 모바일 신분증 | [01-mobile-id.md](./01-mobile-id.md) |
| 간편인증 | [02-easy-sign.md](./02-easy-sign.md) |
| 공동인증서 | [03-joint-cert.md](./03-joint-cert.md) |
| 금융인증서 | [04-fin-cert.md](./04-fin-cert.md) |
| 설치형 연동 가이드 | [06-install-type-integration.md](./06-install-type-integration.md) |

---

*최종 수정: 2026-05-19 | 작성: OnePass 플랫폼 개발팀*
