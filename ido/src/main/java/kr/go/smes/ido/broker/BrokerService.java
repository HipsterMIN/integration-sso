package kr.go.smes.ido.broker;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * ido BrokerService — OIDC Authorization URL 발급 요청
 *
 * <p>ido 는 FE(React)로부터 "카카오 로그인 시작" 요청을 받아
 * q-sign 에 Authorization URL 발급을 위임한다.
 *
 * <p>흐름:
 * <pre>
 *   FE → GET /api/v1/broker/kakao/authorize?returnUrl=...
 *       → BrokerController → BrokerService
 *       → q-sign POST /api/v1/oidc/kakao/auth-url  (state/nonce 생성 + Redis 저장)
 *       ← { authorizationUrl: "https://kauth.kakao.com/..." }
 *       → 302 → 카카오 로그인 페이지
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerService {

    private final RestTemplate restTemplate;

    @Value("${ido.qsign.base-url:http://localhost:8081}")
    private String qsignBaseUrl;

    @Value("${ido.qsign.internal-sig-ttl-seconds:60}")
    private int internalSigTtl;

    /**
     * q-sign 에 카카오 Authorization URL 발급 요청
     *
     * @param correlationId  흐름 추적 ID
     * @param returnUrl      인증 완료 후 이동할 기관 URL
     * @param requestedLevel 요청 인증 수준 (L1)
     * @return 브라우저가 리다이렉트해야 할 카카오 Authorization URL
     */
    @SuppressWarnings("unchecked")
    public String buildKakaoAuthorizationUrl(String correlationId,
                                              String returnUrl,
                                              String requestedLevel) {
        String url = qsignBaseUrl + "/api/v1/oidc/kakao/auth-url";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);
        headers.set("X-Internal-Caller", "ido");
        headers.set("X-Internal-Sig", buildSig(correlationId));

        Map<String, String> body = Map.of(
                "correlationId",   correlationId,
                "returnUrl",       returnUrl != null ? returnUrl : "",
                "requestedLevel",  requestedLevel != null ? requestedLevel : "L1"
        );

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            if (resp.getBody() == null || !resp.getBody().containsKey("authorizationUrl")) {
                throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                        "q-sign 응답에 authorizationUrl 없음");
            }
            String authUrl = (String) resp.getBody().get("authorizationUrl");
            log.info("[BrokerService] Authorization URL 수신: correlationId={}", correlationId);
            return authUrl;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BrokerService] q-sign 호출 실패: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId, e);
        }
    }

    private String buildSig(String correlationId) {
        // PoC 수준 — 실운영: HMAC-SHA256(correlationId + timestamp, secret)
        return "sig-" + correlationId.replace("-", "").substring(0, 8);
    }
}
