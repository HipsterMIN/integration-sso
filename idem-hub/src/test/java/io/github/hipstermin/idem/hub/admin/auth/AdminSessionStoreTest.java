package io.github.hipstermin.idem.hub.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminSessionStore — 동시 세션 1·유휴 TTL·절대 만료·MFA 토큰 1회 소비")
class AdminSessionStoreTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    final Map<String, String> kv = new HashMap<>();
    AdminProperties props = new AdminProperties();
    AdminSessionStore sut;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(values);
        given(values.get(anyString())).willAnswer(inv -> kv.get(inv.getArgument(0, String.class)));
        org.mockito.Mockito.doAnswer(inv -> { kv.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        given(values.getAndDelete(anyString())).willAnswer(inv -> kv.remove(inv.getArgument(0, String.class)));
        given(redis.delete(anyString())).willAnswer(inv -> kv.remove(inv.getArgument(0, String.class)) != null);
        sut = new AdminSessionStore(redis, new ObjectMapper().findAndRegisterModules(), props);
    }

    private AdminUserEntity user() {
        return AdminUserEntity.builder().adminId("a1").username("sys").role(AdminRole.SYSTEM_ADMIN).passwordHash("x").build();
    }

    @Test
    void createAndTouch_thenSecondLoginEvictsFirst() {
        AdminSessionStore.AdminSession s1 = sut.create(user(), "1.1.1.1");
        assertThat(sut.touch(s1.sessionId())).isPresent();
        // create + touch — 유휴 TTL(15m) 로 두 번 저장된다
        verify(values, org.mockito.Mockito.times(2)).set(eq(AdminSessionStore.SESSION_PREFIX + s1.sessionId()), anyString(), eq(Duration.ofMinutes(15)));

        AdminSessionStore.AdminSession s2 = sut.create(user(), "1.1.1.1");
        assertThat(sut.touch(s1.sessionId())).as("동시 세션 1 — 이전 세션은 끝난다").isEmpty();
        assertThat(sut.touch(s2.sessionId())).isPresent();
    }

    @Test
    void absoluteExpiry() {
        AdminSessionStore.AdminSession s = sut.create(user(), "ip");
        AdminSessionStore.AdminSession old = new AdminSessionStore.AdminSession(s.sessionId(), "a1", "sys", AdminRole.SYSTEM_ADMIN, null, false,
                java.time.Instant.now().minus(Duration.ofHours(9)), java.time.Instant.now(), "ip");
        sut.update(old);
        assertThat(sut.touch(s.sessionId())).isEmpty();
        assertThat(kv).doesNotContainKey(AdminSessionStore.SESSION_PREFIX + s.sessionId());
    }

    @Test
    void mfaTokenConsumedOnce() {
        String token = sut.createMfaToken(new AdminSessionStore.PendingMfa("a1", "VERIFY", null, "ip"));
        assertThat(sut.consumeMfaToken(token)).isPresent();
        assertThat(sut.consumeMfaToken(token)).isEmpty();
        assertThat(sut.consumeMfaToken("nope")).isEmpty();
    }

    @Test
    void deleteAllOf() {
        AdminSessionStore.AdminSession s = sut.create(user(), "ip");
        sut.deleteAllOf("a1");
        assertThat(sut.touch(s.sessionId())).isEmpty();
        assertThat(kv).doesNotContainKey(AdminSessionStore.USER_PREFIX + "a1");
        ReflectionTestUtils.setField(props.getSession(), "idleMinutes", 1L);
    }
}
