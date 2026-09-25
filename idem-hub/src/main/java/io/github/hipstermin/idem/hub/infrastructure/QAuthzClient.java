package io.github.hipstermin.idem.hub.infrastructure;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Q-Authz 내부 API 클라이언트 — 연합 인가 역할 조회.
 *
 * <p>토큰(CAST/Handoff) 발급 시 사용자×대상기관의 유효 역할을 조회해
 * 토큰 {@code roles[]} 클레임에 임베드한다. 플랫폼은 굵은 RBAC 역할만 배송하고,
 * 세밀한 권한 해석/집행은 대상 기관(또는 후속 L3 PDP)의 책임이다.
 *
 * <h3>가용성 정책 — D2 fail-secure</h3>
 * <p>{@code idem.hub.authz.enabled=true}(기본) 이면 조회 실패는 <b>거부</b>({@link PlatformErrorCode#IDEM_HUB_AUTHZ_UNAVAILABLE}, 503) 다 —
 * 종전에는 장애 시 빈 역할을 돌려 "역할 없음" 과 "인가 서비스 다운" 을 소비측이 구분할 수 없었다.
 * Idem IM(authz) 없이 SSO 만 쓰는 설치는 {@code idem.hub.authz.enabled=false} 로 <b>명시</b>해야 하며, 그때는 항상 빈 역할(L0)이다.
 *
 * @see io.github.hipstermin.idem.hub.infrastructure.QimClientImpl 동일 HTTP 클라이언트 패턴
 */
@Slf4j
@Component
public class QAuthzClient {

    private final RestTemplate qAuthzRestTemplate;

    @Value("${idem.hub.authz.base-url:http://localhost:8086}")
    private String qAuthzBaseUrl;

    /**
     * q-authz 내부 API 키 — {@code IDEM_HUB_AUTHZ_INTERNAL_API_KEY} 환경변수 주입.
     * D2: enabled 인데 비어 있으면 부팅 차단 (allow-empty-api-key 는 로컬·테스트 전용).
     */
    @Value("${idem.hub.authz.internal-api-key:}")
    private String qAuthzInternalApiKey;

    /** false 면 authz 를 호출하지 않고 항상 빈 역할 — SSO 단독 설치용 명시적 스위치 (조용한 폴백 아님) */
    @Value("${idem.hub.authz.enabled:true}")
    private boolean enabled = true;

    /** D2: enabled 인데 내부 API 키가 비면 부팅 차단 (로컬·테스트에서만 true) */
    @Value("${idem.hub.authz.allow-empty-api-key:false}")
    private boolean allowEmptyApiKey;

    @jakarta.annotation.PostConstruct
    void validateConfiguration() {
        if (enabled && (qAuthzInternalApiKey == null || qAuthzInternalApiKey.isBlank()) && !allowEmptyApiKey) {
            throw new IllegalStateException("[QAuthzClient] idem.hub.authz.enabled=true 인데 IDEM_HUB_AUTHZ_INTERNAL_API_KEY 가 비어 있습니다. "
                    + "키를 주입하거나, authz 없는 SSO 단독 설치면 idem.hub.authz.enabled=false 로 명시하십시오. "
                    + "로컬·테스트에서만 idem.hub.authz.allow-empty-api-key=true 로 우회 가능합니다.");
        }
        if (!enabled) {
            log.warn("[QAuthzClient] idem.hub.authz.enabled=false — 연합 인가 조회 없이 항상 빈 역할(L0) 로 동작합니다");
        }
    }

    public QAuthzClient(@Qualifier("qAuthzRestTemplate") RestTemplate qAuthzRestTemplate) {
        this.qAuthzRestTemplate = qAuthzRestTemplate;
    }

    /**
     * 사용자×기관의 유효 역할 코드 목록 조회.
     * {@code GET /api/v1/internal/authz/users/{qimUserId}/effective-roles?agencyCode=...}
     *
     * @return 역할 코드 목록(ACTIVE·만료 미경과). 미부여·authz 비활성 시 빈 리스트(절대 null 아님).
     * @throws PlatformException {@link PlatformErrorCode#IDEM_HUB_AUTHZ_UNAVAILABLE} — authz 장애·비정상 응답 (D2 fail-secure)
     */
    @SuppressWarnings("unchecked")
    public List<String> getEffectiveRoles(String qimUserId, String agencyCode, String correlationId) {
        if (qimUserId == null || qimUserId.isBlank() || agencyCode == null || agencyCode.isBlank()) {
            return Collections.emptyList();
        }
        if (!enabled) {
            return Collections.emptyList();
        }
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(qAuthzBaseUrl)
                    .path("/api/v1/internal/authz/users/{qimUserId}/effective-roles")
                    .queryParam("agencyCode", agencyCode)
                    .buildAndExpand(qimUserId)
                    .toUriString();

            ResponseEntity<Map> response = qAuthzRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(correlationId)), Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object rolesObj = response.getBody().get("roles");
                if (rolesObj instanceof List<?> list) {
                    return list.stream().filter(String.class::isInstance)
                            .map(String.class::cast).toList();
                }
            }
            log.error("[QAuthzClient] 역할 조회 비정상 응답 → 거부: status={} user={} agency={}",
                    response.getStatusCode(), qimUserId, agencyCode);
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "authz 비정상 응답: " + response.getStatusCode());
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            // D2 fail-secure: 인가 서비스 장애를 "역할 없음" 으로 위장하지 않는다
            log.error("[QAuthzClient] 역할 조회 실패 → 거부: user={} agency={} err={}", qimUserId, agencyCode, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "authz 조회 실패: " + e.getMessage());
        }
    }

    /**
     * S8-b: 할당 여부 + 유효 역할을 한 번에 — {@code GET /api/v1/internal/authz/users/{id}/access?agencyCode=}.
     * 장애·비정상 응답은 {@link PlatformErrorCode#IDEM_HUB_AUTHZ_UNAVAILABLE} (fail-secure).
     * {@code idem.hub.authz.enabled=false} 면 {@link ServiceAccess#disabled()} — 할당 필수 정책은 그 자체로 거부된다.
     */
    @SuppressWarnings("unchecked")
    public ServiceAccess getServiceAccess(String qimUserId, String agencyCode, String correlationId) {
        if (!enabled) {
            return ServiceAccess.disabled();
        }
        if (qimUserId == null || qimUserId.isBlank() || agencyCode == null || agencyCode.isBlank()) {
            return new ServiceAccess(true, false, null, List.of());
        }
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(qAuthzBaseUrl)
                    .path("/api/v1/internal/authz/users/{qimUserId}/access")
                    .queryParam("agencyCode", agencyCode)
                    .buildAndExpand(qimUserId)
                    .toUriString();
            ResponseEntity<Map> response = qAuthzRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(correlationId)), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<?, ?> body = response.getBody();
                Object rolesObj = body.get("roles");
                List<String> roles = rolesObj instanceof List<?> list
                        ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                        : List.of();
                boolean assigned = Boolean.TRUE.equals(body.get("assigned"));
                Object src = body.get("assignmentSource");
                return new ServiceAccess(true, assigned, src instanceof String st ? st : null, roles);
            }
            log.error("[QAuthzClient] 접근 정보 조회 비정상 응답 → 거부: status={} user={} agency={}",
                    response.getStatusCode(), qimUserId, agencyCode);
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "authz 비정상 응답: " + response.getStatusCode());
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[QAuthzClient] 접근 정보 조회 실패 → 거부: user={} agency={} err={}", qimUserId, agencyCode, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "authz 조회 실패: " + e.getMessage());
        }
    }

    private HttpHeaders buildHeaders(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId != null ? correlationId : "");
        if (qAuthzInternalApiKey != null && !qAuthzInternalApiKey.isBlank()) {
            headers.set("X-Internal-Api-Key", qAuthzInternalApiKey);
        }
        return headers;
    }
}
