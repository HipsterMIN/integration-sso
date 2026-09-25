package io.github.hipstermin.idem.hub.fe.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** D3: 프로파일 policy.session 이 FE 세션에 실제로 적용된다 — 줄이기만 하고 늘리지 않으며, 동시 세션 초과분은 오래된 것부터 끝낸다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FeSessionServiceImpl.applySessionPolicy")
class FeSessionServiceImplPolicyTest {

    @Mock RedisTemplate<String, Object> redis;
    @Mock ValueOperations<String, Object> values;
    @Mock SetOperations<String, Object> sets;
    FeSessionServiceImpl sut;
    final Instant created = Instant.now().minus(Duration.ofMinutes(10));

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(values);
        given(redis.opsForSet()).willReturn(sets);
        sut = new FeSessionServiceImpl(redis);
        ReflectionTestUtils.setField(sut, "slidingTtlMinutes", 30L);
        ReflectionTestUtils.setField(sut, "absoluteTimeoutMinutes", 480L);
    }

    private FeSession session(String id, Instant createdAt) {
        return FeSession.builder().feSessionId(id).qimUserId("u1").authResultId("ar").authLevel("L1")
                .createdAt(createdAt).lastActivityAt(createdAt).absoluteExpiresAt(createdAt.plus(Duration.ofMinutes(480))).build();
    }

    @Test
    @DisplayName("유휴·절대 만료를 프로파일 값으로 줄인다")
    void tightens() {
        given(values.get("fe:session:s1")).willReturn(session("s1", created));
        given(redis.getExpire("fe:session:s1")).willReturn(1800L);

        FeSession updated = sut.applySessionPolicy("s1", 10, 60, null).orElseThrow();

        assertThat(updated.getAbsoluteExpiresAt()).isEqualTo(created.plus(Duration.ofMinutes(60)));
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("fe:session:s1"), any(), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("프로파일 값이 더 느슨하면 아무것도 늘리지 않는다")
    void neverLoosens() {
        given(values.get("fe:session:s1")).willReturn(session("s1", created));
        given(redis.getExpire("fe:session:s1")).willReturn(1800L);

        FeSession updated = sut.applySessionPolicy("s1", 60, 600, null).orElseThrow();

        assertThat(updated.getAbsoluteExpiresAt()).isEqualTo(created.plus(Duration.ofMinutes(480)));
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("fe:session:s1"), any(), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(1800));
    }

    @Test
    @DisplayName("concurrent=1 이면 같은 사용자의 다른 세션을 오래된 순으로 만료하고 이 세션은 남긴다")
    void enforcesConcurrent() {
        given(values.get("fe:session:s1")).willReturn(session("s1", created));
        given(values.get("fe:session:old1")).willReturn(session("old1", created.minus(Duration.ofHours(2))));
        given(values.get("fe:session:old2")).willReturn(session("old2", created.minus(Duration.ofHours(1))));
        given(redis.getExpire("fe:session:s1")).willReturn(1800L);
        given(sets.members("fe:user-sessions:u1")).willReturn(Set.of("s1", "old1", "old2"));

        sut.applySessionPolicy("s1", null, null, 1);

        verify(redis).delete("fe:session:old1");
        verify(redis).delete("fe:session:old2");
        verify(redis, never()).delete("fe:session:s1");
    }

    @Test
    @DisplayName("concurrent=2 이면 가장 오래된 하나만 만료")
    void concurrentTwoKeepsNewest() {
        given(values.get("fe:session:s1")).willReturn(session("s1", created));
        given(values.get("fe:session:old1")).willReturn(session("old1", created.minus(Duration.ofHours(2))));
        given(values.get("fe:session:old2")).willReturn(session("old2", created.minus(Duration.ofHours(1))));
        given(redis.getExpire("fe:session:s1")).willReturn(1800L);
        given(sets.members("fe:user-sessions:u1")).willReturn(Set.of("s1", "old1", "old2"));

        sut.applySessionPolicy("s1", null, null, 2);

        verify(redis).delete("fe:session:old1");
        verify(redis, never()).delete("fe:session:old2");
    }

    @Test
    void missingSessionIsEmpty() {
        given(values.get("fe:session:none")).willReturn(null);
        assertThat(sut.applySessionPolicy("none", 10, 10, 1)).isEmpty();
    }
}
