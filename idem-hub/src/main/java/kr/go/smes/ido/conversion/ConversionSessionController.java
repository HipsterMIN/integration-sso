package kr.go.smes.ido.conversion;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.conversion.dto.ConversionSessionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 전환 세션 조회 API
 *
 * <p>FE Step1 → GET /api/v1/conversion/session/{sessionId} 호출 시
 * Redis에서 {@link ConversionSession}을 조회하여 mbrId / redirectUri 등을 반환한다.
 * CI 평문 등 민감 정보는 반환하지 않는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/conversion/session")
@RequiredArgsConstructor
public class ConversionSessionController {

    private static final String REDIS_KEY_PREFIX = "conversion:session:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 전환 세션 단건 조회
     *
     * @param correlationId X-Correlation-Id 헤더 (optional)
     * @param sessionId     경로 변수: 전환 세션 ID
     * @return 세션 정보 (mbrId, redirectUri, userType, agencyCode)
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<ConversionSessionResponse> getSession(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable String sessionId) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        String key = REDIS_KEY_PREFIX + sessionId;
        ConversionSession session = (ConversionSession) redisTemplate.opsForValue().get(key);

        if (session == null) {
            log.warn("[ConversionSession] 세션 없음 sessionId={}", sessionId);
            throw new PlatformException(PlatformErrorCode.CONVERSION_SESSION_NOT_FOUND, sessionId);
        }

        log.info("[ConversionSession] 세션 조회 sessionId={} agencyCode={}",
                sessionId, session.getAgencyCode());

        return ResponseEntity.ok(ConversionSessionResponse.builder()
                .mbrId(session.getMbrId())
                .redirectUri(session.getRedirectUri())
                .userType(session.getUserType() != null ? session.getUserType() : "INDIVIDUAL")
                .agencyCode(session.getAgencyCode())
                .build());
    }
}
