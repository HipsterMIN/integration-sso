package kr.go.smes.qsign.broker.oidc;

import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.util.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 카카오 OIDC Authorization Code Flow — Q-Sign 콜백 수신 컨트롤러
 *
 * <p>엔드포인트:
 * <ul>
 *   <li>GET /api/v1/oidc/kakao/callback — 카카오가 직접 호출하는 redirect_uri</li>
 * </ul>
 *
 * <p>Authorization URL 발급(1단계)은 ido BrokerController 가 담당.
 * Q-Sign 은 카카오로부터 code+state 를 수신하는 역할만 담당.
 *
 * <p>보안 고려:
 * <ul>
 *   <li>state 검증 — CSRF 방어 (OidcStateStore)</li>
 *   <li>nonce 검증 — ID Token replay attack 방어</li>
 *   <li>JWKS RS256 서명 검증</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/oidc/kakao")
@RequiredArgsConstructor
public class KakaoOidcBrokerController {

    private final KakaoOidcBrokerService brokerService;

    /**
     * 카카오 Authorization Code Callback
     *
     * <p>카카오 로그인 완료 후 카카오가 이 엔드포인트로 리다이렉트.
     * 처리 완료 후 기관 returnUrl 로 302 리다이렉트.
     *
     * @param code  카카오 authorization code
     * @param state CSRF 방어용 state (OidcStateStore 에 저장된 값과 일치해야 함)
     * @param error 카카오 인증 거부/취소 시 전달되는 에러 코드
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> kakaoCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription) {

        String correlationId = CorrelationIdHolder.get();
        log.info("[KakaoCallback] 수신: state={} error={} correlationId={}",
                state, error, correlationId);

        // ── 사용자 거부 / 카카오 오류 처리 ───────────────────────────────
        if (error != null) {
            log.warn("[KakaoCallback] 카카오 인증 거부/오류: error={} desc={}", error, errorDescription);
            return redirect("/error?code=IDP_AUTH_DENIED&reason=" + encodeParam(error));
        }

        if (code == null || state == null) {
            log.warn("[KakaoCallback] code 또는 state 누락");
            return redirect("/error?code=IDP_RESPONSE_INVALID");
        }

        try {
            // ── 브로커 서비스에 위임 ──────────────────────────────────────
            String redirectUrl = brokerService.handleCallback(code, state);
            log.info("[KakaoCallback] 인증 완료 → redirect: {}", redirectUrl);
            return redirect(redirectUrl);

        } catch (PlatformException e) {
            log.error("[KakaoCallback] 인증 실패: code={} correlationId={}",
                    e.getErrorCode().getCode(), correlationId, e);
            return redirect("/error?code=" + e.getErrorCode().getCode()
                    + "&cid=" + correlationId);
        } catch (Exception e) {
            log.error("[KakaoCallback] 예상치 못한 오류: correlationId={}", correlationId, e);
            return redirect("/error?code=INTERNAL_ERROR&cid=" + correlationId);
        } finally {
            CorrelationIdHolder.clear();
        }
    }

    // ── 내부 유틸 ──────────────────────────────────────────────────────────

    private ResponseEntity<Void> redirect(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(url));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }

    private String encodeParam(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
