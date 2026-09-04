package kr.go.smes.qim.api;

import kr.go.smes.qim.consent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Q-IM 개인정보 동의 API 컨트롤러 (P2 §12.3)
 *
 * <p><b>엔드포인트</b>:
 * <pre>
 * POST   /api/v1/internal/users/{qimUserId}/consents            — 동의 기록
 * DELETE /api/v1/internal/users/{qimUserId}/consents/{type}     — 선택 동의 철회
 * GET    /api/v1/internal/users/{qimUserId}/consents            — 동의 현황 조회
 * GET    /api/v1/internal/consent-versions                      — 활성 버전 목록
 * </pre>
 *
 * <p><b>보안</b>: X-Internal-Api-Key 헤더 검증 ({@link kr.go.smes.qim.config.InternalApiKeyInterceptor})
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;

    /**
     * 동의 기록
     * POST /api/v1/internal/users/{qimUserId}/consents
     *
     * <p>요청 예시:
     * <pre>{@code
     * {
     *   "consentType": "PRIVACY_POLICY",
     *   "versionId": "01HXXX...",   // null이면 최신 ACTIVE 버전 자동 선택
     *   "agreedVia": "WEB_SIGNUP",
     *   "clientIp": "1.2.3.4"
     * }
     * }</pre>
     */
    @PostMapping("/api/v1/internal/users/{qimUserId}/consents")
    public ResponseEntity<ConsentResult> agree(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId,
            @RequestBody ConsentRequest request) {

        log.info("[ConsentCtrl] 동의 요청: qimUserId={} type={}", qimUserId, request.getConsentType());

        ConsentRequest resolved = request.getCorrelationId() != null
                ? request
                : ConsentRequest.builder()
                        .versionId(request.getVersionId())
                        .consentType(request.getConsentType())
                        .agreedVia(request.getAgreedVia())
                        .clientIp(request.getClientIp())
                        .correlationId(correlationId)
                        .build();

        ConsentResult result = consentService.agree(qimUserId, resolved);
        return ResponseEntity.ok(result);
    }

    /**
     * 선택 동의 철회
     * DELETE /api/v1/internal/users/{qimUserId}/consents/{consentType}
     *
     * <p>필수 동의(required=true)는 철회 불가 → IM_WITHDRAWAL_NOT_ALLOWED(E-IM-206) 반환.
     */
    @DeleteMapping("/api/v1/internal/users/{qimUserId}/consents/{consentType}")
    public ResponseEntity<ConsentResult> withdraw(
            @PathVariable String qimUserId,
            @PathVariable String consentType,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId,
            @RequestParam(defaultValue = "사용자 요청") String reason) {

        log.info("[ConsentCtrl] 동의 철회: qimUserId={} type={}", qimUserId, consentType);
        ConsentResult result = consentService.withdraw(qimUserId, consentType, reason, correlationId);
        return ResponseEntity.ok(result);
    }

    /**
     * 사용자 동의 현황 조회
     * GET /api/v1/internal/users/{qimUserId}/consents
     */
    @GetMapping("/api/v1/internal/users/{qimUserId}/consents")
    public ResponseEntity<List<ConsentResult>> getStatus(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        return ResponseEntity.ok(consentService.getConsentStatus(qimUserId));
    }

    /**
     * 활성 동의 버전 목록 조회 (회원가입·재동의 화면 로딩)
     * GET /api/v1/internal/consent-versions
     */
    @GetMapping("/api/v1/internal/consent-versions")
    public ResponseEntity<List<ConsentVersionInfo>> getActiveVersions(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        return ResponseEntity.ok(consentService.getActiveVersions());
    }
}
