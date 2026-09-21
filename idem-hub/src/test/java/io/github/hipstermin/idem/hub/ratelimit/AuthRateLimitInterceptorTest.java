package io.github.hipstermin.idem.hub.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * D2: (1) Lua 인자를 문자열 직렬화기로 보낸다 (JSON 직렬화기로 "\"20\"" 이 가던 결함), (2) Redis 장애 = 503 + 감사 (fail-open 제거).
 */
@ExtendWith(MockitoExtension.class)
class AuthRateLimitInterceptorTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock AuditLogPublisher auditLogPublisher;

    private AuthRateLimitInterceptor sut;

    @BeforeEach
    void setUp() {
        sut = new AuthRateLimitInterceptor(redisTemplate, auditLogPublisher);
        ReflectionTestUtils.setField(sut, "rateLimitEnabled", true);
        ReflectionTestUtils.setField(sut, "tpsLimit", 20);
        ReflectionTestUtils.setField(sut, "perMinuteLimit", 100);
        ReflectionTestUtils.setField(sut, "dailyLimit", 1000);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("정상 — Lua ARGV 는 StringRedisSerializer 로, 카운터가 한도 이하면 통과 + X-RateLimit 헤더")
    void normal_passes_withStringArgs() throws Exception {
        given(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class),
                anyList(), any(), any())).willReturn(1L);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/providers/MOCK/initiate");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = sut.preHandle(req, res, new Object());

        assertThat(allowed).isTrue();
        assertThat(res.getHeader("X-RateLimit-Remaining-Second")).isEqualTo("19");
        // TPS 호출 인자는 "20"/"2" 문자열, 세 번(TPS·분·일) 모두 문자열 직렬화기
        then(redisTemplate).should().execute(any(RedisScript.class), any(RedisSerializer.class),
                any(RedisSerializer.class), anyList(), eq("20"), eq("2"));
        ArgumentCaptor<RedisSerializer<?>> argsSer = ArgumentCaptor.forClass(RedisSerializer.class);
        then(redisTemplate).should(org.mockito.Mockito.times(3)).execute(any(RedisScript.class), argsSer.capture(),
                any(RedisSerializer.class), anyList(), any(), any());
        assertThat(argsSer.getAllValues()).allMatch(StringRedisSerializer.class::isInstance);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("(D2) Redis 장애 → 503 + 감사 기록, 요청은 통과하지 않는다")
    void redisDown_denies503_andAudits() throws Exception {
        given(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class),
                anyList(), any(), any())).willThrow(new RedisConnectionFailureException("down"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/providers/MOCK/initiate");
        req.setRemoteAddr("10.0.0.7");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = sut.preHandle(req, res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("E-IDO-116");
        ArgumentCaptor<AuditLogPublisher.AuditEntry> audit = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        then(auditLogPublisher).should().publish(audit.capture());
        assertThat(audit.getValue().eventAction()).isEqualTo("RATE_LIMIT_BACKEND_UNAVAILABLE");
        assertThat(audit.getValue().actorId()).isEqualTo("10.0.0.7");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("한도 초과 → 429 (감사 아님, 정상 차단)")
    void overLimit_429() throws Exception {
        given(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class),
                anyList(), any(), any())).willReturn(21L);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = sut.preHandle(new MockHttpServletRequest("POST", "/api/v1/auth/x"), res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(429);
        then(auditLogPublisher).should(never()).publish(any());
    }

    @Test
    @DisplayName("직렬화기 상수는 문자열형이다 (회귀 방지)")
    void serializerIsString() {
        Object ser = ReflectionTestUtils.getField(AuthRateLimitInterceptor.class, "LUA_ARGS_SERIALIZER");
        assertThat(ser).isInstanceOf(StringRedisSerializer.class);
    }
}
