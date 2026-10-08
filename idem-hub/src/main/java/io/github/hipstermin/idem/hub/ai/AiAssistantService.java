package io.github.hipstermin.idem.hub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.audit.AuditQueryService;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileValidator;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 1.1 AI 운영 보조 — 관리 콘솔의 세 가지 보조: 자연어 → 프로파일 초안(스키마 검증 필수), 감사 요약, 장애 요약.
 *
 * <ul>
 *   <li><b>인증 경로에는 없다.</b> 관리 API 뒤에서만 돌고, 초안은 저장하지 않는다(관리자가 검토해 PUT 한다).</li>
 *   <li>LLM 에는 집계·표본·스키마만 간다 — 감사 원문 전부, IP, metadata, 비밀값은 보내지 않는다.</li>
 *   <li>꺼져 있으면 {@code E-IDO-140}(404). 엔드포인트가 공개 호스트면 {@code allowPublicEndpoint} 없이는 켜지지 않는다.</li>
 *   <li>호출마다 감사({@code ADMIN / AI_*})를 남긴다 — 내용은 남기지 않고 모델·크기·위반 수만.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    public static final String AUDIT_CATEGORY = "ADMIN";
    public static final String ACTION_DRAFT = "AI_PROFILE_DRAFT";
    public static final String ACTION_AUDIT = "AI_AUDIT_SUMMARY";
    public static final String ACTION_INCIDENT = "AI_INCIDENT_SUMMARY";
    static final int PROMPT_MAX = 4000;

    private final AiProperties props;
    private final LlmClient llm;
    private final ServiceProfileValidator validator;
    private final AuditQueryService auditQuery;
    private final OpsSnapshotService ops;
    private final AdminTenantScope tenantScope;
    private final AuditLogPublisher auditLogPublisher;
    private final ObjectMapper objectMapper;

    private boolean enabled;
    private String disabledReason;

    @PostConstruct
    void init() {
        if (!props.isEnabled()) { enabled = false; disabledReason = "idem.hub.ai.enabled=false"; return; }
        String host = props.endpointHost();
        if (host == null || host.isBlank()) {
            enabled = false; disabledReason = "base-url 이 올바르지 않습니다: " + props.getBaseUrl();
            log.warn("[AI] 운영 보조를 켜지 않습니다 — {}", disabledReason);
            return;
        }
        if (!props.isAllowPublicEndpoint() && !ServiceProfileValidator.isInternalHost(host)) {
            enabled = false;
            disabledReason = "LLM 엔드포인트 " + host + " 가 사설망·루프백이 아닙니다 — 온프레미스 LLM 만 기본 허용(IDEM_HUB_AI_ALLOW_PUBLIC_ENDPOINT=true 로 명시해야 공개 엔드포인트)";
            log.warn("[AI] 운영 보조를 켜지 않습니다 — {}", disabledReason);
            return;
        }
        enabled = true; disabledReason = null;
        log.info("[AI] 운영 보조 켬: model={} host={}", props.getModel(), host);
    }

    public record Status(boolean enabled, String model, String endpointHost, String reason) {}

    public Status status() {
        return new Status(enabled, enabled ? props.getModel() : null, enabled ? props.endpointHost() : null, disabledReason);
    }

    public boolean isEnabled() { return enabled; }

    private void requireEnabled(String cid) {
        if (!enabled) throw new PlatformException(PlatformErrorCode.AI_DISABLED, cid, disabledReason);
    }

    // ── 1. 자연어 → 프로파일 초안 ───────────────────────────────────────────

    public record DraftRequest(String prompt, String serviceCode, JsonNode base) {}
    public record Draft(JsonNode draft, List<String> violations, String model, String note) {}

    public Draft draftProfile(DraftRequest req, AdminPrincipal admin, String cid) {
        requireEnabled(cid);
        if (req == null || req.prompt() == null || req.prompt().isBlank()) throw new PlatformException(PlatformErrorCode.AI_INVALID_INPUT, cid, "prompt 가 비어 있습니다");
        if (req.prompt().length() > PROMPT_MAX) throw new PlatformException(PlatformErrorCode.AI_INVALID_INPUT, cid, "prompt 는 " + PROMPT_MAX + "자 이하");
        if (req.serviceCode() != null && !req.serviceCode().isBlank()) tenantScope.checkService(admin, req.serviceCode());

        StringBuilder user = new StringBuilder();
        user.append("요청:\n").append(req.prompt().strip()).append("\n\n");
        if (req.serviceCode() != null && !req.serviceCode().isBlank()) user.append("service.code 는 반드시 \"").append(req.serviceCode()).append("\" 다.\n");
        if (!admin.isGlobal()) user.append("service.tenant 는 반드시 \"").append(admin.tenantCode()).append("\" 다.\n");
        if (req.base() != null && req.base().isObject() && !req.base().isEmpty()) {
            user.append("\n현재 프로파일(이 문서를 출발점으로 요청대로 고친다. 요청과 무관한 키는 그대로 둔다):\n").append(req.base().toPrettyString()).append('\n');
        }
        String text = llm.chat(draftSystemPrompt(), user.toString(), true, cid);
        String json = LlmClient.extractJsonObject(text);
        if (json == null) throw new PlatformException(PlatformErrorCode.AI_OUTPUT_INVALID, cid, "응답에 JSON 객체가 없습니다");
        JsonNode draft;
        try {
            draft = objectMapper.readTree(json);
        } catch (java.io.IOException e) {
            throw new PlatformException(PlatformErrorCode.AI_OUTPUT_INVALID, cid, "JSON 해석 실패: " + e.getMessage());
        }
        if (!draft.isObject()) throw new PlatformException(PlatformErrorCode.AI_OUTPUT_INVALID, cid, "JSON 객체가 아닙니다");
        ObjectNode d = (ObjectNode) draft;
        if (!d.has("schemaVersion")) d.put("schemaVersion", 1);
        ObjectNode service = d.has("service") && d.get("service").isObject() ? (ObjectNode) d.get("service") : d.putObject("service");
        if (req.serviceCode() != null && !req.serviceCode().isBlank()) service.put("code", req.serviceCode());
        if (!admin.isGlobal()) service.put("tenant", admin.tenantCode());
        List<String> violations = validator.violations(d);
        audit(ACTION_DRAFT, admin, cid, service.path("code").asText(null), Map.of(
                "model", props.getModel(), "promptChars", req.prompt().length(), "draftChars", json.length(), "violations", violations.size()));
        log.info("[AI] 프로파일 초안: admin={} service={} violations={} cid={}", admin.username(), service.path("code").asText(null), violations.size(), cid);
        return new Draft(d, violations, props.getModel(),
                violations.isEmpty() ? "스키마 검증 통과 — 저장은 관리자가 검토한 뒤 PUT 한다" : "스키마 위반 " + violations.size() + "건 — 고치기 전에는 저장되지 않는다");
    }

    String draftSystemPrompt() {
        return """
                당신은 Idem 회원통합·연합인가 플랫폼의 서비스 프로파일 작성 보조다.
                운영자의 한국어 요청을 읽고 아래 JSON 스키마(service-profile v1)를 따르는 프로파일 JSON 객체 **하나만** 출력한다.
                규칙:
                - 출력은 JSON 객체만. 설명·마크다운·코드펜스 금지.
                - schemaVersion 은 1. service.code 는 영문·숫자·_·- (2~64자), 요청에 코드가 없으면 이름에서 영문 대문자 코드를 만든다.
                - 연동 방식: 표준 OIDC 는 protocol.type=OIDC_RP(redirectUris 필수), 서버 간 Handoff 는 DIRECT(endpoints.callbackWhitelist).
                - 비밀값(토큰·secret·키)은 절대 넣지 않는다. SCIM 토큰은 credentialRef 참조만.
                - 모르는 값은 지어내지 말고 키를 빼라. 요청에 없는 정책은 보수적으로(minAuthLevel L1, assignment 는 요청이 있을 때만).
                - 스키마에 없는 키를 만들지 않는다.
                스키마:
                """ + validator.schemaText();
    }

    // ── 2. 감사 요약 ─────────────────────────────────────────────────────────

    public record AuditSummaryRequest(Instant from, Instant to, String category, String action, String agencyCode, String outcome) {}
    public record AuditSummary(AuditDigest digest, String summary, String model) {}

    public AuditSummary summarizeAudit(AuditSummaryRequest req, AdminPrincipal admin, String cid) {
        requireEnabled(cid);
        if (!admin.isGlobal()) {
            if (req.agencyCode() == null || req.agencyCode().isBlank()) throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, cid, "Tenant 범위 관리자는 agencyCode 를 지정해야 합니다");
            if (!tenantScope.inScope(admin, req.agencyCode())) throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, cid, "다른 Tenant 의 Service: " + req.agencyCode());
        }
        AuditQueryService.Page page = auditQuery.search(new AuditQueryService.Query(req.from(), req.to(), req.category(), req.action(), null,
                req.agencyCode(), req.outcome(), null, 0, AuditQueryService.MAX_SIZE));
        AuditDigest digest = AuditDigest.of(page.total(), page.items(), props.getAuditSampleRows());
        String summary;
        if (page.items().isEmpty()) {
            summary = "조건에 맞는 감사 기록이 없습니다.";
        } else {
            summary = llm.chat(auditSystemPrompt(), toJson(digest), false, cid).strip();
        }
        audit(ACTION_AUDIT, admin, cid, req.agencyCode(), Map.of("model", props.getModel(), "rows", page.items().size(), "total", page.total()));
        return new AuditSummary(digest, summary, props.getModel());
    }

    String auditSystemPrompt() {
        return """
                당신은 Idem 플랫폼 운영자를 돕는 감사 로그 분석 보조다. 입력은 감사 기록의 집계(JSON)와 표본 몇 줄이다.
                한국어로, 사실만, 간결하게(10줄 안팎) 쓴다:
                1) 기간과 전체 건수, 2) 두드러진 행위·기관, 3) 실패(FAILURE) 의 원인별 묶음과 건수, 4) 운영자가 확인할 것 2~3가지.
                수치는 입력에 있는 그대로 쓰고, 입력에 없는 사실은 만들지 않는다. 추정은 '추정'이라고 표시한다.
                개인 식별 정보(이름·전화·주민번호 등)는 쓰지 않는다. 행위자 ID 는 입력된 마스킹 형태 그대로 둔다.
                """;
    }

    // ── 3. 장애 요약 ─────────────────────────────────────────────────────────

    public record IncidentSummary(OpsSnapshotService.Snapshot snapshot, String summary, String model) {}

    public IncidentSummary summarizeIncident(AdminPrincipal admin, String cid) {
        requireEnabled(cid);
        if (!admin.isGlobal()) throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, cid, "장애 요약은 전역 관리자만");
        OpsSnapshotService.Snapshot snap = ops.snapshot();
        String summary = llm.chat(incidentSystemPrompt(), toJson(snap), false, cid).strip();
        audit(ACTION_INCIDENT, admin, cid, null, Map.of("model", props.getModel()));
        return new IncidentSummary(snap, summary, props.getModel());
    }

    String incidentSystemPrompt() {
        return """
                당신은 Idem 플랫폼(idem-hub) 운영자를 돕는 장애 진단 보조다. 입력은 지금 시점의 운영 스냅샷(JSON)이다:
                health 구성요소(db·redis·kms), 웹훅·SCIM 아웃박스와 SLO IdP 재시도 큐의 상태별 건수(PENDING 5분 초과·24시간 FAILED·마지막 오류),
                감사 FAILURE 건수(1시간·24시간, 분류별), 감사 유실·WAL 지표, 감사 이상 탐지 플래그(24시간, 규칙별 — 관찰 모드라 경보는 아니다).
                한국어로, 사실만, 간결하게(10줄 안팎):
                1) 지금 정상인가 — 한 줄 판정(정상 / 주의 / 장애 의심), 2) 근거가 된 수치, 3) 의심되는 구성요소와 확인 순서(기관 쪽 문제인지 플랫폼 쪽 문제인지 구분),
                4) 당장 할 조치와 하지 말아야 할 것. 입력에 없는 사실은 만들지 않는다. 추정은 '추정'이라고 표시한다.
                기준: PENDING 5분 초과가 늘면 릴레이나 대상 기관 장애, FAILED 24h 는 재시도 초과(기관 응답 4xx 면 기관 설정, 5xx·연결 실패면 기관 장애),
                health 가 DOWN 인 구성요소가 있으면 그것이 1순위, audit.lost.total 이 늘면 감사 저장 경로 점검.
                """;
    }

    private String toJson(Object o) {
        try { return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(o); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private void audit(String action, AdminPrincipal admin, String cid, String agencyCode, Map<String, Object> meta) {
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory(AUDIT_CATEGORY).eventAction(action)
                .actorType("ADMIN").actorId(admin.username()).resourceType("AI").resourceId(action)
                .agencyCode(agencyCode).correlationId(cid).outcome("SUCCESS").metadata(meta)
                .build());
    }
}
