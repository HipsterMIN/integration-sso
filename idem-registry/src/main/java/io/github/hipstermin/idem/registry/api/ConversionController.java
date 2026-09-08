package io.github.hipstermin.idem.registry.api;

import io.github.hipstermin.idem.registry.conversion.ConversionSessionResult;
import io.github.hipstermin.idem.registry.conversion.ConversionSessionService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Q-IM 통합계정 전환 세션 API 컨트롤러 (P2 §12.1)
 *
 * <p><b>엔드포인트</b>:
 * <pre>
 * POST   /api/v1/internal/users/{qimUserId}/conversion          — 세션 시작
 * POST   /api/v1/internal/conversion/{sessionId}/fetch-candidates — 후보 조회
 * POST   /api/v1/internal/conversion/{sessionId}/select         — 계정 선택
 * POST   /api/v1/internal/conversion/{sessionId}/link           — 연결 실행
 * DELETE /api/v1/internal/conversion/{sessionId}                — 취소
 * GET    /api/v1/internal/conversion/{sessionId}                — 세션 조회
 * </pre>
 *
 * <p><b>보안</b>: X-Internal-Api-Key 헤더 검증 ({@link io.github.hipstermin.idem.registry.config.InternalApiKeyInterceptor})
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ConversionController {

    private final ConversionSessionService conversionSessionService;

    /**
     * 전환 세션 시작 — INITIATED
     * POST /api/v1/internal/users/{qimUserId}/conversion
     */
    @PostMapping("/api/v1/internal/users/{qimUserId}/conversion")
    public ResponseEntity<ConversionSessionResult> initiate(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId) {

        log.info("[ConvCtrl] 전환 시작: qimUserId={}", qimUserId);
        return ResponseEntity.ok(conversionSessionService.initiate(qimUserId, correlationId));
    }

    /**
     * 유관 시스템 회원 후보 조회 — INITIATED → MEMBERS_FETCHED
     * POST /api/v1/internal/conversion/{sessionId}/fetch-candidates
     */
    @PostMapping("/api/v1/internal/conversion/{sessionId}/fetch-candidates")
    public ResponseEntity<ConversionSessionResult> fetchCandidates(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId) {

        log.info("[ConvCtrl] 후보 조회: sessionId={}", sessionId);
        return ResponseEntity.ok(
                conversionSessionService.fetchCandidates(sessionId, correlationId));
    }

    /**
     * 연결 대상 계정 선택 — MEMBERS_FETCHED → ACCOUNT_SELECTED
     * POST /api/v1/internal/conversion/{sessionId}/select
     *
     * <p>요청 바디: {@code {"selectedAgencyCodes": ["GOV_A", "GOV_B"]}}
     */
    @PostMapping("/api/v1/internal/conversion/{sessionId}/select")
    public ResponseEntity<ConversionSessionResult> selectAccounts(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId,
            @RequestBody Map<String, List<String>> body) {

        List<String> selected = body.getOrDefault("selectedAgencyCodes", List.of());
        log.info("[ConvCtrl] 계정 선택: sessionId={} selected={}", sessionId, selected);
        return ResponseEntity.ok(
                conversionSessionService.selectAccounts(sessionId, selected, correlationId));
    }

    /**
     * 계정 연결 실행 — ACCOUNT_SELECTED → LINKING → COMPLETED
     * POST /api/v1/internal/conversion/{sessionId}/link
     */
    @PostMapping("/api/v1/internal/conversion/{sessionId}/link")
    public ResponseEntity<ConversionSessionResult> link(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId) {

        log.info("[ConvCtrl] 계정 연결: sessionId={}", sessionId);
        return ResponseEntity.ok(conversionSessionService.link(sessionId, correlationId));
    }

    /**
     * 전환 세션 취소
     * DELETE /api/v1/internal/conversion/{sessionId}
     */
    @DeleteMapping("/api/v1/internal/conversion/{sessionId}")
    public ResponseEntity<ConversionSessionResult> cancel(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId,
            @RequestParam(defaultValue = "사용자 취소") String reason) {

        log.info("[ConvCtrl] 세션 취소: sessionId={}", sessionId);
        return ResponseEntity.ok(
                conversionSessionService.cancel(sessionId, reason, correlationId));
    }

    /**
     * 세션 상태 조회
     * GET /api/v1/internal/conversion/{sessionId}
     */
    @GetMapping("/api/v1/internal/conversion/{sessionId}")
    public ResponseEntity<ConversionSessionResult> getSession(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId) {

        return ResponseEntity.ok(
                conversionSessionService.getSession(sessionId, correlationId));
    }
}
