package kr.go.smes.ido.infrastructure;

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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Q-Authz 내부 API 클라이언트 — 연합 인가 역할 조회.
 *
 * <p>토큰(CAST/Handoff) 발급 시 사용자×대상기관의 유효 역할을 조회해
 * 토큰 {@code roles[]} 클레임에 임베드한다. 플랫폼은 굵은 RBAC 역할만 배송하고,
 * 세밀한 권한 해석/집행은 대상 기관(또는 후속 L3 PDP)의 책임이다.
 *
 * <h3>가용성 정책 — fail-open</h3>
 * <p>역할은 <b>가산적(additive)</b>이다. q-authz 장애·미설정 시 빈 리스트를 반환하여
 * SSO/Handoff 발급 자체는 계속되도록 한다(성숙도 L0 = 신원만 = 빈 역할과 동일).
 * 인가 강제(fail-closed)는 토큰을 소비하는 집행점(기관 PEP)의 책임이지,
 * 토큰 발급(인증 가용성)을 막아서는 안 된다.
 *
 * @see kr.go.smes.ido.infrastructure.QimClientImpl 동일 HTTP 클라이언트 패턴
 */
@Slf4j
@Component
public class QAuthzClient {

    private final RestTemplate qAuthzRestTemplate;

    @Value("${ido.q-authz.base-url:http://localhost:8086}")
    private String qAuthzBaseUrl;

    /**
     * q-authz 내부 API 키 — {@code IDO_QAUTHZ_INTERNAL_API_KEY} 환경변수 주입.
     * 미설정 시 q-authz가 401 → 빈 역할로 fail-open(경고 로그).
     */
    @Value("${ido.q-authz.internal-api-key:}")
    private String qAuthzInternalApiKey;

    public QAuthzClient(@Qualifier("qAuthzRestTemplate") RestTemplate qAuthzRestTemplate) {
        this.qAuthzRestTemplate = qAuthzRestTemplate;
    }

    /**
     * 사용자×기관의 유효 역할 코드 목록 조회.
     * {@code GET /api/v1/internal/authz/users/{qimUserId}/effective-roles?agencyCode=...}
     *
     * @return 역할 코드 목록(ACTIVE·만료 미경과). 장애·미부여 시 빈 리스트(절대 null 아님).
     */
    @SuppressWarnings("unchecked")
    public List<String> getEffectiveRoles(String qimUserId, String agencyCode, String correlationId) {
        if (qimUserId == null || qimUserId.isBlank() || agencyCode == null || agencyCode.isBlank()) {
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
            log.warn("[QAuthzClient] 역할 조회 비정상 응답 — 빈 역할 처리: status={} user={} agency={}",
                    response.getStatusCode(), qimUserId, agencyCode);
            return Collections.emptyList();
        } catch (Exception e) {
            // fail-open: 인가 서비스 장애가 토큰 발급(인증 가용성)을 막지 않도록 빈 역할 반환
            log.warn("[QAuthzClient] 역할 조회 실패 — fail-open(빈 역할): user={} agency={} err={}",
                    qimUserId, agencyCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    private HttpHeaders buildHeaders(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId != null ? correlationId : "");
        if (qAuthzInternalApiKey != null && !qAuthzInternalApiKey.isBlank()) {
            headers.set("X-Internal-Api-Key", qAuthzInternalApiKey);
        } else {
            log.warn("[QAuthzClient] IDO_QAUTHZ_INTERNAL_API_KEY 미설정 — q-authz 인증 헤더 누락. " +
                     "운영 배포 전 환경변수 설정 필요. correlationId={}", correlationId);
        }
        return headers;
    }
}
