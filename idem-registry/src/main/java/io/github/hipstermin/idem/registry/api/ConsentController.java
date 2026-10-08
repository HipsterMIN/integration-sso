package io.github.hipstermin.idem.registry.api;

import io.github.hipstermin.idem.registry.consent.*;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
 * <p><b>보안</b>: X-Internal-Api-Key 헤더 검증 ({@link io.github.hipstermin.idem.registry.config.InternalApiKeyInterceptor})
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
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestParam(required = false) String serviceCode,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "false") boolean catalog) {
        // 1.1 동의 카탈로그: serviceCode 가 있으면 그 범위. catalog=true 면 서비스가 보는 합(플랫폼 공통 + 서비스 전용, ACTIVE)
        if (catalog) return ResponseEntity.ok(consentService.catalog(serviceCode));
        if (serviceCode != null || includeInactive) return ResponseEntity.ok(consentService.listVersions(serviceCode, includeInactive));
        return ResponseEntity.ok(consentService.getActiveVersions());
    }

    // ── 1.1 동의 카탈로그 (플랜 §5 #8) — hub 관리 API·로그인 프런트가 부른다 ────

    public record PublishBody(String serviceCode, String consentType, String versionTag, String title, String contentUrl,
                              Boolean required, Instant effectiveAt) {}

    /** 새 버전 발행 — 같은 범위·유형의 ACTIVE 는 SUPERSEDED. POST /api/v1/internal/consent-versions */
    @PostMapping("/api/v1/internal/consent-versions")
    public ResponseEntity<ConsentVersionInfo> publish(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody PublishBody body) {
        ConsentVersionInfo v = consentService.publish(new ConsentService.PublishRequest(body.serviceCode(), body.consentType(), body.versionTag(),
                body.title(), body.contentUrl(), body.required(), body.effectiveAt(), correlationId));
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    /** 버전 종료(카탈로그에서 뺀다). POST /api/v1/internal/consent-versions/{versionId}/retire */
    @PostMapping("/api/v1/internal/consent-versions/{versionId}/retire")
    public ResponseEntity<ConsentVersionInfo> retire(
            @PathVariable String versionId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return ResponseEntity.ok(consentService.retire(versionId, correlationId));
    }

    /** 사용자가 아직 동의하지 않은 카탈로그 항목. GET /api/v1/internal/users/{qimUserId}/consents/missing?serviceCode= */
    @GetMapping("/api/v1/internal/users/{qimUserId}/consents/missing")
    public ResponseEntity<List<ConsentVersionInfo>> missing(
            @PathVariable String qimUserId,
            @RequestParam(required = false) String serviceCode,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        return ResponseEntity.ok(consentService.missing(qimUserId, serviceCode));
    }
}
