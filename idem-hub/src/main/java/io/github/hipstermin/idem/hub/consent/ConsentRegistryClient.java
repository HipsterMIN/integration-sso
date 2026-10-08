package io.github.hipstermin.idem.hub.consent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 1.1 동의 카탈로그 — registry 내부 API({@code /api/v1/internal/consent-versions}, {@code …/users/{id}/consents}) 클라이언트.
 * 호출 규약은 {@link io.github.hipstermin.idem.hub.infrastructure.QimClientImpl} 과 같다(내부 API 키·상관관계 ID, 장애는 E-IDO-106).
 * registry 의 4xx(검증 오류 E-IM-208 등)는 그 코드·메시지 그대로 올린다.
 */
@Slf4j
@Component
public class ConsentRegistryClient {

    private final RestTemplate rest;
    private final ObjectMapper om;
    private final String baseUrl;
    private final String apiKey;

    public ConsentRegistryClient(@Qualifier("qimRestTemplate") RestTemplate rest, ObjectMapper om,
                                 @Value("${idem.hub.registry.base-url:http://localhost:8082}") String baseUrl,
                                 @Value("${idem.hub.registry.internal-api-key:}") String apiKey) {
        this.rest = rest;
        this.om = om;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
    }

    /** 서비스가 보는 카탈로그(플랫폼 공통 + 서비스 전용, ACTIVE) */
    public List<ConsentItem> catalog(String serviceCode, String cid) {
        return list(UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/internal/consent-versions").queryParam("catalog", "true")
                .queryParam("serviceCode", serviceCode).build().toUri(), cid);
    }

    /** 한 범위의 버전 목록 — serviceCode null 이면 플랫폼 공통 */
    public List<ConsentItem> listVersions(String serviceCode, boolean includeInactive, String cid) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/internal/consent-versions").queryParam("includeInactive", includeInactive);
        if (serviceCode != null && !serviceCode.isBlank()) b.queryParam("serviceCode", serviceCode);
        return list(b.build().toUri(), cid);
    }

    /** 사용자가 아직 동의하지 않은 카탈로그 항목 */
    public List<ConsentItem> missing(String qimUserId, String serviceCode, String cid) {
        return list(UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/internal/users/" + qimUserId + "/consents/missing")
                .queryParam("serviceCode", serviceCode).build().toUri(), cid);
    }

    public ConsentItem publish(String serviceCode, String consentType, String versionTag, String title, String contentUrl, Boolean required,
                               Instant effectiveAt, String cid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceCode", serviceCode);
        body.put("consentType", consentType);
        body.put("versionTag", versionTag);
        body.put("title", title);
        body.put("contentUrl", contentUrl);
        body.put("required", required);
        body.put("effectiveAt", effectiveAt);
        return one(URI.create(baseUrl + "/api/v1/internal/consent-versions"), HttpMethod.POST, body, cid);
    }

    public ConsentItem retire(String versionId, String cid) {
        return one(URI.create(baseUrl + "/api/v1/internal/consent-versions/" + versionId + "/retire"), HttpMethod.POST, null, cid);
    }

    /** 동의 기록 — agreedVia 는 경로 표시(LOGIN_FRONT:<service>), IP 는 감사·증빙용 */
    public void agree(String qimUserId, String versionId, String consentType, String agreedVia, String clientIp, String cid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionId", versionId);
        body.put("consentType", consentType);
        body.put("agreedVia", agreedVia);
        body.put("clientIp", clientIp);
        exchange(URI.create(baseUrl + "/api/v1/internal/users/" + qimUserId + "/consents"), HttpMethod.POST, body, cid);
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private List<ConsentItem> list(URI uri, String cid) {
        String body = exchange(uri, HttpMethod.GET, null, cid);
        try {
            return body == null || body.isBlank() ? List.of() : om.readValue(body, new TypeReference<List<ConsentItem>>() {});
        } catch (java.io.IOException e) {
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, cid, "registry 동의 응답 해석 실패: " + e.getMessage());
        }
    }

    private ConsentItem one(URI uri, HttpMethod method, Object body, String cid) {
        String res = exchange(uri, method, body, cid);
        try {
            return om.readValue(res, ConsentItem.class);
        } catch (java.io.IOException | NullPointerException e) {
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, cid, "registry 동의 응답 해석 실패");
        }
    }

    private String exchange(URI uri, HttpMethod method, Object body, String cid) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Correlation-Id", cid != null ? cid : "");
        if (apiKey != null && !apiKey.isBlank()) h.set("X-Internal-Api-Key", apiKey);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        String json = null;
        if (body != null) {
            h.setContentType(MediaType.APPLICATION_JSON);
            try { json = om.writeValueAsString(body); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        try {
            ResponseEntity<String> r = rest.exchange(uri, method, new HttpEntity<>(json, h), String.class);
            return r.getBody();
        } catch (HttpStatusCodeException e) {
            // registry 의 검증 오류(E-IM-208 등)는 그대로 — 관리자가 무엇이 틀렸는지 본다
            String detail = e.getResponseBodyAsString();
            log.warn("[ConsentClient] registry {} {} → {}: {}", method, uri.getPath(), e.getStatusCode().value(), detail);
            if (e.getStatusCode().is4xxClientError()) {
                throw new PlatformException(PlatformErrorCode.IM_CONSENT_VERSION_INVALID, cid, "registry: " + e.getStatusCode().value() + " " + abbreviate(detail));
            }
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, cid, "registry 동의 API " + e.getStatusCode().value());
        } catch (RestClientException e) {
            log.error("[ConsentClient] registry 동의 API 장애: {} {} : {}", method, uri.getPath(), e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, cid, "registry 동의 API 연결 실패");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
