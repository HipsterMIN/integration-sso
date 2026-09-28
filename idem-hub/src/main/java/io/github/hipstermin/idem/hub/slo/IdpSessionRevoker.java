package io.github.hipstermin.idem.hub.slo;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 * 1.1: gate 내부 API 로 Keycloak 세션을 끊는 호출 하나 — {@code SloServiceImpl}(첫 시도)와 {@code SloIdpLogoutRetryRelay}(재시도)가 같이 쓴다.
 *
 * <p>{@code POST {gate}/api/v1/internal/session/logout} — X-Internal-Sig. 결과 판정:
 * <ul>
 *   <li>2xx 이고 {@code X-Idp-Logout-Outcome} 이 FAILED 가 아니면 성공 (REVOKED_* / NOT_FOUND / SKIPPED — NOT_FOUND 는 이미 없는 세션이라 성공으로 본다)</li>
 *   <li>비 2xx(gate 는 1.1 부터 Keycloak 실패를 502 로 낸다)·예외·FAILED 헤더 → 실패 ({@link Result#success()} false)</li>
 * </ul>
 */
@Slf4j
@Component
public class IdpSessionRevoker {

    public static final String OUTCOME_HEADER = "X-Idp-Logout-Outcome";

    public record Result(boolean success, String outcome, String error) {
        public static Result ok(String outcome) { return new Result(true, outcome, null); }
        public static Result fail(String outcome, String error) { return new Result(false, outcome, error); }
    }

    private final RestTemplate restTemplate;

    @Value("${idem.hub.gate.base-url:http://localhost:8081}")
    private String gateBaseUrl;

    @Value("${idem.hub.gate.internal-sig-secret:}")
    private String internalSigSecret;

    public IdpSessionRevoker(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Result revoke(String idpSub, String idpSid, String qimUserId, String correlationId) {
        try {
            String url = gateBaseUrl + "/api/v1/internal/session/logout";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Correlation-Id",  correlationId);
            headers.set("X-Internal-Caller", "idem-hub");
            headers.set("X-Internal-Sig",    buildInternalSig(correlationId));

            Map<String, String> body = new HashMap<>();
            body.put("correlationId", correlationId);
            body.put("qimUserId", qimUserId);
            if (idpSub != null) body.put("sub", idpSub);
            if (idpSid != null) body.put("sid", idpSid);

            ResponseEntity<Void> resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
            String outcome = resp.getHeaders().getFirst(OUTCOME_HEADER);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                return Result.fail(outcome, "gate 응답 " + resp.getStatusCode());
            }
            if ("FAILED".equalsIgnoreCase(outcome)) {
                return Result.fail(outcome, "Keycloak 세션 종료 실패(outcome=FAILED)");
            }
            return Result.ok(outcome != null ? outcome : "UNKNOWN");
        } catch (Exception e) {
            // gate 가 1.1 부터 502 를 내면 RestTemplate 기본 핸들러가 예외로 올린다 — 여기서 실패로 판정
            return Result.fail(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** payload = "{correlationId}:{epochSeconds}", HMAC-SHA256 Hex. 비밀이 비어 있으면 예외(D2 fail-secure — 무서명으로 숨기지 않는다). */
    String buildInternalSig(String correlationId) {
        if (internalSigSecret == null || internalSigSecret.isBlank()) {
            throw new IllegalStateException("IDEM_HUB_INTERNAL_SIG_SECRET 미설정 — SLO 내부 서명 불가: correlationId=" + correlationId);
        }
        long epochSeconds = System.currentTimeMillis() / 1000L;
        return CryptoProviders.current().hmacSha256Hex(internalSigSecret.getBytes(StandardCharsets.UTF_8), correlationId + ":" + epochSeconds);
    }
}
