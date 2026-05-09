package kr.go.smes.ido.broker;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.broker.dto.OidcCompleteRequest;
import kr.go.smes.ido.fe.session.FeSession;
import kr.go.smes.ido.fe.session.FeSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * q-sign → ido 내부 콜백 컨트롤러 (q-sign 모드 전용)
 *
 * <p>브로커 모드별 동작:
 * <ul>
 *   <li>{@code broker.mode=qsign}    : 활성 — q-sign이 인증 완료 후 이 엔드포인트 호출</li>
 *   <li>{@code broker.mode=keycloak} : 비활성 — Keycloak 콜백은 {@code KeycloakCallbackController}가 처리</li>
 * </ul>
 *
 * <p>q-sign 모드 흐름:
 * <pre>
 *   카카오 콜백 → q-sign (state 검증/token 교환/AuthResult 저장/Kafka 발행)
 *       → POST /api/internal/v1/oidc/complete
 *       → ido (FE 세션 발급 + feSessionId 쿠키 + redirectUrl 반환)
 *       → q-sign → 302 → returnUrl
 * </pre>
 *
 * <p>Keycloak 모드에서는 이 흐름이 더 이상 사용되지 않음.
 * Keycloak → GET /api/v1/broker/callback → KeycloakCallbackController가 직접 처리.
 *
 * <p>보안:
 * <ul>
 *   <li>X-Internal-Caller: q-sign — 내부 서비스 식별</li>
 *   <li>X-Internal-Sig: HMAC-SHA256 서명 (PoC: 간단한 서명)</li>
 *   <li>운영에서는 mTLS로 추가 보호</li>
 * </ul>
 *
 * <p>엔드포인트: POST /api/internal/v1/oidc/complete
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/v1/oidc")
@RequiredArgsConstructor
public class OidcCompleteController {

    private static final String COOKIE_NAME = "feSessionId";

    private final FeSessionService     feSessionService;
    private final InternalSigVerifier  internalSigVerifier;

    @Value("${ido.broker.mode:qsign}")
    private String brokerMode;

    /**
     * OIDC 인증 완료 후 FE 세션 발급 (q-sign 모드 전용)
     *
     * <p>q-sign이 AuthResult를 DB에 저장한 뒤 이 엔드포인트를 호출.
     * ido는:
     * <ol>
     *   <li>브로커 모드 확인 (keycloak 모드면 409 반환 — 잘못된 경로)</li>
     *   <li>FE 세션 생성 (Redis 저장, feSessionId 발급)</li>
     *   <li>feSessionId 쿠키를 응답 헤더에 포함</li>
     *   <li>최종 redirectUrl 반환 → q-sign이 브라우저를 리다이렉트</li>
     * </ol>
     *
     * @param internalSig X-Internal-Sig 헤더 (서명 검증)
     * @param caller      X-Internal-Caller 헤더
     * @param req         OidcCompleteRequest 바디
     * @return { "redirectUrl": "...", "feSessionId": "..." }
     */
    @PostMapping("/complete")
    public ResponseEntity<Map<String, String>> complete(
            @RequestHeader(value = "X-Internal-Sig", required = false) String internalSig,
            @RequestHeader(value = "X-Internal-Caller", required = false) String caller,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody OidcCompleteRequest req,
            HttpServletResponse response) {

        String cid = req.getCorrelationId() != null
                ? req.getCorrelationId()
                : (correlationId != null ? correlationId : CorrelationIdHolder.get());
        CorrelationIdHolder.set(cid);

        // ── P1-03: X-Internal-Sig HMAC-SHA256 수신 측 검증 ───────────────
        // 설계서 §9.4 — q-sign → ido 내부 서명 검증 (재계산 + ±60초 타임스탬프 유효성)
        // q-sign 모드에서만 서명 검증 수행 (keycloak 모드는 아래에서 409 반환)
        if (!"keycloak".equals(brokerMode)) {
            if (!internalSigVerifier.verify(internalSig, cid)) {
                log.warn("[OidcComplete] X-Internal-Sig 검증 실패: caller={} correlationId={}", caller, cid);
                throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, cid);
            }
            log.debug("[OidcComplete] X-Internal-Sig 검증 통과: caller={} correlationId={}", caller, cid);
        }

        // ── Keycloak 모드 확인 ────────────────────────────────────────────
        if ("keycloak".equals(brokerMode)) {
            log.warn("[OidcComplete] keycloak 모드에서 내부 콜백 수신 (잘못된 경로): caller={} correlationId={}",
                    caller, cid);
            return ResponseEntity.status(409)
                    .body(Map.of(
                            "error",   "BROKER_MODE_MISMATCH",
                            "message", "keycloak 모드에서는 /api/v1/broker/callback을 사용하세요"
                    ));
        }

        log.info("[OidcComplete] q-sign 내부 콜백 수신: authResultId={} caller={}",
                req.getAuthResultId(), caller);

        // ── returnUrl 화이트리스트 검증 ────────────────────────────────────
        String returnUrl = req.getReturnUrl();
        if (returnUrl != null && !returnUrl.isBlank()
                && !feSessionService.isValidReturnUrl(returnUrl)) {
            log.warn("[OidcComplete] returnUrl 화이트리스트 거부: {}", returnUrl);
            return ResponseEntity.badRequest()
                    .body(Map.of("redirectUrl", "/error?code=INVALID_RETURN_URL"));
        }

        // ── FE 세션 생성 ──────────────────────────────────────────────────
        // PoC: identifierHash를 qimUserId 대용으로 사용
        // 실운영: Q-IM 조회 후 실제 qimUserId 획득 필요
        FeSession session = feSessionService.create(
                req.getIdentifierHash(),
                req.getAuthResultId(),
                req.getAuthLevel(),
                returnUrl
        );

        // ── feSessionId 쿠키 발급 ─────────────────────────────────────────
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session.getFeSessionId())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // ── 최종 redirectUrl 결정 ─────────────────────────────────────────
        String redirectUrl = (returnUrl != null && !returnUrl.isBlank())
                ? returnUrl : "/conversion/complete";

        log.info("[OidcComplete] FE 세션 발급 완료: feSessionId={}... redirectUrl={} correlationId={}",
                session.getFeSessionId().substring(0, Math.min(8, session.getFeSessionId().length())),
                redirectUrl, cid);

        return ResponseEntity.ok(Map.of(
                "redirectUrl", redirectUrl,
                "feSessionId", session.getFeSessionId()   // q-sign 로깅용
        ));
    }
}
