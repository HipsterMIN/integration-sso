package io.github.hipstermin.idem.hub.broker.keycloak;

import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.broker.BrokerAuditLogService;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Keycloak → IdO 직접 콜백 컨트롤러 (문서 §7)
 *
 * <p>Keycloak 모드에서 redirect_uri = ido 직접 수신.
 * 기존(q-sign 모드)에서는 q-sign이 Kakao 콜백을 수신했으나,
 * Keycloak 도입 후 이 엔드포인트가 Keycloak의 authorization code를 수신.
 *
 * <p>처리 흐름:
 * <pre>
 *   Keycloak → GET /api/v1/broker/callback?code=...&state=...
 *       → KeycloakOidcService.handleCallback()
 *           (state 검증 / token 교환 / JWT 검증 / AuthResult 생성 / Kafka 발행)
 *       → FE 세션 생성 → feSessionId 쿠키 Set
 *       → 302 → returnUrl (기관 콜백)
 * </pre>
 *
 * <p>보안:
 * <ul>
 *   <li>state 파라미터 — Redis 1회 소비 (CSRF 방지)</li>
 *   <li>nonce 검증 — id_token replay attack 방지</li>
 *   <li>error 파라미터 존재 시 즉시 에러 페이지 리다이렉트</li>
 * </ul>
 *
 * <p>엔드포인트: GET /api/v1/broker/callback
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/broker")
@RequiredArgsConstructor
public class KeycloakCallbackController {

    private static final String COOKIE_NAME = "feSessionId";

    private final KeycloakOidcService   keycloakOidcService;
    private final BrokerAuditLogService brokerAuditLogService;

    @Value("${ido.broker.mode:qsign}")
    private String brokerMode;

    /**
     * Keycloak Authorization Code 콜백 수신
     *
     * <p>Keycloak이 사용자 인증 완료 후 이 URL로 리다이렉트.
     * {@code ?code=...&state=...} 쿼리 파라미터로 authorization code 전달.
     *
     * @param code  authorization code (Keycloak 발급)
     * @param state CSRF 검증용 state (Redis에 저장된 값과 비교)
     * @param error Keycloak 인증 실패 시 에러 코드 (access_denied 등)
     * @param errorDescription 에러 상세 설명
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationIdHeader,
            HttpServletRequest request,
            HttpServletResponse response) {

        String cid = (correlationIdHeader != null && !correlationIdHeader.isBlank())
                ? correlationIdHeader : CorrelationIdHolder.get();
        CorrelationIdHolder.set(cid);
        String clientIp = resolveClientIp(request);

        // ── CALLBACK 수신 기록 (P1: broker_audit_log) ─────────────────────
        brokerAuditLogService.record(BrokerAuditLogService.AuditEntry.builder()
                .correlationId(cid)
                .providerCode("KEYCLOAK")
                .providerType("STANDARD_OIDC")
                .brokerMode("keycloak")
                .action(BrokerAuditLogService.ACTION_CALLBACK)
                .clientIp(clientIp)
                .build());

        // ── Keycloak 인증 실패 처리 ───────────────────────────────────────
        if (error != null) {
            log.warn("[KeycloakCallback] Keycloak 인증 오류: error={} description={} cid={}",
                    error, errorDescription, cid);
            brokerAuditLogService.recordFail(cid, "KEYCLOAK", "STANDARD_OIDC",
                    "keycloak", "KEYCLOAK_AUTH_FAILED", error, clientIp);
            return redirectToError("KEYCLOAK_AUTH_FAILED", error);
        }

        // ── Keycloak 모드 확인 ────────────────────────────────────────────
        if (!"keycloak".equals(brokerMode)) {
            log.warn("[KeycloakCallback] keycloak 모드가 아닌데 callback 수신됨: mode={}", brokerMode);
            return redirectToError("BROKER_MODE_MISMATCH", "broker mode is " + brokerMode);
        }

        // ── 파라미터 검증 ─────────────────────────────────────────────────
        if (code == null || code.isBlank()) {
            log.warn("[KeycloakCallback] code 파라미터 없음: cid={}", cid);
            return redirectToError("MISSING_CODE", "authorization code is missing");
        }
        if (state == null || state.isBlank()) {
            log.warn("[KeycloakCallback] state 파라미터 없음: cid={}", cid);
            return redirectToError("MISSING_STATE", "state is missing");
        }

        try {
            // ── 핵심 인증 처리 ────────────────────────────────────────────
            KeycloakOidcService.CallbackResult result =
                    keycloakOidcService.handleCallback(code, state);

            FeSession feSession = result.getFeSession();

            // ── feSessionId 쿠키 발급 ─────────────────────────────────────
            ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, feSession.getFeSessionId())
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("Lax")
                    .path("/")
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            // ── returnUrl 결정 → 302 리다이렉트 ──────────────────────────
            String returnUrl = result.getReturnUrl();
            String redirectUrl = (returnUrl != null && !returnUrl.isBlank())
                    ? returnUrl : "/conversion/complete";

            log.info("[KeycloakCallback] 인증 완료 → 리다이렉트: feSessionId={}... redirectUrl={} cid={}",
                    feSession.getFeSessionId().substring(0, Math.min(8, feSession.getFeSessionId().length())),
                    redirectUrl, result.getCorrelationId());

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectUrl));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (PlatformException e) {
            log.error("[KeycloakCallback] 인증 처리 오류: code={} cid={}",
                    e.getErrorCode().getCode(), cid, e);
            brokerAuditLogService.recordFail(cid, "KEYCLOAK", "STANDARD_OIDC",
                    "keycloak", e.getErrorCode().getCode(), e.getMessage(), clientIp);
            return redirectToError(e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[KeycloakCallback] 예상치 못한 오류: cid={}", cid, e);
            brokerAuditLogService.recordFail(cid, "KEYCLOAK", "STANDARD_OIDC",
                    "keycloak", "INTERNAL_ERROR", e.getMessage(), clientIp);
            return redirectToError("INTERNAL_ERROR", "internal server error");
        }
    }

    // ── 에러 페이지 리다이렉트 ─────────────────────────────────────────────

    private ResponseEntity<Void> redirectToError(String code, String detail) {
        String errorUrl = "/error?code=" + code
                + (detail != null ? "&detail=" + encodeParam(detail) : "");
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(errorUrl));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }

    private String encodeParam(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
