package io.github.hipstermin.idem.hub.fe.api;

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
import org.springframework.web.bind.annotation.*;

/**
 * Onepass FE 세션 API (IdO BFF 역할)
 * 설계서 §12.2 / §12.3 참조
 *
 * <p>onepass-fe 가 순수 React SPA 로 전환됨에 따라
 * 구 LoginController(/api/v1/session) 기능을 IdO 가 승계.
 *
 * <p>기본 경로: /api/v1/fe-session
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fe-session")
@RequiredArgsConstructor
public class FeSessionController {

    private static final String COOKIE_NAME = "feSessionId";

    private static final String PROV_TOKEN_KEY_PREFIX = "prov:token:used:";
    private static final Duration PROV_TOKEN_TTL = Duration.ofMinutes(30);

    private final FeSessionService feSessionService;
    private final RedisTemplate<String, Object> redisTemplate;

    // ── GET /api/v1/fe-session/check ──────────────────────────────────────

    /**
     * 딥링크 진입 — 기존 FE 세션 유효성 확인
     * 설계서 §12.3: 유효 → Handoff 직행 / 만료 → 로그인 화면
     */
    @GetMapping("/check")
    public ResponseEntity<?> checkSession(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @CookieValue(name = COOKIE_NAME, required = false) String feSessionId,
            @RequestParam(required = false) String returnUrl) {

        setupCorrelation(correlationId);

        // returnUrl 화이트리스트 검증
        if (returnUrl != null && !feSessionService.isValidReturnUrl(returnUrl)) {
            log.warn("[FeSession] 허용되지 않은 returnUrl={}", returnUrl);
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_RETURN_URL",
                    "message", "허용되지 않은 returnUrl"));
        }

        if (feSessionId != null) {
            return feSessionService.findById(feSessionId)
                    .map(session -> {
                        // Sliding TTL 갱신
                        feSessionService.refresh(feSessionId);
                        return ResponseEntity.ok().body(Map.of(
                                "valid",       true,
                                "qimUserId",   session.getQimUserId(),
                                "authLevel",   session.getAuthLevel(),
                                "advisoryFlag", session.isAdvisoryFlag()
                        ));
                    })
                    .orElseGet(() -> ResponseEntity.ok().body(Map.of("valid", false)));
        }

        return ResponseEntity.ok().body(Map.of("valid", false));
    }

    // ── POST /api/v1/fe-session/logout ────────────────────────────────────

    /**
     * FE 세션 로그아웃 (쿠키 제거)
     * 설계서 §12.4 외부 채널 로그아웃
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @CookieValue(name = COOKIE_NAME, required = false) String feSessionId,
            HttpServletResponse response) {

        setupCorrelation(correlationId);

        if (feSessionId != null) {
            feSessionService.expire(feSessionId);
            log.info("[FeSession] 로그아웃 feSessionId={}", feSessionId);
        }

        // feSessionId 쿠키 제거
        ResponseCookie clearCookie = ResponseCookie.from(COOKIE_NAME, "")
                .maxAge(0)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());

        return ResponseEntity.noContent().build();
    }

    // ── POST /api/v1/fe-session (내부 — Q-Sign 인증 완료 후 세션 발급) ──

    /**
     * FE 세션 발급 (Q-Sign 인증 완료 후 내부 호출)
     * Q-Sign → IdO 콜백 경로에서 호출되어 feSessionId 쿠키 설정.
     *
     * <p><b>F4.8 (Sprint β-2)</b>: 본 엔드포인트는 {@link InternalCallerAuthInterceptor}
     * 가 선검증한다. 호출자는 {@code X-Internal-Caller} + {@code X-Internal-Api-Key}
     * 헤더를 동반해야 한다. 컨트롤러에서는 검증된 caller 식별자를 Request Attribute
     * ({@code validatedInternalCaller}) 에서 조회한다.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> createSession(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody FeSessionCreateRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {

        setupCorrelation(correlationId);
        String caller = (String) request.getAttribute(
                InternalCallerAuthInterceptor.ATTR_VALIDATED_CALLER);

        // returnUrl 화이트리스트 검증
        if (req.getReturnUrl() != null && !feSessionService.isValidReturnUrl(req.getReturnUrl())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_RETURN_URL",
                    "message", "허용되지 않은 returnUrl"));
        }

        FeSession session = feSessionService.create(
                req.getQimUserId(),
                req.getAuthResultId(),
                req.getAuthLevel(),
                req.getReturnUrl());

        // feSessionId 쿠키 설정 (Secure / HttpOnly / SameSite=Lax)
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session.getFeSessionId())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        log.info("[FeSession] 세션 발급 qimUserId={} caller={}",
                session.getQimUserId(), caller);

        return ResponseEntity.ok(Map.of(
                "feSessionId", session.getFeSessionId(),
                "returnUrl",   session.getReturnUrl() != null ? session.getReturnUrl() : ""));
    }

    // ── POST /api/v1/fe-session/conversion (회원전환 완료 후 세션 발급) ──

    /**
     * 회원전환 완료 후 FE 세션 발급
     *
     * <p>Step5(provisioning) 완료 후 FE가 호출하여 feSessionId 쿠키를 발급받는다.
     * provisioningToken Redis SETNX 검증으로 일회성 사용을 보장한다.
     */
    @PostMapping("/conversion")
    public ResponseEntity<Map<String, String>> createConversionSession(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody FeSessionConversionRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {

        setupCorrelation(correlationId);
        // F4.8: 인터셉터가 검증한 caller 식별자 (감사 로그용)
        String caller = (String) request.getAttribute(
                InternalCallerAuthInterceptor.ATTR_VALIDATED_CALLER);

        // provisioningToken 일회성 검증 (Redis SETNX)
        String tokenKey = PROV_TOKEN_KEY_PREFIX + req.getProvisioningToken();
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(tokenKey, "used", PROV_TOKEN_TTL);
        if (!Boolean.TRUE.equals(isNew)) {
            log.warn("[FeSession/conversion] provisioningToken 재사용 시도 token={}",
                    req.getProvisioningToken().substring(0, Math.min(8, req.getProvisioningToken().length())));
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "TOKEN_ALREADY_USED",
                                 "message", "이미 사용된 provisioningToken입니다."));
        }

        // qimUserId 결정: 개인(mbrUuid) 또는 기업(entMbrNo)
        String qimUserId = req.getMbrUuid() != null ? req.getMbrUuid() : req.getEntMbrNo();
        if (qimUserId == null || qimUserId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "MISSING_USER_ID",
                                 "message", "mbrUuid 또는 entMbrNo 중 하나는 필수입니다."));
        }

        // returnUrl 화이트리스트 검증
        if (req.getReturnUrl() != null && !feSessionService.isValidReturnUrl(req.getReturnUrl())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_RETURN_URL",
                    "message", "허용되지 않은 returnUrl"));
        }

        FeSession session = feSessionService.create(qimUserId, null, "CONV", req.getReturnUrl());

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session.getFeSessionId())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        log.info("[FeSession/conversion] 세션 발급 qimUserId={} caller={}", qimUserId, caller);

        return ResponseEntity.ok(Map.of(
                "feSessionId", session.getFeSessionId(),
                "returnUrl",   session.getReturnUrl() != null ? session.getReturnUrl() : ""));
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────

    private void setupCorrelation(String correlationId) {
        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);
    }
}
