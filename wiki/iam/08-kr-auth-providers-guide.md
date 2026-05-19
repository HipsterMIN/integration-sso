# 국내 인증 수단 종합 가이드

> **문서 분류**: IAM (Identity & Access Management) 연동 가이드  
> **버전**: v1.0.0  
> **작성일**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 아키텍트, 보안 담당자  
> **관련 서비스**: `ido` (port 8083), `q-sign`, `onepass-fe`

---

## 목차

1. [국내 인증 생태계 전체 지도](#1-국내-인증-생태계-전체-지도)
2. [법적 근거 및 공공기관 요건](#2-법적-근거-및-공공기관-요건)
3. [인증 수단 상세: 공인/공공 계열](#3-인증-수단-상세-공인공공-계열)
   - 3.1 [공동인증서 (구 공인인증서)](#31-공동인증서-구-공인인증서)
   - 3.2 [금융인증서 (금결원 yeskey)](#32-금융인증서-금결원-yeskey)
   - 3.3 [디지털원패스 (행안부)](#33-디지털원패스-행안부)
   - 3.4 [GPKI 정부 공개키 인증서](#34-gpki-정부-공개키-인증서)
   - 3.5 [모바일 신분증 (행안부 DID)](#35-모바일-신분증-행안부-did)
4. [인증 수단 상세: 민간인증서 계열](#4-인증-수단-상세-민간인증서-계열)
   - 4.1 [PASS 인증 (통신 3사)](#41-pass-인증-통신-3사)
   - 4.2 [카카오 인증서](#42-카카오-인증서)
   - 4.3 [네이버 인증서](#43-네이버-인증서)
   - 4.4 [KB국민은행 인증서](#44-kb국민은행-인증서)
   - 4.5 [토스 인증서](#45-토스-인증서)
   - 4.6 [삼성패스](#46-삼성패스)
   - 4.7 [라온시큐어 OmniOne](#47-라온시큐어-omnione)
5. [인증 수단 상세: OIDC/OAuth2 소셜 계열](#5-인증-수단-상세-oidcoauth2-소셜-계열)
   - 5.1 [카카오 OAuth2 소셜 로그인](#51-카카오-oauth2-소셜-로그인)
   - 5.2 [네이버 OAuth2 소셜 로그인](#52-네이버-oauth2-소셜-로그인)
6. [Any-ID 정부 통합인증을 통한 간접 연동](#6-any-id-정부-통합인증을-통한-간접-연동)
7. [OnePass 플랫폼 연동 아키텍처](#7-onepass-플랫폼-연동-아키텍처)
8. [provider_config DB 등록 방법](#8-provider_config-db-등록-방법)
9. [신규 인증 수단 추가 절차 (코드 레벨)](#9-신규-인증-수단-추가-절차-코드-레벨)
10. [인증 수단별 계약·신청 경로 정리](#10-인증-수단별-계약신청-경로-정리)
11. [인증 등급 매핑 기준](#11-인증-등급-매핑-기준)
12. [보안 고려사항](#12-보안-고려사항)

---

## 1. 국내 인증 생태계 전체 지도

```
┌─────────────────────────────────────────────────────────────────────┐
│                     국내 인증 수단 분류 체계                          │
├─────────────────┬───────────────────────────────────────────────────┤
│  공공/법정 인증  │  민간인증서 (행안부 고시)  │  소셜/OIDC            │
├─────────────────┼───────────────────────────────────────────────────┤
│ 공동인증서(NPKI) │ PASS (통신 3사)           │ 카카오 OAuth2         │
│ 금융인증서(yeskey)│ 카카오인증서             │ 네이버 OAuth2         │
│ GPKI (정부PKI)  │ 네이버인증서              │ 구글 OAuth2           │
│ 모바일신분증(DID)│ KB인증서                  │ 애플 Sign In          │
│ 디지털원패스    │ 토스인증서                │                       │
│                 │ 삼성패스                  │                       │
│                 │ 라온 OmniOne              │                       │
└─────────────────┴───────────────────────────────────────────────────┘

연동 경로:
  A. Any-ID 설치형 → 행안부 허가 인증수단 일괄 처리 [현재 구현]
  B. 각 사업자 직접 API 연동 [추가 구현 가능]
  C. Keycloak IdP 등록 (표준 OIDC/OAuth2) [현재 구현]
```

### 1.1 인증 수단별 표준 준수 레벨

| 인증 수단 | 프로토콜 | CI 제공 | 공공기관 적용 | 행안부 고시 | OnePass 경로 |
|-----------|---------|---------|------------|------------|-------------|
| 공동인증서 | PKCS#7 서명 | ✅ | ✅ | 법정 | Any-ID / 직접 |
| 금융인증서 | PKCS#7 서명 | ✅ | ✅ | 법정 | Any-ID / 직접 |
| GPKI | X.509 서명 | ✅ | ✅ | 법정 | Any-ID / 직접 |
| 모바일 신분증 | ISO 18013-5 DID | ✅ | ✅ | 법정 | Any-ID |
| 디지털원패스 | OIDC/OAuth2 | ✅ | ✅ | 행안부 직영 | Any-ID / 직접 |
| PASS | RESTful API | ✅ | ✅ | 2021년 고시 | Any-ID / 직접 |
| 카카오인증서 | RESTful API | ✅ | ✅ | 2021년 고시 | Any-ID / 직접 |
| 네이버인증서 | RESTful API | ✅ | ✅ | 2021년 고시 | Any-ID / 직접 |
| KB인증서 | RESTful API | ✅ | ✅ | 2022년 고시 | Any-ID |
| 토스인증 | RESTful API | ✅ | ✅ | 2022년 고시 | Any-ID |
| 삼성패스 | RESTful API | ✅ | ⚠️ | 일부 고시 | Any-ID |
| 라온 OmniOne | PKI + REST | ⚠️ | ❌ | 미고시 | 직접만 가능 |
| 카카오 OAuth2 | OIDC/OAuth2 | ❌(sub만) | ❌ | 해당없음 | Keycloak |
| 네이버 OAuth2 | OAuth2 | ❌ | ❌ | 해당없음 | Keycloak |

> **CI(연계정보)**: 주민등록번호를 해시한 표준 식별자. 공공기관 본인확인에는 CI가 필수.

---

## 2. 법적 근거 및 공공기관 요건

### 2.1 핵심 법령

```
전자서명법 (2020년 전면 개정)
  ├── §2 (정의): 공인전자서명 폐지, 전자서명 동등성 원칙 도입
  ├── §22의2: 공공기관 전자서명 이용 기준
  └── §22의3: 행안부 장관의 전자서명 이용 기준 고시 권한

행안부 고시 제2023-73호 (공공분야 전자서명 이용 기준)
  ├── §3: 공공기관이 이용 가능한 전자서명 수단 목록
  ├── §4: 행안부 평가·고시된 민간전자서명만 공공기관 적용 가능
  └── §5: 인증 등급(L1/L2/L3) 정의 및 적용 기준

전자정부법 제10조: 전자정부서비스 본인확인 근거
전자정부법 시행령 제12조 (개정 2024-05-21): Any-ID CI/주민번호 공식 인정
개인정보보호법 제24조의2: CI 수집·이용 제한 (정보주체 동의 필수)
```

### 2.2 공공기관 인증 수단 적용 기준

```
공공기관이 인증 수단을 도입할 때 확인해야 할 사항:

  ① 행안부 고시 목록 포함 여부
     - 고시 미포함 수단(예: 라온 OmniOne) → 법적 근거 불명확
     - 법무팀 검토 및 행안부 사전 협의 필요

  ② CI 제공 여부
     - 공공기관 본인확인 목적: CI 필수
     - 소셜 로그인(카카오 OAuth2): CI 미제공 → 회원 연동 용도로만 사용

  ③ 인증 등급(LoA)
     - 금융거래/법적 문서: L3 (공동인증서/금융인증서)
     - 일반 민원 처리: L2 이상 (PASS/모바일신분증)
     - 단순 조회: L1 이상

  ④ 개인정보보호
     - CI 처리 시 개인정보처리방침 갱신
     - 정보주체 동의 흐름 구현 필수
```

---

## 3. 인증 수단 상세: 공인/공공 계열

### 3.1 공동인증서 (구 공인인증서)

#### 개요

```
운영기관: 금융결제원(KFTC), KICA, 한국정보인증, CrossCert, SignKorea, NHN기술 (6개 CA)
프로토콜: PKCS#7 전자서명 + X.509 인증서
인증등급: L3 (최고 등급 — 법적 효력 보유)
CI 제공: ✅
저장위치: PC(HDD/USB), 클라우드(금결원 클라우드), 모바일
법적 근거: 전자서명법 §2 (전자서명 수단으로 인정)
```

#### 직접 연동 방법 (금결원 NPKI API)

```
신청 경로:
  금융결제원 개발자 센터 → https://www.kftc.or.kr
  KICA 인증 연동 → https://www.kica.sign.or.kr
  계약 유형: B2B 계약 (유료, 건당 과금)
```

**방법 1: Any-ID 설치형 경유 (권장 — 현재 구현)**

```java
// BrokerService → AnyIdBrokerAdapter → anyid.dev:1443/cert
// 이미 구현 완료 (AnyIdBrokerAdapter.java 참조)
// provider 코드: JOINT_CERT
```

**방법 2: 금결원 NPKI 라이브러리 직접 연동**

```java
// build.gradle.kts
dependencies {
    // 금결원 제공 NPKI SDK (계약 후 수령)
    implementation(files("libs/npki-sdk-2.x.x.jar"))
    implementation(files("libs/npki-server-api-2.x.x.jar"))
}
```

```java
// JointCertBrokerAdapter.java
@Slf4j
@Service
@RequiredArgsConstructor
public class JointCertBrokerAdapter {

    @Value("${joint-cert.npki.service-id}")
    private String serviceId;

    @Value("${joint-cert.npki.service-password}")
    private String servicePassword;

    /**
     * 공동인증서 서명 검증
     *
     * @param signedData  PKCS#7 서명값 (Base64)
     * @param originalData 원본 데이터
     * @param certDn       인증서 DN
     * @return 검증 결과 (CI 포함)
     */
    public JointCertResult verify(String signedData, String originalData, String certDn) {
        // NPKI SDK 호출
        NpkiServer server = new NpkiServer();
        server.setServiceId(serviceId);
        server.setPassword(servicePassword);

        try {
            // 1. 서명 검증
            int result = server.verifySignedData(
                Base64.decode(signedData),
                originalData.getBytes(StandardCharsets.UTF_8)
            );

            if (result != NpkiResult.SUCCESS) {
                throw new PlatformException(
                    PlatformErrorCode.IDP_RESPONSE_INVALID,
                    "공동인증서 서명 검증 실패: code=" + result
                );
            }

            // 2. CI 추출 (행안부 연계정보)
            String ci = server.getCi();
            String dn = server.getSubjectDn();

            log.info("[JointCert] 서명 검증 성공: dn={}", dn);

            return JointCertResult.builder()
                .ci(ci)
                .dn(dn)
                .authLevel("L3")
                .certSerialNo(server.getSerialNumber())
                .build();

        } catch (NpkiException e) {
            log.error("[JointCert] NPKI 오류: code={} msg={}", e.getCode(), e.getMessage());
            throw new PlatformException(
                PlatformErrorCode.IDP_RESPONSE_INVALID,
                "공동인증서 오류: " + e.getMessage()
            );
        }
    }

    /**
     * 공동인증서 팝업 서명 요청 데이터 생성
     * FE 팝업(CrossBrowse 등)에 전달할 signData 생성
     */
    public JointCertChallenge createChallenge(String correlationId) {
        String randomData = generateSecureRandom(32);  // 32바이트 난수
        String signTarget = correlationId + ":" + randomData + ":" + Instant.now().getEpochSecond();

        // Redis에 TTL 60초로 저장 (콜백 시 검증)
        challengeStore.save(correlationId, signTarget);

        return JointCertChallenge.builder()
            .signData(signTarget)
            .correlationId(correlationId)
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    }
}
```

**application.yml 설정**

```yaml
joint-cert:
  npki:
    service-id: ${JOINT_CERT_SERVICE_ID:}
    service-password: ${JOINT_CERT_SERVICE_PASSWORD:}
    ca-cert-path: ${JOINT_CERT_CA_CERT_PATH:classpath:certs/npki-ca.crt}
    verify-endpoint: ${JOINT_CERT_VERIFY_URL:https://api.kftc.or.kr/npki/v1/verify}
```

---

### 3.2 금융인증서 (금결원 yeskey)

#### 개요

```
운영기관: 금융결제원 (yeskey 브랜드)
프로토콜: PKCS#7 전자서명 (공동인증서와 동일 기술 기반)
인증등급: L3
CI 제공: ✅
저장위치: 금융결제원 클라우드 (앱/웹 접근)
특징: 사용자 클라우드 저장 → USB/HDD 없이 사용
CDN: https://4user.yeskey.or.kr/v1/fincert.js (운영)
      https://t-4user.yeskey.or.kr/v1/fincert.js (개발)
```

#### 연동 방법

**방법 1: Any-ID 설치형 경유 (권장 — 현재 구현)**

```java
// provider 코드: FINANCIAL_CERT
// config.anyidc.json의 financial_cert 섹션 참조
// script_url: https://t-4user.yeskey.or.kr/v1/fincert.js (개발)
```

**방법 2: yeskey SDK 직접 연동**

```html
<!-- FE: fincert.js 로드 -->
<script src="https://4user.yeskey.or.kr/v1/fincert.js"></script>
<script>
  const fincert = new Fincert({
    serviceId: 'YOUR_SERVICE_ID',    // 금결원 계약 후 발급
    callbackUrl: '/api/v1/auth/financial-cert/callback',
    signData: '${signData}'          // 서버에서 생성한 서명 대상 데이터
  });

  document.getElementById('btn-fincert').addEventListener('click', () => {
    fincert.auth({
      onSuccess: (result) => {
        // result.signedData: PKCS#7 서명값 (Base64)
        // result.certDn: 인증서 DN
        fetch('/api/v1/auth/financial-cert/verify', {
          method: 'POST',
          body: JSON.stringify({
            signedData: result.signedData,
            certDn: result.certDn,
            correlationId: '${correlationId}'
          })
        });
      },
      onError: (error) => console.error(error)
    });
  });
</script>
```

```java
// FinancialCertController.java
@RestController
@RequestMapping("/api/v1/auth/financial-cert")
@RequiredArgsConstructor
public class FinancialCertController {

    private final FinancialCertService financialCertService;

    /** 서명 대상 데이터 생성 (FE에서 fincert.js에 전달) */
    @GetMapping("/challenge")
    public ResponseEntity<Map<String, String>> challenge(
            @RequestHeader("X-Correlation-Id") String correlationId) {

        String signData = financialCertService.createChallenge(correlationId);
        return ResponseEntity.ok(Map.of(
            "signData", signData,
            "correlationId", correlationId
        ));
    }

    /** fincert.js 콜백 — 서명 검증 + CI 획득 */
    @PostMapping("/verify")
    public ResponseEntity<Void> verify(
            @RequestBody FinancialCertVerifyRequest request,
            HttpServletResponse response) {

        FinancialCertResult result = financialCertService.verify(
            request.getSignedData(),
            request.getCertDn(),
            request.getCorrelationId()
        );

        // feSessionId 쿠키 발급
        feSessionService.issue(result.getCi(), response);

        return ResponseEntity.ok().build();
    }
}
```

```yaml
# application.yml
financial-cert:
  yeskey:
    service-id: ${FINANCIAL_CERT_SERVICE_ID:}
    service-password: ${FINANCIAL_CERT_SERVICE_PASSWORD:}
    api-endpoint: ${FINANCIAL_CERT_API_URL:https://api.yeskey.or.kr}
    cdn-url: ${FINANCIAL_CERT_CDN_URL:https://4user.yeskey.or.kr/v1/fincert.js}
```

---

### 3.3 디지털원패스 (행안부)

#### 개요

```
운영기관: 행정안전부 (직영 서비스)
프로토콜: OIDC Authorization Code Flow (표준)
인증등급: L1~L3 (인증수단에 따라 다름)
CI 제공: ✅
특징:
  - 행안부 직영 → 별도 사용료 없음 (공공기관 무료)
  - Any-ID 이전 세대 공공 인증 허브 (현재 Any-ID와 병행 운영)
  - 정부24 계정으로 로그인 가능
  - 간편인증(민간인증서) 포함
개발: https://dev.onepass.go.kr
운영: https://www.onepass.go.kr
```

#### 연동 방법 (OIDC 직접 연동)

```
신청 경로:
  1. 디지털원패스 서비스 신청: https://www.onepass.go.kr/join
  2. 기관 정보 등록 (기관코드, 서비스명, redirect_uri)
  3. Client ID / Client Secret 발급 (영업일 3~5일)
```

```java
// DigitalOnepassProperties.java
@Getter @Setter
@Component
@ConfigurationProperties(prefix = "auth.digital-onepass")
public class DigitalOnepassProperties {
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String baseUrl = "https://www.onepass.go.kr";
    private String authorizationEndpoint = "/oauth2/authorize";
    private String tokenEndpoint = "/oauth2/token";
    private String userinfoEndpoint = "/oauth2/userinfo";
    private String jwksUri = "/oauth2/jwks";
}
```

```java
// DigitalOnepassBrokerAdapter.java
@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalOnepassBrokerAdapter {

    private final DigitalOnepassProperties props;
    private final RestTemplate restTemplate;
    private final OidcStateStore stateStore;

    /**
     * 디지털원패스 Authorization URL 생성
     * OIDC Authorization Code Flow (표준 RFC 6749 + OIDC Core 1.0)
     */
    public String buildAuthorizationUrl(String correlationId, String returnUrl,
                                         String requestedLevel) {
        // state/nonce 생성 + Redis 저장
        OidcStateEntry entry = stateStore.create(correlationId, returnUrl,
            requestedLevel, "digital-onepass", 300);

        // acr_values로 인증 등급 요청
        String acrValues = switch (requestedLevel) {
            case "L3" -> "urn:go:kr:onepass:acr:3";
            case "L2" -> "urn:go:kr:onepass:acr:2";
            default   -> "urn:go:kr:onepass:acr:1";
        };

        return UriComponentsBuilder
            .fromUriString(props.getBaseUrl() + props.getAuthorizationEndpoint())
            .queryParam("response_type", "code")
            .queryParam("client_id",     props.getClientId())
            .queryParam("redirect_uri",  props.getRedirectUri())
            .queryParam("scope",         "openid profile ci")  // ci scope 필수
            .queryParam("state",         entry.getState())
            .queryParam("nonce",         entry.getNonce())
            .queryParam("acr_values",    acrValues)
            .build(false).toUriString();
    }

    /**
     * 콜백 처리: code → token 교환 → CI 획득
     */
    public DigitalOnepassResult handleCallback(String code, String state,
                                                String correlationId) {
        // 1. state 검증
        OidcStateEntry entry = stateStore.consume(state)
            .orElseThrow(() -> new PlatformException(
                PlatformErrorCode.IDP_RESPONSE_INVALID,
                correlationId, "디지털원패스 state 불일치 — CSRF 의심"
            ));

        // 2. token 교환
        MultiValueMap<String, String> tokenReq = new LinkedMultiValueMap<>();
        tokenReq.add("grant_type",   "authorization_code");
        tokenReq.add("code",          code);
        tokenReq.add("redirect_uri",  props.getRedirectUri());
        tokenReq.add("client_id",     props.getClientId());
        tokenReq.add("client_secret", props.getClientSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<Map> tokenResp = restTemplate.exchange(
            props.getBaseUrl() + props.getTokenEndpoint(),
            HttpMethod.POST,
            new HttpEntity<>(tokenReq, headers),
            Map.class
        );

        String idToken      = (String) tokenResp.getBody().get("id_token");
        String accessToken  = (String) tokenResp.getBody().get("access_token");

        // 3. id_token JWKS 서명 검증
        JwtClaims claims = verifyIdToken(idToken, entry.getNonce());

        // 4. userinfo 호출 → CI 획득
        HttpHeaders uiHeaders = new HttpHeaders();
        uiHeaders.setBearerAuth(accessToken);

        ResponseEntity<Map> userinfoResp = restTemplate.exchange(
            props.getBaseUrl() + props.getUserinfoEndpoint(),
            HttpMethod.GET,
            new HttpEntity<>(uiHeaders),
            Map.class
        );

        String ci        = (String) userinfoResp.getBody().get("ci");
        String authLevel = (String) userinfoResp.getBody().get("acr");

        log.info("[DigitalOnepass] 인증 성공: authLevel={} correlationId={}", authLevel, correlationId);

        return DigitalOnepassResult.builder()
            .ci(ci)
            .sub(claims.getSubject())
            .authLevel(authLevel)
            .correlationId(correlationId)
            .build();
    }

    private JwtClaims verifyIdToken(String idToken, String expectedNonce) {
        // JWKS URI에서 공개키 로드 후 RS256 서명 검증
        // 생략 — KeycloakOidcService.verifyIdToken() 참고
        return JwtClaims.parse(idToken);
    }
}
```

```yaml
# application.yml
auth:
  digital-onepass:
    client-id: ${DIGITAL_ONEPASS_CLIENT_ID:}
    client-secret: ${DIGITAL_ONEPASS_CLIENT_SECRET:}
    redirect-uri: ${DIGITAL_ONEPASS_REDIRECT_URI:http://localhost:8083/api/v1/auth/digital-onepass/callback}
    base-url: ${DIGITAL_ONEPASS_BASE_URL:https://dev.onepass.go.kr}
```

---

### 3.4 GPKI 정부 공개키 인증서

#### 개요

```
운영기관: 행정안전부 (정부 CA)
프로토콜: X.509 PKI 인증서 + PKCS#7 서명
인증등급: L3 (공무원 전용)
CI 제공: ✅ (공무원 고유 식별자)
대상: 공무원, 군인, 공공기관 임직원
발급처: 행안부 GPKI 관리센터 (https://gca.go.kr)
특징: 일반 국민 대상 서비스에는 사용 불가 (공무원 전용)
```

#### 연동 방법

```
신청 경로: 행안부 GPKI 서비스 연계 신청 (공문 발송)
URL: https://www.gca.go.kr → 서비스 연계 신청
```

```java
// GpkiBrokerAdapter.java
@Service
@RequiredArgsConstructor
public class GpkiBrokerAdapter {

    @Value("${gpki.api.endpoint}")
    private String gpkiApiEndpoint;

    @Value("${gpki.service.id}")
    private String gpkiServiceId;

    /**
     * GPKI 서명 검증 + 공무원 식별자 추출
     * 행안부 GPKI API 규격: POST /gpki/v1/verify
     */
    public GpkiResult verify(String signedData, String originalData, String correlationId) {
        Map<String, String> body = Map.of(
            "serviceId",   gpkiServiceId,
            "signedData",  signedData,     // PKCS#7 서명값 (Base64)
            "originalData", originalData   // 서명 대상 원본
        );

        ResponseEntity<Map> resp = restTemplate.postForEntity(
            gpkiApiEndpoint + "/gpki/v1/verify",
            new HttpEntity<>(body, jsonHeaders()),
            Map.class
        );

        String resultCode = (String) resp.getBody().get("resultCode");
        if (!"0000".equals(resultCode)) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID,
                correlationId, "GPKI 검증 실패: " + resultCode);
        }

        return GpkiResult.builder()
            .employeeId((String) resp.getBody().get("employeeId"))  // 공무원 번호
            .orgCode((String)    resp.getBody().get("orgCode"))      // 기관 코드
            .certDn((String)     resp.getBody().get("certDn"))
            .authLevel("L3")
            .build();
    }
}
```

---

### 3.5 모바일 신분증 (행안부 DID)

#### 개요

```
운영기관: 행정안전부
기술: ISO 18013-5 모바일 운전면허증 표준 + W3C DID (분산 신원)
인증등급: L2 (행안부 기준)
CI 제공: ✅
저장: 정부24 앱, PASS 앱 (이동통신사 협력)
특징:
  - 실물 신분증 수준의 신뢰도
  - 오프라인 QR 제시 가능 (Bluetooth/NFC)
  - 선택적 개인정보 공개 (나이 확인 등)
```

#### 연동 방법

**Any-ID 설치형 경유 (유일한 방법 — 현재 구현)**

```java
// provider 코드: MOBILE_ID
// AnyIdBrokerAdapter → anyid.dev:1443/mid/api/v1/init

// 흐름:
// 1. ido → POST https://www.anyid.dev:1443/mid/api/v1/init
//    Body: { srvc_no, callback_url, correlationId }
// 2. Any-ID → QR코드 생성 or 앱 푸시
// 3. 사용자 → 정부24 앱으로 QR 스캔 or 앱 승인
// 4. Any-ID → ido GET /api/v1/anyid/mobile-id/callback?txId=...
// 5. ido → POST https://www.anyid.dev:1443/mid/api/v1/verify
//    Body: { srvc_no, txId, correlationId }
// 6. ← { result_code, identifier(CI), dn, auth_level }
```

> 모바일 신분증은 Any-ID 설치형을 통해서만 연동 가능.  
> 독자적 직접 연동 API는 행안부가 미제공 (Any-ID 창구 일원화 정책).

---

## 4. 인증 수단 상세: 민간인증서 계열

### 4.1 PASS 인증 (통신 3사)

#### 개요

```
운영사: SKT(SK텔레콤) + KT + LGU+ 공동 운영
중계사: NICE평가정보, KCB(코리아크레딧뷰로)를 통해서도 연동 가능
프로토콜: RESTful API (사업자별 규격)
인증등급: L2 (통신사 가입자 인증 기반)
CI 제공: ✅
행안부 고시: 2021년 고시 완료
특징: 국내 휴대폰 번호 기반 인증 (내국인 전용)
```

#### 직접 API 연동

**방법 A: NICE 본인인증 API 경유 (권장)**

```
계약: NICE평가정보 → https://www.niceid.co.kr
      SMS 본인인증 + PASS앱 동시 지원
      건당 과금 (약 50~100원)
```

```java
// PassBrokerAdapter.java (NICE API 방식)
@Slf4j
@Service
@RequiredArgsConstructor
public class PassBrokerAdapter {

    private final NiceAuthClient niceAuthClient;  // 현재 NiceAuthService 참조

    /**
     * PASS 인증 시작 — NICE API로 본인인증 요청
     *
     * NICE 표준 암호화: AES-128-ECB 또는 3DES (사업자 협의)
     * 현재 ido에는 NiceAuthService가 이미 구현되어 있음 (auth 패키지 참조)
     */
    public PassInitResult initiate(String correlationId, String returnUrl) {
        // NiceAuthService.initiateAuth()와 동일 흐름
        // EncodeData 생성 → FE에 전달 → NICE 팝업 호출
        NiceEncodeData encoded = niceAuthClient.createEncodeData(correlationId, returnUrl);

        return PassInitResult.builder()
            .encodeData(encoded.getEncodeData())
            .tokenVersionId(encoded.getTokenVersionId())
            .integrityValue(encoded.getIntegrityValue())
            .correlationId(correlationId)
            .build();
    }

    /**
     * PASS 콜백 처리 — 암호화된 결과 복호화 후 CI 추출
     */
    public PassAuthResult handleCallback(String encodeData, String correlationId) {
        // 복호화: NICE AES-128-ECB
        NiceDecodeResult decoded = niceAuthClient.decrypt(encodeData);

        return PassAuthResult.builder()
            .ci(decoded.getCi())
            .phoneNumber(decoded.getMobileNo())
            .name(decoded.getName())
            .birthDate(decoded.getBirthDate())
            .gender(decoded.getGender())
            .authLevel("L2")
            .build();
    }
}
```

**방법 B: PASS 직접 API (통신사별 계약)**

```java
// PassDirectAdapter.java (SKT 직접 API 예시)
@Service
public class PassDirectAdapter {

    @Value("${pass.skt.api-key}")
    private String sktApiKey;

    @Value("${pass.skt.base-url}")
    private String sktBaseUrl;

    /**
     * SKT PASS API — 본인인증 요청
     * API 규격: SKT PASS 개발자 센터 (계약 후 접근)
     * POST {sktBaseUrl}/pass/v2/auth/request
     */
    public PassInitResult initiateSkt(String correlationId, String callbackUrl) {
        Map<String, Object> body = Map.of(
            "serviceId",   "YOUR_SERVICE_ID",
            "callbackUrl", callbackUrl,
            "txId",        correlationId,
            "authType",    "PASS"  // PASS앱 인증
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", sktApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange(
            sktBaseUrl + "/pass/v2/auth/request",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class
        );

        return PassInitResult.builder()
            .txId((String) resp.getBody().get("txId"))
            .redirectUrl((String) resp.getBody().get("redirectUrl"))
            .build();
    }
}
```

```yaml
# application.yml
pass:
  # NICE 경유 (권장)
  nice:
    client-id: ${PASS_NICE_CLIENT_ID:}
    client-secret: ${PASS_NICE_CLIENT_SECRET:}
  # SKT 직접 (계약 후)
  skt:
    api-key: ${PASS_SKT_API_KEY:}
    base-url: ${PASS_SKT_BASE_URL:https://api.sktpass.com}
  # KT 직접 (계약 후)
  kt:
    api-key: ${PASS_KT_API_KEY:}
    base-url: ${PASS_KT_BASE_URL:https://api.pass.olleh.com}
  # LGU+ 직접 (계약 후)
  lgu:
    api-key: ${PASS_LGU_API_KEY:}
    base-url: ${PASS_LGU_BASE_URL:https://api.pass.lguplus.com}
```

**Any-ID 설치형 경유**

```java
// provider 코드: EASY_SIGN (간편인증 중 PASS 선택)
// Any-ID EASY_SIGN에 PASS앱이 포함됨
```

---

### 4.2 카카오 인증서

#### 개요

```
운영사: 카카오
프로토콜: 카카오 전자서명 REST API
인증등급: L2 (카카오계정 + 전화번호 인증 기반)
CI 제공: ✅ (행안부 CI 연계)
행안부 고시: 2021년 고시 완료
API 문서: https://developers.kakao.com/docs/latest/ko/kakaocert/common
특징:
  - 카카오톡 앱에서 서명 수행
  - 전자서명 / 본인확인 / 세금계산서 서명 지원
  - 계약: 카카오 비즈니스 채널 통해 사전 협의 필요
```

#### 직접 API 연동

```
계약 경로: https://business.kakao.com → 카카오 인증서 비즈니스 서비스 신청
           카카오 고객사 심사 → App Key + Secret 발급
           월정액 + 건당 과금
```

```java
// KakaoCertBrokerAdapter.java
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoCertBrokerAdapter {

    @Value("${kakao-cert.app-key}")
    private String appKey;

    @Value("${kakao-cert.admin-key}")
    private String adminKey;

    private static final String KAKAO_CERT_API = "https://kapi.kakao.com";

    /**
     * 카카오 전자서명 요청 생성
     * POST https://kapi.kakao.com/v1/cert/signrequest
     *
     * 공식 문서: https://developers.kakao.com/docs/latest/ko/kakaocert/rest-api#request-sign
     */
    public KakaoCertRequest createSignRequest(String correlationId,
                                               String signData,
                                               String receiverPhoneNo) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "KakaoAK " + adminKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "biz_number",    "YOUR_BIZ_NUMBER",     // 사업자 번호
            "title",         "OnePass 본인확인",
            "extra_message", "서비스 이용을 위한 본인확인입니다.",
            "transaction_id", correlationId,
            "receiver_uuids", List.of(/* 카카오 UUID — 사전 조회 필요 */),
            "sign_data",     signData,               // 서명 대상 데이터 (SHA-256 해시)
            "return_url",    "/api/v1/auth/kakao-cert/callback"
        );

        ResponseEntity<Map> resp = restTemplate.exchange(
            KAKAO_CERT_API + "/v1/cert/signrequest",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class
        );

        String requestId = (String) resp.getBody().get("request_id");
        log.info("[KakaoCert] 서명 요청 생성: requestId={} correlationId={}",
            requestId, correlationId);

        return KakaoCertRequest.builder()
            .requestId(requestId)
            .expiresAt(Instant.now().plusSeconds(180))  // 3분 유효
            .correlationId(correlationId)
            .build();
    }

    /**
     * 카카오 전자서명 결과 조회
     * GET https://kapi.kakao.com/v1/cert/signrequest/{requestId}
     */
    public KakaoCertResult getSignResult(String requestId, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "KakaoAK " + adminKey);

        ResponseEntity<Map> resp = restTemplate.exchange(
            KAKAO_CERT_API + "/v1/cert/signrequest/" + requestId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class
        );

        Map<String, Object> body = resp.getBody();
        String status = (String) body.get("status");  // COMPLETED / WAITING / FAILED

        if (!"COMPLETED".equals(status)) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID,
                correlationId, "카카오 인증서 서명 미완료: status=" + status);
        }

        // 서명 결과에서 CI 추출
        Map<String, Object> signResult = (Map<String, Object>) body.get("sign_result");
        String ci          = (String) signResult.get("ci");
        String signedData  = (String) signResult.get("signed_data");

        return KakaoCertResult.builder()
            .ci(ci)
            .signedData(signedData)
            .requestId(requestId)
            .authLevel("L2")
            .correlationId(correlationId)
            .build();
    }

    /**
     * 카카오 UUID 조회 (전화번호 → 카카오 UUID)
     * 전자서명 요청 전 수신자 UUID가 필요
     * POST https://kapi.kakao.com/v1/user/ids
     */
    public String resolveKakaoUuid(String phoneNumber) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "KakaoAK " + adminKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "phone_number_list", List.of(phoneNumber)
        );

        ResponseEntity<Map> resp = restTemplate.exchange(
            KAKAO_CERT_API + "/v1/user/ids",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class
        );

        List<Map<String, Object>> elements =
            (List<Map<String, Object>>) resp.getBody().get("elements");

        if (elements == null || elements.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID,
                null, "카카오 UUID 조회 실패: 카카오 미가입 번호");
        }

        return (String) elements.get(0).get("uuid");
    }
}
```

```yaml
kakao-cert:
  app-key: ${KAKAO_CERT_APP_KEY:}
  admin-key: ${KAKAO_CERT_ADMIN_KEY:}
  biz-number: ${KAKAO_BIZ_NUMBER:}
```

**Any-ID 설치형 경유**

```java
// EASY_SIGN 선택 시 내부적으로 카카오인증서 포함
// provider 코드: EASY_SIGN → 사용자가 카카오인증서 선택
```

---

### 4.3 네이버 인증서

#### 개요

```
운영사: 네이버 (NHN → 네이버클라우드)
프로토콜: CLOVA Sign REST API
인증등급: L2
CI 제공: ✅
행안부 고시: 2021년 고시 완료
API 문서: https://api.ncloud-docs.com/docs/ai-application-service-clovasign
특징:
  - 네이버 앱에서 서명
  - CLOVA Sign: 전자서명 + 문서 서명 지원
  - 계약: 네이버클라우드 플랫폼 통해 신청
```

#### 직접 API 연동

```
계약: 네이버클라우드 플랫폼 → CLOVA Sign 서비스 신청
      https://www.ncloud.com/product/appService/clovaSign
```

```java
// NaverCertBrokerAdapter.java
@Slf4j
@Service
@RequiredArgsConstructor
public class NaverCertBrokerAdapter {

    @Value("${naver-cert.client-id}")
    private String clientId;

    @Value("${naver-cert.client-secret}")
    private String clientSecret;

    private static final String CLOVA_SIGN_API = "https://ncloud.apigw.ntruss.com/clovasign/v1";

    /**
     * 네이버 인증서 서명 요청
     * POST {CLOVA_SIGN_API}/sign/request
     */
    public NaverCertRequest createRequest(String correlationId, String signData) {
        HttpHeaders headers = buildNaverHeaders();

        Map<String, Object> body = Map.of(
            "title",       "OnePass 본인확인",
            "content",     "서비스 이용을 위한 본인확인 서명 요청입니다.",
            "externalId",  correlationId,
            "signData",    signData,       // SHA-256 해시 (Base64)
            "expiresIn",   180             // 3분
        );

        ResponseEntity<Map> resp = restTemplate.exchange(
            CLOVA_SIGN_API + "/sign/request",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class
        );

        return NaverCertRequest.builder()
            .requestId((String) resp.getBody().get("requestId"))
            .deepLink((String) resp.getBody().get("deepLink"))  // 네이버앱 딥링크
            .correlationId(correlationId)
            .build();
    }

    /**
     * 서명 결과 조회 (폴링 또는 콜백)
     * GET {CLOVA_SIGN_API}/sign/request/{requestId}
     */
    public NaverCertResult getResult(String requestId, String correlationId) {
        HttpHeaders headers = buildNaverHeaders();

        ResponseEntity<Map> resp = restTemplate.exchange(
            CLOVA_SIGN_API + "/sign/request/" + requestId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class
        );

        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        String status = (String) result.get("status");

        if (!"COMPLETED".equals(status)) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID,
                correlationId, "네이버 서명 미완료: status=" + status);
        }

        String ci = (String) result.get("ci");
        log.info("[NaverCert] 서명 완료: correlationId={}", correlationId);

        return NaverCertResult.builder()
            .ci(ci)
            .requestId(requestId)
            .signedData((String) result.get("signedData"))
            .authLevel("L2")
            .build();
    }

    /** 네이버클라우드 API 서명 헤더 생성 (HMAC-SHA256) */
    private HttpHeaders buildNaverHeaders() {
        long timestamp = System.currentTimeMillis();
        String sig = hmacSha256(
            "POST\n" + CLOVA_SIGN_API + "/sign/request\n" + timestamp,
            clientSecret
        );

        HttpHeaders h = new HttpHeaders();
        h.set("x-ncp-apigw-timestamp", String.valueOf(timestamp));
        h.set("x-ncp-iam-access-key", clientId);
        h.set("x-ncp-apigw-signature-v2", sig);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 서명 실패", e);
        }
    }
}
```

---

### 4.4 KB국민은행 인증서

#### 개요

```
운영사: KB국민은행
인증등급: L2~L3
CI 제공: ✅
행안부 고시: 2022년 고시 완료
특징: KB스타뱅킹 앱에서 서명, 금융 거래 수준 신뢰도
연동: KB국민은행 B2B 파트너십 계약 필요 (공공기관 무료 협의 가능)
```

> **현재 권장**: Any-ID 설치형 EASY_SIGN 경유로 KB인증서 자동 포함.  
> 직접 연동은 KB국민은행 기업 API 팀과 별도 NDA 체결 후 진행.

---

### 4.5 토스 인증서

#### 개요

```
운영사: 비바리퍼블리카 (토스)
인증등급: L2
CI 제공: ✅
행안부 고시: 2022년 고시 완료
API: 토스페이먼츠 / 토스뱅크 B2B API
특징: 토스앱에서 서명, 간편하고 빠른 UX
```

> **현재 권장**: Any-ID 설치형 EASY_SIGN 경유로 토스인증 자동 포함.

---

### 4.6 삼성패스

#### 개요

```
운영사: 삼성전자
인증등급: L2 (생체인증 FIDO2 기반)
CI 제공: ✅ (일부 제한)
행안부 고시: 일부 고시
특징: 갤럭시 기기 전용, 지문/홍채/얼굴 생체인증
API: 삼성 Developer Program (계약 필요, 까다로운 심사)
```

> 삼성패스 독자 직접 연동은 삼성 파트너 심사를 통과한 기업만 가능.  
> 일반적으로 **Any-ID EASY_SIGN**을 통해 간접 지원.

---

### 4.7 라온시큐어 OmniOne

#### 개요

```
운영사: 라온시큐어
프로토콜: PKI 기반 분산 신원(DID) + REST API
인증등급: L2~L3 (서비스에 따라 상이)
CI 제공: ⚠️ (자체 DID 식별자 — 행안부 CI 연계 별도 계약)
행안부 고시: 미고시 (2026년 기준)
특징:
  - 기업 B2B 전용 서비스
  - 정부 관련 프로젝트에서 일부 사용 (행안부 DID 시범 사업)
  - 공공기관 본인확인 공식 적용 시 법적 검토 필요
```

```
⚠️ 주의: 공공기관 본인확인 용도로 단독 적용 시 행안부 고시 미포함.
법무팀 검토 + 행안부 사전 협의 없이 사용하면 전자서명법 위반 소지.
```

#### 직접 API 연동 (B2B 계약 후)

```java
// OmniOneBrokerAdapter.java
@Service
@RequiredArgsConstructor
public class OmniOneBrokerAdapter {

    @Value("${omnione.api-key}")
    private String apiKey;

    @Value("${omnione.base-url}")
    private String baseUrl;

    /**
     * OmniOne DID 인증 요청
     * 라온시큐어 OmniOne API (계약 후 문서 수령)
     */
    public OmniOneRequest createRequest(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "serviceId",   "YOUR_SERVICE_ID",
            "txId",         correlationId,
            "authType",    "DID",
            "callbackUrl", "/api/v1/auth/omnione/callback"
        );

        ResponseEntity<Map> resp = restTemplate.exchange(
            baseUrl + "/v1/auth/request",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class
        );

        return OmniOneRequest.builder()
            .txId((String) resp.getBody().get("txId"))
            .qrCode((String) resp.getBody().get("qrCode"))
            .deepLink((String) resp.getBody().get("deepLink"))
            .build();
    }
}
```

---

## 5. 인증 수단 상세: OIDC/OAuth2 소셜 계열

> **중요**: 소셜 OAuth2 로그인은 CI를 제공하지 않는다.  
> 공공기관 본인확인 목적으로는 사용 불가. 회원 가입/연동 보조 수단으로만 사용.

### 5.1 카카오 OAuth2 소셜 로그인

#### 개요

```
프로토콜: OIDC Authorization Code Flow (RFC 6749 + OIDC Core 1.0)
CI 제공: ❌ (sub — 카카오 자체 식별자만 제공)
공공기관 본인확인: ❌ 불가
용도: 회원 가입 연동, 편의 로그인
개발자 센터: https://developers.kakao.com
```

#### Keycloak를 통한 연동 (현재 구현)

```yaml
# Keycloak Admin Console에서 설정
# Identity Provider → Add Provider → Kakao

# Keycloak realm.json
{
  "identityProviders": [{
    "alias": "social-kakao",
    "providerId": "oidc",
    "config": {
      "clientId": "${KAKAO_CLIENT_ID}",
      "clientSecret": "${KAKAO_CLIENT_SECRET}",
      "authorizationUrl": "https://kauth.kakao.com/oauth/authorize",
      "tokenUrl": "https://kauth.kakao.com/oauth/token",
      "userInfoUrl": "https://kapi.kakao.com/v2/user/me",
      "defaultScope": "openid profile",
      "syncMode": "INHERIT"
    }
  }]
}
```

```yaml
# ido application.yml
ido:
  keycloak:
    idp-hint-mapping:
      kakao: social-kakao   # kc_idp_hint 매핑 (이미 구현됨)
```

#### ido에서 직접 연동 (Keycloak 미사용 시)

```java
// KakaoOidcAdapter.java
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoOidcAdapter {

    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.client-secret}")
    private String clientSecret;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    private static final String KAKAO_AUTH_URL  = "https://kauth.kakao.com/oauth/authorize";
    private static final String KAKAO_TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String KAKAO_USER_URL  = "https://kapi.kakao.com/v2/user/me";

    /** Authorization URL 생성 */
    public String buildAuthorizationUrl(String state, String nonce) {
        return UriComponentsBuilder.fromUriString(KAKAO_AUTH_URL)
            .queryParam("client_id",    clientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("response_type","code")
            .queryParam("scope",        "openid profile")
            .queryParam("state",        state)
            .queryParam("nonce",        nonce)
            .build(false).toUriString();
    }

    /** Authorization Code → Access Token 교환 */
    public KakaoTokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",   "authorization_code");
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);
        params.add("redirect_uri",  redirectUri);
        params.add("code",          code);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<KakaoTokenResponse> resp = restTemplate.exchange(
            KAKAO_TOKEN_URL,
            HttpMethod.POST,
            new HttpEntity<>(params, headers),
            KakaoTokenResponse.class
        );

        return resp.getBody();
    }

    /** 사용자 정보 조회 */
    public KakaoUserInfo getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<Map> resp = restTemplate.exchange(
            KAKAO_USER_URL,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class
        );

        Long kakaoId = (Long) resp.getBody().get("id");
        Map<String, Object> profile =
            (Map<String, Object>) ((Map<String, Object>) resp.getBody().get("kakao_account")).get("profile");

        return KakaoUserInfo.builder()
            .kakaoId(String.valueOf(kakaoId))  // sub
            .nickname((String) profile.get("nickname"))
            .profileImageUrl((String) profile.get("profile_image_url"))
            // CI 없음 — 소셜 로그인은 CI 미제공
            .build();
    }
}
```

#### 개발자 센터 등록 절차

```
1. https://developers.kakao.com 접속
2. 내 애플리케이션 → 애플리케이션 추가
3. 앱 이름, 사업자명 입력
4. 플랫폼 등록 → Web → 사이트 도메인 입력
5. 카카오 로그인 활성화 → Redirect URI 등록
   예: http://localhost:8083/api/v1/broker/callback (개발)
       https://www.smes.go.kr/api/v1/broker/callback (운영)
6. 동의항목 설정:
   - 필수: profile_nickname
   - 선택: profile_image, openid (OIDC)
7. 앱 키 확인: REST API 키 (client_id로 사용)
8. 보안 → Client Secret 생성 (client_secret으로 사용)
```

---

### 5.2 네이버 OAuth2 소셜 로그인

#### 개요

```
프로토콜: OAuth2 Authorization Code Flow (OIDC 미지원 — id_token 없음)
CI 제공: ❌
공공기관 본인확인: ❌ 불가
개발자 센터: https://developers.naver.com
```

#### 개발자 센터 등록 절차

```
1. https://developers.naver.com 접속
2. Application 등록
3. 사용 API: 네이버 로그인 선택
4. 환경 추가: Web → 서비스 URL, Callback URL 입력
   Callback URL 예: http://localhost:8083/api/v1/broker/callback
5. Client ID / Client Secret 확인
```

```java
// NaverOidcAdapter.java
@Service
@RequiredArgsConstructor
public class NaverOidcAdapter {

    @Value("${naver.client-id}")
    private String clientId;

    @Value("${naver.client-secret}")
    private String clientSecret;

    @Value("${naver.redirect-uri}")
    private String redirectUri;

    private static final String NAVER_AUTH_URL  = "https://nid.naver.com/oauth2.0/authorize";
    private static final String NAVER_TOKEN_URL = "https://nid.naver.com/oauth2.0/token";
    private static final String NAVER_USER_URL  = "https://openapi.naver.com/v1/nid/me";

    /** Authorization URL 생성 — state 필수 (CSRF 방어) */
    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(NAVER_AUTH_URL)
            .queryParam("response_type", "code")
            .queryParam("client_id",     clientId)
            .queryParam("redirect_uri",  redirectUri)
            .queryParam("state",         state)  // CSRF 방어 필수
            .build(false).toUriString();
    }

    /** 사용자 정보 조회 — 네이버는 id_token 미제공, userinfo API로만 획득 */
    public NaverUserInfo getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);

        ResponseEntity<Map> resp = restTemplate.exchange(
            NAVER_USER_URL,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class
        );

        Map<String, Object> response = (Map<String, Object>) resp.getBody().get("response");

        return NaverUserInfo.builder()
            .naverId((String) response.get("id"))          // sub 대용
            .name((String) response.get("name"))
            .email((String) response.get("email"))
            .mobile((String) response.get("mobile"))
            // CI 없음
            .build();
    }
}
```

---

## 6. Any-ID 정부 통합인증을 통한 간접 연동

### 6.1 Any-ID가 지원하는 인증 수단 전체 목록

```
Any-ID 설치형 EASY_SIGN에 포함된 민간인증서 (2025년 기준):
  ├─ 카카오인증서
  ├─ PASS (SKT / KT / LGU+)
  ├─ 네이버인증서
  ├─ KB국민은행 인증서
  ├─ 토스인증서
  ├─ 뱅크샐러드
  ├─ 삼성패스
  └─ NHN페이코

Any-ID가 별도 모듈로 지원하는 인증:
  ├─ 모바일신분증 (MOBILE_ID)
  ├─ 공동인증서 (JOINT_CERT)
  ├─ 금융인증서 (FINANCIAL_CERT)
  └─ 민간ID/소셜 (PRIVATE_ID — social-relay)
```

### 6.2 Any-ID 연동 장단점

```
장점:
  ✅ 단일 계약으로 11개 이상 인증 수단 커버
  ✅ 행안부 직영 → 법적 리스크 없음
  ✅ 신규 인증수단 추가 시 코드 변경 불필요 (Any-ID 서버 업데이트)
  ✅ CI 표준화 — 모든 수단에서 동일 CI 형식
  ✅ SSO 허브 기능 포함

단점:
  ❌ anyid.dev 서버 가용성에 의존 (SLA 확인 필요)
  ❌ 특정 인증 수단 UI 커스터마이징 제한
  ❌ 개별 사업자 기능(카카오 전자서명 특화 기능 등) 미지원
  ❌ 네트워크 홉 1개 추가 → 레이턴시 미세 증가
```

### 6.3 현재 구현 현황

```java
// 현재 ido에서 Any-ID 연동 진입점
// 1. BrokerService.buildNonOidcAuthorizationUrl()
//    → isAnyIdProvider() → true
//    → AnyIdBrokerAdapter.buildAuthorizationUrl()
//       → initiateAuth() → POST anyid.dev:1443/api/v1/init
//       ← redirectUrl → FE 리다이렉트

// 2. AnyIdController.callback()
//    → AnyIdBrokerAdapter.verifyCallback()
//       → POST anyid.dev:1443/api/v1/verify
//       ← CI / DN / authLevel
```

---

## 7. OnePass 플랫폼 연동 아키텍처

### 7.1 전체 플로우 다이어그램

```
FE (onepass-fe)
   │
   │ GET /api/v1/broker/{provider}/authorize
   ▼
ido (BrokerController)
   │
   │ buildAuthorizationUrl(provider, correlationId, returnUrl, level)
   ▼
BrokerService
   │
   ├─ [Layer 1] ProviderRouter.resolve(provider)
   │     ├─ STANDARD_OIDC → [Layer 2] brokerMode
   │     │     ├─ qsign    → Q-Sign (카카오/네이버 OIDC 처리)
   │     │     └─ keycloak → Keycloak Authorization URL 생성
   │     │
   │     └─ NON_STANDARD → isAnyIdProvider()
   │           ├─ true  → AnyIdBrokerAdapter
   │           │             ├─ MOBILE_ID      → anyid.dev:1443/mid
   │           │             ├─ EASY_SIGN      → anyid.dev:1443/easy
   │           │             ├─ JOINT_CERT     → anyid.dev:1443/cert
   │           │             ├─ FINANCIAL_CERT → anyid.dev:1443/fcert
   │           │             └─ PRIVATE_ID     → anyid.dev:1443/pid
   │           └─ false → NonOidcBrokerAdapter
   │                         ├─ PASS     → NICE API (현재 구현)
   │                         ├─ GPKI     → 행안부 GPKI API
   │                         └─ 기타
   │
   ▼ 302 Redirect
사용자 브라우저 → 인증 서버 UI
   │
   │ 인증 완료
   ▼
AnyIdController.callback() / OidcCompleteController.callback()
   │
   ├─ AuthResult 생성
   ├─ Kafka 발행 (qsign.auth.events)
   └─ feSessionId 쿠키 발급
```

---

## 8. provider_config DB 등록 방법

### 8.1 테이블 구조

```sql
-- ido schema
CREATE TABLE ido.provider_config (
    id            BIGSERIAL    PRIMARY KEY,
    provider_code VARCHAR(50)  NOT NULL UNIQUE,
    display_name  VARCHAR(100) NOT NULL,
    auth_level    VARCHAR(5)   NOT NULL,   -- L1 / L2 / L3
    broker_mode   VARCHAR(30)  NOT NULL,   -- anyid / pass / kakao-cert / naver-cert / oidc
    provider_type VARCHAR(30)  NOT NULL,   -- STANDARD_OIDC / SEMI_STANDARD_OIDC / NON_STANDARD
    active        BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### 8.2 Any-ID 인증 수단 등록 (현재 미완료 — 후속 작업)

```sql
-- V999__anyid_provider_config.sql
-- Flyway 마이그레이션 파일: ido/src/main/resources/db/migration/

INSERT INTO ido.provider_config
  (provider_code, display_name, auth_level, broker_mode, provider_type, active)
VALUES
  ('MOBILE_ID',      '모바일 신분증',         'L2', 'anyid', 'NON_STANDARD', true),
  ('EASY_SIGN',      '간편인증',              'L1', 'anyid', 'NON_STANDARD', true),
  ('JOINT_CERT',     '공동인증서',            'L3', 'anyid', 'NON_STANDARD', true),
  ('FINANCIAL_CERT', '금융인증서',            'L3', 'anyid', 'NON_STANDARD', true),
  ('PRIVATE_ID',     '민간ID (소셜 로그인)',  'L1', 'anyid', 'NON_STANDARD', true)
ON CONFLICT (provider_code) DO UPDATE SET
  display_name  = EXCLUDED.display_name,
  auth_level    = EXCLUDED.auth_level,
  broker_mode   = EXCLUDED.broker_mode,
  provider_type = EXCLUDED.provider_type,
  active        = EXCLUDED.active,
  updated_at    = now();
```

### 8.3 신규 인증 수단 추가 시 등록 예시

```sql
-- PASS 직접 API 연동 추가 시
INSERT INTO ido.provider_config
  (provider_code, display_name, auth_level, broker_mode, provider_type, active)
VALUES
  ('PASS', 'PASS 본인인증 (통신 3사)', 'L2', 'pass', 'NON_STANDARD', true);

-- 카카오 인증서 직접 연동 추가 시
INSERT INTO ido.provider_config
  (provider_code, display_name, auth_level, broker_mode, provider_type, active)
VALUES
  ('KAKAO_CERT', '카카오 인증서', 'L2', 'kakao-cert', 'NON_STANDARD', true);

-- 디지털원패스 OIDC 추가 시
INSERT INTO ido.provider_config
  (provider_code, display_name, auth_level, broker_mode, provider_type, active)
VALUES
  ('DIGITAL_ONEPASS', '디지털원패스', 'L2', 'oidc', 'STANDARD_OIDC', true);
```

---

## 9. 신규 인증 수단 추가 절차 (코드 레벨)

### 9.1 체크리스트

```
신규 인증 수단을 OnePass에 추가하는 전체 절차:

  □ 1. 사업자 계약/신청 완료
  □ 2. provider_config DB 레코드 INSERT
  □ 3. XxxBrokerAdapter.java 구현 (신규 어댑터)
  □ 4. XxxProperties.java 구현 (@ConfigurationProperties)
  □ 5. application.yml + application-local.yml 설정 추가
  □ 6. BrokerService.buildNonOidcAuthorizationUrl() 분기 추가
  □ 7. XxxController.java 구현 (콜백 엔드포인트)
  □ 8. K8s Secret 등록 (운영 환경 변수)
  □ 9. ProviderConfig.ProviderType 확인 (NON_STANDARD 여부)
  □ 10. 통합 테스트 작성
```

### 9.2 예시: PASS 직접 API 어댑터 추가

**Step 1 — DB 등록**

```sql
INSERT INTO ido.provider_config VALUES
  (DEFAULT, 'PASS', 'PASS 본인인증', 'L2', 'pass', 'NON_STANDARD', true, now(), now());
```

**Step 2 — Properties 클래스**

```java
// PassProperties.java
@Getter @Setter
@Component
@ConfigurationProperties(prefix = "auth.pass")
public class PassProperties {
    private Nice nice = new Nice();
    private Http http = new Http();

    @Getter @Setter
    public static class Nice {
        private String clientId     = "";
        private String clientSecret = "";
        private String returnUrl    = "";
        private int    timeoutSeconds = 10;
    }

    @Getter @Setter
    public static class Http {
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs    = 5000;
    }
}
```

**Step 3 — 어댑터 구현**

```java
// PassBrokerAdapter.java
@Slf4j
@Service
@RequiredArgsConstructor
public class PassBrokerAdapter {

    private final PassProperties props;
    private final NiceAuthClient niceAuthClient;  // 기존 NICE 클라이언트 재사용

    /**
     * PASS 인증 시작 URL 반환
     * BrokerService.buildNonOidcAuthorizationUrl() 에서 호출
     */
    public String buildAuthorizationUrl(String provider, String correlationId,
                                         String returnUrl, String requestedLevel) {
        log.info("[PASS-Broker] 인증 시작: correlationId={}", correlationId);

        // NICE API로 encodeData 생성
        NiceEncodeData encoded = niceAuthClient.createEncodeData(correlationId, returnUrl);

        // FE NICE 팝업 페이지로 리다이렉트
        // FE에서 encodeData를 NICE 팝업에 전달
        return UriComponentsBuilder.fromPath("/auth/nice/popup")
            .queryParam("encodeData",      encoded.getEncodeData())
            .queryParam("tokenVersionId",  encoded.getTokenVersionId())
            .queryParam("integrityValue",  encoded.getIntegrityValue())
            .queryParam("correlationId",   correlationId)
            .build(false).toUriString();
    }

    /**
     * NICE 콜백 결과 처리
     * POST /api/v1/auth/pass/callback 에서 호출
     */
    public PassAuthResult handleCallback(String encodeData, String correlationId) {
        NiceDecodeResult decoded = niceAuthClient.decrypt(encodeData);

        return PassAuthResult.builder()
            .ci(decoded.getCi())
            .phoneNumber(decoded.getMobileNo())
            .name(decoded.getName())
            .birthDate(decoded.getBirthDate())
            .authLevel("L2")
            .correlationId(correlationId)
            .build();
    }
}
```

**Step 4 — BrokerService 분기 추가**

```java
// BrokerService.java 수정
@Service
@RequiredArgsConstructor
public class BrokerService {

    // 기존 의존성...
    private final AnyIdBrokerAdapter anyIdBrokerAdapter;
    private final PassBrokerAdapter  passBrokerAdapter;   // ← 신규 추가

    private String buildNonOidcAuthorizationUrl(String provider, String correlationId,
                                                  String returnUrl, String requestedLevel) {
        String upper = provider.toUpperCase().replace("-", "_");

        // Any-ID 판별 (기존)
        if (isAnyIdProvider(upper, correlationId)) {
            return anyIdBrokerAdapter.buildAuthorizationUrl(
                provider, correlationId, returnUrl, requestedLevel);
        }

        // PASS 직접 판별 (신규 추가)
        if (isPassProvider(upper, correlationId)) {
            return passBrokerAdapter.buildAuthorizationUrl(
                provider, correlationId, returnUrl, requestedLevel);
        }

        // NonOidc 기타 (기존)
        return buildNonOidcInitiateUrl(provider, returnUrl, correlationId, requestedLevel);
    }

    private boolean isPassProvider(String providerCode, String correlationId) {
        try {
            return providerConfigRepository.findByCode(providerCode)
                .map(cfg -> "pass".equalsIgnoreCase(cfg.getBrokerMode()))
                .orElse("PASS".equals(providerCode));
        } catch (Exception e) {
            return "PASS".equals(providerCode);
        }
    }
}
```

**Step 5 — 콜백 컨트롤러**

```java
// PassController.java
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/pass")
@RequiredArgsConstructor
public class PassController {

    private final PassBrokerAdapter  passBrokerAdapter;
    private final NonOidcAuthService nonOidcAuthService;
    private final FeSessionService   feSessionService;

    /** NICE 콜백 수신 (PASS 인증 완료) */
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam String encodeData,
            @RequestParam String tokenVersionId,
            @RequestParam String integrityValue,
            @RequestParam String correlationId,
            HttpServletResponse response) {

        log.info("[PASS] 콜백 수신: correlationId={}", correlationId);

        // 1. NICE 복호화 + CI 획득
        PassAuthResult result = passBrokerAdapter.handleCallback(encodeData, correlationId);

        // 2. NonOidcAuthCommand 생성 + processAuth()
        NonOidcAuthCommand command = NonOidcAuthCommand.builder()
            .correlationId(correlationId)
            .providerCode("PASS")
            .rawIdentifier(result.getCi())
            .requestedLevel("L2")
            .providerVerified(true)
            .build();

        String authResultId = nonOidcAuthService.processAuth(command);

        // 3. feSessionId 쿠키 발급
        feSessionService.issue(authResultId, response);

        // 4. 기관 returnUrl 리다이렉트
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(result.getReturnUrl()))
            .build();
    }
}
```

**Step 6 — application.yml**

```yaml
auth:
  pass:
    nice:
      client-id:      ${PASS_NICE_CLIENT_ID:}
      client-secret:  ${PASS_NICE_CLIENT_SECRET:}
      return-url:     ${PASS_NICE_RETURN_URL:http://localhost:8083/api/v1/auth/pass/callback}
      timeout-seconds: 10
    http:
      connect-timeout-ms: 3000
      read-timeout-ms:    5000
```

---

## 10. 인증 수단별 계약·신청 경로 정리

| 인증 수단 | 신청 기관 | 신청 URL | 비용 | 기간 |
|----------|---------|---------|------|------|
| **Any-ID 설치형** | 행안부 Any-ID 사업단 | https://www.anyid.go.kr | 무료 (공공기관) | 2~4주 |
| **공동인증서 (금결원)** | 금융결제원 | https://www.kftc.or.kr | 유료 (건당) | 2~4주 |
| **공동인증서 (KICA)** | 한국정보인증 | https://www.kica.sign.or.kr | 유료 (건당) | 2~4주 |
| **금융인증서 (yeskey)** | 금융결제원 | https://www.yeskey.or.kr | 유료 (건당) | 2~4주 |
| **디지털원패스** | 행안부 | https://www.onepass.go.kr/join | 무료 (공공기관) | 1~2주 |
| **GPKI** | 행안부 | https://www.gca.go.kr | 무료 | 4~8주 (공문) |
| **PASS (NICE 경유)** | NICE평가정보 | https://www.niceid.co.kr | 유료 (건당) | 1~2주 |
| **PASS (SKT 직접)** | SK텔레콤 | SKT B2B 제안 요청 | 유료 (협의) | 4~8주 |
| **카카오 인증서** | 카카오 비즈니스 | https://business.kakao.com | 유료 (건당+월정액) | 2~4주 |
| **카카오 OAuth2** | 카카오 개발자센터 | https://developers.kakao.com | 무료 | 즉시 |
| **네이버 OAuth2** | 네이버 개발자센터 | https://developers.naver.com | 무료 | 즉시 |
| **네이버 인증서** | 네이버클라우드 | https://www.ncloud.com | 유료 | 2~4주 |
| **KB인증서** | KB국민은행 B2B | 공문 발송 | 협의 | 4~8주 |
| **토스인증** | 비바리퍼블리카 | https://toss.im/developer | 협의 | 4~8주 |
| **라온 OmniOne** | 라온시큐어 | https://www.raonsecure.com | 유료 (협의) | 4~8주 |

---

## 11. 인증 등급 매핑 기준

```
행안부 LoA(Level of Assurance) 체계:

┌────┬──────────────────────────────────────────────────────┐
│ L1 │ 저위험 — 기억 기반 (ID/PW, 간편비밀번호)               │
│    │ 대상: 단순 조회, 게시글 열람                             │
│    │ 예: 소셜 로그인, 간편인증 일부                           │
├────┼──────────────────────────────────────────────────────┤
│ L2 │ 중위험 — 소유 기반 (SMS, 앱, 생체)                      │
│    │대상: 민원 신청, 개인정보 조회, 일반 금융                  │
│    │ 예: PASS, 카카오인증서, 모바일신분증, 생체인증             │
├────┼──────────────────────────────────────────────────────┤
│ L3 │ 고위험 — 공개키 서명 (PKI 전자서명)                      │
│    │ 대상: 법적 효력 문서, 금융 거래, 고위험 행정              │
│    │ 예: 공동인증서, 금융인증서, GPKI                         │
└────┴──────────────────────────────────────────────────────┘
```

### 11.1 인증 수단별 등급 매핑

| 인증 수단 | 기본 등급 | 최대 등급 | 비고 |
|----------|---------|---------|------|
| 소셜 OAuth2 (카카오/네이버) | L1 | L1 | CI 없음 |
| 간편인증 (EASY_SIGN) | L1 | L2 | 인증서 선택에 따라 |
| PASS | L2 | L2 | 통신사 가입자 인증 |
| 카카오 인증서 | L2 | L2 | 전자서명 |
| 네이버 인증서 | L2 | L2 | 전자서명 |
| 모바일 신분증 | L2 | L2 | DID 기반 |
| 디지털원패스 | L1 | L3 | 선택 인증수단에 따라 |
| 공동인증서 | L3 | L3 | PKI 전자서명 |
| 금융인증서 | L3 | L3 | PKI 전자서명 |
| GPKI | L3 | L3 | 공무원 전용 |

### 11.2 서비스별 최소 등급 요건 예시

```java
// ProviderConfig.java — 서비스별 최소 등급 검증
public class AuthLevelValidator {

    /**
     * 서비스 유형별 최소 인증 등급 요건
     */
    public static void validate(String serviceType, String actualLevel) {
        int required = switch (serviceType) {
            case "SIMPLE_INQUIRY"    -> 1;  // 단순 조회
            case "GENERAL_CIVIL"     -> 2;  // 일반 민원
            case "FINANCIAL"         -> 2;  // 금융 조회
            case "LEGAL_DOCUMENT"    -> 3;  // 법적 문서
            case "HIGH_RISK_FINANCE" -> 3;  // 고위험 금융
            default                  -> 2;
        };

        int actual = Integer.parseInt(actualLevel.substring(1));  // "L2" → 2
        if (actual < required) {
            throw new InsufficientAuthLevelException(
                "서비스 '" + serviceType + "'에는 L" + required + " 이상 필요. 현재: " + actualLevel
            );
        }
    }
}
```

---

## 12. 보안 고려사항

### 12.1 CI 처리 보안

```java
// CI는 절대 평문으로 저장하지 않는다
// 저장 전 SHA-256 또는 HMAC-SHA256으로 해시

public class CiHasher {

    private final String hmacSecret;  // 환경변수 주입

    /**
     * CI → 식별자 해시 변환
     * 동일 CI에 대해 항상 동일한 해시값 반환 (결정적 해시)
     * 단, 서비스별 secret으로 cross-service 역추적 방지
     */
    public String hashCi(String ci, String serviceId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            String key = hmacSecret + ":" + serviceId;  // 서비스별 분리
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(ci.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("CI 해시 실패", e);
        }
    }
}
```

### 12.2 인증 콜백 보안

```java
// 콜백 수신 시 반드시 적용해야 할 보안 검증:

// 1. state/nonce 검증 (CSRF 방어)
OidcStateEntry entry = stateStore.consume(state)
    .orElseThrow(() -> new SecurityException("state 불일치 — CSRF 의심"));

// 2. correlationId 매칭 (재사용 공격 방지)
if (!entry.getCorrelationId().equals(correlationId)) {
    throw new SecurityException("correlationId 불일치");
}

// 3. txId 1회 소비 (리플레이 공격 방지)
if (!txIdStore.consumeOnce(txId)) {
    throw new SecurityException("txId 재사용 — 리플레이 공격 의심");
}

// 4. 타임스탬프 검증 (±5분)
if (Math.abs(Instant.now().getEpochSecond() - timestamp) > 300) {
    throw new SecurityException("인증 시간 초과");
}
```

### 12.3 API 키 관리

```yaml
# ❌ 절대 하드코딩 금지
kakao-cert:
  admin-key: "KakaoAK 1234abcd..."  # 절대 안 됨

# ✅ 환경변수/Secret 주입
kakao-cert:
  admin-key: ${KAKAO_CERT_ADMIN_KEY:}

# K8s Secret 등록
# kubectl create secret generic auth-secrets \
#   --from-literal=KAKAO_CERT_ADMIN_KEY="KakaoAK ..." \
#   --from-literal=ANYID_SSO_SECRET_CODE="WDtDc2ek..."
```

### 12.4 returnUrl 화이트리스트

```java
// 인증 완료 후 리다이렉트 URL은 반드시 검증
// BrokerService / AnyIdController 등 모든 리다이렉트 지점에 적용

@Component
public class ReturnUrlValidator {

    @Value("${ido.fe.allowed-return-urls}")
    private List<String> allowedUrls;

    public void validate(String returnUrl, String correlationId) {
        if (returnUrl == null || returnUrl.isBlank()) return;

        boolean allowed = allowedUrls.stream()
            .anyMatch(base -> returnUrl.startsWith(base));

        if (!allowed) {
            log.error("[Security] returnUrl 화이트리스트 위반: url={} cid={}",
                returnUrl, correlationId);
            throw new PlatformException(
                PlatformErrorCode.INVALID_RETURN_URL, correlationId,
                "허용되지 않은 returnUrl: " + returnUrl
            );
        }
    }
}
```

---

## 참고 자료

| 항목 | URL |
|------|-----|
| 행안부 Any-ID 공식 포털 | https://www.anyid.go.kr |
| 행안부 디지털원패스 | https://www.onepass.go.kr |
| 행안부 고시 제2023-73호 | 행안부 법제처 국가법령정보센터 |
| 금융결제원 NPKI | https://www.kftc.or.kr |
| 금융인증서 yeskey | https://www.yeskey.or.kr |
| NICE 본인인증 API | https://www.niceid.co.kr |
| 카카오 개발자센터 | https://developers.kakao.com |
| 네이버 개발자센터 | https://developers.naver.com |
| 네이버클라우드 CLOVA Sign | https://www.ncloud.com/product/appService/clovaSign |
| 라온시큐어 OmniOne | https://www.raonsecure.com |
| 전자서명법 (국가법령) | https://www.law.go.kr |

---

## 관련 문서

| 문서 | 경로 |
|------|------|
| Any-ID 전체 개요 | `wiki/iam/00-overview.md` |
| 모바일 신분증 상세 | `wiki/iam/01-mobile-id.md` |
| 간편인증 상세 | `wiki/iam/02-easy-sign.md` |
| 공동인증서 상세 | `wiki/iam/03-joint-cert.md` |
| 금융인증서 상세 | `wiki/iam/04-fin-cert.md` |
| CI/DN 브로커링 | `wiki/iam/05-ci-dn-brokering.md` |
| Any-ID 설치형 연동 | `wiki/iam/06-install-type-integration.md` |
| SSO 세션 관리 | `wiki/iam/07-sso-session.md` |
| **(현재 문서)** 국내 인증 종합 가이드 | `wiki/iam/08-kr-auth-providers-guide.md` |
