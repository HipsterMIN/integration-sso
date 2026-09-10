package io.github.hipstermin.idem.hub.broker.nonoidc;

import io.github.hipstermin.idem.common.domain.IdOAuthInput;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.broker.IdpBrokerResult;
import io.github.hipstermin.idem.hub.broker.IdpBrokerService;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 비OIDC 인증 수단 브로커 컨트롤러 (문서 §7 — Option 3 진입점)
 *
 * <p>이 컨트롤러가 {@link IdpBrokerService}(구현체: {@link NonOidcBrokerAdapter})의
 * 유일한 호출자다. 이전까지는 이 진입 경로가 없어 NonOidcAuthService에 도달할 방법이 없었음.
 *
 * <p>지원 인증 수단 (경로변수 provider):
 * <ul>
 *   <li>{@code pass}           — PASS 본인인증 (L2)</li>
 *   <li>{@code financial-cert} — 금융인증서 (L3)</li>
 *   <li>{@code gpki}           — 정부 공개키 인증서 (L3)</li>
 *   <li>{@code joint-cert}     — 공동인증서 (L3)</li>
 * </ul>
 *
 * <p>흐름:
 * <pre>
 *   [인증 시작]
 *   FE → GET /api/v1/broker/{provider}/nonoidc/initiate?returnUrl=...
 *       → NonOidcBrokerAdapter.initiateAuth()
 *       ← IdpBrokerResult { redirectUrl, providerTxId, status }
 *       → 302 → 사업자 인증 페이지 (REDIRECT_REQUIRED)
 *         또는 202 JSON { providerTxId } (DIRECT_CALL_REQUIRED)
 *
 *   [콜백 수신]
 *   사업자 → GET /api/v1/broker/{provider}/nonoidc/callback?txId=...&identifier=...
 *       → NonOidcBrokerAdapter.normalizeResponse()
 *           → NonOidcAuthService.processAuth()  (AuthResult + Kafka)
 *       → FeSessionService.create()
 *       ← 302 → returnUrl  (feSessionId 쿠키 포함)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/broker/{provider}/nonoidc")
@RequiredArgsConstructor
public class NonOidcBrokerController {

    private static final String COOKIE_NAME = "feSessionId";

    private final IdpBrokerService idpBrokerService;   // NonOidcBrokerAdapter 주입
    private final FeSessionService feSessionService;
    private final NonOidcAuthService nonOidcAuthService; // 실패 카운트용 직접 참조

