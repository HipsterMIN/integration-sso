package io.github.hipstermin.idem.hub.slo;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.qim.sp.infrastructure.InstMbrIdMappingRepository;
import io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** S6 PR-2: SLO 가 gate 에 Keycloak sub·sid 를 넘긴다. 1.1: 실패는 재시도 큐에 적재한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SloServiceImpl — Keycloak 세션 종료 요청과 실패 시 재시도 큐")
class SloServiceImplIdpTest {

    static WireMockServer gate = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    @Mock WebhookDispatcherService webhook;
    @Mock AuditLogPublisher audit;
    @Mock InstMbrIdMappingRepository mapping;
    @Mock SloIdpLogoutRetryQueue retryQueue;
    SloServiceImpl sut;

    @BeforeAll static void start() { gate.start(); }
    @AfterAll static void stop() { gate.stop(); }

    @BeforeEach
    void setUp() {
        gate.resetAll();
        IdpSessionRevoker revoker = new IdpSessionRevoker(new RestTemplate());
        ReflectionTestUtils.setField(revoker, "gateBaseUrl", "http://localhost:" + gate.port());
        ReflectionTestUtils.setField(revoker, "internalSigSecret", "0123456789abcdef0123456789abcdef");
        sut = new SloServiceImpl(revoker, retryQueue, webhook, audit, mapping, new ObjectMapper());
        given(mapping.findByQimUserId(anyString())).willReturn(Optional.empty());
        gate.stubFor(WireMock.post(urlEqualTo("/api/v1/internal/session/logout"))
                .willReturn(aResponse().withStatus(204).withHeader("X-Idp-Logout-Outcome", "REVOKED_SESSION")));
    }

    private static FeSession kcSession(String id) {
        return FeSession.builder().feSessionId(id).qimUserId("qim-1").idpSub("kc-sub").idpSid("sid-1").build();
    }

    @Test
    @DisplayName("idpSub·idpSid 가 있으면 그 값을 보내고 qimUserId 를 sub 로 쓰지 않는다")
    void sendsIdpSubAndSid() {
        sut.executeSlo(kcSession("fe-1"), "cid-1");
        gate.verify(1, postRequestedFor(urlEqualTo("/api/v1/internal/session/logout"))
                .withRequestBody(containing("\"sub\":\"kc-sub\"")).withRequestBody(containing("\"sid\":\"sid-1\""))
                .withRequestBody(notMatching(".*\"sub\":\"qim-1\".*"))
                .withHeader("X-Internal-Sig", WireMock.matching("[0-9a-f]{64}")));
        verify(retryQueue, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Keycloak 을 거치지 않은 세션(idp 정보 없음)은 gate 를 부르지 않는다")
    void nonKeycloakSession_skipsGate() {
        sut.executeSlo(FeSession.builder().feSessionId("fe-2").qimUserId("qim-2").build(), "cid-2");
        gate.verify(0, postRequestedFor(urlEqualTo("/api/v1/internal/session/logout")));
    }

    @Test
    @DisplayName("1.1: gate 가 502(Keycloak 실패)면 재시도 큐에 적재한다")
    void gate502_enqueuesRetry() {
        gate.stubFor(WireMock.post(urlEqualTo("/api/v1/internal/session/logout"))
                .willReturn(aResponse().withStatus(502).withHeader("X-Idp-Logout-Outcome", "FAILED")));
        sut.executeSlo(kcSession("fe-3"), "cid-3");
        verify(retryQueue).enqueue(eq("fe-3"), eq("qim-1"), eq("kc-sub"), eq("sid-1"), eq("cid-3"), anyString());
    }

    @Test
    @DisplayName("1.1: 204 라도 outcome 헤더가 FAILED 면 실패로 보고 적재한다 (구 gate 호환)")
    void outcomeFailedHeader_enqueuesRetry() {
        gate.stubFor(WireMock.post(urlEqualTo("/api/v1/internal/session/logout"))
                .willReturn(aResponse().withStatus(204).withHeader("X-Idp-Logout-Outcome", "FAILED")));
        sut.executeSlo(kcSession("fe-4"), "cid-4");
        verify(retryQueue).enqueue(eq("fe-4"), eq("qim-1"), any(), any(), eq("cid-4"), contains("FAILED"));
    }

    @Test
    @DisplayName("1.1: gate 가 죽어 있어도(연결 실패) SLO 는 계속되고 재시도 큐에 적재한다")
    void gateDown_enqueuesRetry() {
        IdpSessionRevoker revoker = new IdpSessionRevoker(new RestTemplate());
        ReflectionTestUtils.setField(revoker, "gateBaseUrl", "http://127.0.0.1:1");   // 닫힌 포트
        ReflectionTestUtils.setField(revoker, "internalSigSecret", "0123456789abcdef0123456789abcdef");
        sut = new SloServiceImpl(revoker, retryQueue, webhook, audit, mapping, new ObjectMapper());
        sut.executeSlo(kcSession("fe-5"), "cid-5");
        verify(retryQueue).enqueue(eq("fe-5"), any(), any(), any(), eq("cid-5"), anyString());
        verify(audit).publish(any());   // ③ 감사는 그대로 진행
    }
}
