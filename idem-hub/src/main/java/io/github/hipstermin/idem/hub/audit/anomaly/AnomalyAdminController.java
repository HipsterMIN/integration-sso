package io.github.hipstermin.idem.hub.audit.anomaly;

import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1 감사 이상 탐지(관찰 모드) 관리 API ({@code /api/v1/admin/anomalies/**}) — 전 역할이 읽고 검토한다(AUDITOR 포함, 인가 매트릭스 별도 행).
 * 테넌트 관리자는 감사 조회와 같이 {@code agencyCode} 가 필수다.
 * <ul>
 *   <li>{@code GET  /}            — 플래그 목록 (from·to·rule·agencyCode·severity·review=UNREVIEWED|REVIEWED|TRUE_POSITIVE|FALSE_POSITIVE|UNSURE·page·size≤200)</li>
 *   <li>{@code GET  /stats?days}  — 규칙별 건수·검토 결과·정밀도, 일별 건수, 커서 (3개월 뒤 경보 승격 결정의 근거)</li>
 *   <li>{@code POST /{flagId}/review} {verdict, note} — 검토 기록 (감사 ADMIN/ANOMALY_REVIEWED)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/anomalies")
@RequiredArgsConstructor
public class AnomalyAdminController {

    private final AnomalyAdminService service;

    public record ReviewBody(String verdict, String note) {}

    @GetMapping
    public AnomalyAdminService.Page list(@RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to,
                                         @RequestParam(required = false) String rule,
                                         @RequestParam(required = false) String agencyCode,
                                         @RequestParam(required = false) String severity,
                                         @RequestParam(required = false) String review,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size,
                                         AdminPrincipal admin) {
        return service.list(new AnomalyAdminService.Query(from, to, rule, agencyCode, severity, review, page, size), admin);
    }

    @GetMapping("/stats")
    public AnomalyAdminService.Stats stats(@RequestParam(defaultValue = "90") int days,
                                           @RequestParam(required = false) String agencyCode,
                                           AdminPrincipal admin) {
        return service.stats(days, admin, agencyCode);
    }

    @PostMapping(value = "/{flagId}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> review(@PathVariable String flagId, @RequestBody ReviewBody body, AdminPrincipal admin,
                                      @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String cid = correlationId != null ? correlationId : UUID.randomUUID().toString();
        return service.review(flagId, body.verdict(), body.note(), admin, cid);
    }
}
