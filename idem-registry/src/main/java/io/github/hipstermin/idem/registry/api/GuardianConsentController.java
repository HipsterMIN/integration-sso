package io.github.hipstermin.idem.registry.api;

import io.github.hipstermin.idem.registry.guardian.GuardianConsentService;
import io.github.hipstermin.idem.registry.guardian.GuardianConsentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 14세 미만 보호자 동의 API 컨트롤러
 *
 * <p>보안: X-Internal-Api-Key 헤더 검증 (InternalApiKeyInterceptor) 적용
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>POST  /api/v1/internal/guardian/consent  — 보호자 동의 완료 처리</li>
 *   <li>GET   /api/v1/internal/guardian/{qimUserId}/status — 보호자 동의 상태 조회</li>
 * </ul>
 *
 * <p>설계서 §P3-05 참조
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/guardian")
@RequiredArgsConstructor
public class GuardianConsentController {

    private final GuardianConsentService guardianConsentService;

    /**
     * 보호자 동의 완료 처리
     *
     * <pre>
     * POST /api/v1/internal/guardian/consent
     * {
     *   "minorQimUserId":    "...",
     *   "guardianQimUserId": "...",
     *   "correlationId":     "..."
     * }
     * </pre>
     *
     * <ul>
     *   <li>200 OK — 동의 완료</li>
     *   <li>403 E-IM-212 — 미성년자 아님</li>
     *   <li>404 E-IM-213 — 보호자 정보 없음</li>
     *   <li>409 E-IM-214 — 이미 보호자 동의 완료</li>
     * </ul>
     */
    @PostMapping("/consent")
    public ResponseEntity<Map<String, String>> grantConsent(@Valid @RequestBody GuardianConsentRequest req) {
        log.info("[GuardianAPI] 보호자 동의 요청: minorQimUserId={} correlationId={}",
                req.minorQimUserId(), req.correlationId());

        guardianConsentService.grantConsent(
                req.minorQimUserId(),
                req.guardianQimUserId(),
                req.correlationId());

        return ResponseEntity.ok(Map.of(
                "result", "CONSENT_GRANTED",
                "minorQimUserId", req.minorQimUserId(),
                "guardianQimUserId", req.guardianQimUserId()));
    }

    /**
     * 보호자 동의 상태 조회
     *
     * <pre>
     * GET /api/v1/internal/guardian/{qimUserId}/status
     * </pre>
     *
     * <ul>
     *   <li>200 OK — 조회 성공</li>
     *   <li>404 E-IM-201 — 사용자 없음</li>
     * </ul>
     */
    @GetMapping("/{qimUserId}/status")
    public ResponseEntity<GuardianConsentStatus> getStatus(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "N/A") String correlationId) {
        log.info("[GuardianAPI] 보호자 동의 상태 조회: qimUserId={} correlationId={}", qimUserId, correlationId);
        GuardianConsentStatus status = guardianConsentService.getStatus(qimUserId, correlationId);
        return ResponseEntity.ok(status);
    }

    /** 보호자 동의 요청 DTO */
    public record GuardianConsentRequest(
            @NotBlank(message = "minorQimUserId는 필수입니다.")
            String minorQimUserId,
            @NotBlank(message = "guardianQimUserId는 필수입니다.")
            String guardianQimUserId,
            @NotBlank(message = "correlationId는 필수입니다.")
            String correlationId) {
    }
}
