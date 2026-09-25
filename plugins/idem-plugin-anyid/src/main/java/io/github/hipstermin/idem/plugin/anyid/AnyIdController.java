package io.github.hipstermin.idem.plugin.anyid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.spi.broker.BrokerAuthCompletion;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.common.util.UuidV7;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Any-ID 설치형 연동 전용 컨트롤러 (플러그인, S5b)
 *
 * <p>행안부 Any-ID 설치형 인증 흐름의 진입점(initiate)과 콜백(callback)을 처리한다.<br>
 * 운영기관 식별자(srvc_no 등)는 idem.hub.anyid.* 설정으로 주입된다 (S1 범용화).
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>GET {@code /api/v1/anyid/{provider}/initiate}  — 인증 시작 → Any-ID UI 리다이렉트</li>
 *   <li>GET {@code /api/v1/anyid/{provider}/callback}  — Any-ID 콜백 수신 → SSO 토큰 검증</li>
 *   <li>POST {@code /api/v1/anyid/{provider}/ssob}     — FE 인증 결과(ssob) 수신 → 복호화 처리</li>
 *   <li>POST {@code /api/v1/anyid/txId}                — 거래 ID 발급 (FE SDK 초기화용)</li>
 *   <li>POST {@code /api/v1/anyid/ssob}                — provider 없는 통합 ssob 처리 (FE 호환 응답)</li>
 *   <li>GET {@code /api/v1/anyid/oidc/ssoLogin}        — SSO 로그인 처리</li>
 *   <li>GET {@code /api/v1/anyid/config}               — 설정 조회 (FE용, 비밀값 제외)</li>
 * </ul>
 *
 * <h3>코어와의 경계</h3>
 * <pre>
 * 플러그인: 벤더 프로토콜 — SDK ssob 복호화({@link SsobDecryptor}), SSO 토큰 검증, 인증 시작 URL
 * 코어    : {@link BrokerAuthCompletion} — AuthResult 저장·감사·이벤트 발행·FE 세션 발급
 * </pre>
 * SDK jar 가 없어 {@link SsobDecryptor} 빈이 없으면 ssob 계열 엔드포인트는 503 을 돌려준다.
 *
 * @see AnyIdBrokerAdapter
 * @see AnyIdAutoConfiguration
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/anyid")
public class AnyIdController {

    private final AnyIdBrokerAdapter          anyIdBrokerAdapter;
    private final ObjectProvider<SsobDecryptor> ssobDecryptor;
    private final BrokerAuthCompletion        completion;
    private final AnyIdProperties             anyIdProperties;
    private final ObjectMapper                objectMapper;

