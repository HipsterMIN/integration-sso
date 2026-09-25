package io.github.hipstermin.idem.hub.slo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.qim.sp.infrastructure.InstMbrIdMappingRepository;
import io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** S6 PR-2: SLO 가 gate 에 Keycloak sub·sid 를 넘긴다 (종전엔 qimUserId 를 sub 로 넘겨 항상 실패). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SloServiceImpl — Keycloak 세션 종료 요청은 FE 세션의 idpSub·idpSid 로")
class SloServiceImplIdpTest {

    static WireMockServer gate = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    @Mock WebhookDispatcherService webhook;
    @Mock AuditLogPublisher audit;
    @Mock InstMbrIdMappingRepository mapping;
    SloServiceImpl sut;

    @BeforeAll static void start() { gate.start(); }
    @AfterAll static void stop() { gate.stop(); }

    @BeforeEach
    void setUp() {
        gate.resetAll();
        sut = new SloServiceImpl(new RestTemplate(), webhook, audit, mapping, new ObjectMapper());
        ReflectionTestUtils.setField(sut, "qsignBaseUrl", "http://localhost:" + gate.port());
        ReflectionTestUtils.setField(sut, "internalSigSecret", "0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(sut, "internalSigTtlSeconds", 60);
        given(mapping.findByQimUserId(anyString())).willReturn(Optional.empty());
        gate.stubFor(WireMock.post(urlEqualTo("/api/v1/internal/session/logout")).willReturn(aResponse().withStatus(204)));
    }

    @Test
    @DisplayName("idpSub·idpSid 가 있으면 그 값을 보내고 qimUserId 를 sub 로 쓰지 않는다")
    void sendsIdpSubAndSid() {
        FeSession s = FeSession.builder().feSessionId("fe-1").qimUserId("qim-1").idpSub("kc-sub").idpSid("sid-1").build();
        sut.executeSlo(s, "cid-1");
        gate.verify(1, postRequestedFor(urlEqualTo("/api/v1/internal/session/logout"))
                .withRequestBody(containing("\"sub\":\"kc-sub\"")).withRequestBody(containing("\"sid\":\"sid-1\""))
                .withRequestBody(notMatching(".*\"sub\":\"qim-1\".*"))
                .withHeader("X-Internal-Sig", WireMock.matching("[0-9a-f]{64}")));
    }

    @Test
    @DisplayName("Keycloak 을 거치지 않은 세션(idp 정보 없음)은 gate 를 부르지 않는다")
    void nonKeycloakSession_skipsGate() {
        FeSession s = FeSession.builder().feSessionId("fe-2").qimUserId("qim-2").build();
        sut.executeSlo(s, "cid-2");
        gate.verify(0, postRequestedFor(urlEqualTo("/api/v1/internal/session/logout")));
    }
}
