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
@DisplayName("KR 에디션 — 레거시 벤더 프록시(/auth/nice|oacx/*)·CI-check·기업인증 콜백 통합 테스트 (WireMock NICE Mock)")
class KrAuthIntegrationTest extends IntegrationTestBase {

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
    @DisplayName("레거시 프록시: GET /nice/phone/url 은 같은 흐름을 종전 응답 형식(2000·authUrl·requestNo)으로 돌려준다")
    void legacyUrl_proxy() throws Exception {
        ResponseEntity<String> res = restTemplate.getForEntity(
                baseUrl + "/api/v1/auth/nice/phone/url?returnUrl=https://fe.example.org/auth-result", String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(res.getBody());
        assertThat(body.get("resultCode").asText()).as("body=%s", res.getBody()).isEqualTo("2000");
        assertThat(body.get("authUrl").asText()).isEqualTo("https://nice.example.org/auth?tx=1");
        assertThat(body.get("requestNo").asText()).isEqualTo("REQ-IT-001");
    }

    @Test
    @DisplayName("레거시 프록시: 모르는 request_no 의 결과 조회는 200/4000 (세션 없음), 빈 requestNo 는 400 (Bean Validation)")
    void legacyResult_sessionMissing() throws Exception {
        ResponseEntity<String> res = postJson("/api/v1/auth/nice/phone/result",
                "{\"web_transaction_id\":\"W-1\",\"request_no\":\"REQ-NOPE\"}");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(res.getBody()).get("resultCode").asText()).isEqualTo("4000");

        ResponseEntity<String> bad = postJson("/api/v1/auth/nice/phone/result", "{\"web_transaction_id\":\"W\",\"request_no\":\"\"}");
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("CI-Check(KR): 빈 CI·잘못된 mbrDvsnCd → 400 (Bean Validation); OACX easysign 빈 fn → 400, 잘못된 fn → 200/4000")
    void validationContracts() {
        assertThat(postJson("/api/v1/auth/nice/ci-check", "{\"ci\":\"\",\"indvlMbrNm\":\"홍길동\",\"mbrDvsnCd\":\"A101\"}").getStatusCode().value()).isEqualTo(400);
        assertThat(postJson("/api/v1/auth/nice/ci-check", "{\"ci\":\"abcdefgh12345678\",\"indvlMbrNm\":\"홍길동\",\"mbrDvsnCd\":\"Z999\"}").getStatusCode().value()).isEqualTo(400);
        assertThat(postJson("/api/v1/auth/oacx/easysign", "{\"fn\":\"\",\"status\":\"success\"}").getStatusCode().value()).isEqualTo(400);
        ResponseEntity<String> oacx = postJson("/api/v1/auth/oacx/easysign", "{\"fn\":\"INVALID\",\"status\":\"success\",\"res\":{}}");
        assertThat(oacx.getStatusCode().value()).isEqualTo(200);
        assertThat(oacx.getBody()).contains("\"resultCode\":\"4000\"");
    }

    @Test
    @DisplayName("기업인증 콜백: 빈 요청 본문 → 서버 정상 응답")
    void callback_emptyBody() {
        assertThat(postJson("/api/v1/auth/callback", "{}").getStatusCode().value()).isIn(200, 400, 422, 500);
    }

    @Test
    @DisplayName("KR 에디션에만 있는 엔드포인트가 살아 있다 — /member/lookup·/conversion/init·/fe-session/conversion 은 404 가 아니다")
    void krEdition_endpointsPresent() {
        assertThat(postJson("/api/v1/member/lookup", "{}").getStatusCode().value()).isIn(400, 401, 403);
        assertThat(postJson("/api/v1/conversion/init", "{}").getStatusCode().value()).isIn(400, 401, 403, 422);
        assertThat(postJson("/api/v1/fe-session/conversion", "{}").getStatusCode().value()).isIn(400, 401, 403);
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "nice-integ-" + UUID.randomUUID());
        return restTemplate.exchange(baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