    // ──────────────────────────────────────────────────────────────────────
    // 1. 인증 시작
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 비OIDC 인증 시작 — 사업자 인증 페이지로 리다이렉트
     *
     * @param provider       경로변수 (pass / financial-cert / gpki / joint-cert)
     * @param returnUrl      인증 완료 후 이동할 기관 URL
     * @param requestedLevel 요청 인증 수준 (실제 수준은 provider에 따라 override)
     * @param correlationId  흐름 추적 ID (없으면 자동 생성)
     */
    @GetMapping("/initiate")
    public ResponseEntity<Object> initiate(
            @PathVariable String provider,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(defaultValue = "L1") String requestedLevel,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid         = resolveCorrelationId(correlationId);
        String providerCode = normalizeProviderCode(provider);

        log.info("[NonOidcBrokerController] 인증 시작: provider={} providerCode={} cid={}",
                provider, providerCode, cid);

        try {
            String callbackUrl = buildCallbackUrl(provider, returnUrl, cid);
            IdpBrokerResult result = idpBrokerService.initiateAuth(providerCode, cid, callbackUrl);

            return switch (result.getStatus()) {
                case REDIRECT_REQUIRED -> {
                    log.info("[NonOidcBrokerController] 사업자 리다이렉트: cid={}", cid);
                    HttpHeaders headers = new HttpHeaders();
                    headers.setLocation(URI.create(result.getRedirectUrl()));
                    yield ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
                }
                case DIRECT_CALL_REQUIRED -> {
                    log.info("[NonOidcBrokerController] 직접 호출 방식: providerTxId={} cid={}",
                            result.getProviderTxId(), cid);
                    yield ResponseEntity.accepted().body(Map.of(
                            "providerTxId",  result.getProviderTxId(),
                            "providerCode",  result.getProviderCode(),
                            "correlationId", cid
                    ));
                }
                case CIRCUIT_OPEN -> {
                    log.warn("[NonOidcBrokerController] Circuit Breaker OPEN: provider={} cid={}",
                            providerCode, cid);
                    yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                            "error",   PlatformErrorCode.IDP_CIRCUIT_OPEN.getCode(),
                            "message", "인증 수단 일시 불가: " + providerCode
                    ));
                }
            };

        } catch (PlatformException e) {
            log.error("[NonOidcBrokerController] 인증 시작 오류: code={} cid={}",
                    e.getErrorCode().getCode(), cid, e);
            return redirectToError(e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[NonOidcBrokerController] 예상치 못한 오류: cid={}", cid, e);
            return redirectToError("INTERNAL_ERROR", "internal server error");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. 사업자 콜백 수신
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 비OIDC 사업자 콜백 수신 — 인증 결과 처리 + FE 세션 발급
     *
     * <p>사업자가 인증 완료 후 ido callback URL로 리다이렉트.
     * PoC: 공통 파라미터명({@code txId}, {@code identifier}) 사용.
     * 운영: 사업자별 파라미터명 상이 → 전용 @RequestParam 매핑 필요.
     *
     * @param provider    인증 수단 경로변수
     * @param txId        사업자 트랜잭션 ID
     * @param identifier  사업자가 반환한 식별자 (PoC 평문, 운영 암호화)
     * @param returnUrl   인증 완료 후 이동할 기관 URL
     * @param error       사업자 인증 실패 코드 (실패 시)
     */
    @GetMapping("/callback")
    public ResponseEntity<Object> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String txId,
            @RequestParam(required = false) String identifier,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletResponse httpResponse) {

        String cid         = resolveCorrelationId(correlationId);
        String providerCode = normalizeProviderCode(provider);

        // ── 사업자 인증 실패 ────────────────────────────────────────────
        if (error != null) {
            log.warn("[NonOidcBrokerController] 사업자 인증 오류: error={} desc={} cid={}",
                    error, errorDescription, cid);
            return redirectToError("NONOIDC_AUTH_FAILED", error);
        }

        // ── 필수 파라미터 검증 ──────────────────────────────────────────
        if (txId == null || txId.isBlank()) {
            log.warn("[NonOidcBrokerController] txId 없음: cid={}", cid);
            return redirectToError("MISSING_TX_ID", "transaction id is missing");
        }
        if (identifier == null || identifier.isBlank()) {
            log.warn("[NonOidcBrokerController] identifier 없음: cid={}", cid);
            return redirectToError("MISSING_IDENTIFIER", "identifier is missing");
        }

        log.info("[NonOidcBrokerController] 콜백 수신: provider={} txId={} cid={}", provider, txId, cid);

        try {
            // ── 응답 정규화 → AuthResult 생성 + Kafka 발행 ───────────────
            Map<String, Object> rawResponse = Map.of(
                    "identifier", identifier,
                    "txId",       txId,
                    "provider",   providerCode
            );
            IdOAuthInput authInput = idpBrokerService.normalizeResponse(
                    providerCode, cid, txId, rawResponse);

            // ── FE 세션 생성 ─────────────────────────────────────────────
            // NonOidcBrokerAdapter.normalizeResponse() 내부에서 authResultId를
            // internalSignature 필드에 담아 반환하는 규약 (PoC 설계)
            String authResultId   = authInput.getInternalSignature();
            String authLevel      = authInput.getRequestedAuthLevel() != null
                    ? authInput.getRequestedAuthLevel().name() : "L1";
            String identifierHash = authInput.getIdentifierHash();

            FeSession feSession = feSessionService.create(
                    identifierHash,
                    authResultId,
                    authLevel,
                    returnUrl
            );

            // ── feSessionId 쿠키 발급 ────────────────────────────────────
            ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, feSession.getFeSessionId())
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("Lax")
                    .path("/")
                    .build();
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            String redirectTarget = (returnUrl != null && !returnUrl.isBlank())
                    ? returnUrl : "/conversion/complete";

            log.info("[NonOidcBrokerController] 인증 완료: feSessionId={}... redirectUrl={} cid={}",
                    feSession.getFeSessionId().substring(0, Math.min(8, feSession.getFeSessionId().length())),
                    redirectTarget, cid);

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectTarget));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (PlatformException e) {
            recordFailureIfNeeded(e, identifier, providerCode, cid);
            log.error("[NonOidcBrokerController] 콜백 처리 오류: code={} cid={}",
                    e.getErrorCode().getCode(), cid, e);
            return redirectToError(e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[NonOidcBrokerController] 예상치 못한 오류: cid={}", cid, e);
            return redirectToError("INTERNAL_ERROR", "internal server error");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 내부 유틸
    // ──────────────────────────────────────────────────────────────────────

    /** 경로변수 provider → 표준 providerCode 변환 (pass→PASS, financial-cert→FINANCIAL_CERT) */
    private String normalizeProviderCode(String provider) {
        if (provider == null) return "UNKNOWN";
        return provider.toUpperCase().replace("-", "_");
    }

    /**
     * ido 콜백 URL 생성 — 사업자에게 전달할 redirect_uri.
     * returnUrl + correlationId를 쿼리파라미터로 포함하여
     * 콜백 수신 시 returnUrl을 복원할 수 있도록 한다.
     */
    private String buildCallbackUrl(String provider, String returnUrl, String correlationId) {
        StringBuilder sb = new StringBuilder("/api/v1/broker/")
                .append(provider).append("/nonoidc/callback")
                .append("?cid=").append(encodeParam(correlationId));
        if (returnUrl != null && !returnUrl.isBlank()) {
            sb.append("&returnUrl=").append(encodeParam(returnUrl));
        }
        return sb.toString();
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

    /**
     * 인증 실패 카운트 기록 — 연속 5회 실패 시 30분 잠금.
     * QS_AUTH_FAILED / IDP_SIGNATURE_MISMATCH 계열 오류만 카운트.
     */
    private void recordFailureIfNeeded(PlatformException e, String identifier,
                                        String providerCode, String correlationId) {
        if (PlatformErrorCode.QS_AUTH_FAILED.equals(e.getErrorCode())
                || PlatformErrorCode.IDP_SIGNATURE_MISMATCH.equals(e.getErrorCode())) {
            try {
                MessageDigest md   = MessageDigest.getInstance("SHA-256");
                byte[]        hash = md.digest(identifier.getBytes(StandardCharsets.UTF_8));
                String identifierHash = HexFormat.of().formatHex(hash);
                nonOidcAuthService.recordFailure(identifierHash, providerCode, correlationId);
            } catch (Exception ignored) {
                log.warn("[NonOidcBrokerController] 실패 카운트 기록 실패 (무시): cid={}", correlationId);
            }
        }
    }

    private ResponseEntity<Object> redirectToError(String code, String detail) {
        String errorUrl = "/error?code=" + code
                + (detail != null ? "&detail=" + encodeParam(detail) : "");
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(errorUrl));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
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
