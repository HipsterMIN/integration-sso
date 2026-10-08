package io.github.hipstermin.idem.hub.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@DisplayName("ConsentRegistryClient — registry 동의 카탈로그 내부 API 호출 규약·오류 매핑")
class ConsentRegistryClientTest {

    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    private final ObjectMapper om = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final ConsentRegistryClient sut = new ConsentRegistryClient(rest, om, "http://registry.test/", "k-internal");

    private static final String ITEMS = "[{\"versionId\":\"v1\",\"consentType\":\"TERMS_OF_SERVICE\",\"status\":\"ACTIVE\",\"versionTag\":\"2026-10\",\"title\":\"이용약관\","
            + "\"contentUrl\":\"https://idem.example.org/terms\",\"required\":true,\"effectiveAt\":\"2026-10-01T00:00:00Z\"},"
            + "{\"versionId\":\"v2\",\"consentType\":\"MARKETING\",\"serviceCode\":\"AG1\",\"status\":\"ACTIVE\",\"required\":false,\"unknownField\":1}]";

    @Test
    @DisplayName("catalog·missing·listVersions: 쿼리·내부 API 키·상관관계 ID 를 싣고 목록을 해석한다(미지 필드 무시, serviceCode 없음 = 플랫폼 공통)")
    void listCalls() {
        server.expect(requestTo("http://registry.test/api/v1/internal/consent-versions?catalog=true&serviceCode=AG1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", "k-internal"))
                .andExpect(header("X-Correlation-Id", "cid-1"))
                .andRespond(withSuccess(ITEMS, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://registry.test/api/v1/internal/users/u1/consents/missing?serviceCode=AG1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://registry.test/api/v1/internal/consent-versions?includeInactive=true"))
                .andRespond(withSuccess(ITEMS, MediaType.APPLICATION_JSON));

        List<ConsentItem> catalog = sut.catalog("AG1", "cid-1");
        assertThat(catalog).hasSize(2);
        assertThat(catalog.get(0).versionId()).isEqualTo("v1");
        assertThat(catalog.get(0).required()).isTrue();
        assertThat(catalog.get(0).platformItem()).isTrue();
        assertThat(catalog.get(0).effectiveAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(catalog.get(1).platformItem()).isFalse();
        assertThat(sut.missing("u1", "AG1", "cid-2")).isEmpty();
        assertThat(sut.listVersions(null, true, "cid-3")).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName("publish·retire·agree: JSON 본문과 경로")
    void writeCalls() {
        server.expect(requestTo("http://registry.test/api/v1/internal/consent-versions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.serviceCode").value("AG1"))
                .andExpect(jsonPath("$.consentType").value("MARKETING"))
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.effectiveAt").value("2026-10-01T00:00:00Z"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"versionId\":\"v9\",\"consentType\":\"MARKETING\",\"serviceCode\":\"AG1\",\"status\":\"ACTIVE\",\"required\":false}"));
        server.expect(requestTo("http://registry.test/api/v1/internal/consent-versions/v9/retire"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"versionId\":\"v9\",\"consentType\":\"MARKETING\",\"status\":\"SUPERSEDED\",\"required\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://registry.test/api/v1/internal/users/u1/consents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.versionId").value("v9"))
                .andExpect(jsonPath("$.consentType").value("MARKETING"))
                .andExpect(jsonPath("$.agreedVia").value("LOGIN_FRONT:AG1"))
                .andExpect(jsonPath("$.clientIp").value("203.0.113.9"))
                .andRespond(withSuccess("{\"qimUserId\":\"u1\",\"consentType\":\"MARKETING\",\"agreed\":true}", MediaType.APPLICATION_JSON));

        ConsentItem v = sut.publish("AG1", "MARKETING", "1", "마케팅", "https://a.example.org/m", false, Instant.parse("2026-10-01T00:00:00Z"), "cid");
        assertThat(v.versionId()).isEqualTo("v9");
        assertThat(sut.retire("v9", "cid").status()).isEqualTo("SUPERSEDED");
        sut.agree("u1", "v9", "MARKETING", "LOGIN_FRONT:AG1", "203.0.113.9", "cid");
        server.verify();
    }

    @Test
    @DisplayName("registry 4xx(검증 오류)는 E-IM-208 + 응답 본문, 5xx·연결 실패는 E-IDO-106")
    void errorMapping() {
        server.expect(requestTo("http://registry.test/api/v1/internal/consent-versions"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"E-IM-208\",\"message\":\"consentType 형식\"}"));
        server.expect(requestTo("http://registry.test/api/v1/internal/users/u1/consents/missing?serviceCode=AG1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo("http://registry.test/api/v1/internal/users/u1/consents/missing?serviceCode=AG1"))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> sut.publish("AG1", "bad type", null, null, null, true, null, "cid"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IM_CONSENT_VERSION_INVALID))
                .hasMessageContaining("consentType 형식");
        assertThatThrownBy(() -> sut.missing("u1", "AG1", "cid"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE));
        assertThatThrownBy(() -> sut.missing("u1", "AG1", "cid"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE));
        server.verify();
    }
}
