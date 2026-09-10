package io.github.hipstermin.idem.hub.ext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * ExtProxyController 인가 속성 전파 단위 테스트.
 *
 * <p>buildForwardHeaders()를 ReflectionTestUtils로 직접 호출하여
 * X-Authz-* 헤더 주입 및 anti-spoofing(FE 위조 헤더 제거)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExtProxyController 인가 속성 전파")
class ExtProxyControllerAuthzTest {

    @Mock RestTemplate qimRestTemplate;
    @Mock FeSessionService feSessionService;
    @Mock QAuthzClient qAuthzClient;

    private ExtProxyController controller;

    private static final String QIM_USER_ID = "qim-user-ext-1";
    private static final String FE_SESSION_ID = "fe-sess-ext-1";

    @BeforeEach
    void setUp() {
        controller = new ExtProxyController(qimRestTemplate, feSessionService, qAuthzClient);
        ReflectionTestUtils.setField(controller, "extApiKey", "ext-key");
    }

    @SuppressWarnings("unchecked")
    private HttpHeaders invokeBuildForwardHeaders(MockHttpServletRequest req) {
        return (HttpHeaders) ReflectionTestUtils.invokeMethod(controller, "buildForwardHeaders", req);
    }

    private FeSession session() {
        return FeSession.builder()
                .feSessionId(FE_SESSION_ID)
                .qimUserId(QIM_USER_ID)
                .authLevel("MEDIUM")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Test
    @DisplayName("FE 세션 보유 → X-Authz-User/Roles/Scope 주입")
    void injectsAuthzHeaders_whenSessionPresent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("feSessionId", FE_SESSION_ID));
        when(feSessionService.findById(FE_SESSION_ID)).thenReturn(Optional.of(session()));
        when(qAuthzClient.getEffectiveRoles(eq(QIM_USER_ID), eq("PLATFORM"), any()))
                .thenReturn(List.of("PLATFORM_ADMIN", "SUPPORT_STAFF"));

        HttpHeaders headers = invokeBuildForwardHeaders(req);

        assertThat(headers.getFirst("X-Authz-User")).isEqualTo(QIM_USER_ID);
        assertThat(headers.getFirst("X-Authz-Scope")).isEqualTo("PLATFORM");
        assertThat(headers.getFirst("X-Authz-Roles")).isEqualTo("PLATFORM_ADMIN,SUPPORT_STAFF");
    }

    @Test
    @DisplayName("anti-spoofing: FE가 위조한 X-Authz-* 헤더는 제거되고 서버값으로 대체")
    void stripsSpoofedAuthzHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("feSessionId", FE_SESSION_ID));
        req.addHeader("X-Authz-User", "attacker");
        req.addHeader("X-Authz-Roles", "PLATFORM_ADMIN");
        when(feSessionService.findById(FE_SESSION_ID)).thenReturn(Optional.of(session()));
        when(qAuthzClient.getEffectiveRoles(eq(QIM_USER_ID), eq("PLATFORM"), any()))
                .thenReturn(List.of());

        HttpHeaders headers = invokeBuildForwardHeaders(req);

        // 위조 user 제거 → 서버 해석값으로 대체, 위조 역할 제거 → 빈 문자열(L0)
        assertThat(headers.getFirst("X-Authz-User")).isEqualTo(QIM_USER_ID);
        assertThat(headers.get("X-Authz-User")).hasSize(1);
        assertThat(headers.getFirst("X-Authz-Roles")).isEmpty();
    }

    @Test
    @DisplayName("세션 없음 → 인가 헤더 미주입(fail-open 통과)")
    void noSession_noAuthzHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest(); // 쿠키 없음

        HttpHeaders headers = invokeBuildForwardHeaders(req);

        assertThat(headers.containsKey("X-Authz-User")).isFalse();
        assertThat(headers.containsKey("X-Authz-Roles")).isFalse();
        // ext-api-key는 정상 주입 (기존 동작 유지)
        assertThat(headers.getFirst("X-Ext-Api-Key")).isEqualTo("ext-key");
    }

    @Test
    @DisplayName("q-authz 장애여도 anti-spoof 위조 헤더는 제거(fail-open)")
    void failOpen_stillStripsSpoofed() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("feSessionId", FE_SESSION_ID));
        req.addHeader("X-Authz-User", "attacker");
        lenient().when(feSessionService.findById(FE_SESSION_ID))
                .thenThrow(new RuntimeException("session store down"));

        HttpHeaders headers = invokeBuildForwardHeaders(req);

        // 전파는 실패해도 위조 헤더는 절대 통과하지 않아야 함
        assertThat(headers.containsKey("X-Authz-User")).isFalse();
    }
}
