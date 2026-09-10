package io.github.hipstermin.idem.hub.auth.spi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.spi.identity.AuthWidgetDescriptor;
import io.github.hipstermin.idem.common.spi.identity.IdentityProviderRegistry;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
import io.github.hipstermin.idem.hub.auth.dto.im.QimRegisterResponse;
import io.github.hipstermin.idem.hub.identity.SubjectRegistrationService;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IdentityVerificationControllerTest {

    private MockMvc mvc;

    /** 코어 테스트는 플러그인 클래스를 보지 않으므로 인라인 가짜 제공자를 쓴다. */
    static class FakeProvider implements IdentityVerificationProvider {
        @Override public String code() { return "FAKE"; }
        @Override public AuthResult.AuthLevel level() { return AuthResult.AuthLevel.L3; }
        @Override public VerificationStart initiate(VerificationRequest r) {
            return new VerificationStart("FAKE", "tx-1", r.returnUrl() + "#go", Map.of("k", String.valueOf(r.param("name"))));
        }
        @Override public VerifiedIdentity complete(VerificationCallback c) {
            if ("bad".equals(c.txId())) throw new IdentityVerificationException("FAKE", "NOPE", "denied");
            return new VerifiedIdentity("FAKE", c.txId(), "subj", "이름", "20000101", "2", "010", "K",
                    AuthResult.AuthLevel.L3, Instant.EPOCH, Map.of());
        }
        @Override public Optional<AuthWidgetDescriptor> widget() {
            return Optional.of(new AuthWidgetDescriptor("/plugins/fake/w.js", "Fake", Map.of("a", 1)));
        }
    }

    @BeforeEach
    void setUp() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of(new FakeProvider()));
        // S4: complete 는 registry 에 주체를 등록한다 — QimClient 를 흉내 내 qimUserId 를 돌려준다
        QimClient qimClient = org.mockito.Mockito.mock(QimClient.class);
        org.mockito.Mockito.when(qimClient.registerSubject(org.mockito.ArgumentMatchers.any()))
                .thenReturn(QimRegisterResponse.builder().qimUserId("qim-fake-1").status("ACTIVE").isNew(true).build());
        mvc = MockMvcBuilders.standaloneSetup(new IdentityVerificationController(registry, new SubjectRegistrationService(qimClient)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /providers — 코드·등급·위젯 기술자")
    void list() throws Exception {
        mvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("FAKE"))
                .andExpect(jsonPath("$[0].level").value("L3"))
                .andExpect(jsonPath("$[0].widget.scriptUrl").value("/plugins/fake/w.js"))
                .andExpect(jsonPath("$[0].widget.globalName").value("Fake"));
    }

    @Test
    @DisplayName("POST /{code}/initiate — 코드는 대소문자 무시, 본문 없이도 동작")
    void initiate() throws Exception {
        mvc.perform(post("/api/v1/auth/providers/fake/initiate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"returnUrl\":\"https://fe\",\"params\":{\"name\":\"n\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txId").value("tx-1"))
                .andExpect(jsonPath("$.redirectUrl").value("https://fe#go"))
                .andExpect(jsonPath("$.params.k").value("n"));
        mvc.perform(post("/api/v1/auth/providers/FAKE/initiate"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /{code}/complete — 성공은 identity + registration(qimUserId), 실패는 E-IDO-110, 미등록 코드는 E-IDO-109")
    void complete() throws Exception {
        mvc.perform(post("/api/v1/auth/providers/FAKE/complete").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "c-9")
                        .content("{\"txId\":\"tx-1\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.subjectKey").value("subj"))
                .andExpect(jsonPath("$.identity.subjectScheme").value("EXTERNAL_SUB"))
                .andExpect(jsonPath("$.identity.level").value("L3"))
                .andExpect(jsonPath("$.registration.qimUserId").value("qim-fake-1"))
                .andExpect(jsonPath("$.registration.newUser").value(true));

        mvc.perform(post("/api/v1/auth/providers/FAKE/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txId\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E-IDO-110"));

        mvc.perform(post("/api/v1/auth/providers/NOPE/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txId\":\"tx-1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E-IDO-109"));

        mvc.perform(post("/api/v1/auth/providers/FAKE/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{}}"))
                .andExpect(status().isBadRequest());
    }
}
