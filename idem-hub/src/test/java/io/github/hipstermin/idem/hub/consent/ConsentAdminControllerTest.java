package io.github.hipstermin.idem.hub.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminAuthFilter;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipalArgumentResolver;
import io.github.hipstermin.idem.hub.admin.auth.AdminRole;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentAdminController — 서비스 전용·플랫폼 공통 동의 버전 목록·발행·종료, 범위·감사")
class ConsentAdminControllerTest {

    @Mock ConsentRegistryClient client;
    @Mock AdminTenantScope tenantScope;
    @Mock AuditLogPublisher audit;
    MockMvc mvc;

    static final AdminPrincipal GLOBAL = new AdminPrincipal("a1", "sys", AdminRole.SYSTEM_ADMIN, null, "sid", false);
    static final AdminPrincipal TENANT = new AdminPrincipal("a2", "pol", AdminRole.POLICY_ADMIN, "T1", "sid", false);
    static final ConsentItem V1 = new ConsentItem("v1", "MARKETING", "AG1", "ACTIVE", "1", "마케팅", "https://a.example.org/m", false, Instant.EPOCH);
    static final ConsentItem P1 = new ConsentItem("p1", "TERMS_OF_SERVICE", null, "ACTIVE", "2026-10", "이용약관", null, true, Instant.EPOCH);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ConsentAdminController(client, tenantScope, audit))
                .setCustomArgumentResolvers(new AdminPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("서비스 목록: includeInactive·catalog 플래그를 registry 에 전달, 테넌트 범위 검사")
    void listService() throws Exception {
        given(client.listVersions(eq("AG1"), eq(true), anyString())).willReturn(List.of(V1));
        given(client.catalog(eq("AG1"), anyString())).willReturn(List.of(P1, V1));
        mvc.perform(get("/api/v1/admin/services/AG1/consents?includeInactive=true").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, TENANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].versionId").value("v1")).andExpect(jsonPath("$[0].required").value(false));
        mvc.perform(get("/api/v1/admin/services/AG1/consents?catalog=true").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, TENANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].consentType").value("TERMS_OF_SERVICE"));
        verify(tenantScope, org.mockito.Mockito.times(2)).checkService(TENANT, "AG1");
        mvc.perform(get("/api/v1/admin/services/AG1/consents")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("서비스 범위 밖(다른 Tenant)은 403 E-IDO-131")
    void tenantScopeViolation() throws Exception {
        doThrow(new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "다른 Tenant")).when(tenantScope).checkService(TENANT, "AG2");
        mvc.perform(get("/api/v1/admin/services/AG2/consents").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, TENANT))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("E-IDO-131"));
        verify(client, never()).listVersions(any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("서비스 발행: 유형은 대문자 정규화, 201, 감사 ADMIN/CONSENT_VERSION_PUBLISHED(agencyCode·scope SERVICE); consentType 없으면 409 E-IM-208")
    void publishService() throws Exception {
        given(client.publish(eq("AG1"), eq("MARKETING"), eq("1"), eq("마케팅"), eq("https://a.example.org/m"), eq(false), eq(Instant.parse("2026-10-01T00:00:00Z")), anyString()))
                .willReturn(V1);
        mvc.perform(post("/api/v1/admin/services/AG1/consents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consentType\":\" marketing \",\"versionTag\":\"1\",\"title\":\"마케팅\",\"contentUrl\":\"https://a.example.org/m\",\"required\":false,\"effectiveAt\":\"2026-10-01T00:00:00Z\"}")
                        .requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, TENANT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versionId").value("v1"));
        ArgumentCaptor<AuditLogPublisher.AuditEntry> entry = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(entry.capture());
        assertThat(entry.getValue().eventAction()).isEqualTo("CONSENT_VERSION_PUBLISHED");
        assertThat(entry.getValue().actorId()).isEqualTo("pol");
        assertThat(entry.getValue().agencyCode()).isEqualTo("AG1");
        assertThat(entry.getValue().resourceId()).isEqualTo("v1");
        assertThat(entry.getValue().metadata()).containsEntry("scope", "SERVICE").containsEntry("consentType", "MARKETING");

        mvc.perform(post("/api/v1/admin/services/AG1/consents").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")
                        .requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, TENANT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E-IM-208"));
    }

    @Test
    @DisplayName("서비스 종료: 그 서비스 범위의 버전만 — 아니면 404 E-IM-207 이고 registry 를 부르지 않는다")
    void retireService() throws Exception {
        given(client.listVersions(eq("AG1"), eq(true), anyString())).willReturn(List.of(V1));
        mvc.perform(post("/api/v1/admin/services/AG1/consents/p1/retire").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, GLOBAL))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("E-IM-207"));
        verify(client, never()).retire(any(), any());

        given(client.retire(eq("v1"), anyString())).willReturn(new ConsentItem("v1", "MARKETING", "AG1", "SUPERSEDED", "1", "마케팅", null, false, Instant.EPOCH));
        mvc.perform(post("/api/v1/admin/services/AG1/consents/v1/retire").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, GLOBAL))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUPERSEDED"));
        ArgumentCaptor<AuditLogPublisher.AuditEntry> entry = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(entry.capture());
        assertThat(entry.getValue().eventAction()).isEqualTo("CONSENT_VERSION_RETIRED");
    }

    @Test
    @DisplayName("플랫폼 공통: 전역 관리자만 — 테넌트 관리자는 403; 전역은 serviceCode 없이 발행·목록·종료, 감사 agencyCode null·scope PLATFORM")
    void platformScope() throws Exception {
        mvc.perform(get("/api/v1/admin/consents").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, TENANT))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("E-IDO-131"));
        mvc.perform(post("/api/v1/admin/consents").contentType(MediaType.APPLICATION_JSON).content("{\"consentType\":\"TERMS_OF_SERVICE\"}")
                        .requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, TENANT))
                .andExpect(status().isForbidden());
        verify(client, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());

        given(client.listVersions(isNull(), eq(false), anyString())).willReturn(List.of(P1));
        mvc.perform(get("/api/v1/admin/consents").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, GLOBAL))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].versionId").value("p1")).andExpect(jsonPath("$[0].serviceCode").doesNotExist());
        given(client.publish(isNull(), eq("TERMS_OF_SERVICE"), eq("2026-10"), eq("이용약관"), isNull(), eq(true), isNull(), anyString())).willReturn(P1);
        mvc.perform(post("/api/v1/admin/consents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consentType\":\"TERMS_OF_SERVICE\",\"versionTag\":\"2026-10\",\"title\":\"이용약관\",\"required\":true}")
                        .requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, GLOBAL))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.consentType").value("TERMS_OF_SERVICE"));
        ArgumentCaptor<AuditLogPublisher.AuditEntry> entry = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(entry.capture());
        assertThat(entry.getValue().agencyCode()).isNull();
        assertThat(entry.getValue().metadata()).containsEntry("scope", "PLATFORM");
        verify(tenantScope, never()).checkService(any(), any());
    }
}
