package io.github.hipstermin.idem.hub.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.auth.dto.AuthResult;
import io.github.hipstermin.idem.hub.auth.dto.im.QimMemberInfo;
import io.github.hipstermin.idem.hub.auth.dto.im.QimRegisterResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Q-IM HTTP 클라이언트 구현체 (v4.0 — SSO 소셜 계정 지원)
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
 *
 * <p>v4.0 추가 (SSO — Keycloak identifierHash 수정):
 * <ul>
 *   <li>{@link #findBySocialSub(String, String, String)} — Keycloak sub로 소셜 계정 조회</li>
 *   <li>{@link #registerSocialUser(String, String, String, String)} — 소셜 신규 사용자 Q-IM 등록</li>
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

    /**
     * [P2 수정] Q-IM 내부 API 호출 키 — 환경변수 주입 (하드코딩 제거)
     *
     * <p>기존: {@code headers.set("X-Internal-Api-Key", "ido-internal")} — 하드코딩으로 소스 노출 위험.
     * <p>수정: {@code IDO_QIM_INTERNAL_API_KEY} 환경변수에서 주입.
     * <p>운영: K8s Secret / Vault에서 주입 필수. 기본값 빈 문자열 → Q-IM 서버가 401 반환하여 실패 조기 감지.
     * <p>로컬 개발: {@code IDO_QIM_INTERNAL_API_KEY=ido-internal} (docker-compose.yml에 설정)
     */
    @Value("${ido.qim.internal-api-key:}")
    private String qimInternalApiKey;

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
     * <p><b>Sprint α-3 / F4.6 변경</b> — 예외 구분:
     * <ul>
     *   <li>2xx + di 존재 → DI 반환 (= 기관 매핑 있음 → APPROVED)</li>
     *   <li>404 / 2xx + di 없음 → {@code null} 반환 (= 영구 미매핑 → 정상 GUEST)</li>
     *   <li>5xx / 4xx(404 제외) / 네트워크 오류 / 타임아웃 →
     *       {@link PlatformException}({@link PlatformErrorCode#IDO_QIM_UNREACHABLE}) throw
     *       (= 일시 장애 → 호출자가 503으로 응답하여 클라이언트 재시도 유도)</li>
     * </ul>
     *
     * <p>이전 구현은 모든 예외를 swallow하고 null을 반환했기 때문에
     * Q-IM 장애 상황에서도 영구 미매핑(=정상 GUEST)으로 오인되어
     * 데이터 무결성 위험이 있었음 (분석: 04_handoff_flow.md F4.6).
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
                Object di = response.getBody().get("di");
                return (di instanceof String s && !s.isBlank()) ? s : null;
            }
            // 2xx 외 응답 (실질적으로 도달 안 함 — Spring이 4xx/5xx를 예외로 변환)
            log.warn("[QimClient][F4.6] DI 조회 비정상 응답: status={} qimUserId={} agency={}",
                    response.getStatusCode(), qimUserId, agencyCode);
            return null;
        } catch (HttpClientErrorException.NotFound e) {
            // 404: 영구 미매핑 — 정상 케이스 (GUEST)
            log.info("[QimClient][F4.6] DI 미매핑(404) — GUEST 정당: qimUserId={} agency={}", qimUserId, agencyCode);
            return null;
        } catch (PlatformException e) {
            // 상위 PlatformException은 그대로 전파
            throw e;
        } catch (RestClientException e) {
            // 5xx / 4xx(404 제외) / 네트워크 오류 / 타임아웃 — 일시 장애
            log.error("[QimClient][F4.6] DI 조회 일시 장애 — IDO_QIM_UNREACHABLE: agency={} err={}",
                    agencyCode, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        } catch (Exception e) {
            // 예상 외 예외도 안전 우선 거부 (= 장애로 간주)
            log.error("[QimClient][F4.6] DI 조회 예외(unexpected) — IDO_QIM_UNREACHABLE: agency={} err={}",
                    agencyCode, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
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

    // ── findBySocialSub ───────────────────────────────────────────────────

    /**
     * v2.4.0 (SSO): Keycloak sub로 Q-IM 소셜 계정 조회
     * POST /api/v1/internal/users/find-by-social-sub
     *
     * <p>Keycloak 콜백에서 발급된 sub(JWT subject)와 providerCode로
     * Q-IM에 등록된 소셜 계정을 조회한다.
     * 404 응답 → 미등록 → {@code Optional.empty()} 반환.
     * 그 외 오류 → {@link PlatformException} 발생.
     *
     * @param sub           Keycloak JWT sub 클레임 (provider 내부 사용자 식별자)
     * @param providerCode  인증 제공자 코드 (e.g. "KAKAO", "NAVER")
     * @param correlationId 요청 추적 ID
     */
    @Override
    public Optional<QimMemberInfo> findBySocialSub(String sub, String providerCode, String correlationId) {
        log.debug("[QimClient] 소셜 sub 조회: providerCode={}", providerCode);
        try {
            HttpHeaders headers = buildHeaders(correlationId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("sub", sub);
            body.put("providerCode", providerCode);

            String url = qimBaseUrl + "/api/v1/internal/users/find-by-social-sub";
            ResponseEntity<QimMemberInfo> response = qimRestTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    QimMemberInfo.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("[QimClient] 소셜 sub 조회 성공: qimUserId={}", response.getBody().getQimUserId());
                return Optional.of(response.getBody());
            }
            return Optional.empty();

        } catch (HttpClientErrorException.NotFound e) {
            // 404: 미등록 소셜 계정 — 정상 케이스 (이후 registerSocialUser 호출)
            log.debug("[QimClient] 소셜 미등록 사용자: providerCode={}", providerCode);
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("[QimClient] 소셜 sub 조회 네트워크 오류: {}", e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        } catch (Exception e) {
            log.error("[QimClient] 소셜 sub 조회 예외: providerCode={} err={}", providerCode, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
    }

    // ── registerSocialUser ────────────────────────────────────────────────

    /**
     * v2.4.0 (SSO): Keycloak 소셜 신규 사용자 Q-IM 등록
     * POST /api/v1/internal/users/register-social
     *
     * <p>{@link #findBySocialSub}에서 {@code Optional.empty()} 반환 시 호출.
     * Q-IM이 sub+providerCode 조합으로 소셜 계정을 생성하고 qimUserId를 발급한다.
     * identifierHash는 Q-IM이 초기 식별자로 보관 (추후 CI 연동 시 사용 가능).
     *
     * @param sub            Keycloak JWT sub 클레임
     * @param providerCode   인증 제공자 코드 (e.g. "KAKAO", "NAVER")
     * @param identifierHash SHA-256(sub) — CI 미보유 소셜 사용자의 임시 식별자
     * @param correlationId  요청 추적 ID
     */
    @Override
    public QimRegisterResponse registerSocialUser(String sub, String providerCode,
                                                   String identifierHash, String correlationId) {
        log.info("[QimClient] 소셜 신규 사용자 등록: providerCode={}", providerCode);
        try {
            HttpHeaders headers = buildHeaders(correlationId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("sub", sub);
            body.put("providerCode", providerCode);
            body.put("identifierHash", identifierHash);

            String url = qimBaseUrl + "/api/v1/internal/users/register-social";
            ResponseEntity<QimRegisterResponse> response = qimRestTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    QimRegisterResponse.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("[QimClient] 소셜 등록 비정상 응답: status={}", response.getStatusCode());
                throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
            }

            QimRegisterResponse result = response.getBody();
            log.info("[QimClient] 소셜 등록 완료: qimUserId={} isNew={}", result.getQimUserId(), result.getIsNew());
            return result;

        } catch (PlatformException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("[QimClient] 소셜 등록 네트워크 오류: {}", e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        } catch (Exception e) {
            log.error("[QimClient] 소셜 등록 예외: providerCode={} err={}", providerCode, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
    }

    // ── private ────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId != null ? correlationId : "");
        // [P2 수정] 환경변수 IDO_QIM_INTERNAL_API_KEY 에서 주입 (하드코딩 제거)
        if (qimInternalApiKey != null && !qimInternalApiKey.isBlank()) {
            headers.set("X-Internal-Api-Key", qimInternalApiKey);
        } else {
            log.warn("[QimClient][P2-보안경고] IDO_QIM_INTERNAL_API_KEY 미설정 — Q-IM API 인증 헤더 누락. " +
                     "운영 배포 전 반드시 환경변수 설정 필요. correlationId={}", correlationId);
        }
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
