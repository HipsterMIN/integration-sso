package com.onepass.fe.api;

import com.onepass.common.util.CorrelationIdHolder;
import com.onepass.fe.session.FeSession;
import com.onepass.fe.session.FeSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Onepass FE 로그인 / 세션 API
 * 설계서 12.2 / 12.3절 참조
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/session")
@RequiredArgsConstructor
public class LoginController {

    private final FeSessionService feSessionService;

    /**
     * 딥링크 진입 — 기존 외부 세션 유효성 확인
     * GET /api/v1/session/check?returnUrl=...
     * 설계서 12.3절: 세션 유효 → IdO Handoff 직행, 만료 → 로그인 화면
     */
    @GetMapping("/check")
    public ResponseEntity<?> checkSession(
            @CookieValue(name = "feSessionId", required = false) String feSessionId,
            @RequestParam(required = false) String returnUrl) {

        if (returnUrl != null && !feSessionService.isValidReturnUrl(returnUrl)) {
            return ResponseEntity.badRequest().body("허용되지 않은 returnUrl");
        }

        if (feSessionId != null) {
            return feSessionService.findById(feSessionId)
                    .map(session -> ResponseEntity.ok().body(
                            java.util.Map.of("valid", true, "qimUserId", session.getQimUserId())))
                    .orElseGet(() -> ResponseEntity.ok().body(
                            java.util.Map.of("valid", false)));
        }
        return ResponseEntity.ok().body(java.util.Map.of("valid", false));
    }

    /**
     * 세션 만료 (외부 로그아웃)
     * POST /api/v1/session/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "feSessionId", required = false) String feSessionId,
            HttpServletResponse response) {

        if (feSessionId != null) {
            feSessionService.expire(feSessionId);
            log.info("[FE] 세션 만료 feSessionId={}", feSessionId);
        }

        // 쿠키 제거
        ResponseCookie clearCookie = ResponseCookie.from("feSessionId", "")
                .maxAge(0)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());
        return ResponseEntity.noContent().build();
    }
}
