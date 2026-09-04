package kr.go.smes.ido.broker.anyid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Any-ID 설치형 직접 연동 브로커 어댑터
 *
 * <p>행안부 Any-ID 사업단 발급 개발키를 기반으로 인증 흐름을 처리한다.<br>
 * 기관 #311 — 중소기업기술정보진흥원 (중소벤처24기업마당), srvc_no=1000001157
 *
 * <p><b>지원 인증 수단</b>:
 * <ul>
 *   <li>{@code MOBILE_ID}      — 모바일 신분증 (행안부 DID, L2)</li>
 *   <li>{@code EASY_SIGN}      — 간편인증 (카카오·네이버·PASS, L1~L2)</li>
 *   <li>{@code JOINT_CERT}     — 공동인증서 (NPKI, L3)</li>
 *   <li>{@code FINANCIAL_CERT} — 금융인증서 (yeskey, L3)</li>
 *   <li>{@code PRIVATE_ID}     — 민간ID/소셜 (social-relay, L1)</li>
 * </ul>
 *
 * <p><b>활성화 조건</b>:
 * <pre>
 * IDO_BROKER_MODE 값과 무관하게 자동 활성화.
 * BrokerService Layer 1: ProviderRouter.resolve() → DIRECT_BROKER
 * BrokerService Layer 2: isAnyIdProvider() 확인 → 이 어댑터 호출
 * </pre>
 *
 * <p><b>인증 흐름 (Any-ID 설치형)</b>:
 * <pre>
 *   1. [인증 시작] GET /api/v1/anyid/{provider}/initiate?returnUrl=...
 *      → AnyIdBrokerAdapter.initiateAuth()
 *      → Any-ID 인증 서버 POST /api/v1/init (srvc_no, provider, callback)
 *      ← { txId, redirectUrl } → 302 Any-ID 인증 UI
 *
 *   2. [사용자 인증] Any-ID 인증 UI (스크립트 팝업 또는 모바일 앱)
 *
 *   3. [콜백 수신] GET /api/v1/anyid/{provider}/callback?txId=...&code=...
 *      → AnyIdBrokerAdapter.normalizeCallback()
 *      → Any-ID 인증 서버 POST /api/v1/verify (txId, code, srvc_no)
 *      ← { result_code, identifier(CI/DN), auth_level }
 *
 *   4. [SSO 처리] Any-ID SSO 어댑터로 SSO 토큰 발급
 *      → AnyIdSsoService.issueToken()
 *
 *   5. [세션 발급] FeSessionService.create() → feSessionId 쿠키
 * </pre>
 *
 * <p><b>민간ID (social-relay) 흐름</b>:
 * <pre>
 *   1. GET /api/v1/anyid/pid/initiate?returnUrl=...&socialProvider=kakao
 *      → 302 → https://www.anyid.dev:1443/pid/auth.do?srvc_no=...&provider=kakao&callback=...
 *   2. Any-ID pid → 소셜 IdP (카카오/네이버) → pid callback
 *   3. GET /api/v1/anyid/pid/callback?txId=...&result=...
 *      → Any-ID pid 결과 조회 → CI/DN 획득
 * </pre>
 *
 * @see AnyIdProperties
 * @see AnyIdController
 * @see kr.go.smes.ido.broker.BrokerService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnyIdBrokerAdapter {

    private final AnyIdProperties anyIdProperties;
    private final ObjectMapper    objectMapper;

    private RestTemplate restTemplate;

    @PostConstruct
    void init() {
        AnyIdProperties.Http http = anyIdProperties.getHttp();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(http.getConnectTimeoutMs());
        factory.setReadTimeout(http.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);

        log.info("[AnyId-Broker] 어댑터 초기화: srvc_no={} auth={}:{}",
                anyIdProperties.getSrvcNo(),
                anyIdProperties.getAuth().getDomain(),
                anyIdProperties.getAuth().getPort());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 인증 시작
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Any-ID 인증 시작 — provider별 인증 초기화 및 리다이렉트 URL 반환
     *
     * <p>Any-ID 인증 서버에 init 요청 후 사용자를 인증 UI로 리다이렉트.
     *
     * @param provider       인증 수단 코드 (MOBILE_ID / EASY_SIGN / JOINT_CERT / FINANCIAL_CERT / PRIVATE_ID)
     * @param correlationId  흐름 추적 ID
     * @param callbackUrl    ido 콜백 URL (인증 완료 후 Any-ID → ido 호출)
     * @param returnUrl      최종 기관 귀환 URL (콜백 처리 완료 후 이동)
     * @return Any-ID 인증 UI 리다이렉트 URL
     * @throws PlatformException 지원하지 않는 provider 또는 Any-ID 서버 오류
     */
    public String initiateAuth(String provider, String correlationId,
                                String callbackUrl, String returnUrl) {
        log.info("[AnyId-Broker] 인증 시작: provider={} srvc_no={} correlationId={}",
                provider, anyIdProperties.getSrvcNo(), correlationId);

        // 민간ID(소셜)는 별도 흐름
        if ("PRIVATE_ID".equalsIgnoreCase(provider) || "PID".equalsIgnoreCase(provider)) {
            return buildPidRedirectUrl(callbackUrl, null);
        }

        String initUrl = buildAuthUrl("/api/v1/init");
        Map<String, Object> initBody = buildInitBody(provider, correlationId, callbackUrl);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(initBody, headers);

            ResponseEntity<String> resp = restTemplate.exchange(
                    initUrl, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            assertAnyIdSuccess(root, "init", correlationId);

            String txId       = root.path("txId").asText();
            String redirectUrl = root.path("redirectUrl").asText();

            if (txId.isBlank() || redirectUrl.isBlank()) {
                // PoC 환경(연결 불가)에서 fallback: 더미 리다이렉트 URL 구성
                log.warn("[AnyId-Broker] init 응답에 txId/redirectUrl 없음 — fallback URL 구성: correlationId={}",
                        correlationId);
                return buildFallbackRedirectUrl(provider, correlationId, callbackUrl);
            }

            log.info("[AnyId-Broker] init 완료: txId={} correlationId={}", txId, correlationId);
            return redirectUrl;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            // 개발 환경에서 anyid.dev 서버 연결 불가 시 fallback
            log.warn("[AnyId-Broker] Any-ID 서버 연결 실패 — fallback URL 반환: correlationId={} err={}",
                    correlationId, e.getMessage());
            return buildFallbackRedirectUrl(provider, correlationId, callbackUrl);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 콜백 처리 (인증 결과 검증)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Any-ID 인증 결과 콜백 처리 — 인증 결과 검증 및 CI/DN 획득
     *
     * <p>Any-ID 인증 서버에 verify 요청하여 txId에 해당하는 인증 결과를 확인한다.
     *
     * @param provider      인증 수단 코드
     * @param correlationId 흐름 추적 ID
     * @param txId          Any-ID 트랜잭션 ID
     * @param code          Any-ID 인증 완료 코드 (인증 서버 발급)
     * @return 인증 결과 (CI, DN, authLevel 등)
     * @throws PlatformException 인증 실패 또는 서명 검증 오류
     */
    public AnyIdAuthResult verifyCallback(String provider, String correlationId,
                                           String txId, String code) {
        log.info("[AnyId-Broker] 콜백 검증: provider={} txId={} correlationId={}",
                provider, txId, correlationId);

        String verifyUrl = buildAuthUrl("/api/v1/verify");
        Map<String, Object> verifyBody = Map.of(
                "srvc_no",       anyIdProperties.getSrvcNo(),
                "provider",      normalizeProvider(provider),
                "txId",          txId,
                "code",          code != null ? code : "",
                "correlationId", correlationId
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(verifyBody, headers);

            ResponseEntity<String> resp = restTemplate.exchange(
                    verifyUrl, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            assertAnyIdSuccess(root, "verify", correlationId);

            return extractAuthResult(root, provider, correlationId);

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AnyId-Broker] verify 실패: correlationId={} err={}", correlationId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "Any-ID verify 실패: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Authorization URL 생성 (BrokerService anyid 모드 지원)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Any-ID Authorization URL 생성 — BrokerService.buildAuthorizationUrl() 에서 호출
     *
     * <p>BrokerService가 Layer 1(ProviderRouter)에서 DIRECT_BROKER로 판별하고
     * isAnyIdProvider() 확인 후 이 메서드를 호출한다. IDO_BROKER_MODE 값과 무관.
     * provider → Any-ID 인증 수단 URL 매핑:
     * <pre>
     *   kakao / naver     → PRIVATE_ID (민간ID/소셜) → social-relay URL
     *   mobile-id         → MOBILE_ID  → 모바일 신분증 URL
     *   easy-sign         → EASY_SIGN  → 간편인증 URL
     *   joint-cert        → JOINT_CERT → 공동인증서 URL
     *   financial-cert    → FINANCIAL_CERT → 금융인증서 URL
     * </pre>
     *
     * @param provider       인증 수단 (FE 경로변수: kakao, naver, mobile-id 등)
     * @param correlationId  흐름 추적 ID
     * @param returnUrl      인증 완료 후 기관 귀환 URL
     * @param requestedLevel 요청 인증 수준 (L1/L2/L3)
     * @return Any-ID 인증 시작 URL (브라우저 리다이렉트 대상)
     */
    public String buildAuthorizationUrl(String provider, String correlationId,
                                         String returnUrl, String requestedLevel) {
        log.info("[AnyId-Broker] Authorization URL 생성: provider={} correlationId={} level={}",
                provider, correlationId, requestedLevel);

        String anyidProvider = resolveAnyIdProvider(provider);
        String callbackUrl   = buildIdoCallbackUrl(anyidProvider, returnUrl, correlationId);

        return initiateAuth(anyidProvider, correlationId, callbackUrl, returnUrl);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SSO 토큰 검증
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Any-ID SSO 세션 토큰 검증
     *
     * <p>SSO 어댑터를 통해 세션 토큰 유효성 확인 후 사용자 정보를 반환한다.
     * HMAC-SHA256 서명 검증: {@code ido.anyid.sso.secret-code}
     *
     * @param ssoToken      검증할 SSO 세션 토큰
     * @param correlationId 흐름 추적 ID
     * @return SSO 검증 결과 (agencyCode, userId, authLevel, exp 등)
     */
    public JsonNode verifySsoToken(String ssoToken, String correlationId) {
        AnyIdProperties.Sso sso = anyIdProperties.getSso();

        String verifyUrl = sso.getVerifyUrl();
        if (verifyUrl == null || verifyUrl.isBlank()) {
            verifyUrl = sso.resolvedBaseUrl() + "/sso/api/v1/verify";
        }

        log.debug("[AnyId-Broker] SSO 토큰 검증: url={} correlationId={}", verifyUrl, correlationId);

        // HMAC 서명 생성 (SSO 어댑터 규약)
        String hmacSig = buildSsoHmacSignature(ssoToken, sso.getSecretCode(), correlationId);

        Map<String, String> requestBody = Map.of(
                "sso_token",   ssoToken,
                "agency_code", anyIdProperties.getAgencyCode(),
                "signature",   hmacSig
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> resp = restTemplate.exchange(
                    verifyUrl, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            assertAnyIdSuccess(root, "sso-verify", correlationId);

            log.info("[AnyId-Broker] SSO 토큰 검증 성공: correlationId={}", correlationId);
            return root.path("data");

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AnyId-Broker] SSO 토큰 검증 실패: correlationId={} err={}",
                    correlationId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "Any-ID SSO 토큰 검증 실패: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 유틸
    // ══════════════════════════════════════════════════════════════════════

    /** FE provider 코드 → Any-ID provider 코드 변환 */
    private String resolveAnyIdProvider(String provider) {
        if (provider == null) return "EASY_SIGN";
        return switch (provider.toLowerCase().replace("-", "_")) {
            case "kakao", "naver", "google", "payco" -> "PRIVATE_ID";
            case "mobile_id", "mobileid", "did"     -> "MOBILE_ID";
            case "easy_sign", "easysign", "pass"    -> "EASY_SIGN";
            case "joint_cert", "jointcert", "npki"  -> "JOINT_CERT";
            case "financial_cert", "financialcert",
                 "fincert", "yeskey"                -> "FINANCIAL_CERT";
            default -> provider.toUpperCase().replace("-", "_");
        };
    }

    /** Any-ID provider 코드 정규화 */
    private String normalizeProvider(String provider) {
        if (provider == null) return "EASY_SIGN";
        return provider.toUpperCase().replace("-", "_");
    }

    /** Any-ID 인증 서버 URL 조립 */
    private String buildAuthUrl(String path) {
        return anyIdProperties.getAuth().resolvedBaseUrl() + path;
    }

    /** Any-ID init 요청 Body 생성 */
    private Map<String, Object> buildInitBody(String provider, String correlationId, String callbackUrl) {
        return Map.of(
                "srvc_no",       anyIdProperties.getSrvcNo(),
                "provider",      normalizeProvider(provider),
                "callback_url",  callbackUrl,
                "correlationId", correlationId,
                "enc_alg",       anyIdProperties.getKms().getEncAlg()
        );
    }

    /**
     * 민간ID(social-relay) 리다이렉트 URL 생성
     *
     * <pre>
     * https://www.anyid.dev:1443/pid/auth.do
     *   ?srvc_no=1000001157
     *   &client_id={clientId}
     *   &callback={ido callback url}
     *   &provider={소셜 provider}
     * </pre>
     */
    private String buildPidRedirectUrl(String callbackUrl, String socialProvider) {
        AnyIdProperties.Pid pid = anyIdProperties.getPid();

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(pid.getProviderUrl())
                .queryParam("srvc_no",   anyIdProperties.getSrvcNo())
                .queryParam("client_id", pid.getClientId())
                .queryParam("callback",  callbackUrl);

        if (socialProvider != null && !socialProvider.isBlank()) {
            builder.queryParam("provider", socialProvider.toLowerCase());
        }

        String url = builder.build(false).toUriString();
        log.info("[AnyId-Broker] 민간ID redirect URL 생성: {}", url);
        return url;
    }

    /**
     * ido 콜백 URL 생성 — Any-ID가 인증 완료 후 호출하는 URL
     *
     * <pre>
     * /api/v1/anyid/{provider}/callback?returnUrl={returnUrl}&cid={correlationId}
     * </pre>
     */
    private String buildIdoCallbackUrl(String provider, String returnUrl, String correlationId) {
        StringBuilder sb = new StringBuilder("/api/v1/anyid/")
                .append(provider.toLowerCase().replace("_", "-"))
                .append("/callback")
                .append("?cid=").append(encodeParam(correlationId));
        if (returnUrl != null && !returnUrl.isBlank()) {
            sb.append("&returnUrl=").append(encodeParam(returnUrl));
        }
        return sb.toString();
    }

    /**
     * 개발 환경 fallback URL — anyid.dev 서버 연결 불가 시
     * config.anyidc.json의 ui_path 기반 로컬 시뮬레이션 URL 반환
     */
    private String buildFallbackRedirectUrl(String provider, String correlationId, String callbackUrl) {
        String uiPath = switch (normalizeProvider(provider)) {
            case "MOBILE_ID"      -> "/anyid/mid/popup.html";
            case "EASY_SIGN"      -> "/anyid/easy/popup.html";
            case "JOINT_CERT"     -> "/anyid/cert/popup.html";
            case "FINANCIAL_CERT" -> "/anyid/fcert/popup.html";
            case "PRIVATE_ID"     -> "/anyid/pid/popup.html";
            default               -> "/anyid/easy/popup.html";
        };

        return UriComponentsBuilder
                .fromPath(uiPath)
                .queryParam("srvc_no",       anyIdProperties.getSrvcNo())
                .queryParam("provider",      normalizeProvider(provider))
                .queryParam("callback",      encodeParam(callbackUrl))
                .queryParam("correlationId", correlationId)
                .build(false)
                .toUriString();
    }

    /** Any-ID verify 응답에서 AuthResult 추출 */
    private AnyIdAuthResult extractAuthResult(JsonNode root, String provider, String correlationId) {
        String resultCode  = root.path("result_code").asText("0000");
        String identifier  = root.path("identifier").asText();   // CI 값
        String dn          = root.path("dn").asText();            // DN (이름)
        String authLevel   = root.path("auth_level").asText("L1");
        String txId        = root.path("txId").asText();
        String providerId  = root.path("provider_id").asText();   // 소셜 사용자 ID (민간ID)

        log.info("[AnyId-Broker] 인증 결과 추출: provider={} authLevel={} correlationId={}",
                provider, authLevel, correlationId);

        return AnyIdAuthResult.builder()
                .resultCode(resultCode)
                .identifier(identifier)
                .dn(dn)
                .authLevel(authLevel)
                .txId(txId)
                .providerId(providerId)
                .providerCode(normalizeProvider(provider))
                .srvcNo(anyIdProperties.getSrvcNo())
                .correlationId(correlationId)
                .build();
    }

    /** Any-ID API 응답 성공 검증 (result_code=0000) */
    private void assertAnyIdSuccess(JsonNode root, String operation, String correlationId) {
        String resultCode = root.path("result_code").asText("9999");
        if (!"0000".equals(resultCode)) {
            String resultMsg = root.path("result_msg").asText("unknown");
            log.error("[AnyId-Broker] {} API 오류: result_code={} result_msg={} correlationId={}",
                    operation, resultCode, resultMsg, correlationId);
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    String.format("Any-ID %s 오류: result_code=%s msg=%s", operation, resultCode, resultMsg));
        }
    }

    /**
     * SSO HMAC-SHA256 서명 생성
     *
     * <p>Any-ID SSO 어댑터 규약: {@code HMAC-SHA256(ssoToken, secretCode)}
     * secretCode는 Base64 인코딩된 바이트를 Raw 키로 사용.
     *
     * @param ssoToken   서명 대상 SSO 토큰
     * @param secretCode Base64 인코딩된 HMAC 시크릿 (행안부 발급)
     * @return HMAC-SHA256 서명 (Base64)
     */
    private String buildSsoHmacSignature(String ssoToken, String secretCode, String correlationId) {
        if (secretCode == null || secretCode.isBlank()) {
            log.warn("[AnyId-Broker] SSO secret-code 미설정 — 서명 없이 전송: correlationId={}", correlationId);
            return "";
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(
                    secretCode.replace('-', '+').replace('_', '/'));
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] hmac = mac.doFinal(ssoToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            log.error("[AnyId-Broker] SSO HMAC 서명 생성 실패: correlationId={}", correlationId, e);
            return "";
        }
    }

    private String encodeParam(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
