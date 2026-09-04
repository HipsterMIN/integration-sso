package kr.go.smes.ido.slo;

import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.fe.session.FeSession;
import kr.go.smes.ido.fe.session.FeSessionService;
import kr.go.smes.ido.metrics.SloMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Single Logout (SLO) 엔드포인트
 * 설계서 §13.3 / Sprint 2 P1-01
 *
 * <p><b>SLO 완전 흐름</b>:
 * <pre>
 *   FE 로그아웃 버튼
 *     → POST /api/v1/slo/initiate
 *       ① feSession 만료 (Redis 삭제)
 *       ② SloServiceImpl.executeSlo() 위임
 *           → Q-Sign → Keycloak 세션 종료 (비치명적)
 *           → 기관 로그아웃 Webhook Outbox 적재 (비치명적)
 *           → 감사 로그 기록
 *       ③ feSessionId 쿠키 제거
 *     → 204 No Content
 * </pre>
 *
 * <p>기본 경로: {@code /api/v1/slo}
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/slo")
@RequiredArgsConstructor
public class SloController {

    private static final String COOKIE_NAME = "feSessionId";

    private final FeSessionService feSessionService;
    private final SloService       sloService;
    private final SloMetrics       sloMetrics;

    /**
     * SLO 시작 — FE 로그아웃 버튼 클릭 시 호출
     *
     * <p><b>처리 순서</b>:
     * <ol>
     *   <li>feSessionId 쿠키 유효성 확인</li>
     *   <li>feSession 만료 (Redis 즉시 삭제)</li>
     *   <li>SloService.executeSlo() — Keycloak + Webhook + 감사로그 (비치명적)</li>
     *   <li>feSessionId 쿠키 Clear-Site-Data 제거</li>
     * </ol>
     *
     * <p><b>응답</b>:
     * <ul>
     *   <li>204 No Content — SLO 성공 (feSessionId 없어도 204 — 멱등성)</li>
     * </ul>
     */
    @PostMapping("/initiate")
    public ResponseEntity<Void> initiateSlo(
            @CookieValue(name = COOKIE_NAME, required = false) String feSessionId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletResponse response) {

        String cid = resolveCorrelationId(correlationId);
        CorrelationIdHolder.set(cid);

        sloMetrics.incrementSloInitiate();
        long startMs = System.currentTimeMillis();

        if (feSessionId != null && !feSessionId.isBlank()) {
            FeSession session = feSessionService.findById(feSessionId).orElse(null);

            // ① feSession 즉시 만료 (세션 존재 여부 무관 — 방어적 처리)
            feSessionService.expire(feSessionId);
            log.info("[SLO] feSession 만료: feSessionId={} correlationId={}", feSessionId, cid);
            sloMetrics.incrementSloCompleted();

            // ② SLO 오케스트레이션 실행 (Keycloak + Webhook + 감사로그)
            if (session != null) {
                sloService.executeSlo(session, cid);
            } else {
                log.warn("[SLO] feSession 조회 실패 (이미 만료?) — SLO 오케스트레이션 스킵: " +
                         "feSessionId={} correlationId={}", feSessionId, cid);
            }
        } else {
            sloMetrics.incrementSloSkipped();
            log.debug("[SLO] feSessionId 없음 — 쿠키 제거만 수행: correlationId={}", cid);
        }

        sloMetrics.recordSloDuration(System.currentTimeMillis() - startMs);

        // ③ feSessionId 쿠키 제거 (Max-Age=0)
        clearSessionCookie(response);

        return ResponseEntity.noContent().build();
    }

    // ── private ────────────────────────────────────────────────────────────

    private void clearSessionCookie(HttpServletResponse response) {
        ResponseCookie clearCookie = ResponseCookie.from(COOKIE_NAME, "")
                .maxAge(0)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());
    }

    private String resolveCorrelationId(String correlationId) {
        return (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : CorrelationIdHolder.generate();
    }
}
