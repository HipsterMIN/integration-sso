package io.github.hipstermin.idem.gate.keycloak;

import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Keycloak Authorization Code Callback 수신 컨트롤러
 *
 * <p>Keycloak 이 인증 완료 후 이 엔드포인트로 302 리다이렉트한다.
 * 카카오·네이버 등 소셜 IdP 가 직접 이 URI 를 호출하지 않는다.
 * 소셜 IdP → Keycloak 내부 처리 → Keycloak → q-sign callback 순서.
 *
 * <p><b>엔드포인트</b>: GET /api/v1/oidc/keycloak/callback
 *
 * <p>Keycloak Client 설정 필수:
 * <pre>
 *   Valid Redirect URIs: http://localhost:8081/api/v1/oidc/keycloak/callback
 * </pre>
 *
 * <p>보안 검증 (KeycloakCallbackService 위임):
 * <ul>
 *   <li>state 검증 — CSRF 방어 (Redis 1회 소비)</li>
 *   <li>nonce 검증 — ID Token replay attack 방어</li>
 *   <li>Keycloak JWKS RS256 서명 검증</li>
 *   <li>audience, exp 클레임 검증</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/oidc/keycloak")
@RequiredArgsConstructor
public class KeycloakCallbackController {

    private final KeycloakCallbackService callbackService;

    /**
     * Keycloak Authorization Code Callback
     *
     * <p>처리 완료 후 ido 가 지정한 최종 URL 로 302 리다이렉트.
     *
     * @param code             Keycloak authorization code (필수)
     * @param state            CSRF 방어 state (필수)
     * @param error            Keycloak/소셜 IdP 오류 코드 (선택 — 사용자 거부 등)
     * @param errorDescription 오류 설명 (선택)
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> keycloakCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription) {

        String correlationId = CorrelationIdHolder.get();
        log.info("[KeycloakCallback] 수신: state={} error={} correlationId={}",
                state, error, correlationId);

        try {
            // ── 사용자 거부 / IdP/Keycloak 오류 처리 ─────────────────────
            if (error != null) {
                log.warn("[KeycloakCallback] 인증 거부/오류: error={} description={}",
                        error, errorDescription);
                return redirect("/error?code=IDP_AUTH_DENIED&reason=" + encode(error));
            }

            // ── 필수 파라미터 누락 체크 ───────────────────────────────────
            if (code == null || state == null) {
                log.warn("[KeycloakCallback] code 또는 state 누락: code={} state={}", code, state);
                return redirect("/error?code=IDP_RESPONSE_INVALID");
            }

            // ── Callback 서비스에 위임 ────────────────────────────────────
            String redirectUrl = callbackService.handleCallback(code, state);
            log.info("[KeycloakCallback] 인증 완료 → redirect: {}", redirectUrl);
            return redirect(redirectUrl);

        } catch (PlatformException e) {
            log.error("[KeycloakCallback] 인증 실패: errorCode={} correlationId={}",
                    e.getErrorCode().getCode(), correlationId, e);
            return redirect("/error?code=" + e.getErrorCode().getCode()
                    + "&cid=" + correlationId);
        } catch (Exception e) {
            log.error("[KeycloakCallback] 예상치 못한 오류: correlationId={}", correlationId, e);
            return redirect("/error?code=INTERNAL_ERROR&cid=" + correlationId);
        } finally {
            CorrelationIdHolder.clear();
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────────

    private ResponseEntity<Void> redirect(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(url));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
