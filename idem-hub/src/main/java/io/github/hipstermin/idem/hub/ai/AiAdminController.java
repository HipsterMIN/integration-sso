package io.github.hipstermin.idem.hub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hipstermin.idem.common.error.ErrorResponse;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1 AI 운영 보조 관리 API ({@code /api/v1/admin/ai/**}) — 관리 세션 + 2단계 뒤. 인가 매트릭스는 "그 외" 규칙:
 * GET(status·audit-summary·incident-summary) 은 전 역할, POST(profile-draft) 는 SYSTEM·POLICY (docs/admin-auth.md).
 * <ul>
 *   <li>{@code GET  /status} — 켜짐 여부·모델·호스트 (꺼져 있어도 200, 콘솔이 메뉴를 숨긴다)</li>
 *   <li>{@code POST /profile-draft} {prompt, serviceCode?, base?} → {draft, violations[], model, note}. 저장하지 않는다</li>
 *   <li>{@code GET  /audit-summary?from&to&category&action&agencyCode&outcome} → {digest, summary, model}</li>
 *   <li>{@code GET  /incident-summary} → {snapshot, summary, model} (전역 관리자만)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiredArgsConstructor
public class AiAdminController {

    private final AiAssistantService service;

    public record DraftBody(String prompt, String serviceCode, JsonNode base) {}

    @GetMapping("/status")
    public AiAssistantService.Status status(AdminPrincipal admin) {
        return service.status();
    }

    @PostMapping(value = "/profile-draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AiAssistantService.Draft profileDraft(@RequestBody DraftBody body, AdminPrincipal admin,
                                                 @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String cid = correlationId != null ? correlationId : UUID.randomUUID().toString();
        return service.draftProfile(new AiAssistantService.DraftRequest(body.prompt(), body.serviceCode(), body.base()), admin, cid);
    }

    @GetMapping("/audit-summary")
    public AiAssistantService.AuditSummary auditSummary(@RequestParam(required = false) Instant from,
                                                        @RequestParam(required = false) Instant to,
                                                        @RequestParam(required = false) String category,
                                                        @RequestParam(required = false) String action,
                                                        @RequestParam(required = false) String agencyCode,
                                                        @RequestParam(required = false) String outcome,
                                                        AdminPrincipal admin,
                                                        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String cid = correlationId != null ? correlationId : UUID.randomUUID().toString();
        return service.summarizeAudit(new AiAssistantService.AuditSummaryRequest(from, to, category, action, agencyCode, outcome), admin, cid);
    }

    @GetMapping("/incident-summary")
    public AiAssistantService.IncidentSummary incidentSummary(AdminPrincipal admin,
                                                              @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String cid = correlationId != null ? correlationId : UUID.randomUUID().toString();
        return service.summarizeIncident(admin, cid);
    }

    /** 상세 메시지(꺼진 이유·LLM 오류)를 그대로 — 관리 API 는 내부 호출자용 (ServiceProfileAdminController 와 같다) */
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatformException(PlatformException ex) {
        log.warn("[AiAdmin] {} cid={} : {}", ex.getErrorCode().getCode(), ex.getCorrelationId(), ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .body(ErrorResponse.builder().code(ex.getErrorCode().getCode()).message(ex.getMessage())
                        .correlationId(ex.getCorrelationId()).timestamp(Instant.now()).build());
    }
}
