package io.github.hipstermin.idem.gate.oidcfront;

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

/** gate → hub (S6 PR-2): IdP 세션 종료 통지 — hub 가 그 Keycloak 세션에서 난 FE 세션을 만료한다. 실패는 로그(비치명적). */
@Slf4j
@Component
public class HubSessionClient {

    private final RestTemplate restTemplate;

    @Value("${idem.gate.hub.base-url:http://localhost:8083}")
    private String idoBaseUrl;

    @Value("${idem.gate.hub.internal-sig-secret:}")
    private String internalSigSecret;

    public HubSessionClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** @return hub 가 만료한 FE 세션 수, 실패면 -1 */
    public int notifyIdpLogout(String sub, String sid, String reason, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (sub != null) body.put("sub", sub);
        if (sid != null) body.put("sid", sid);
        body.put("reason", reason);
        body.put("correlationId", correlationId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Correlation-Id", correlationId);
        h.set("X-Internal-Caller", "idem-gate");
        h.set("X-Internal-Sig", CryptoProviders.current().hmacSha256Hex(internalSigSecret.getBytes(StandardCharsets.UTF_8),
                correlationId + ":" + (System.currentTimeMillis() / 1000L)));
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(URI.create(idoBaseUrl + "/api/internal/v1/session/idp-logout"),
                    HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
            Object n = resp.getBody() != null ? resp.getBody().get("expired") : null;
            return n instanceof Number num ? num.intValue() : 0;
        } catch (Exception e) {
            log.warn("[OIDC-FRONT] hub IdP 로그아웃 통지 실패 (비치명적): cid={} err={}", correlationId, e.getMessage());
            return -1;
        }
    }
}
