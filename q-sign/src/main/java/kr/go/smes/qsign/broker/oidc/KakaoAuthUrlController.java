package kr.go.smes.qsign.broker.oidc;

import kr.go.smes.common.util.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * q-sign — ido 로부터 카카오 Authorization URL 발급 요청 수신
 *
 * <p>ido BrokerService 가 이 엔드포인트를 POST 로 호출.
 * q-sign 은 state/nonce 를 생성하고 Redis 에 저장한 후 Authorization URL 을 반환.
 *
 * <p>엔드포인트: POST /api/v1/oidc/kakao/auth-url
 * 호출자: ido (내부 서비스, X-Internal-Caller: ido)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/oidc/kakao")
@RequiredArgsConstructor
public class KakaoAuthUrlController {

    private final KakaoOidcBrokerService brokerService;

    /**
     * 카카오 Authorization URL 발급
     *
     * @param body correlationId, returnUrl, requestedLevel
     * @return { "authorizationUrl": "https://kauth.kakao.com/oauth/authorize?..." }
     */
    @PostMapping("/auth-url")
    public ResponseEntity<Map<String, String>> issueAuthorizationUrl(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Internal-Caller", required = false) String caller,
            @RequestBody Map<String, String> body) {

        String cid = body.getOrDefault("correlationId",
                correlationId != null ? correlationId : CorrelationIdHolder.get());
        CorrelationIdHolder.set(cid);

        String returnUrl       = body.getOrDefault("returnUrl", "");
        String requestedLevel  = body.getOrDefault("requestedLevel", "L1");

        log.info("[KakaoAuthUrl] Authorization URL 발급 요청: correlationId={} caller={}", cid, caller);

        String authorizationUrl = brokerService.buildAuthorizationUrl(cid, returnUrl, requestedLevel);

        return ResponseEntity.ok(Map.of("authorizationUrl", authorizationUrl));
    }
}
