package kr.go.smes.ido.infrastructure;

import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Q-IM HTTP 클라이언트 구현체 (IdO → Q-IM 조회)
 * 설계서 §10.4 / §11.5.4 참조
 *
 * <p>Cache miss 시 Q-IM 직접 조회 → 조회 실패 시 안전 우선 원칙으로 Handoff 거부 (E-IDO-106)
 * <p>GET /api/v1/users/{qimUserId} 엔드포인트 호출
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimClientImpl implements QimClient {

    private final RestTemplate qimRestTemplate;

    @Value("${ido.qim.base-url:http://localhost:8082}")
    private String qimBaseUrl;

    @Value("${ido.qim.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${ido.qim.read-timeout-ms:5000}")
    private int readTimeoutMs;

    /**
     * Q-IM 사용자 상태 조회
     * 실패 시 안전 우선 원칙(§11.5.4): PlatformException(IDO_QIM_UNREACHABLE) 발생
     */
    @Override
    public UserStatus getUserStatus(String qimUserId, String correlationId) {
        log.debug("[QimClient] Q-IM 사용자 상태 조회: qimUserId={} correlationId={}", qimUserId, correlationId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Correlation-Id", correlationId);

            String url = qimBaseUrl + "/api/v1/users/" + qimUserId;
            ResponseEntity<Map> response = qimRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );

            if (response.getBody() == null) {
                log.warn("[QimClient] Q-IM 응답 본문 없음: qimUserId={}", qimUserId);
                throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
            }

            String statusStr = (String) response.getBody().get("status");
            if (statusStr == null) {
                log.warn("[QimClient] Q-IM 응답에 status 필드 없음: qimUserId={}", qimUserId);
                throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
            }

            UserStatus status = parseUserStatus(statusStr, correlationId);
            log.debug("[QimClient] Q-IM 조회 성공: qimUserId={} status={}", qimUserId, status);
            return status;

        } catch (PlatformException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("[QimClient] Q-IM 조회 실패 (네트워크/타임아웃): qimUserId={} error={}",
                    qimUserId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        } catch (Exception e) {
            log.error("[QimClient] Q-IM 조회 중 예외: qimUserId={}", qimUserId, e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
    }

    private UserStatus parseUserStatus(String statusStr, String correlationId) {
        try {
            return UserStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            log.warn("[QimClient] 알 수 없는 UserStatus 값: {} — 안전 우선 SUSPENDED 처리", statusStr);
            // 알 수 없는 상태는 안전 우선으로 거부 (§11.5.4)
            return UserStatus.SUSPENDED;
        }
    }
}
