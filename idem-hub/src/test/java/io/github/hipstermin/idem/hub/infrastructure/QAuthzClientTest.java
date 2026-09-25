package io.github.hipstermin.idem.hub.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
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
    @DisplayName("(D2) q-authz 장애: 빈 역할로 위장하지 않고 IDO_AUTHZ_UNAVAILABLE 로 거부")
    void getEffectiveRoles_failSecureOnError() {
        when(qAuthzRestTemplate.exchange(
                any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("connection refused"));

        assertThatThrownBy(() -> client.getEffectiveRoles("u1", "GOV_SMES", "cid"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE));
    }

    @Test
    @DisplayName("(D2) idem.hub.authz.enabled=false — 명시적 스위치: authz 호출 없이 항상 빈 역할")
    void getEffectiveRoles_disabledExplicitly() {
        ReflectionTestUtils.setField(client, "enabled", false);

        assertThat(client.getEffectiveRoles("u1", "GOV_SMES", "cid")).isEmpty();
        verifyNoInteractions(qAuthzRestTemplate);
    }

    @Test
    @DisplayName("입력 누락: 호출 없이 빈 역할 반환")
    void getEffectiveRoles_blankInput() {
        assertThat(client.getEffectiveRoles(null, "GOV_SMES", "cid")).isEmpty();
        assertThat(client.getEffectiveRoles("u1", "", "cid")).isEmpty();
    }

    // ── S8-b getServiceAccess ─────────────────────────────────────────────────
    @Test
    @DisplayName("S8-b getServiceAccess: assigned·assignmentSource·roles 를 파싱한다")
    void getServiceAccess_success() {
        ResponseEntity<Map> resp = new ResponseEntity<>(
                Map.of("qimUserId", "u1", "agencyCode", "GOV_SMES", "assigned", true,
                        "assignmentSource", "CONSOLE", "roles", List.of("MANAGER")),
                HttpStatus.OK);
        when(qAuthzRestTemplate.exchange(
                contains("/users/u1/access"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(resp);
        ServiceAccess access = client.getServiceAccess("u1", "GOV_SMES", "cid");
        assertThat(access.authzEnabled()).isTrue();
        assertThat(access.assigned()).isTrue();
        assertThat(access.assignmentSource()).isEqualTo("CONSOLE");
        assertThat(access.roles()).containsExactly("MANAGER");
    }

    @Test
    @DisplayName("S8-b getServiceAccess: 장애 → IDO_AUTHZ_UNAVAILABLE (미할당으로 위장하지 않는다)")
    void getServiceAccess_failSecure() {
        when(qAuthzRestTemplate.exchange(
                any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("down"));
        assertThatThrownBy(() -> client.getServiceAccess("u1", "GOV_SMES", "cid"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE));
    }

    @Test
    @DisplayName("S8-b getServiceAccess: idem.hub.authz.enabled=false → disabled() (authzEnabled=false, 호출 없음)")
    void getServiceAccess_disabled() {
        ReflectionTestUtils.setField(client, "enabled", false);
        ServiceAccess access = client.getServiceAccess("u1", "GOV_SMES", "cid");
        assertThat(access.authzEnabled()).isFalse();
        assertThat(access.assigned()).isFalse();
        verifyNoInteractions(qAuthzRestTemplate);
    }
}
