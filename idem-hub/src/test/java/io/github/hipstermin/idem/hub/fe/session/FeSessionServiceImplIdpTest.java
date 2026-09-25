package io.github.hipstermin.idem.hub.fe.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
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

/** S6 PR-2: FE 세션이 Keycloak sub·sid 를 기억하고, IdP 세션 종료 통지로 정확히 그 세션을 만료한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FeSessionServiceImpl — IdP(sub·sid) 역인덱스와 invalidateByIdpSession")
class FeSessionServiceImplIdpTest {

    @Mock RedisTemplate<String, Object> redis;
    @Mock ValueOperations<String, Object> values;
    @Mock SetOperations<String, Object> sets;
    FeSessionServiceImpl sut;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(values);
        given(redis.opsForSet()).willReturn(sets);
        sut = new FeSessionServiceImpl(redis);
        ReflectionTestUtils.setField(sut, "slidingTtlMinutes", 30);
        ReflectionTestUtils.setField(sut, "absoluteTimeoutMinutes", 480);
    }

    @Test
    @DisplayName("create(…, idpSub, idpSid): 세션에 담기고 fe:idp-sid·fe:idp-sub 역인덱스가 만들어진다")
    void create_storesIdpIndexes() {
        FeSession s = sut.create("qim-1", "ar-1", "L2", null, "kc-sub", "sid-1");
        assertThat(s.getIdpSub()).isEqualTo("kc-sub");
        assertThat(s.getIdpSid()).isEqualTo("sid-1");
        verify(values).set(eq("fe:idp-sid:sid-1"), eq(s.getFeSessionId()), any(Duration.class));
        verify(sets).add("fe:idp-sub:kc-sub", s.getFeSessionId());

        FeSession legacy = sut.create("qim-2", "ar-2", "L1", null);
        assertThat(legacy.getIdpSub()).isNull();
        assertThat(legacy.getIdpSid()).isNull();
    }

    @Test
    @DisplayName("invalidateByIdpSession(sid): 그 세션 하나만 만료하고 인덱스를 지운다")
    void invalidateBySid() {
        FeSession s = FeSession.builder().feSessionId("fe-1").qimUserId("qim-1").idpSub("kc-sub").idpSid("sid-1").build();
        given(values.get("fe:idp-sid:sid-1")).willReturn("fe-1");
        given(values.get("fe:session:fe-1")).willReturn(s);

        int n = sut.invalidateByIdpSession("kc-sub", "sid-1", "BACKCHANNEL_LOGOUT");

        assertThat(n).isEqualTo(1);
        verify(redis).delete("fe:session:fe-1");
        verify(sets).remove("fe:user-sessions:qim-1", "fe-1");
        verify(redis, org.mockito.Mockito.atLeastOnce()).delete("fe:idp-sid:sid-1");
        verify(sets).remove("fe:idp-sub:kc-sub", "fe-1");
    }

    @Test
    @DisplayName("invalidateByIdpSession(sub 만): 그 사용자의 IdP 세션 전부 만료, 없으면 0")
    void invalidateBySub() {
        given(sets.members("fe:idp-sub:kc-sub")).willReturn(Set.of("fe-1", "fe-2"));
        given(values.get("fe:session:fe-1")).willReturn(FeSession.builder().feSessionId("fe-1").qimUserId("q").build());
        given(values.get("fe:session:fe-2")).willReturn(FeSession.builder().feSessionId("fe-2").qimUserId("q").build());
        assertThat(sut.invalidateByIdpSession("kc-sub", null, "SLO")).isEqualTo(2);
        verify(redis).delete("fe:idp-sub:kc-sub");

        given(sets.members("fe:idp-sub:nobody")).willReturn(Set.of());
        assertThat(sut.invalidateByIdpSession("nobody", null, "SLO")).isZero();
    }

    @Test
    @DisplayName("refresh 가 idp 필드를 잃지 않는다")
    void refresh_keepsIdpFields() {
        FeSession s = FeSession.builder().feSessionId("fe-1").qimUserId("q").authResultId("a").authLevel("L1")
                .createdAt(java.time.Instant.now()).lastActivityAt(java.time.Instant.now())
                .absoluteExpiresAt(java.time.Instant.now().plusSeconds(3600)).idpSub("kc-sub").idpSid("sid-1").build();
        given(values.get("fe:session:fe-1")).willReturn(s);
        ArgumentCaptor<Object> saved = ArgumentCaptor.forClass(Object.class);
        FeSession r = sut.refresh("fe-1");
        verify(values).set(eq("fe:session:fe-1"), saved.capture(), any(Duration.class));
        assertThat(((FeSession) saved.getValue()).getIdpSid()).isEqualTo("sid-1");
        assertThat(r.getIdpSub()).isEqualTo("kc-sub");
    }
}
