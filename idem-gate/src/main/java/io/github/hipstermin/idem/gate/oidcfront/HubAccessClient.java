package io.github.hipstermin.idem.gate.oidcfront;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * gate → hub 접근 판정 호출 (S6). 서명은 hub {@code InternalSigVerifier} 가 검증하는 {@code HMAC(cid:epoch)}.
 * hub 가 닿지 않거나 200 이 아니면 거부(E-IDO-116) — 표준 OIDC 경로에서도 정책 우회는 없다.
 */
@Slf4j
@Component
public class HubAccessClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${idem.gate.hub.base-url:http://localhost:8083}")
    private String idoBaseUrl;

    @Value("${idem.gate.hub.internal-sig-secret:}")
    private String internalSigSecret;

    public HubAccessClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public AccessDecision evaluate(String clientId, String sub, String identityProvider, String acr, String sid, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("sub", sub);
        body.put("identityProvider", identityProvider);
        body.put("acr", acr);
        body.put("sid", sid);
        body.put("correlationId", correlationId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);
        headers.set("X-Internal-Caller", "idem-gate");
        headers.set("X-Internal-Sig", sign(correlationId));
        try {
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(idoBaseUrl + "/api/internal/v1/oidc-rp/access"),
                    HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.error("[OIDC-FRONT] hub 판정 응답 이상: status={} cid={}", resp.getStatusCode(), correlationId);
                return AccessDecision.denied("E-IDO-116", "정책 판정 서비스 응답 이상");
            }
            return objectMapper.readValue(resp.getBody(), AccessDecision.class);
        } catch (Exception e) {
            log.error("[OIDC-FRONT] hub 판정 호출 실패 → 거부: cid={} err={}", correlationId, e.getMessage());
            return AccessDecision.denied("E-IDO-116", "정책 판정 서비스에 닿을 수 없음");
        }
    }

    String sign(String correlationId) {
        long epochSeconds = System.currentTimeMillis() / 1000L;
        return CryptoProviders.current().hmacSha256Hex(internalSigSecret.getBytes(StandardCharsets.UTF_8), correlationId + ":" + epochSeconds);
    }
}
