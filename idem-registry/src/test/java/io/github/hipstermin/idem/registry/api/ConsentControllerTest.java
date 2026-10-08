package io.github.hipstermin.idem.registry.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.registry.consent.ConsentService;
import io.github.hipstermin.idem.registry.consent.ConsentVersionInfo;
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

/** 1.1 동의 카탈로그 내부 API — 목록 분기(catalog·범위·이력)·발행·종료·미동의 (standalone MockMvc). */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentController — 1.1 카탈로그 엔드포인트")
class ConsentControllerTest {

    @Mock ConsentService consentService;
    MockMvc mvc;

    static ConsentVersionInfo info(String id, String scope, String type, boolean required) {
        return ConsentVersionInfo.builder().versionId(id).serviceCode(scope).consentType(type).status("ACTIVE")
                .versionTag("v1").title(type + " 제목").required(required).effectiveAt(Instant.parse("2026-10-01T00:00:00Z")).build();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ConsentController(consentService)).build();
    }

    @Test
    void listBranches() throws Exception {
        given(consentService.getActiveVersions()).willReturn(List.of(info("p1", null, "TERMS_OF_SERVICE", true)));
        mvc.perform(get("/api/v1/internal/consent-versions")).andExpect(status().isOk()).andExpect(jsonPath("$[0].versionId").value("p1"));
        given(consentService.catalog("AG1")).willReturn(List.of(info("p1", null, "TERMS_OF_SERVICE", true), info("s1", "AG1", "THIRD_PARTY_SHARE", true)));
        mvc.perform(get("/api/v1/internal/consent-versions?serviceCode=AG1&catalog=true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].serviceCode").value("AG1"));
        given(consentService.listVersions("AG1", true)).willReturn(List.of(info("s1", "AG1", "THIRD_PARTY_SHARE", true)));
        mvc.perform(get("/api/v1/internal/consent-versions?serviceCode=AG1&includeInactive=true")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void publishRetireMissing() throws Exception {
        given(consentService.publish(any())).willReturn(info("s2", "AG1", "THIRD_PARTY_SHARE", true));
        mvc.perform(post("/api/v1/internal/consent-versions").contentType(MediaType.APPLICATION_JSON).header("X-Correlation-Id", "c-1")
                        .content("{\"serviceCode\":\"AG1\",\"consentType\":\"THIRD_PARTY_SHARE\",\"versionTag\":\"2026-10\",\"title\":\"제3자 제공\",\"required\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versionId").value("s2"));
        ArgumentCaptor<ConsentService.PublishRequest> req = ArgumentCaptor.forClass(ConsentService.PublishRequest.class);
        org.mockito.Mockito.verify(consentService).publish(req.capture());
        org.assertj.core.api.Assertions.assertThat(req.getValue().serviceCode()).isEqualTo("AG1");
        org.assertj.core.api.Assertions.assertThat(req.getValue().correlationId()).isEqualTo("c-1");

        given(consentService.retire(eq("s2"), any())).willReturn(info("s2", "AG1", "THIRD_PARTY_SHARE", true));
        mvc.perform(post("/api/v1/internal/consent-versions/s2/retire")).andExpect(status().isOk()).andExpect(jsonPath("$.versionId").value("s2"));

        given(consentService.missing("u1", "AG1")).willReturn(List.of(info("s2", "AG1", "THIRD_PARTY_SHARE", true)));
        mvc.perform(get("/api/v1/internal/users/u1/consents/missing?serviceCode=AG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionId").value("s2")).andExpect(jsonPath("$[0].required").value(true));
    }
}
