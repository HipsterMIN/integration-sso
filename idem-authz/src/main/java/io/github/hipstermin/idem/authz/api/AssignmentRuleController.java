package io.github.hipstermin.idem.authz.api;

import io.github.hipstermin.idem.authz.api.dto.AccessEvaluateRequest;
import io.github.hipstermin.idem.authz.api.dto.AssignmentRuleRequest;
import io.github.hipstermin.idem.authz.api.dto.AssignmentRuleResponse;
import io.github.hipstermin.idem.authz.api.dto.ServiceAccessResponse;
import io.github.hipstermin.idem.authz.application.AssignmentRuleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1 규칙 할당 내부 API ({@code /api/v1/internal/authz/**}, {@code X-Internal-Api-Key}).
 *
 * <ul>
 *   <li>POST   /assignment-rules              — 규칙 생성 (GROUP | ATTRIBUTE)</li>
 *   <li>GET    /assignment-rules?agencyCode=  — 규칙 목록(비활성 포함)</li>
 *   <li>DELETE /assignment-rules/{id}         — 규칙 비활성화 + 실체화 할당 회수 → {@code {revoked: n}}</li>
 *   <li>POST   /users/{qimUserId}/access      — hub 발급 경로의 접근 평가(직접 할당 → 규칙 실체화). GET 은 읽기 전용으로 남는다</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/internal/authz")
@RequiredArgsConstructor
public class AssignmentRuleController {

    private final AssignmentRuleService ruleService;

    @PostMapping("/assignment-rules")
    public ResponseEntity<AssignmentRuleResponse> create(
            @Valid @RequestBody AssignmentRuleRequest req,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                AssignmentRuleResponse.from(ruleService.create(req, resolveActor(actor), clientIp(request), correlationId)));
    }

    @GetMapping("/assignment-rules")
    public ResponseEntity<List<AssignmentRuleResponse>> list(@RequestParam("agencyCode") String agencyCode) {
        return ResponseEntity.ok(ruleService.list(agencyCode).stream().map(AssignmentRuleResponse::from).toList());
    }

    @DeleteMapping("/assignment-rules/{id}")
    public ResponseEntity<Map<String, Object>> disable(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletRequest request) {
        UUID ruleId;
        try {
            ruleId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "규칙 id 형식 오류: " + id);
        }
        int revoked = ruleService.disable(ruleId, resolveActor(actor), clientIp(request), correlationId);
        return ResponseEntity.ok(Map.of("id", id, "enabled", false, "revoked", revoked));
    }

    /** hub 발급 경로의 접근 평가 — 직접 할당이 없으면 규칙을 실체화한다. 응답은 GET /access 와 같은 모양. */
    @PostMapping("/users/{qimUserId}/access")
    public ResponseEntity<ServiceAccessResponse> evaluate(
            @PathVariable String qimUserId,
            @Valid @RequestBody AccessEvaluateRequest req,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletRequest request) {
        AssignmentRuleService.Access a = ruleService.evaluate(qimUserId, req.agencyCode(), req.attributes(),
                clientIp(request), correlationId);
        return ResponseEntity.ok(new ServiceAccessResponse(qimUserId, req.agencyCode(), a.assigned(), a.assignmentSource(), a.roles()));
    }

    private String resolveActor(String actor) {
        return (actor == null || actor.isBlank()) ? "SYSTEM" : actor;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
