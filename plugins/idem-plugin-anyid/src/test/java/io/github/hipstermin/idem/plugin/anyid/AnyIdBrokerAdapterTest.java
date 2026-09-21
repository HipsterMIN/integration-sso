package io.github.hipstermin.idem.plugin.anyid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@DisplayName("AnyIdBrokerAdapter — DirectBrokerAdapter 구현")
class AnyIdBrokerAdapterTest {

    private AnyIdProperties props;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private AnyIdBrokerAdapter adapter;

    @BeforeEach
    void setUp() {
        props = new AnyIdProperties();
        props.setSrvcNo("SRVC-TEST");
        props.setAgencyCode("AG-TEST");
        props.setAgencyName("테스트기관");
        props.getAuth().setBaseUrl("https://auth.example.test");
        props.getPid().setProviderUrl("https://auth.example.test/pid/auth.do");
        props.getPid().setClientId("pid-client");
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        adapter = new AnyIdBrokerAdapter(props, new ObjectMapper(), restTemplate);
    }

    @Test
    @DisplayName("id 는 provider_config.broker_mode 값 anyid, supports 는 AnyID 인증수단 코드 휴리스틱")
    void idAndSupports() {
        assertThat(adapter.id()).isEqualTo("anyid");
        assertThat(adapter.supports("MOBILE_ID")).isTrue();
        assertThat(adapter.supports("financial-cert")).isTrue();
        assertThat(adapter.supports("ANYID_CUSTOM")).isTrue();
        assertThat(adapter.supports("KAKAO")).isFalse();
        assertThat(adapter.supports("PASS")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    @DisplayName("운영기관 식별자가 비어 있으면 IDO_PROVIDER_NOT_CONFIGURED 로 거부한다")
    void rejectsWhenAgencyNotConfigured() {
        props.setSrvcNo("");
        assertThatThrownBy(() -> adapter.buildAuthorizationUrl("mobile-id", "cid-1", null, "L2"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_PROVIDER_NOT_CONFIGURED));
    }

    @Test
    @DisplayName("init 성공 → AnyID 가 준 redirectUrl 을 그대로 돌려준다 (srvc_no·callback 포함)")
    void initSuccess() {
        server.expect(requestTo("https://auth.example.test/api/v1/init"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.srvc_no").value("SRVC-TEST"))
                .andExpect(jsonPath("$.provider").value("MOBILE_ID"))
                .andExpect(jsonPath("$.callback_url").value(org.hamcrest.Matchers.startsWith("/api/v1/anyid/mobile-id/callback?cid=cid-1")))
                .andRespond(withSuccess("{\"result_code\":\"0000\",\"txId\":\"TX-1\",\"redirectUrl\":\"https://ui.example.test/mid?tx=TX-1\"}",
                        MediaType.APPLICATION_JSON));

        String url = adapter.buildAuthorizationUrl("mobile-id", "cid-1", "https://svc.example.test/done", "L2");

        assertThat(url).isEqualTo("https://ui.example.test/mid?tx=TX-1");
        server.verify();
    }

    @Test
    @DisplayName("init 이 result_code≠0000 이면 IDP_RESPONSE_INVALID")
    void initVendorError() {
        server.expect(requestTo("https://auth.example.test/api/v1/init"))
                .andRespond(withSuccess("{\"result_code\":\"1001\",\"result_msg\":\"bad srvc\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.buildAuthorizationUrl("easy-sign", "cid-2", null, "L1"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDP_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("(D2) AnyID 서버 장애(5xx·연결 실패)면 로컬 팝업 폴백 없이 IDP_PROVIDER_UNAVAILABLE 로 거부한다")
    void initServerErrorIsRejected() {
        server.expect(requestTo("https://auth.example.test/api/v1/init")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.buildAuthorizationUrl("joint-cert", "cid-3", null, "L3"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE));
    }

    @Test
    @DisplayName("소셜(kakao 등)은 PRIVATE_ID → pid 리다이렉트 URL (HTTP 호출 없음)")
    void privateIdRedirect() {
        String url = adapter.buildAuthorizationUrl("kakao", "cid-4", "https://svc.example.test/done", "L1");

        assertThat(url).startsWith("https://auth.example.test/pid/auth.do?")
                .contains("srvc_no=SRVC-TEST")
                .contains("client_id=pid-client")
                .contains("callback=");
        server.verify(); // 요청 없음
    }
}