    public AnyIdController(AnyIdBrokerAdapter anyIdBrokerAdapter,
                           ObjectProvider<SsobDecryptor> ssobDecryptor,
                           BrokerAuthCompletion completion,
                           AnyIdProperties anyIdProperties,
                           ObjectMapper objectMapper) {
        this.anyIdBrokerAdapter = anyIdBrokerAdapter;
        this.ssobDecryptor = ssobDecryptor;
        this.completion = completion;
        this.anyIdProperties = anyIdProperties;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 인증 시작
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID 인증 시작 — provider별 인증 UI 리다이렉트
     *
     * @param provider       인증 수단 (mobile-id / easy-sign / joint-cert / financial-cert / pid)
     * @param returnUrl      인증 완료 후 기관 귀환 URL
     * @param requestedLevel 요청 인증 수준 (L1/L2/L3)
     * @param correlationId  흐름 추적 ID (없으면 자동 생성)
     * @param socialProvider 민간ID 경우 소셜 provider (kakao / naver 등, optional)
     */
    @GetMapping("/{provider}/initiate")
    public ResponseEntity<Void> initiate(
            @PathVariable String provider,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(defaultValue = "L1") String requestedLevel,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(required = false) String socialProvider) {

        String cid = resolveCorrelationId(correlationId);
        log.info("[AnyIdController] 인증 시작: provider={} level={} cid={}", provider, requestedLevel, cid);

        try {
            String callbackUrl = buildCallbackUrl(provider, returnUrl, cid);
            String redirectUrl = anyIdBrokerAdapter.initiateAuth(provider, cid, callbackUrl, returnUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectUrl));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (Exception e) {
            log.error("[AnyIdController] 인증 시작 실패: provider={} cid={} err={}", provider, cid, e.getMessage());
            return redirectToError("ANYID_INIT_FAILED", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 콜백 수신 (Any-ID → ido) — SSO 모드용
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID SSO 모드 콜백 수신 — SSO 서버에 토큰을 검증하고 FE 세션을 발급한다.
     */
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String txId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid = resolveCorrelationId(correlationId);

        if (error != null) {
            log.warn("[AnyIdController] 인증 실패 콜백: error={} desc={} cid={}", error, errorDescription, cid);
            return redirectToError("ANYID_AUTH_FAILED", error);
        }

        if (txId == null || txId.isBlank()) {
            log.warn("[AnyIdController] txId 없음: cid={}", cid);
            return redirectToError("MISSING_TX_ID", "transaction id is missing");
        }

        log.info("[AnyIdController] SSO 콜백 수신: provider={} txId={} cid={}", provider, txId, cid);

        try {
            var ssoData = anyIdBrokerAdapter.verifySsoToken(txId, cid);

            String userId    = ssoData.path("userId").asText();
            String authLevel = ssoData.path("authLevel").asText("L1");

            BrokerAuthCompletion.Result result = completion.complete(new BrokerAuthCompletion.Command(
                    cid, normalizeProviderCode(provider), txId, userId, authLevel, returnUrl));

            String redirectTarget = (returnUrl != null && !returnUrl.isBlank())
                    ? returnUrl : "/conversion/complete";

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectTarget));
            headers.add(HttpHeaders.SET_COOKIE,
                    buildSessionCookie(result.feSessionId(), anyIdProperties.getSso().getSessionTtlSeconds()));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (Exception e) {
            log.error("[AnyIdController] SSO 콜백 처리 실패: provider={} cid={} err={}",
                    provider, cid, e.getMessage());
            return redirectToError("ANYID_CALLBACK_ERROR", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ssob 수신 처리 — SDK 복호화 핵심 엔드포인트
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID ssob 수신 및 처리 — SDK 복호화 후 코어에 인증 완료를 넘긴다.
     *
     * @param body {@code { "ssob": "...", "tag": "...", "txId": "..." }}
     * @return {@code { "status": "success", "authLevel": "L2", ... }} 또는 에러
     */
    @PostMapping("/{provider}/ssob")
    public ResponseEntity<Map<String, Object>> processSsob(
            @PathVariable String provider,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid = resolveCorrelationId(correlationId);
        String ssobStr = body.get("ssob");
        String tag     = body.get("tag");      // txId와 동일
        String txId    = body.getOrDefault("txId", tag);

        log.info("[AnyIdController] ssob 처리 시작: provider={} txId={} cid={}", provider, txId, cid);

        if (ssobStr == null || ssobStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "fail",
                    "code",   "MISSING_SSOB",
                    "message", "ssob 파라미터 없음"
            ));
        }
        SsobDecryptor decryptor = ssobDecryptor.getIfAvailable();
        if (decryptor == null) {
            return sdkUnavailable(Map.of("status", "fail", "code", "ANYID_SDK_UNAVAILABLE",
                    "message", "AnyID SDK 미탑재 — ssob 복호화 불가"));
        }

        try {
            Map<String, Object> ssob = decryptor.decrypt(ssobStr, tag);
            String ci        = AnyIdSsob.extractCi(ssob, cid);
            String authLevel = AnyIdSsob.extractAuthLevel(ssob);
            String name      = AnyIdSsob.extractName(ssob);
            String providerCode = normalizeProviderCode(provider);

            log.info("[AnyIdController] ssob 복호화 성공: provider={} authLevel={} cid={}", provider, authLevel, cid);

            BrokerAuthCompletion.Result result = completion.complete(new BrokerAuthCompletion.Command(
                    cid, providerCode, txId, ci, authLevel, null));
            log.info("[AnyIdController] AuthResult 저장: authResultId={} cid={}", result.authResultId(), cid);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.SET_COOKIE,
                    buildSessionCookie(result.feSessionId(), anyIdProperties.getSso().getSessionTtlSeconds()));

            Map<String, Object> response = Map.of(
                    "status",       "success",
                    "authLevel",    authLevel,
                    "authResultId", result.authResultId(),
                    "provider",     providerCode,
                    "name",         name
            );

            log.info("[AnyIdController] 인증 완료: provider={} authLevel={} cid={}", provider, authLevel, cid);
            return ResponseEntity.ok().headers(headers).body(response);

        } catch (PlatformException e) {
            log.error("[AnyIdController] ssob 처리 PlatformException: cid={} code={} msg={}",
                    cid, e.getErrorCode(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status",  "fail",
                    "code",    e.getErrorCode() != null ? e.getErrorCode().name() : "ANYID_ERROR",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[AnyIdController] ssob 처리 예외: provider={} cid={} err={}",
                    provider, cid, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status",  "fail",
                    "code",    "ANYID_SSOB_ERROR",
                    "message", "ssob 처리 중 서버 오류: " + e.getMessage()
            ));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // SSO 로그인 처리 (anyidAdaptor.ssoLogin() 경로)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID SSO 로그인 처리 — {@code GET /oidc/ssoLogin?data={base64 JSON {txId, ssob, userSeCd, afData}}}
     */
    @GetMapping("/oidc/ssoLogin")
    public ResponseEntity<Void> ssoLogin(
            @RequestParam String data,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid = resolveCorrelationId(correlationId);
        log.info("[AnyIdController] SSO 로그인: cid={}", cid);

        SsobDecryptor decryptor = ssobDecryptor.getIfAvailable();
        if (decryptor == null) {
            return redirectToError("ANYID_SDK_UNAVAILABLE", "AnyID SDK 미탑재");
        }

        try {
            String jsonStr = new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
            Map<String, String> payload = objectMapper.readValue(jsonStr,
                    new TypeReference<Map<String, String>>() {});

            String txId    = payload.get("txId");
            String ssobStr = payload.get("ssob");

            Map<String, Object> ssob = decryptor.decrypt(ssobStr, txId);
            String ci        = AnyIdSsob.extractCi(ssob, cid);
            String authLevel = AnyIdSsob.extractAuthLevel(ssob);

            BrokerAuthCompletion.Result result = completion.complete(new BrokerAuthCompletion.Command(
                    cid, "EASY_SIGN", txId, ci, authLevel, null));   // SSO 경로는 간편인증으로 처리

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create("/conversion/complete"));
            headers.add(HttpHeaders.SET_COOKIE,
                    buildSessionCookie(result.feSessionId(), anyIdProperties.getSso().getSessionTtlSeconds()));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (Exception e) {
            log.error("[AnyIdController] SSO 로그인 실패: cid={} err={}", cid, e.getMessage());
            return redirectToError("ANYID_SSO_LOGIN_FAILED", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 설정 정보 제공 (FE용) — 비밀값 제외
    // ──────────────────────────────────────────────────────────────────────

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = Map.of(
                "srvc_no",     anyIdProperties.getSrvcNo(),
                "agency_code", anyIdProperties.getAgencyCode(),
                "agency_name", anyIdProperties.getAgencyName(),
                "auth_base",   anyIdProperties.getAuth().resolvedBaseUrl(),
                "sso_base",    anyIdProperties.getSso().resolvedBaseUrl(),
                "portal_base", anyIdProperties.getPortal().resolvedBaseUrl(),
                "pid_url",     anyIdProperties.getPid().getProviderUrl(),
                "config_file", anyIdProperties.getConfigFile()
        );
        return ResponseEntity.ok(config);
    }

    // ──────────────────────────────────────────────────────────────────────
    // txId 발급 — FE SDK 초기화용 거래 ID 생성
    // ──────────────────────────────────────────────────────────────────────

    /** {@code POST /api/v1/anyid/txId → { "txId": "yyyyMMddHHmmss-{uuid8}" }} */
    @PostMapping("/txId")
    public ResponseEntity<Map<String, String>> issueTxId() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String uuidPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String txId = datePart + "-" + uuidPart;

        log.debug("[AnyIdController] txId 발급: {}", txId);
        return ResponseEntity.ok(Map.of("txId", txId));
    }

    // ──────────────────────────────────────────────────────────────────────
    // ssob 통합 처리 — provider 없는 FE 호환 엔드포인트
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID ssob 통합 처리 — FE 가 기대하는 응답 형식:
     * <pre>
     * 성공: { "resultCode": "2000", "ci": "{authResultId}", "resultMsg": "인증 완료", "authLevel": "L2" }
     * 실패: { "resultCode": "5000", "resultMsg": "...", "errorCode": "..." }
     * </pre>
     * provider 는 {@code userSeCd} 로 추론하고 없으면 EASY_SIGN.
     */
    @PostMapping("/ssob")
    public ResponseEntity<Map<String, Object>> processSsobUnified(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid     = resolveCorrelationId(correlationId);
        String ssobStr = body.get("ssob");
        String tag     = body.get("tag");
        String txId    = body.getOrDefault("txId", tag);
        String userSeCd = body.getOrDefault("userSeCd", "");
        String provider = resolveProviderFromUserSeCd(userSeCd);

        log.info("[AnyIdController] ssob 통합 처리: provider={} txId={} cid={}", provider, txId, cid);

        if (ssobStr == null || ssobStr.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("resultCode", "5001");
            err.put("resultMsg",  "ssob 파라미터가 없습니다");
            err.put("errorCode",  "MISSING_SSOB");
            return ResponseEntity.badRequest().body(err);
        }
        SsobDecryptor decryptor = ssobDecryptor.getIfAvailable();
        if (decryptor == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("resultCode", "5001");
            err.put("resultMsg",  "AnyID SDK 미탑재 — ssob 복호화 불가");
            err.put("errorCode",  "ANYID_SDK_UNAVAILABLE");
            return sdkUnavailable(err);
        }

        try {
            Map<String, Object> ssob = decryptor.decrypt(ssobStr, tag);
            String ci        = AnyIdSsob.extractCi(ssob, cid);
            String authLevel = AnyIdSsob.extractAuthLevel(ssob);
            String name      = AnyIdSsob.extractName(ssob);

            log.info("[AnyIdController] ssob 복호화 완료: authLevel={} cid={}", authLevel, cid);

            BrokerAuthCompletion.Result result = completion.complete(new BrokerAuthCompletion.Command(
                    cid, normalizeProviderCode(provider), txId, ci, authLevel, null));
            log.info("[AnyIdController] AuthResult 저장: authResultId={} cid={}", result.authResultId(), cid);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.SET_COOKIE,
                    buildSessionCookie(result.feSessionId(), anyIdProperties.getSso().getSessionTtlSeconds()));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("resultCode", "2000");
            response.put("ci",         result.authResultId());   // authResultId를 CI lookup token으로 사용
            response.put("resultMsg",  "인증 완료");
            response.put("authLevel",  authLevel);
            response.put("name",       name);

            log.info("[AnyIdController] 통합 인증 완료: authLevel={} cid={}", authLevel, cid);
            return ResponseEntity.ok().headers(headers).body(response);

        } catch (PlatformException e) {
            log.error("[AnyIdController] ssob 통합 처리 PlatformException: cid={} code={} msg={}",
                    cid, e.getErrorCode(), e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("resultCode", "5000");
            err.put("resultMsg",  e.getMessage());
            err.put("errorCode",  e.getErrorCode() != null ? e.getErrorCode().name() : "ANYID_ERROR");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);

        } catch (Exception e) {
            log.error("[AnyIdController] ssob 통합 처리 예외: cid={} err={}", cid, e.getMessage(), e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("resultCode", "5000");
            err.put("resultMsg",  "ssob 처리 중 서버 오류: " + e.getMessage());
            err.put("errorCode",  "ANYID_SSOB_ERROR");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 헬스 체크
    // ──────────────────────────────────────────────────────────────────────

    /** Any-ID 연동 상태 확인 (비밀값 없음) */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status",      "UP",
                "sdk",         ssobDecryptor.getIfAvailable() != null ? "present" : "absent",
                "srvc_no",     anyIdProperties.getSrvcNo(),
                "auth_domain", anyIdProperties.getAuth().getDomain(),
                "sso_domain",  anyIdProperties.getSso().getDomain(),
                "kms_host",    anyIdProperties.getKms().getServerHost(),
                "enc_alg",     anyIdProperties.getKms().getEncAlg()
        ));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 내부 유틸
    // ──────────────────────────────────────────────────────────────────────

    private static ResponseEntity<Map<String, Object>> sdkUnavailable(Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private String resolveCorrelationId(String header) {
        if (header != null && !header.isBlank()) {
            CorrelationIdHolder.set(header);
            return header;
        }
        String existing = CorrelationIdHolder.get();
        if (existing != null && !existing.isBlank()) return existing;
        String generated = UuidV7.generate();
        CorrelationIdHolder.set(generated);
        return generated;
    }

    private String buildCallbackUrl(String provider, String returnUrl, String correlationId) {
        StringBuilder sb = new StringBuilder("/api/v1/anyid/")
                .append(provider)
                .append("/callback")
                .append("?cid=").append(encodeParam(correlationId));
        if (returnUrl != null && !returnUrl.isBlank()) {
            sb.append("&returnUrl=").append(encodeParam(returnUrl));
        }
        return sb.toString();
    }

    /** feSessionId 쿠키 — SameSite=Lax, HttpOnly, Max-Age */
    static String buildSessionCookie(String feSessionId, long ttlSeconds) {
        return String.format("fe_session=%s; Path=/; HttpOnly; SameSite=Lax; Max-Age=%d", feSessionId, ttlSeconds);
    }

    /** FE provider 경로변수 → provider_code 정규화 */
    static String normalizeProviderCode(String provider) {
        if (provider == null) return "EASY_SIGN";
        return switch (provider.toLowerCase().replace("-", "_")) {
            case "mobile_id", "mid"              -> "MOBILE_ID";
            case "easy_sign", "easy"             -> "EASY_SIGN";
            case "joint_cert", "npki"            -> "JOINT_CERT";
            case "financial_cert", "fincert"     -> "FINANCIAL_CERT";
            case "pid", "private_id"             -> "PRIVATE_ID";
            default -> provider.toUpperCase().replace("-", "_");
        };
    }

    /**
     * userSeCd → provider 추론: 01 모바일 신분증 · 02 간편인증 · 03 공동인증서 · 04 금융인증서 · 05 민간ID.
     * 없으면 easy-sign.
     */
    static String resolveProviderFromUserSeCd(String userSeCd) {
        if (userSeCd == null || userSeCd.isBlank()) return "easy-sign";
        return switch (userSeCd.trim()) {
            case "01" -> "mobile-id";
            case "02" -> "easy-sign";
            case "03" -> "joint-cert";
            case "04" -> "financial-cert";
            case "05" -> "pid";
            default   -> "easy-sign";
        };
    }

    private ResponseEntity<Void> redirectToError(String code, String detail) {
        String errorUrl = "/error?code=" + code
                + (detail != null ? "&detail=" + encodeParam(detail) : "");
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(errorUrl));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }

    private static String encodeParam(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
