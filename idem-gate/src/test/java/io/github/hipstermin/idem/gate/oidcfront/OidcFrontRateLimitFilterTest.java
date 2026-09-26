package io.github.hipstermin.idem.gate.oidcfront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OidcFrontRateLimitFilter — 1.0.1 (3차 점검 M17) 공개 OIDC 프런트 IP 레이트리밋")
class OidcFrontRateLimitFilterTest {

    @Mock StringRedisTemplate redis;
    final Map<String, Long> counters = new HashMap<>();
    final OidcFrontProperties props = new OidcFrontProperties();

    OidcFrontRateLimitFilter filter() {
        given(redis.execute(any(RedisScript.class), anyList(), any()))
                .willAnswer(inv -> counters.merge(inv.getArgument(1, List.class).get(0).toString(), 1L, Long::sum));
        return new OidcFrontRateLimitFilter(redis, props);
    }

    private MockHttpServletResponse run(OidcFrontRateLimitFilter f, String path, String ip, String xff) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        req.setRemoteAddr(ip);
        if (xff != null) req.addHeader("X-Forwarded-For", xff);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        if (chain.getRequest() != null) res.setHeader("X-Chain", "passed");
        return res;
    }

    @Test
    void perSecondLimit_thenPerMinute_perIp() throws Exception {
        props.getRateLimit().setPerSecond(3);
        props.getRateLimit().setPerMinute(5);
        OidcFrontRateLimitFilter f = filter();
        for (int i = 0; i < 3; i++) assertThat(run(f, "/realms/idem/protocol/openid-connect/auth", "1.1.1.1", null).getHeader("X-Chain")).isEqualTo("passed");
        MockHttpServletResponse r = run(f, "/realms/idem/protocol/openid-connect/auth", "1.1.1.1", null);
        assertThat(r.getStatus()).isEqualTo(429);
        assertThat(r.getHeader("Retry-After")).isEqualTo("1");
        assertThat(r.getContentAsString()).contains("rate_limited");
        // 다른 IP 는 별개
        assertThat(run(f, "/realms/idem/protocol/openid-connect/auth", "2.2.2.2", null).getHeader("X-Chain")).isEqualTo("passed");
        // 분당 한도: 초 키를 흉내 내기 위해 카운터를 비운다
        counters.keySet().removeIf(k -> k.startsWith(OidcFrontRateLimitFilter.SEC_PREFIX));
        for (int i = 0; i < 2; i++) run(f, "/realms/idem/x", "1.1.1.1", null);
        counters.keySet().removeIf(k -> k.startsWith(OidcFrontRateLimitFilter.SEC_PREFIX));
        assertThat(run(f, "/realms/idem/x", "1.1.1.1", null).getStatus()).isEqualTo(429);
    }

    @Test
    void staticResourcesAndOtherPathsNotCounted_andDisableSwitch() throws Exception {
        OidcFrontRateLimitFilter f = filter();
        assertThat(f.shouldNotFilter(new MockHttpServletRequest("GET", "/resources/a/login.css") {{ setRequestURI("/resources/a/login.css"); }})).isTrue();
        assertThat(f.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/oidc/backchannel-logout") {{ setRequestURI("/api/v1/oidc/backchannel-logout"); }})).isTrue();
        assertThat(f.shouldNotFilter(new MockHttpServletRequest("GET", "/realms/idem/protocol/openid-connect/auth") {{ setRequestURI("/realms/idem/protocol/openid-connect/auth"); }})).isFalse();
        props.getRateLimit().setEnabled(false);
        assertThat(f.shouldNotFilter(new MockHttpServletRequest("GET", "/realms/idem/protocol/openid-connect/auth") {{ setRequestURI("/realms/idem/protocol/openid-connect/auth"); }})).isTrue();
    }

    @Test
    void forwardedFor_onlyWhenTrusted_lastHop() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/realms/idem/x");
        req.setRemoteAddr("10.0.0.9");
        req.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        assertThat(OidcFrontRateLimitFilter.clientIp(req, false)).isEqualTo("10.0.0.9");
        assertThat(OidcFrontRateLimitFilter.clientIp(req, true)).isEqualTo("203.0.113.7");
    }

    @Test
    void redisDown_failsClosed503() throws Exception {
        given(redis.execute(any(RedisScript.class), anyList(), any())).willThrow(new IllegalStateException("down"));
        OidcFrontRateLimitFilter f = new OidcFrontRateLimitFilter(redis, props);
        MockHttpServletResponse r = run(f, "/realms/idem/protocol/openid-connect/token", "1.1.1.1", null);
        assertThat(r.getStatus()).isEqualTo(503);
        assertThat(r.getHeader("X-Chain")).isNull();
    }
}
