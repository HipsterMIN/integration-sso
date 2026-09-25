package io.github.hipstermin.idem.hub.kr.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.hipstermin.idem.hub.integration.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * S5a — NICE 는 플러그인(idem-plugin-nice-oacx)이 제공하고 코어는 SPI 경로와 레거시 프록시만 갖는다.
 * WireMock 이 NICE API(토큰·URL 발급)를 흉내 낸다 (`ido.auth.nice.base-url` → WireMock).
 */
@DisplayName("NICE 본인인증 플러그인 — SPI 경로 통합 테스트 (WireMock NICE Mock). 레거시 프록시·CI-check 는 KR 에디션(idem-kr-hub KrAuthIntegrationTest)")
class NiceAuthIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        wireMockServer.resetAll();
        WireMock.configureFor("localhost", wireMockServer.port());
        // NICE 토큰 발급 (플러그인 NiceApiClient 경로)
        stubFor(post(urlPathEqualTo("/ido/intc/v1.0/auth/token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"result_code\":\"0000\",\"result_message\":\"OK\",\"access_token\":\"mock-at\","
                                + "\"expires_in\":" + (System.currentTimeMillis() + 3_600_000L) + ",\"token_type\":\"bearer\","
                                + "\"iterators\":1000,\"ticket\":\"mock-ticket-1234567890\"}")));
        // NICE 표준창 URL 발급
        stubFor(post(urlPathEqualTo("/ido/intc/v1.0/auth/url"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"result_code\":\"0000\",\"result_message\":\"OK\",\"request_no\":\"REQ-IT-001\","
                                + "\"auth_url\":\"https://nice.example.org/auth?tx=1\",\"transaction_id\":\"TX-IT-001\"}")));
    }

    @Test
    @DisplayName("플러그인이 켜지면 /api/v1/auth/providers 에 NICE_PHONE(L2, EzAuth 위젯) 이 있고 MOCK 도 함께 있다")
    void providers_listNicePhone() throws Exception {
        ResponseEntity<String> res = restTemplate.getForEntity(baseUrl + "/api/v1/auth/providers", String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode list = objectMapper.readTree(res.getBody());
        assertThat(list.findValuesAsText("code")).contains("NICE_PHONE", "MOCK");
        JsonNode nice = null;
        for (JsonNode n : list) if ("NICE_PHONE".equals(n.get("code").asText())) nice = n;
        assertThat(nice).isNotNull();
        assertThat(nice.get("level").asText()).isEqualTo("L2");
        assertThat(nice.at("/widget/globalName").asText()).isEqualTo("EzAuth");
    }

    @Test
    @DisplayName("SPI: POST /providers/NICE_PHONE/initiate → WireMock 토큰·URL 을 거쳐 txId=request_no, redirectUrl=auth_url")
    void spiInitiate_viaPlugin() throws Exception {
        ResponseEntity<String> res = postJson("/api/v1/auth/providers/NICE_PHONE/initiate",
                "{\"returnUrl\":\"https://fe.example.org/auth-result\"}");
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(res.getBody());
        assertThat(body.get("txId").asText()).isEqualTo("REQ-IT-001");
        assertThat(body.get("redirectUrl").asText()).isEqualTo("https://nice.example.org/auth?tx=1");
    }

    // D3-b 에서 KR 에디션으로 옮겨진 뒤 "코어에는 KR 엔드포인트가 없다" 검사는 이 컨텍스트(KR 에디션)에서 성립하지 않아 제거했다
    // — 코어 404 는 CI 설치본 스모크(scripts/ci/install-smoke.sh) 가 코어 hub 에 대해 검사한다

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "nice-integ-" + UUID.randomUUID());
        return restTemplate.exchange(baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
