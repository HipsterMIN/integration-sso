package io.github.hipstermin.idem.hub.kr.fe;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.fe.config.InternalCallerAuthInterceptor;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KR 에디션: 회원전환(SMES 개인 mbrUuid / 기업 entMbrNo) 완료 후 FE 세션 발급 — {@code POST /api/v1/fe-session/conversion}.
 *
 * <p>S8-a: 코어 {@code FeSessionController} 에서 분리. 내부 호출자 인증은 {@link KrHubWebMvcConfig} 가 같은
 * {@link InternalCallerAuthInterceptor} 를 이 경로에 등록한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fe-session")
@RequiredArgsConstructor
public class KrFeSessionConversionController {

    private static final String COOKIE_NAME = "feSessionId";
    private static final String PROV_TOKEN_KEY_PREFIX = "prov:token:used:";
    private static final Duration PROV_TOKEN_TTL = Duration.ofMinutes(30);

    private final FeSessionService feSessionService;
    private final RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/conversion")
    public ResponseEntity<Map<String, String>> createConversionSession(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody FeSessionConversionRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {
        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);
        String caller = (String) request.getAttribute(InternalCallerAuthInterceptor.ATTR_VALIDATED_CALLER);

        // provisioningToken 일회성 검증 (Redis SETNX)
        String tokenKey = PROV_TOKEN_KEY_PREFIX + req.getProvisioningToken();
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(tokenKey, "used", PROV_TOKEN_TTL);
        if (!Boolean.TRUE.equals(isNew)) {
            log.warn("[FeSession/conversion] provisioningToken 재사용 시도 token={}",
                    req.getProvisioningToken().substring(0, Math.min(8, req.getProvisioningToken().length())));
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "TOKEN_ALREADY_USED", "message", "이미 사용된 provisioningToken입니다."));
        }
        // qimUserId 결정: 개인(mbrUuid) 또는 기업(entMbrNo)
        String qimUserId = req.getMbrUuid() != null ? req.getMbrUuid() : req.getEntMbrNo();
        if (qimUserId == null || qimUserId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "MISSING_USER_ID", "message", "mbrUuid 또는 entMbrNo 중 하나는 필수입니다."));
        }
        if (req.getReturnUrl() != null && !feSessionService.isValidReturnUrl(req.getReturnUrl())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_RETURN_URL", "message", "허용되지 않은 returnUrl"));
        }
        FeSession session = feSessionService.create(qimUserId, null, "CONV", req.getReturnUrl());
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session.getFeSessionId())
                .httpOnly(true).secure(true).sameSite("Lax").path("/").build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        log.info("[FeSession/conversion] 세션 발급 qimUserId={} caller={}", qimUserId, caller);
        return ResponseEntity.ok(Map.of(
                "feSessionId", session.getFeSessionId(),
                "returnUrl",   session.getReturnUrl() != null ? session.getReturnUrl() : ""));
    }
}
