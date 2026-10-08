package io.github.hipstermin.idem.hub.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.audit.AuditQueryService;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminRole;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiAssistantService — 켜짐 가드(공개 엔드포인트 거부), 초안은 스키마 검증·코드/테넌트 고정, 감사 요약은 집계만, 장애 요약은 전역만")
class AiAssistantServiceTest {

    @Mock LlmClient llm;
    @Mock AuditQueryService auditQuery;
    @Mock OpsSnapshotService ops;
    @Mock AdminTenantScope tenantScope;
    @Mock AuditLogPublisher audit;
    AiProperties props = new AiProperties();
    AiAssistantService sut;

    static final AdminPrincipal GLOBAL = new AdminPrincipal("a1", "admin", AdminRole.SYSTEM_ADMIN, null, "sid", false);
    static final AdminPrincipal TENANT = new AdminPrincipal("a2", "t-admin", AdminRole.POLICY_ADMIN, "T1", "sid", false);

    @BeforeEach
    void setUp() {
        props.setEnabled(true);
        props.setBaseUrl("http://idem-ai:11434/v1");
        props.setModel("m");
        sut = new AiAssistantService(props, llm, new ServiceProfileValidator(), auditQuery, ops, tenantScope, audit, new ObjectMapper());
        sut.init();
    }

    @Test
    void disabledWhenOffOrPublicEndpoint() {
        props.setEnabled(false); sut.init();
        assertThat(sut.status().enabled()).isFalse();
        assertThatThrownBy(() -> sut.summarizeIncident(GLOBAL, "c")).isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode().getCode()).isEqualTo("E-IDO-140"));

        props.setEnabled(true); props.setBaseUrl("https://api.example.com/v1"); sut.init();
        assertThat(sut.status().enabled()).isFalse();
        assertThat(sut.status().reason()).contains("사설망");

        props.setAllowPublicEndpoint(true); sut.init();
        assertThat(sut.status().enabled()).isTrue();
        assertThat(sut.status().endpointHost()).isEqualTo("api.example.com");
    }

    @Test
    void draftValidatesAgainstSchemaAndPinsCodeAndTenant() {
        given(llm.chat(anyString(), anyString(), eq(true), anyString())).willReturn("""
                ```json
                {"service":{"code":"WRONG","name":"테스트 기관","status":"ACTIVE"},
                 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["https://agency.example.org/cb"]}},
                 "policy":{"minAuthLevel":"L1"}}
                ```""");
        AiAssistantService.Draft d = sut.draftProfile(new AiAssistantService.DraftRequest("콜백 하나짜리 Handoff 기관", "AG_1", null), TENANT, "cid");
        assertThat(d.draft().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(d.draft().path("service").path("code").asText()).isEqualTo("AG_1");
        assertThat(d.draft().path("service").path("tenant").asText()).isEqualTo("T1");
        assertThat(d.violations()).isEmpty();
        verify(tenantScope).checkService(TENANT, "AG_1");
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), user.capture(), eq(true), eq("cid"));
        assertThat(user.getValue()).contains("AG_1").contains("T1");
        ArgumentCaptor<AuditLogPublisher.AuditEntry> entry = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(entry.capture());
        assertThat(entry.getValue().eventAction()).isEqualTo("AI_PROFILE_DRAFT");
    }

    @Test
    void draftReportsSchemaViolationsAndRejectsNonJson() {
        given(llm.chat(anyString(), anyString(), eq(true), anyString())).willReturn("{\"service\":{\"code\":\"x\"},\"protocol\":{\"type\":\"NOPE\"}}");
        AiAssistantService.Draft d = sut.draftProfile(new AiAssistantService.DraftRequest("뭔가", null, null), GLOBAL, "cid");
        assertThat(d.violations()).isNotEmpty();
        assertThat(d.note()).contains("위반");

        given(llm.chat(anyString(), anyString(), eq(true), anyString())).willReturn("죄송합니다, 만들 수 없습니다.");
        assertThatThrownBy(() -> sut.draftProfile(new AiAssistantService.DraftRequest("뭔가", null, null), GLOBAL, "cid"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode().getCode()).isEqualTo("E-IDO-142"));
        assertThatThrownBy(() -> sut.draftProfile(new AiAssistantService.DraftRequest("  ", null, null), GLOBAL, "cid"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode().getCode()).isEqualTo("E-IDO-143"));
    }

    @Test
    void auditSummarySendsDigestOnlyAndSkipsLlmWhenEmpty() {
        Map<String, Object> row = Map.of("occurredAt", "2026-10-07T00:00:00Z", "category", "ADMIN", "action", "ADMIN_LOGIN_FAILED",
                "actorType", "ADMIN", "actorId", "administrator", "outcome", "FAILURE", "outcomeDetail", "bad", "sourceIp", "10.0.0.9", "metadata", "{\"pii\":1}");
        given(auditQuery.search(any())).willReturn(new AuditQueryService.Page(List.of(row), 0, 200, 1));
        given(llm.chat(anyString(), anyString(), eq(false), anyString())).willReturn(" 요약 ");
        AiAssistantService.AuditSummary s = sut.summarizeAudit(new AiAssistantService.AuditSummaryRequest(null, null, "ADMIN", null, null, null), GLOBAL, "cid");
        assertThat(s.summary()).isEqualTo("요약");
        assertThat(s.digest().total()).isEqualTo(1);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), sent.capture(), eq(false), eq("cid"));
        assertThat(sent.getValue()).doesNotContain("10.0.0.9").doesNotContain("pii").doesNotContain("administrator").contains("ad***");

        given(auditQuery.search(any())).willReturn(new AuditQueryService.Page(List.of(), 0, 200, 0));
        AiAssistantService.AuditSummary empty = sut.summarizeAudit(new AiAssistantService.AuditSummaryRequest(null, null, null, null, null, null), GLOBAL, "cid2");
        assertThat(empty.summary()).contains("없습니다");
        verify(llm, never()).chat(anyString(), anyString(), anyBoolean(), eq("cid2"));
    }

    @Test
    void tenantAdminNeedsAgencyInScopeAndIncidentIsGlobalOnly() {
        assertThatThrownBy(() -> sut.summarizeAudit(new AiAssistantService.AuditSummaryRequest(null, null, null, null, null, null), TENANT, "c"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode().getCode()).isEqualTo("E-IDO-131"));
        given(tenantScope.inScope(TENANT, "OTHER")).willReturn(false);
        assertThatThrownBy(() -> sut.summarizeAudit(new AiAssistantService.AuditSummaryRequest(null, null, null, null, "OTHER", null), TENANT, "c"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode().getCode()).isEqualTo("E-IDO-131"));
        assertThatThrownBy(() -> sut.summarizeIncident(TENANT, "c"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode().getCode()).isEqualTo("E-IDO-131"));

        OpsSnapshotService.Snapshot snap = new OpsSnapshotService.Snapshot("now", Map.of("db", "UP"), Map.of("PENDING", 3L), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        given(ops.snapshot()).willReturn(snap);
        given(llm.chat(anyString(), anyString(), eq(false), anyString())).willReturn("정상");
        AiAssistantService.IncidentSummary inc = sut.summarizeIncident(GLOBAL, "c");
        assertThat(inc.summary()).isEqualTo("정상");
        assertThat(inc.snapshot().webhookOutbox()).containsEntry("PENDING", 3L);
    }
}
