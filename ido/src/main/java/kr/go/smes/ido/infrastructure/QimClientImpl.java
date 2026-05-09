package kr.go.smes.ido.infrastructure;

import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.HashMap;

/**
 * Q-IM HTTP 클라이언트 구현체 (v2.0 — Production)
 *
 * <p>v2.0 추가:
 * <ul>
 *   <li>{@link #getDi(String, String, String)} — 기관별 DI 조회/생성</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimClientImpl implements QimClient {

    private final RestTemplate qimRestTemplate;

    @Value("${ido.qim.base-url:http://localhost:8082}")
    private String qimBaseUrl;

    // ── getUserStatus ─────────────────────────────────────────────────────

    @Override
    public UserStatus getUserStatus(String qimUserId, String correlationId) {
        log.debug("[QimClient] 상태 조회: qimUserId={}", qimUserId);
        try {
            HttpHeaders headers = buildHeaders(correlationId);
            String url = qimBaseUrl + "/api/v1/users/" + qimUserId;
            ResponseEntity<Map> response = qimRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (response.getBody() == null) {
                throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
            }
            String statusStr = (String) response.getBody().get("status");
            if (statusStr == null) {
                throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
            }
            return parseUserStatus(statusStr);
        } catch (PlatformException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("[QimClient] 상태 조회 네트워크 오류: {}", e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        } catch (Exception e) {
            log.error("[QimClient] 상태 조회 예외: qimUserId={}", qimUserId, e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
    }

    // ── getUserById ────────────────────────────────────────────────────────

    /**
     * GAP-QIM-01: 사용자 전체 정보 조회 (needsSync=true Selective Pull)
     * GET /api/v1/internal/users/{qimUserId}
     *
     * <p>needsSync=true 이벤트 수신 시 상태(status)뿐만 아니라
     * 프로필(nameMasked, mobileMasked 등) 전체를 pull하여 캐시를 완전 갱신한다.
     * Q-IM 서버 장애 시 null 반환 → 호출 측에서 캐시 무효화(invalidate)로 대체.
     *
     * @return UserResponse 필드 맵 또는 null (장애 시)
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserById(String qimUserId, String correlationId) {
        log.debug("[QimClient] 사용자 전체 조회: qimUserId={}", qimUserId);
        try {
            HttpHeaders headers = buildHeaders(correlationId);
            String url = qimBaseUrl + "/api/v1/internal/users/" + qimUserId;
            ResponseEntity<Map> response = qimRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (Map<String, Object>) response.getBody();
            }
            log.warn("[QimClient] 사용자 전체 조회 비정상 응답: status={} qimUserId={}",
                    response.getStatusCode(), qimUserId);
            return null;
        } catch (RestClientException e) {
            log.warn("[QimClient] 사용자 전체 조회 네트워크 오류: qimUserId={} err={}", qimUserId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[QimClient] 사용자 전체 조회 예외: qimUserId={} err={}", qimUserId, e.getMessage());
            return null;
        }
    }

    // ── getDi ─────────────────────────────────────────────────────────────

    /**
     * 기관별 DI 조회/생성
     * GET /api/v1/internal/users/{qimUserId}/di?agencyCode={agencyCode}
     *
     * <p>실패 시 null 반환 → PolicyEngineImpl이 HMAC fallback 처리
     */
    @Override
    public String getDi(String qimUserId, String agencyCode, String correlationId) {
        log.debug("[QimClient] DI 조회: qimUserId={} agencyCode={}", qimUserId, agencyCode);
        try {
            HttpHeaders headers = buildHeaders(correlationId);
            String url = qimBaseUrl + "/api/v1/internal/users/" + qimUserId + "/di?agencyCode=" + agencyCode;
            ResponseEntity<Map> response = qimRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("di");
            }
            return null;
        } catch (Exception e) {
            log.warn("[QimClient] DI 조회 실패 — HMAC fallback 사용: agencyCode={} err={}", agencyCode, e.getMessage());
            return null;
        }
    }

    // ── private ────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId != null ? correlationId : "");
        headers.set("X-Internal-Api-Key", "ido-internal");
        return headers;
    }

    private UserStatus parseUserStatus(String statusStr) {
        try {
            return UserStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            log.warn("[QimClient] 알 수 없는 UserStatus '{}' → SUSPENDED 처리", statusStr);
            return UserStatus.SUSPENDED;
        }
    }
}
