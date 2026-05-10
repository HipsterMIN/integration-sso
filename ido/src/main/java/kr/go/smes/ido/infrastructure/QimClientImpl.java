package kr.go.smes.ido.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.ido.auth.dto.AuthResult;
import kr.go.smes.ido.auth.dto.im.QimMemberInfo;
import kr.go.smes.ido.auth.dto.im.QimRegisterResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Q-IM HTTP 클라이언트 구현체 (v3.0 — S7-T6)
 *
 * <p>v2.0 추가:
 * <ul>
 *   <li>{@link #getDi(String, String, String)} — 기관별 DI 조회/생성</li>
 * </ul>
 *
 * <p>v3.0 추가 (S7-T6):
 * <ul>
 *   <li>{@link #registerUser(AuthResult, String)} — CI → Q-IM 등록</li>
 *   <li>{@link #findByCi(String, String, String)} — CI로 Q-IM 사용자 조회</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimClientImpl implements QimClient {

    private final RestTemplate qimRestTemplate;
    private final ObjectMapper objectMapper;

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

    // ── registerUser ───────────────────────────────────────────────────────

    /**
     * S7-T6: CI를 Q-IM에 등록
     * POST /api/v1/internal/users/register
     *
     * <p>신규 사용자이면 Q-IM이 CI를 AES-256-GCM으로 암호화하여 저장하고 qimUserId를 발급.
     * 기존 사용자이면 마지막 인증 시각만 갱신하고 {@code isNew=false}로 응답.
     */
    @Override
    public QimRegisterResponse registerUser(AuthResult authResult, String correlationId) {
        String ciMasked = authResult.getCi() != null && authResult.getCi().length() > 8
                ? authResult.getCi().substring(0, 8) + "..."
                : "(null)";
        log.info("[QimClient] 사용자 등록: ci={}", ciMasked);

        try {
            HttpHeaders headers = buildHeaders(correlationId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 요청 바디 구성
            Map<String, Object> body = new HashMap<>();
            body.put("ci", authResult.getCi());
            body.put("di", authResult.getDi());
            body.put("name", authResult.getName());
            body.put("birthday", authResult.getBirthday());
            body.put("gender", authResult.getGender());
            body.put("mobile", authResult.getMobile());
            body.put("mobileCorp", authResult.getMobileCorp());

            String url = qimBaseUrl + "/api/v1/internal/users/register";
            ResponseEntity<QimRegisterResponse> response = qimRestTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    QimRegisterResponse.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("[QimClient] 사용자 등록 비정상 응답: status={}", response.getStatusCode());
                throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
            }

            QimRegisterResponse result = response.getBody();
            log.info("[QimClient] 사용자 등록 완료: qimUserId={} isNew={}", result.getQimUserId(), result.getIsNew());
            return result;

        } catch (PlatformException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("[QimClient] 사용자 등록 네트워크 오류: {}", e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        } catch (Exception e) {
            log.error("[QimClient] 사용자 등록 예외: ci={} err={}", ciMasked, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
    }

    // ── findByCi ──────────────────────────────────────────────────────────

    /**
     * S7-T6: CI로 Q-IM 사용자 조회
     * POST /api/v1/internal/users/find-by-ci
     *
     * <p>Q-IM은 전달된 CI를 해시하여 내부 CI 레코드와 비교.
     * 404 응답 → 미등록 사용자 → {@code Optional.empty()} 반환.
     * 그 외 오류 → {@link PlatformException} 발생.
     */
    @Override
    public Optional<QimMemberInfo> findByCi(String ci, String memberType, String correlationId) {
        String ciMasked = ci != null && ci.length() > 8 ? ci.substring(0, 8) + "..." : "(null)";
        log.debug("[QimClient] CI 조회: ci={} memberType={}", ciMasked, memberType);

        try {
            HttpHeaders headers = buildHeaders(correlationId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("ci", ci);
            body.put("memberType", memberType);

            String url = qimBaseUrl + "/api/v1/internal/users/find-by-ci";
            ResponseEntity<QimMemberInfo> response = qimRestTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    QimMemberInfo.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("[QimClient] CI 조회 성공: qimUserId={}", response.getBody().getQimUserId());
                return Optional.of(response.getBody());
            }
            return Optional.empty();

        } catch (HttpClientErrorException.NotFound e) {
            // 404: 미등록 사용자 — 정상 케이스
            log.debug("[QimClient] CI 미등록 사용자: ci={}", ciMasked);
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("[QimClient] CI 조회 네트워크 오류: {}", e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        } catch (Exception e) {
            log.error("[QimClient] CI 조회 예외: ci={} err={}", ciMasked, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
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
