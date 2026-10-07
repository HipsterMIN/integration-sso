package io.github.hipstermin.idem.hub.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.hipstermin.idem.common.error.PlatformException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@DisplayName("LlmClient — OpenAI 호환 chat/completions, Bearer, 오류 코드, JSON 추출")
class LlmClientTest {

    static final WireMockServer wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    LlmClient client;
    AiProperties props;

    @BeforeAll static void start() { wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        WireMock.configureFor("localhost", wm.port());
        props = new AiProperties();
        props.setBaseUrl("http://localhost:" + wm.port() + "/v1/");
        props.setModel("test-model");
        props.setApiKey("k-1");
        client = new LlmClient(new RestTemplate(new JdkClientHttpRequestFactory()), new ObjectMapper(), props);
    }

    @Test
    void sendsOpenAiShapeAndReadsContent() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer k-1"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"a\\\":1}\"}}]}")));
        String out = client.chat("sys", "user", true, "cid-1");
        assertThat(out).isEqualTo("{\"a\":1}");
        WireMock.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions")).withHeader("X-Correlation-Id", equalTo("cid-1")));
    }

    @Test
    void upstreamErrorIs141AndBadBodyIs142() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client.chat("s", "u", false, "c")).isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode().getCode()).isEqualTo("E-IDO-141"));
        stubFor(post(urlPathEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200).withBody("{\"choices\":[]}")));
        assertThatThrownBy(() -> client.chat("s", "u", false, "c")).isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode().getCode()).isEqualTo("E-IDO-142"));
    }

    @Test
    void extractJsonObjectStripsFencesAndProse() {
        assertThat(LlmClient.extractJsonObject("```json\n{\"x\": 1}\n```")).isEqualTo("{\"x\": 1}");
        assertThat(LlmClient.extractJsonObject("여기 있습니다: {\"x\":{\"y\":2}} 끝.")).isEqualTo("{\"x\":{\"y\":2}}");
        assertThat(LlmClient.extractJsonObject("no json here")).isNull();
        assertThat(LlmClient.extractJsonObject(null)).isNull();
    }
}
