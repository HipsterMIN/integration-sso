package io.github.hipstermin.idem.hub.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("QAuthzClient 단위 테스트")
class QAuthzClientTest {

    @Mock private RestTemplate qAuthzRestTemplate;

    private QAuthzClient client;

    @BeforeEach
    void setUp() {
        client = new QAuthzClient(qAuthzRestTemplate);
        ReflectionTestUtils.setField(client, "qAuthzBaseUrl", "http://localhost:8086");
        ReflectionTestUtils.setField(client, "qAuthzInternalApiKey", "test-key");
    }

    @Test
    @DisplayName("정상 응답: roles 배열을 List<String>로 파싱")
    void getEffectiveRoles_success() {
        ResponseEntity<Map> resp = new ResponseEntity<>(
                Map.of("qimUserId", "u1", "agencyCode", "GOV_SMES",
                        "roles", List.of("MANAGER", "REVIEWER")),
                HttpStatus.OK);
        when(qAuthzRestTemplate.exchange(
                contains("/effective-roles"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(resp);

        List<String> roles = client.getEffectiveRoles("u1", "GOV_SMES", "cid");

        assertThat(roles).containsExactly("MANAGER", "REVIEWER");
    }

    @Test
    @DisplayName("q-authz 장애: fail-open으로 빈 역할 반환(예외 전파 안 함)")
    void getEffectiveRoles_failOpenOnError() {
        when(qAuthzRestTemplate.exchange(
                any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("connection refused"));

        List<String> roles = client.getEffectiveRoles("u1", "GOV_SMES", "cid");

        assertThat(roles).isEmpty();
    }

    @Test
    @DisplayName("입력 누락: 호출 없이 빈 역할 반환")
    void getEffectiveRoles_blankInput() {
        assertThat(client.getEffectiveRoles(null, "GOV_SMES", "cid")).isEmpty();
        assertThat(client.getEffectiveRoles("u1", "", "cid")).isEmpty();
    }
}
