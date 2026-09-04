# IAM-09: Any-ID 설치형 SDK 통합 가이드

> **대상 독자**: 백엔드 개발자, 인프라 담당자  
> **적용 버전**: anyid-auth-sdk-1.0.19, integration-sso `ido` 모듈  
> **기관**: 중소기업기술정보진흥원 (기관번호 #311, srvc_no=`1000001157`)  
> **최종 업데이트**: 2026-05-19

---

## 목차

1. [개요 및 아키텍처](#1-개요-및-아키텍처)
2. [AuthResourceInstall 패키지 구조](#2-authresourceinstall-패키지-구조)
3. [JAR 설치 — integration-sso 적용](#3-jar-설치--integration-sso-적용)
4. [설정 파일 구조](#4-설정-파일-구조)
5. [프론트엔드 연동 — AnyidC.LOAD_MODULE()](#5-프론트엔드-연동--anyidcload_module)
6. [extract → Spring Controller 변환 패턴](#6-extract--spring-controller-변환-패턴)
7. [ssob 복호화 → CI 추출 코드](#7-ssob-복호화--ci-추출-코드)
8. [인증수단별 extract 패턴 분석](#8-인증수단별-extract-패턴-분석)
9. [NonOidcAuthService 연동](#9-nonoidcauthservice-연동)
10. [FeSession 발급 흐름](#10-fesession-발급-흐름)
11. [전체 인증 시퀀스 다이어그램](#11-전체-인증-시퀀스-다이어그램)
12. [BouncyCastle 버전 충돌 해결](#12-bouncycastle-버전-충돌-해결)
13. [트러블슈팅 & FAQ](#13-트러블슈팅--faq)

---

## 1. 개요 및 아키텍처

### 1.1 Any-ID 설치형이란?

행안부 Any-ID 사업단이 제공하는 **기관 WAS 직접 설치형** 인증 SDK다.  
별도 서버를 두지 않고 `anyid-auth-sdk-1.0.19.jar` 등을 WAS의 `WEB-INF/lib`(JSP 환경) 또는 `libs/`(Spring Boot 환경)에 배치하여 사용한다.

```
┌──────────────────────────────────────────────────┐
│  기관 WAS (integration-sso ido 모듈)              │
│                                                  │
│  ┌─────────────────────────────┐                 │
│  │  FE (Thymeleaf/React)        │                 │
│  │  AnyidC.LOAD_MODULE()        │  ← JS SDK      │
│  └──────────┬──────────────────┘                 │
│             │ anyidAdaptor.success(data)          │
│             ▼                                    │
│  ┌─────────────────────────────┐                 │
│  │  AnyIdController            │                 │
│  │  POST /api/v1/anyid/{p}/ssob│                 │
│  └──────────┬──────────────────┘                 │
│             │                                    │
│  ┌──────────▼──────────────────┐                 │
│  │  AnyIdSsobService           │                 │
│  │  AnyidCertRef.decryptSsob() │  ← Java SDK     │
│  └──────────┬──────────────────┘                 │
│             │ CI, authLevel                      │
│  ┌──────────▼──────────────────┐                 │
│  │  NonOidcAuthService         │                 │
│  │  processAuth() → Kafka      │                 │
│  └──────────┬──────────────────┘                 │
│             │                                    │
│  ┌──────────▼──────────────────┐                 │
│  │  FeSessionService           │                 │
│  │  create() → feSessionId     │                 │
│  └─────────────────────────────┘                 │
└──────────────────────────────────────────────────┘
            │ KMS 연동
            ▼
    https://www.anyid.dev:8119/ (ARIA-CBC-256)
```

### 1.2 지원 인증 수단 (5종)

| 수단 코드 | 모듈명 | 인증 수준 | 비고 |
|-----------|--------|-----------|------|
| `MOBILE_ID` | `mip-install` | L2 | 행안부 모바일 신분증 DID |
| `FINANCIAL_CERT` | `fincert-install` | L3 | 금결원 yeskey 금융인증서 |
| `JOINT_CERT` | `anysignlite-install` | L3 | NPKI 공동인증서 (xecure7) |
| `EASY_SIGN` | `esign-install` | L1~L2 | 카카오/네이버/PASS 간편인증 |
| `PRIVATE_ID` | `social-relay` | L1 | 소셜 간접 로그인 |

### 1.3 핵심 SDK 클래스

| 클래스 | JAR | 역할 |
|--------|-----|------|
| `kr.or.anyid.auth.AnyidAuth` | `anyid-auth-sdk-1.0.19.jar` | 서명 검증, HASH, I/O 유틸 |
| `kr.or.anyid.util.AnyidCertRef` | `anyid-auth-util-sdk-1.0.19.jar` | ssob 암호화/복호화 (ARIA-CBC-256) |
| `kr.or.anyid.auth.extract.ExtractConfigurer` | `anyid-auth-sdk-1.0.19.jar` | 인증수단별 extract 처리 빌더 |
| `kr.or.anyid.auth.extract.DefaultMipExtractor` | `anyid-auth-sdk-1.0.19.jar` | 모바일 신분증 extract |
| `kr.or.anyid.auth.extract.DefaultCertificateExtractor` | `anyid-auth-sdk-1.0.19.jar` | 인증서 계열 extract |
| `kr.or.anyid.auth.extract.DefaultPIDExtractor` | `anyid-auth-sdk-1.0.19.jar` | 민간ID extract |
| `kr.or.anyid.auth.extract.DefaultEsignExtractor` | `anyid-auth-sdk-1.0.19.jar` | 간편인증 extract |
| `kr.or.anyid.adaptor.Sso` | `anyid-auth-sdk-1.0.19.jar` | SSO 어댑터 유틸 |
| `kr.or.anyid.adaptor.core.utils.PropertiesManager` | `anyid-auth-sdk-1.0.19.jar` | kdist 경로 자동 관리 |

---

## 2. AuthResourceInstall 패키지 구조

```
AuthResourceInstall/
├── resources/
│   ├── config/pid/pid_api.json        ← 민간ID API 키 (srvcNo, clientId 등)
│   └── ucpid.properties               ← UCPID 연동 설정
│
└── webapp/
    ├── WEB-INF/
    │   ├── config/kdist/kdist-api.json ← KMS 서버 설정 (ARIA-CBC-256)
    │   └── lib/                        ← Java SDK JAR 파일 (13개)
    │       ├── anyid-auth-sdk-1.0.19.jar       ← 핵심 SDK
    │       ├── anyid-auth-util-sdk-1.0.19.jar  ← KMS/ssob 유틸
    │       ├── anyid-agson-1.0.1.jar           ← JSON 유틸
    │       ├── anyid-bc-ref-1.0.2.jar          ← BouncyCastle JDK14 대응 래퍼
    │       ├── bcpkix-jdk15to18-1.68.jar       ← BouncyCastle PKI
    │       ├── bcprov-jdk15to18-1.68.jar       ← BouncyCastle 암복호화 본체
    │       ├── commons-codec-1.15.jar
    │       ├── commons-configuration-1.10.jar
    │       ├── commons-lang-2.6.jar
    │       ├── core-3.3.0.jar / javase-3.3.0.jar  ← QR 코드
    │       ├── gson-2.8.6.jar
    │       ├── kdist-api-1.0.12.jar            ← KMS 클라이언트
    │       ├── pid_api-1.0.38.jar              ← 민간ID API
    │       └── xecure7.jar                     ← AnySign 공동인증서 검증
    │
    ├── config/
    │   ├── config.anyidc.json          ← 인증 UI 모듈 목록 (list 기반)
    │   └── config.anyidc.etc.json      ← 추가 인증수단 (ksbiz, ksign, MagicLine4Web 등)
    │
    ├── anyid/                          ← JS SDK 정적 파일 (app.js, vendor.js 등)
    │
    ├── jsp/                            ← 인증수단별 extract.jsp / accInfo.jsp
    │   ├── mip/extract.jsp             ← 모바일 신분증
    │   ├── fincert/extract.jsp         ← 금융인증서
    │   ├── AnySignLite/extract.jsp     ← 공동인증서 (xecure7 서명 검증)
    │   ├── esign/extract.jsp           ← 간편인증
    │   ├── pid/extract.jsp             ← 민간ID
    │   ├── ksbiz/extract.jsp           ← KSign BIZ
    │   └── ksign/extract.jsp           ← KSign
    │
    └── sample/
        ├── login.jsp                   ← AnyidC.LOAD_MODULE() 샘플
        ├── orgLogin.jsp                ← 이용기관 자체 로그인 (ssob 복호화)
        └── 이용기관 자체 로그인 샘플_anyidAdaptor.jsp  ← anyidAdaptor JS 패턴
```

---

## 3. JAR 설치 — integration-sso 적용

### 3.1 복사 대상 (13개 JAR)

```bash
# SDK 소스 경로
ANYID_LIB=/home/user/anyid-sdk/AuthResourceInstall/webapp/WEB-INF/lib

# 대상 경로 (기존 fileTree("libs") 의존성 선언됨)
IDO_LIB=/home/user/webapp/idem-hub/libs

# BouncyCastle 본체(bcprov-jdk15to18-1.68, bcpkix-jdk15to18-1.68)는
# 기존 bcprov-jdk18on:1.78.1 / bcpkix-jdk18on:1.78.1 로 대체 — 복사 제외
JARS=(
  anyid-auth-sdk-1.0.19.jar
  anyid-auth-util-sdk-1.0.19.jar
  anyid-agson-1.0.1.jar
  anyid-bc-ref-1.0.2.jar        # BouncyCastle JDK14 대응 래퍼 (BouncyCastle 본체 제외)
  commons-codec-1.15.jar
  commons-configuration-1.10.jar
  commons-lang-2.6.jar
  core-3.3.0.jar
  javase-3.3.0.jar
  gson-2.8.6.jar
  kdist-api-1.0.12.jar
  pid_api-1.0.38.jar
  xecure7.jar
)

for jar in "${JARS[@]}"; do
  cp "$ANYID_LIB/$jar" "$IDO_LIB/$jar"
  echo "복사: $jar"
done
```

### 3.2 build.gradle.kts 확인

`idem-hub/build.gradle.kts`에 이미 `fileTree("libs")` 의존성이 선언되어 있어 **별도 수정 불필요**:

```kotlin
// ── 로컬 libs 디렉토리 (OACX SDK, BouncyCastle 등 Maven Central 미등록 JAR) ──
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
```

### 3.3 BouncyCastle 버전 충돌 처리

| 항목 | SDK 제공 | 기존 선언 | 해결 방법 |
|------|----------|-----------|-----------|
| BouncyCastle 본체 | `bcprov-jdk15to18-1.68` | `bcprov-jdk18on:1.78.1` | SDK 본체 제외, 기존 1.78.1 사용 |
| BouncyCastle PKI | `bcpkix-jdk15to18-1.68` | `bcpkix-jdk18on:1.78.1` | SDK 본체 제외, 기존 1.78.1 사용 |
| BouncyCastle 래퍼 | `anyid-bc-ref-1.0.2.jar` | — | **복사 포함** (JDK14 호환 레이어) |

> **핵심 원칙**: `bcprov-jdk15to18` ≠ `bcprov-jdk18on`. 전자는 JDK14 이하 호환, 후자는 JDK18+ 전용.  
> Spring Boot 3.x는 JDK 17+ 기반이므로 `bcprov-jdk18on:1.78.1`로 통일한다.  
> SDK가 BouncyCastle 호출 시 `anyid-bc-ref-1.0.2.jar`가 런타임에 올바른 클래스를 제공한다.

---

## 4. 설정 파일 구조

### 4.1 config.anyidc.json

SDK 샘플의 `list` 기반 구조를 그대로 따른다. Spring Boot 환경에서는 `extract` URL을 REST 컨트롤러 경로로 매핑한다.

```json
{
  "list": [
    {
      "name":  "mip-install",
      "level": 2,
      "urls": {
        "config":  "/api/v1/anyid/mip/config",
        "extract": "/api/v1/anyid/mobile-id/ssob"
      }
    },
    {
      "name":  "fincert-install",
      "level": 2,
      "urls": {
        "accInfo": "/api/v1/anyid/financial-cert/accInfo",
        "extract": "/api/v1/anyid/financial-cert/ssob"
      }
    },
    {
      "name":  "anysignlite-install",
      "level": 2,
      "urls": {
        "accInfo": "/api/v1/anyid/joint-cert/accInfo",
        "extract": "/api/v1/anyid/joint-cert/ssob"
      }
    },
    {
      "name":  "esign-install",
      "level": 2,
      "urls": {
        "config":  "/api/v1/anyid/easy-sign/esign-config",
        "extract": "/api/v1/anyid/easy-sign/ssob"
      }
    },
    {
      "name":  "social-relay",
      "level": 3,
      "urls": {
        "provider": "https://www.anyid.dev:1443/pid/auth.do",
        "extract":  "/api/v1/anyid/pid/ssob"
      }
    }
  ],
  "organization": {
    "srvcNo":     "1000001157",
    "agencyCode": "1000001157",
    "agencyName": "중소벤처24기업마당"
  }
}
```

**중요**: SDK `AnyidC.LOAD_MODULE()`의 `cfg` 파라미터가 이 파일을 로드하므로,  
경로는 반드시 **브라우저에서 접근 가능한 URL**이어야 한다.  
(`/config/config.anyidc.json` 또는 `/api/v1/anyid/config/anyidc` 등)

### 4.2 kdist-api.json (KMS 설정)

```json
{
  "kms_server_host": "https://www.anyid.dev:8119/",
  "srvc_no":         "1000001157",
  "enc_alg":         "ARIA-CBC-256",
  "cversion":        1,
  "app_key":         "…(RSA 암호화된 앱 키, 행안부 발급)…",
  "client_info":     "…(Base64, 행안부 발급)…"
}
```

> **보안**: 운영 환경에서는 `app_key` / `client_info` 를 K8s Secret 또는 Vault로 주입한다.  
> Spring Boot 환경에서는 `AnyIdProperties.Kms#kdistConfig`가 파일 경로를 결정한다.

#### `PropertiesManager.kdistConfigPath` 작동 방식

SDK 내부적으로 `PropertiesManager.kdistConfigPath` 정적 필드에 경로를 저장한다.  
JSP 환경에서는 `request.getServletContext().getRealPath("") + "/WEB-INF/config/kdist/kdist-api.json"` 형태로 설정된다.  
**Spring Boot 환경**에서는 `AnyIdSsobService.init()`에서 classpath 리소스를 File 객체로 변환하여 절대 경로를 결정한다:

```java
@PostConstruct
void init() {
    File file = resourceLoader.getResource("classpath:config/anyid/kdist-local.json").getFile();
    kdistAbsPath = file.getAbsolutePath();
    // → /home/user/webapp/idem-hub/build/resources/main/config/anyid/kdist-local.json
}
```

### 4.3 pid_api.json (민간ID 설정)

```json
{
  "srvcNo":        "1000001157",
  "clientId":      "…(행안부 발급)…",
  "clientSecret":  "…(Base64, 행안부 발급)…",
  "clientApiKey":  "…(행안부 발급)…",
  "reqFlag":       "3",
  "encAlg":        "ARIA-CBC-256"
}
```

---

## 5. 프론트엔드 연동 — AnyidC.LOAD_MODULE()

### 5.1 JS SDK 파일 배치

SDK 패키지의 `webapp/anyid/` 디렉토리를 정적 리소스로 제공한다.

```
idem-hub/src/main/resources/static/anyid/
├── js/
│   ├── manifest.js   ← 로드 순서 1순위
│   ├── vendor.js     ← 로드 순서 2순위
│   └── app.js        ← 로드 순서 3순위 (AnyidC 전역 객체 정의)
├── css/
│   └── app.css
├── fonts/
│   ├── PretendardGOV-Bold.woff
│   ├── PretendardGOV-Medium.woff
│   ├── PretendardGOV-Regular.woff
│   └── PretendardGOVVariable.woff2
└── img/
    ├── ico_sp2_24X24.png
    └── ico_sp_login.png
```

```bash
# SDK 정적 파일 복사 명령
cp -r /home/user/anyid-sdk/AuthResourceInstall/webapp/anyid/ \
      /home/user/webapp/idem-hub/src/main/resources/static/anyid/
```

### 5.2 로그인 페이지 HTML 구조

```html
<!-- 1. CSS/JS 로드 순서 고정 -->
<link href="/anyid/css/app.css" rel="stylesheet">
<script src="/anyid/js/manifest.js"></script>
<script src="/anyid/js/vendor.js"></script>
<script src="/anyid/js/app.js"></script>

<!-- 2. anyidAdaptor 초기화 (인라인 또는 별도 파일) -->
<script>
var anyidAdaptor = anyidAdaptor || {};

// 이용기관 자체 로그인 콜백 — ssoByPass != 0 또는 !data.useSso 시 호출
anyidAdaptor.orgLogin = function(data) {
    const params = new URLSearchParams(location.search);
    var obj = {
        ssob: data.ssob,
        tag:  params.get("tx")   // txId와 동일
    };
    var xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/v1/anyid/" + getProviderFromContext() + "/ssob", true);
    xhr.setRequestHeader("Content-Type", "application/json");
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4 && xhr.status === 200) {
            var response = JSON.parse(xhr.responseText);
            if (response.status === "success") {
                // feSessionId 쿠키가 응답 헤더에 Set-Cookie로 발급됨
                window.location.href = "/conversion/complete";
            }
        }
    };
    xhr.send(JSON.stringify(obj));
};

// SSO 경유 로그인 (data.useSso === true 시 호출)
anyidAdaptor.success = function(data) {
    if (anyidAdaptor.ssoByPass != 0 || !data.useSso) {
        anyidAdaptor.orgLogin(data);
    } else {
        anyidAdaptor.certData = data;
        anyidAdaptor.userCheck();
    }
};

anyidAdaptor.ssoByPass = /*[[${ssoByPass}]]*/ 0;  // Thymeleaf 바인딩
</script>

<!-- 3. 인증 UI 컨테이너 -->
<div id="anyidc"></div>

<!-- 4. toggle 버튼 (선택) -->
<div id="anyidtoggle"></div>

<!-- 5. LOAD_MODULE 초기화 스크립트 -->
<script>
function initAnyId() {
    const params = new URLSearchParams(location.search);
    AnyidC.LOAD_MODULE({
        cfg:    "/config/config.anyidc.json",  // config.anyidc.json URL
        txId:   params.get("tx"),              // 트랜잭션 ID
        tag:    params.get("tx"),              // tag = txId (암호화 키 용도)
        lvl:    parseInt(params.get("acrValues")) || 3,  // 인증 수준 (1~3)
        bypass: anyidAdaptor.ssoByPass,        // SSO 우회 여부
        theme:  "4.1.0",
        toggle: true,                          // 인증수단 토글 UI 표시
        success: function(data) {
            anyidAdaptor.success(data);
        },
        fail: function(err) {
            console.error("Any-ID 인증 실패:", err);
        },
        log: function(data) {
            console.log("Any-ID 로그:", data);
        }
    });
}
document.addEventListener("DOMContentLoaded", initAnyId);
</script>
```

### 5.3 AnyidC.LOAD_MODULE() 파라미터 설명

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `cfg` | `string` | ✅ | config.anyidc.json URL. FE에서 fetch로 인증 수단 목록 로드 |
| `txId` | `string` | ✅ | 트랜잭션 ID. 세션 식별자 (`params.get("tx")` 또는 UUID) |
| `tag` | `string` | ✅ | 암호화 태그. `txId`와 동일값 사용. ssob 복호화 시 필요 |
| `lvl` | `number` | ✅ | 요청 인증 수준. `1`=간편, `2`=신분증/PASS, `3`=인증서 |
| `bypass` | `number` | ✅ | SSO 우회 여부. `0`=SSO 사용, `1`=우회 |
| `theme` | `string` | ✅ | SDK UI 테마 버전. `"4.1.0"` 고정 |
| `toggle` | `boolean` | — | 인증수단 토글 UI 표시 여부 |
| `exclude` | `string` | — | 특정 인증수단 제외. 예: `"01"` 또는 `"01\|02\|03"` |
| `success` | `function` | ✅ | 인증 성공 콜백. `data.ssob`, `data.txId`, `data.useSso` 포함 |
| `fail` | `function` | — | 인증 실패 콜백 |
| `log` | `function` | — | SDK 내부 로그 콜백 |

### 5.4 success 콜백 data 구조

```javascript
// anyidAdaptor.success(data) 파라미터
{
  ssob:      "암호화된 인증 결과 문자열",  // 서버로 전송하여 decryptSsob 처리
  txId:      "트랜잭션 ID",
  tag:       "태그 (=txId)",
  useSso:    true,        // SSO 연동 여부
  userSeCd:  "사용자 구분 코드",
  afData:    "추가 인증 데이터"
}
```

---

## 6. extract → Spring Controller 변환 패턴

SDK 샘플은 `extract.jsp`로 각 인증 수단의 서버 처리를 구현한다.  
Spring Boot 환경에서는 이를 `@PostMapping("/{provider}/ssob")` 으로 대체한다.

### 6.1 JSP 원본 (fincert/extract.jsp) 핵심 코드

```java
// JSP 원본 패턴
new kr.or.anyid.auth.extract.ExtractConfigurer(
    new kr.or.anyid.auth.extract.DefaultCertificateExtractor() {
        @Override
        public kr.or.anyid.auth.extract.IExtractor writeSsobExtensions(Object writable) throws Exception {
            if(writable instanceof Map) {
                Map ssob = (Map) writable;
                ssob.put("clientIp", clientIP);
                ssob.put("ci", ucpid.get("ci"));
                ssob.put("name", ucpid.get("real_name"));
            }
            return this;
        }
        @Override
        public boolean verifySignData(String signData) { return true; }
        // ...
    }
)
.build(
    kr.or.anyid.adaptor.core.utils.PropertiesManager.kdistConfigPath,
    request.getInputStream(),
    response.getWriter()
);
```

### 6.2 Spring Boot 변환 (AnyIdController)

```java
// Spring Boot 변환 — POST /api/v1/anyid/{provider}/ssob
@PostMapping("/{provider}/ssob")
public ResponseEntity<Map<String, Object>> processSsob(
        @PathVariable String provider,
        @RequestBody Map<String, String> body) {

    String ssobStr = body.get("ssob");
    String tag     = body.get("tag");      // = txId

    // SDK 복호화 (AnyidCertRef.decryptSsob)
    Map<String, Object> ssob = anyIdSsobService.decryptSsob(ssobStr, tag, null);

    // CI, authLevel 추출
    String ci        = anyIdSsobService.extractCi(ssob, cid);
    String authLevel = anyIdSsobService.extractAuthLevel(ssob);

    // NonOidcAuthService 연동
    NonOidcAuthCommand command = NonOidcAuthCommand.builder()
        .correlationId(cid)
        .providerCode(normalizeProviderCode(provider))
        .providerTxId(txId)
        .rawIdentifier(ci)
        .requestedLevel(authLevel)
        .providerVerified(true)
        .build();

    String authResultId = nonOidcAuthService.processAuth(command);

    // FeSession 생성
    FeSession session = feSessionService.create(authResultId, authResultId, authLevel, null);

    // feSessionId 쿠키 발급
    HttpHeaders headers = new HttpHeaders();
    headers.add(SET_COOKIE, "fe_session=" + session.getFeSessionId() + "; HttpOnly; SameSite=Lax");

    return ResponseEntity.ok()
        .headers(headers)
        .body(Map.of("status", "success", "authLevel", authLevel));
}
```

---

## 7. ssob 복호화 → CI 추출 코드

### 7.1 핵심 SDK API

```java
import kr.or.anyid.auth.AnyidAuth;
import kr.or.anyid.util.AnyidCertRef;

// --- ssob 복호화 흐름 ---

// 1. SDK 인스턴스 생성
AnyidAuth    anyidAuth    = new AnyidAuth();
AnyidCertRef anyidCertRef = new AnyidCertRef();

// 2. decryptSsob 호출
//    파라미터:
//      ssobStr  - FE에서 전송된 암호화 ssob
//      tag      - 암호화 태그 (= txId, AnyidC.LOAD_MODULE의 tag 값)
//      kdistPath - kdist-api.json 절대 경로
Map<String, Object> resultMap = anyidCertRef.decryptSsob(ssobStr, tag, kdistAbsPath);

// 3. resultMap 구조
// {
//   "status":  "success",
//   "ssobStr": "{ \"ci\":\"…\", \"authLvl\":\"2\", \"name\":\"홍길동\", … }"
// }

// 4. 내부 ssobStr 파싱
String  ssobJsonStr = (String) resultMap.get("ssobStr");
ObjectMapper mapper  = new ObjectMapper();
Map<String, Object> ssob = mapper.readValue(ssobJsonStr, new TypeReference<>() {});

// 5. 필드 추출
String ci        = (String) ssob.get("ci");          // 연계정보 (CI)
String authLvl   = (String) ssob.get("authLvl");     // "1" / "2" / "3"
String name      = (String) ssob.get("name");         // 성명
String brdt      = (String) ssob.get("brdt");         // 생년월일 (일부 수단)
String clientIp  = (String) ssob.get("clientIp");    // 클라이언트 IP
String vid       = (String) ssob.get("vid");          // 가상ID (공동인증서)
```

### 7.2 ssob 내부 필드 전체 목록

| 필드 | 타입 | 인증 수단 | 설명 |
|------|------|-----------|------|
| `ci` | string | 전체 | 연계정보. **SHA-256 해싱 후 DB 저장 필수** |
| `authLvl` | string | 전체 | `"1"`=L1, `"2"`=L2, `"3"`=L3 |
| `name` | string | fincert, pid | 성명 (화면 표시용) |
| `brdt` | string | esign, pid | 생년월일 (YYYYMMDD) |
| `clientIp` | string | 전체 | 클라이언트 IP (extract.jsp에서 삽입) |
| `vid` | string | anysignlite (xecure7) | 가상주민번호 |
| `vidRandom` | string | anysignlite | VID 랜덤 |
| `userSeCd` | string | 전체 | 사용자 구분 코드 |

### 7.3 `AnyIdSsobService` 사용 예제

```java
@Autowired
private AnyIdSsobService anyIdSsobService;

// ssob 복호화
Map<String, Object> ssob = anyIdSsobService.decryptSsob(ssobStr, tag, null);

// CI 추출 (비어있으면 PlatformException)
String ci = anyIdSsobService.extractCi(ssob, correlationId);

// authLevel 정규화 ("1"→"L1", "2"→"L2", "3"→"L3")
String authLevel = anyIdSsobService.extractAuthLevel(ssob);
```

### 7.4 orgLogin.jsp 패턴 (샘플 코드 전체)

```java
// SDK 샘플: webapp/sample/orgLogin.jsp
public void orgLogin(InputStream inputStream, PrintWriter printWriter, String keyPath) throws Exception {
    Map<String, Object> result = new HashMap<>();

    AnyidAuth    anyidcert   = new AnyidAuth();
    AnyidCertRef anyidCertRef = new AnyidCertRef();
    ObjectMapper objectMapper  = new ObjectMapper();

    try {
        // HTTP request body 읽기
        String stream = anyidcert.readValueAsString(inputStream, "UTF-8");
        Map params = objectMapper.readValue(stream, new TypeReference<HashMap<String, Object>>() {});

        String ssobStr = (String) params.get("ssob");
        String tag     = (String) params.get("tag");   // = txId

        // ssob 복호화
        Map<String, Object> resultMap = anyidCertRef.decryptSsob(ssobStr, tag, keyPath);
        String tempStr = (String) resultMap.get("ssobStr");
        Map ssob = objectMapper.readValue(tempStr, new TypeReference<HashMap<String, Object>>() {});

        // 이용기관 자체 로그인에 필요한 정보
        System.out.println(ssob.get("ci"));      // 연계정보
        System.out.println(ssob.get("authLvl")); // 인증 수준

        result.put("resultMap", resultMap);
        result.put("status", "success");
        anyidcert.writeValueAsString(printWriter, result);

    } catch (Exception e) {
        result.put("status", "fail");
        result.put("sResult", "서명 검증에 실패 하였습니다. [" + e.getMessage() + "]");
        anyidcert.writeValueAsString(printWriter, result);
    }
}
```

---

## 8. 인증수단별 extract 패턴 분석

### 8.1 공통 ExtractConfigurer 패턴

모든 인증 수단은 `ExtractConfigurer` 빌더를 통해 extract 처리를 구성한다:

```java
// 공통 패턴
new ExtractConfigurer(new Default{인증수단}Extractor() {
    @Override
    public boolean isHashed() { return true; }

    @Override
    public String hashed() { return request.getHeader("hashed"); }

    @Override
    public IExtractor writeSsobExtensions(Object writable) throws Exception {
        if (writable instanceof Map) {
            Map ssob = (Map) writable;
            ssob.put("clientIp", clientIP);  // ssob에 클라이언트 IP 삽입
            // 수단별 추가 필드 삽입
        }
        return this;
    }
})
.build(
    PropertiesManager.kdistConfigPath,  // kdist-api.json 절대 경로
    request.getInputStream(),
    response.getWriter()
);
```

### 8.2 인증수단별 차이점

#### 모바일 신분증 (mip-install)
```java
// DefaultMipExtractor 사용
// hashed 헤더로 PBKDF2 해시 검증 필수
// verifyHash() 오버라이드로 tag 기반 해시 검증:
byte[] hashedBytes = anyidAuth.deriveKey(stream.getBytes(), tagStr.getBytes(), 1000, 64);
String hashedStr   = anyidAuth.binToHex(hashedBytes);
if (!hashed.equalsIgnoreCase(hashedStr)) { /* fail */ }
```

#### 공동인증서 (anysignlite-install)
```java
// DefaultCertificateExtractor 사용
// xecure7.jar의 SignVerifier로 PKCS#7 서명 검증
XecureConfig   aXecureConfig = new XecureConfig();
SignVerifier    verifier      = new SignVerifier(aXecureConfig, signData, "utf-8");
// 인증서에서 vid, realName 추출 (ASN1 파싱)
TBSCertificate tbsCertificate = extractCertificate(anyidAuth, signData);
ASN1Sequence   asn1Sequence   = extractSubjectAltName(tbsCertificate);
String realName = extractRealName(asn1Sequence);
String vid      = extractVid(asn1Sequence);
```

#### 금융인증서 (fincert-install)
```java
// DefaultCertificateExtractor 사용
// 금융인증서는 UI에서 검증 완료 → verifySignData()는 항상 true 반환
// UCPID API 연동으로 ci, name, brdt 획득:
Map token = accessToken(props, tokenApiUrl, ...);
Map ucpid = reqUCPID(sign, cp_code, nonce, apiUrl, access_token, ...);
ssob.put("ci",   ucpid.get("ci"));
ssob.put("name", ucpid.get("real_name"));
ssob.put("brdt", ucpid.get("birth_date"));
```

#### 간편인증 (esign-install)
```java
// DefaultEsignExtractor 사용
// type() → "install" (설치형)
// parseTokenUrl() → "http://ir.any-id.kr:11005/oacx/api/v1.0/trans"
// proxyParse()로 토큰 파싱 → birthday 추출
```

#### 민간ID (social-relay)
```java
// DefaultPIDExtractor 사용
// isStored() → true (세션 기반 저장)
// retStored() → session.getAttribute("anyid").get("pid")
// .build(kdistPath, inputStream, writer, session.getId())  // sessionId 추가
```

---

## 9. NonOidcAuthService 연동

### 9.1 처리 흐름

```
ssob 복호화 완료
      │
      ▼ CI (rawIdentifier)
NonOidcAuthCommand.builder()
  .correlationId(cid)
  .providerCode("MOBILE_ID" | "EASY_SIGN" | "JOINT_CERT" | "FINANCIAL_CERT" | "PRIVATE_ID")
  .providerTxId(txId)
  .rawIdentifier(ci)          // ← 평문 CI (내부에서 SHA-256 해싱)
  .requestedLevel(authLevel)
  .providerVerified(true)
  .build()
      │
      ▼
NonOidcAuthService.processAuth(command)
  1. computeIdentifierHash(rawCI) → SHA-256 → identifierHash
  2. saveAuthResult() → ido.auth_result INSERT
  3. saveAndPublishEvent() → ido.outbox INSERT + Kafka 발행
  4. brokerAuditLogService.recordComplete()
      │
      ▼ authResultId (UUID)
```

### 9.2 provider_code → authLevel 매핑 (Any-ID 확장)

`NonOidcAuthService.resolveAuthLevel()` 메서드에 Any-ID 수단이 추가되어야 한다:

```java
// NonOidcAuthService.resolveAuthLevel() — Any-ID 수단 추가
private String resolveAuthLevel(String providerCode) {
    return switch (providerCode.toUpperCase()) {
        case "MOBILE_ID"       -> "L2";   // 행안부 DID L2
        case "EASY_SIGN"       -> "L1";   // 간편인증 L1 (PASS는 L2)
        case "JOINT_CERT"      -> "L3";
        case "FINANCIAL_CERT"  -> "L3";
        case "PRIVATE_ID"      -> "L1";
        case "PASS"            -> "L2";
        case "GPKI"            -> "L3";
        default                -> "L1";
    };
}
```

> **참고**: ssob의 `authLvl` 필드가 실제 인증 수준을 포함하므로, 가능하면 ssob에서 추출한 authLevel을 사용한다.

---

## 10. FeSession 발급 흐름

```
NonOidcAuthService.processAuth() → authResultId
      │
      ▼
FeSessionService.create(
  qimUserId  = authResultId,    // PoC: 추후 QIM 연동으로 대체
  authResultId = authResultId,
  authLevel    = "L2",
  returnUrl    = null
)
      │
      ▼
Redis 저장:
  key: "fe:session:{feSessionId}"
  TTL: sliding 30분 / absolute 480분
      │
      ▼
Set-Cookie 응답 헤더:
  fe_session={feSessionId}; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800
```

### 10.1 쿠키 설정 상세

| 속성 | 값 | 이유 |
|------|-----|------|
| `Path` | `/` | 전체 도메인 접근 |
| `HttpOnly` | — | XSS 방어 (JS 접근 차단) |
| `SameSite=Lax` | — | CSRF 방어 (GET 크로스사이트 허용) |
| `Secure` | 운영환경 | HTTPS 전용 (로컬 개발에서는 생략) |
| `Max-Age` | 1800 | 세션 슬라이딩 TTL (30분) |

---

## 11. 전체 인증 시퀀스 다이어그램

### 11.1 이용기관 자체 로그인 (ssoByPass=1)

```
Browser          FE(Thymeleaf)    AnyIdController    AnyIdSsobService    NonOidcAuthService
   │                  │                  │                   │                   │
   │── GET /login ──▶│                  │                   │                   │
   │◀── 인증 페이지 ──│                  │                   │                   │
   │                  │                  │                   │                   │
   │ AnyidC.LOAD_MODULE({             │                   │                   │
   │   cfg:"/config/config.anyidc.json│                   │                   │
   │   txId, tag, lvl:2, bypass:1})  │                   │                   │
   │                  │                  │                   │                   │
   │── 인증 UI 표시 ──▶│                  │                   │                   │
   │ (모바일신분증/공동인증서 등 선택)  │                   │                   │
   │                  │                  │                   │                   │
   │ anyidAdaptor.success(data) 콜백  │                   │                   │
   │  data = {ssob:"...", txId:"..."}  │                   │                   │
   │                  │                  │                   │                   │
   │── POST /api/v1/anyid/mobile-id/ssob                  │                   │
   │   body: {ssob, tag}  ────────────▶│                   │                   │
   │                  │                  │── decryptSsob ──▶│                   │
   │                  │                  │   (AnyidCertRef) │                   │
   │                  │                  │◀── {ci, authLvl} │                   │
   │                  │                  │                   │                   │
   │                  │                  │── processAuth ────────────────────▶│
   │                  │                  │   NonOidcAuthCommand               │
   │                  │                  │◀── authResultId ──────────────────│
   │                  │                  │                   │                   │
   │                  │                  │── FeSession.create()               │
   │                  │                  │◀── feSessionId                     │
   │                  │                  │                   │                   │
   │◀── 200 {status:"success"} ────────│                   │                   │
   │    Set-Cookie: fe_session=...      │                   │                   │
   │                  │                  │                   │                   │
   │── GET /conversion/complete ────────│                   │                   │
```

### 11.2 SSO 경유 로그인 (ssoByPass=0, data.useSso=true)

```
Browser             anyidAdaptor.js    AnyidC SDK(FE)   Any-ID SSO Server
   │                     │                  │                   │
   │ anyidAdaptor.success(data)             │                   │
   │ data.useSso === true                   │                   │
   │── anyidAdaptor.userCheck()             │                   │
   │   POST /oidc/userCheck                 │                   │
   │   {txId, ssob, userSeCd}              │                   │
   │◀── {success:true} 또는 join 팝업      │                   │
   │                     │                  │                   │
   │── anyidAdaptor.ssoLogin()              │                   │
   │   encodedData = btoa({txId,ssob,...}) │                   │
   │── GET /oidc/ssoLogin?data=... ─────────────────────────▶│
   │                     │                  │                   │
   │                     │                  │◀── SSO 검증 결과  │
   │◀── 302 /conversion/complete            │                   │
   │    Set-Cookie: fe_session=...          │                   │
```

---

## 12. BouncyCastle 버전 충돌 해결

### 12.1 배경

| | 버전 | JDK 지원 |
|--|------|----------|
| SDK 제공 (`bcprov-jdk15to18-1.68`) | 1.68 | JDK 1.5~1.8 |
| 기존 선언 (`bcprov-jdk18on:1.78.1`) | 1.78.1 | JDK 18+ |

Spring Boot 3.x는 JDK 17+이므로 `jdk18on` 계열 사용이 적합하다.  
두 버전을 classpath에 동시에 두면 클래스 중복 로드 오류 발생 가능.

### 12.2 해결 방법

```kotlin
// build.gradle.kts — BouncyCastle 의존성
// SDK bcprov-jdk15to18-1.68 / bcpkix-jdk15to18-1.68 는 libs에서 제외
// 기존 jdk18on:1.78.1 유지

implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")  // 기존 유지
implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")  // 기존 유지
// libs/ 디렉토리에는 anyid-bc-ref-1.0.2.jar만 포함 (래퍼)
```

`anyid-bc-ref-1.0.2.jar`는 `bcprov-jdk15to18-1.68`의 JDK14 대응 래퍼 계층이다.  
실제 암호화 연산은 `bcprov-jdk18on:1.78.1`에서 처리되어 호환성이 유지된다.

### 12.3 런타임 검증

```bash
# JAR 내 BouncyCastle 클래스 중복 확인
jar tf idem-hub/libs/anyid-bc-ref-1.0.2.jar | grep "BouncyCastle"
# → 래퍼 클래스만 존재, 암호화 구현체 없음 → 충돌 없음
```

---

## 13. 트러블슈팅 & FAQ

### Q1. `AnyidCertRef.decryptSsob()` 호출 시 `NullPointerException` 발생

**원인**: `kdist-api.json` 경로가 null이거나 파일 접근 불가.

**해결**:
```bash
# 1. kdist-api.json 파일 존재 확인
ls -la idem-hub/src/main/resources/config/anyid/kdist-local.json

# 2. 빌드 후 경로 확인
ls -la idem-hub/build/resources/main/config/anyid/kdist-local.json

# 3. AnyIdSsobService 로그 확인
grep "kdist-api.json 경로" application.log
```

### Q2. ssob 복호화 후 `ci` 필드가 비어있음

**원인**: 인증 수단에 따라 CI를 외부 API(UCPID 등)에서 조회해야 하는 경우.  
금융인증서(fincert)는 UCPID API 호출 결과에서 CI를 가져온다.

**해결**: UCPID API 연동 설정 확인:
```
resources/ucpid.properties:
  tokenApiUrl=...
  apiUrl=...
  cp_code=...
  clientId=...
  clientSecret=...
```

### Q3. `AnyidC.LOAD_MODULE()` 호출 후 UI가 표시되지 않음

**체크리스트**:
1. JS 로드 순서 확인: `manifest.js` → `vendor.js` → `app.js`
2. `<div id="anyidc"></div>` 존재 확인
3. `cfg` URL에서 `config.anyidc.json` 정상 응답 확인 (`200 OK`)
4. `config.anyidc.json`의 `list` 배열에 원하는 인증 수단 포함 확인
5. 브라우저 콘솔 에러 확인

### Q4. `BouncyCastle: No provider` 오류

**원인**: BouncyCastle Security Provider가 JCA에 등록되지 않음.

**해결**: Spring Boot 애플리케이션 시작 시 Provider 등록:
```java
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.Security;

@SpringBootApplication
public class IdoApplication {
    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
}
```

### Q5. 모바일 신분증 `verifyHash` 실패 ("유효한 요청이 아닙니다")

**원인**: FE에서 `hashed` 헤더를 전송하지 않았거나, PBKDF2 해시 불일치.

**해결**: FE 요청 시 `hashed` 헤더 포함:
```javascript
// FE 요청 (mip extract 호출 시)
xhr.setRequestHeader("hashed", computedHash);  // SDK가 자동 계산
```

### Q6. `xecure7.jar` 초기화 실패

**원인**: AnySignLite(공동인증서) 모듈은 xecure7 네이티브 라이브러리가 필요한 경우 있음.

**해결**: WAS에 따라 추가 네이티브 라이브러리 경로 설정 필요. 개발 환경에서는 공동인증서 수단 제외(`exclude: "03"`) 옵션 사용.

### Q7. PR #138 이후 Any-ID DB migration V19와의 관계

`V19__anyid_provider_config.sql`에 등록된 5개 수단은 이 SDK 통합과 직접 연관된다:

| provider_code | broker_mode | SDK 모듈 |
|---------------|-------------|----------|
| `MOBILE_ID` | `anyid` | `mip-install` |
| `EASY_SIGN` | `anyid` | `esign-install` |
| `JOINT_CERT` | `anyid` (기존 `direct`→`anyid`) | `anysignlite-install` |
| `FINANCIAL_CERT` | `anyid` (기존 `direct`→`anyid`) | `fincert-install` |
| `PRIVATE_ID` | `anyid` | `social-relay` |

---

## 참고 자료

| 문서 | 경로 |
|------|------|
| 국내 인증 수단 종합 가이드 | `wiki/iam/08-kr-auth-providers-guide.md` |
| Any-ID DB migration V19 | `idem-hub/src/main/resources/db/migration/V19__anyid_provider_config.sql` |
| AnyIdSsobService | `idem-hub/src/main/java/kr/go/smes/idem-hub/broker/anyid/AnyIdSsobService.java` |
| AnyIdController | `idem-hub/src/main/java/kr/go/smes/idem-hub/broker/anyid/AnyIdController.java` |
| AnyIdBrokerAdapter | `idem-hub/src/main/java/kr/go/smes/idem-hub/broker/anyid/AnyIdBrokerAdapter.java` |
| AnyIdProperties | `idem-hub/src/main/java/kr/go/smes/idem-hub/broker/anyid/AnyIdProperties.java` |
| config.anyidc.json | `idem-hub/src/main/resources/config/anyid/config.anyidc.json` |
| kdist-local.json | `idem-hub/src/main/resources/config/anyid/kdist-local.json` |
| SDK 샘플 login.jsp | `AuthResourceInstall/webapp/sample/login.jsp` |
| SDK 샘플 anyidAdaptor.js | `AuthResourceInstall/webapp/sample/이용기관 자체 로그인 샘플_anyidAdaptor.jsp` |
| SDK 샘플 orgLogin.jsp | `AuthResourceInstall/webapp/sample/orgLogin.jsp` |
