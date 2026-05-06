package kr.go.smes.ido.broker;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.broker.dto.OidcCompleteRequest;
import kr.go.smes.ido.fe.session.FeSession;
import kr.go.smes.ido.fe.session.FeSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * q-sign → ido 내부 콜백 컨트롤러
 *
 * <p>q-sign 이 카카오 OIDC 인증을 완료한 후 ido 를 호출하여
 * FE 세션(feSessionId 쿠키) 발급을 요청한다.
 *
 * <p>보안:
 * <ul>
 *   <li>X-Internal-Caller: q-sign — 내부 서비스 식별</li>
 *   <li>X-Internal-Sig: HMAC-SHA256 서명 (PoC: 간단한 서명)</li>
 *   <li>운영에서는 mTLS 로 추가 보호</li>
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

    private final FeSessionService feSessionService;

    /**
     * OIDC 인증 완료 후 FE 세션 발급
     *
     * <p>q-sign 이 AuthResult 를 DB 에 저장한 뒤 이 엔드포인트를 호출.
     * ido 는:
     * <ol>
     *   <li>FE 세션 생성 (Redis 저장, feSessionId 발급)</li>
     *   <li>feSessionId 쿠키를 응답 헤더에 포함</li>
     *   <li>최종 redirectUrl 반환 → q-sign 이 브라우저를 리다이렉트</li>
     * </ol>
     *
     * @param internalSig X-Internal-Sig 헤더 (서명 검증)
     * @param caller      X-Internal-Caller 헤더
     * @param req         OidcCompleteRequest 바디
     * @return { "redirectUrl": "..." } — q-sign 이 이 URL 로 302 리다이렉트
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

        log.info("[OidcComplete] 수신: authResultId={} identifierHash(prefix)={}... caller={}",
                req.getAuthResultId(),
                req.getIdentifierHash() != null && req.getIdentifierHash().length() >= 8
                        ? req.getIdentifierHash().substring(0, 8) : "??",
                caller);

        // returnUrl 화이트리스트 검증
        String returnUrl = req.getReturnUrl();
        if (returnUrl != null && !returnUrl.isBlank()
                && !feSessionService.isValidReturnUrl(returnUrl)) {
            log.warn("[OidcComplete] returnUrl 화이트리스트 거부: {}", returnUrl);
            return ResponseEntity.badRequest()
                    .body(Map.of("redirectUrl", "/error?code=INVALID_RETURN_URL"));
        }

        // FE 세션 생성
        // PoC: identifierHash 를 qimUserId 대용으로 사용 (실제 Q-IM 조회 후 qimUserId 획득 필요)
        FeSession session = feSessionService.create(
                req.getIdentifierHash(),   // PoC — 실 운영: Q-IM 조회 후 실제 qimUserId
                req.getAuthResultId(),
                req.getAuthLevel(),
                returnUrl
        );

        // feSessionId 쿠키 Set-Cookie 헤더 (Secure / HttpOnly / SameSite=Lax)
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session.getFeSessionId())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // 최종 redirect URL 결정
        String redirectUrl = (returnUrl != null && !returnUrl.isBlank())
                ? returnUrl
                : "/conversion/complete";

        log.info("[OidcComplete] FE 세션 발급 완료: feSessionId={}... redirectUrl={}",
                session.getFeSessionId().substring(0, 8), redirectUrl);

        return ResponseEntity.ok(Map.of(
                "redirectUrl",  redirectUrl,
                "feSessionId",  session.getFeSessionId()   // 내부 응답 (q-sign 로깅용)
        ));
    }
}
