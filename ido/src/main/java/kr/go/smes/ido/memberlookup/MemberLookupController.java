package kr.go.smes.ido.memberlookup;

import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.audit.AuditLogPublisher;
import kr.go.smes.common.event.AuditLogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CI 기반 회원 통합 조회 API 컨트롤러
 *
 * <p><b>엔드포인트</b>:
 * <ul>
 *   <li>POST /api/v1/member/lookup      — CI 기반 회원 조회</li>
 *   <li>GET  /api/v1/member/lookup/hash — identifierHash 기반 회원 조회</li>
 * </ul>
 *
 * <p><b>보안</b>: X-Agency-Code + X-Agency-Key 헤더 검증 (HandoffAgencyKeyInterceptor 적용)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/member")
@RequiredArgsConstructor
public class MemberLookupController {

    private final MemberLookupService memberLookupService;
    private final AuditLogPublisher   auditLogPublisher;

    /**
     * CI 기반 회원 조회
     * POST /api/v1/member/lookup
     * body: { "encryptedCi": "v1.xxx.yyy" }
     */
    @PostMapping("/lookup")
    public ResponseEntity<Map<String, Object>> lookupByCi(
            @RequestHeader("X-Agency-Code") String agencyCode,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody Map<String, String> body) {

        String cid = cid(correlationId);
        String encryptedCi = body.get("encryptedCi");

        if (encryptedCi == null || encryptedCi.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MISSING_ENCRYPTED_CI",
                    "message", "encryptedCi는 필수입니다"));
        }

        log.info("[MemberLookupCtrl] CI 조회 요청: agencyCode={} cid={}", agencyCode, cid);

        Map<String, Object> result = memberLookupService.lookupByCi(encryptedCi, agencyCode, cid);

        // 감사 로그 기록
        publishAudit("MEMBER_LOOKUP_BY_CI", agencyCode, cid, AuditLogEvent.OUTCOME_SUCCESS);

        return ResponseEntity.ok(result);
    }

    /**
     * identifierHash 기반 회원 조회
     * GET /api/v1/member/lookup/hash?identifierHash=...
     */
    @GetMapping("/lookup/hash")
    public ResponseEntity<Map<String, Object>> lookupByHash(
            @RequestHeader("X-Agency-Code") String agencyCode,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam String identifierHash) {

        String cid = cid(correlationId);
        log.info("[MemberLookupCtrl] Hash 조회: agencyCode={} cid={}", agencyCode, cid);

        Map<String, Object> result = memberLookupService.lookupByHash(identifierHash, agencyCode, cid);
        publishAudit("MEMBER_LOOKUP_BY_HASH", agencyCode, cid, AuditLogEvent.OUTCOME_SUCCESS);

        return ResponseEntity.ok(result);
    }

    // ── private ─────────────────────────────────────────────────────────────

    private String cid(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            CorrelationIdHolder.set(correlationId);
            return correlationId;
        }
        return CorrelationIdHolder.generate();
    }

    private void publishAudit(String action, String agencyCode, String cid, String outcome) {
        try {
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
                    .eventAction(action)
                    .actorType(AuditLogEvent.ACTOR_AGENCY)
                    .actorId(agencyCode)
                    .resourceType("MEMBER")
                    .resourceId(agencyCode)
                    .agencyCode(agencyCode)
                    .outcome(outcome)
                    .build());
        } catch (Exception e) {
            log.warn("[MemberLookupCtrl] 감사 로그 실패 (비치명적): {}", e.getMessage());
        }
    }
}
