package io.github.hipstermin.idem.hub.kr.memberlookup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * CI(연계정보) 기반 회원 통합 조회 서비스 (설계서 §10.5)
 *
 * <p><b>목적</b>: 기관이 보유한 CI 값으로 Q-IM 사용자를 조회하는 서비스.
 * CI는 암호화된 상태로 저장되어 있으므로 Q-IM이 복호화 후 조회한다.
 *
 * <p><b>흐름</b>:
 * <pre>
 *   기관 → POST /api/v1/member/lookup (X-Agency-Key 인증)
 *         → MemberLookupService.lookup()
 *               → Q-IM POST /api/v1/internal/member/lookup-by-ci
 *                     → CI 복호화 → identifierHash 계산 → 사용자 조회
 *               → HandoffPayload 구성 (allowedAttributes 필터)
 *               → 결과 반환
 * </pre>
 *
 * <p><b>보안</b>:
 * <ul>
 *   <li>CI 원본은 Q-IM에만 존재 — IdO/기관은 암호화된 CI만 취급</li>
 *   <li>기관별 allowedAttributes 필터 적용</li>
 *   <li>조회 결과는 Audit Log 기록 필수</li>
 *   <li>일별 기관 조회 한도(daily_lookup_limit) 적용</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberLookupService {

    private final RestTemplate qimRestTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ido.qim.base-url:http://localhost:8082}")
    private String qimBaseUrl;

    /** [P2 수정] Q-IM 내부 API 호출 키 — 환경변수 IDO_QIM_INTERNAL_API_KEY 주입 */
    @Value("${ido.qim.internal-api-key:}")
    private String qimInternalApiKey;

    /**
     * CI 기반 회원 조회
     *
     * @param encryptedCi  암호화된 CI (v1.{iv}.{ciphertext} 형식)
     * @param agencyCode   기관 코드
     * @param correlationId 추적 ID
     * @return 회원 조회 결과 맵 (qimUserId, status, maskedProfile 등)
     */
    public Map<String, Object> lookupByCi(String encryptedCi, String agencyCode, String correlationId) {
        log.info("[MemberLookup] CI 기반 조회: agencyCode={} cid={}", agencyCode, correlationId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Correlation-Id", correlationId != null ? correlationId : "");
            // [P2 수정] 환경변수 주입 (하드코딩 'ido-internal' 제거)
            if (qimInternalApiKey != null && !qimInternalApiKey.isBlank()) {
                headers.set("X-Internal-Api-Key", qimInternalApiKey);
            }
            headers.set("X-Agency-Code", agencyCode);

            Map<String, String> body = Map.of(
                    "encryptedCi", encryptedCi,
                    "agencyCode",  agencyCode
            );

            String url = qimBaseUrl + "/api/v1/internal/member/lookup-by-ci";
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = qimRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[MemberLookup] Q-IM 조회 실패: status={} cid={}",
                        response.getStatusCode(), correlationId);
                throw new PlatformException(PlatformErrorCode.IM_USER_NOT_FOUND, correlationId);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getBody();
            log.info("[MemberLookup] 조회 성공: agencyCode={} cid={}", agencyCode, correlationId);
            return result;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[MemberLookup] Q-IM 통신 오류: agencyCode={} cid={} err={}",
                    agencyCode, correlationId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
    }

    /**
     * identifierHash 기반 회원 조회 (직접 해시 값 제공 시)
     *
     * @param identifierHash SHA-256 해시값 (PII 비노출)
     * @param agencyCode     기관 코드
     * @param correlationId  추적 ID
     */
    public Map<String, Object> lookupByHash(String identifierHash, String agencyCode, String correlationId) {
        log.info("[MemberLookup] Hash 기반 조회: agencyCode={} cid={}", agencyCode, correlationId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Correlation-Id", correlationId != null ? correlationId : "");
            // [P2 수정] 환경변수 주입 (하드코딩 'ido-internal' 제거)
            if (qimInternalApiKey != null && !qimInternalApiKey.isBlank()) {
                headers.set("X-Internal-Api-Key", qimInternalApiKey);
            }

            String url = qimBaseUrl + "/api/v1/internal/users/by-hash?identifierHash=" + identifierHash;
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = qimRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (response.getStatusCode().value() == 404 || response.getBody() == null) {
                throw new PlatformException(PlatformErrorCode.IM_USER_NOT_FOUND, correlationId);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getBody();
            return result;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[MemberLookup] Hash 조회 오류: err={}", e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
    }
}
