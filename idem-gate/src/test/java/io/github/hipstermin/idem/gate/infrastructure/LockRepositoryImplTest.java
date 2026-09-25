package io.github.hipstermin.idem.gate.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import io.github.hipstermin.idem.gate.infrastructure.jpa.entity.AuthLockJpaEntity;
import io.github.hipstermin.idem.gate.infrastructure.jpa.repository.AuthLockJpaRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/** D3: 실패 카운터가 임계치에 닿으면 실제로 잠긴다 — 종전에는 카운터만 오르고 locked 가 켜지지 않았다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LockRepositoryImpl — 임계치 잠금·TTL 해제")
class LockRepositoryImplTest {

    @Mock AuthLockJpaRepository jpa;
    final Map<String, AuthLockJpaEntity> store = new HashMap<>();
    LockRepositoryImpl sut;

    @BeforeEach
    void setUp() {
        given(jpa.findById(anyString())).willAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        given(jpa.save(any(AuthLockJpaEntity.class))).willAnswer(inv -> {
            AuthLockJpaEntity e = inv.getArgument(0);
            store.put(e.getLockKey(), e);
            return e;
        });
        sut = new LockRepositoryImpl(jpa);
        ReflectionTestUtils.setField(sut, "lockDurationMinutes", 15L);
    }

    @Test
    @DisplayName("5번째 실패에서 locked=true, unlockAt=now+15m, 사유 MAX_ATTEMPTS")
    void locksAtThreshold() {
        for (int i = 0; i < 4; i++) sut.incrementAttempt("h", "P");
        assertThat(sut.isLocked("h", "P")).isFalse();
        assertThat(store.get("h:P").isLocked()).isFalse();

        sut.incrementAttempt("h", "P");

        AuthLockJpaEntity e = store.get("h:P");
        assertThat(e.isLocked()).isTrue();
        assertThat(e.getAttemptCount()).isEqualTo((short) 5);
        assertThat(e.getLastFailReason()).isEqualTo("MAX_ATTEMPTS");
        assertThat(e.getUnlockAt()).isAfter(Instant.now().plusSeconds(14 * 60));
        assertThat(sut.isLocked("h", "P")).isTrue();
    }

    @Test
    @DisplayName("unlock 은 카운터를 0 으로 되돌리고 잠금을 푼다")
    void unlockResets() {
        for (int i = 0; i < 5; i++) sut.incrementAttempt("h", "P");
        assertThat(sut.isLocked("h", "P")).isTrue();
        sut.unlock("h", "P");
        assertThat(sut.isLocked("h", "P")).isFalse();
        assertThat(store.get("h:P").getAttemptCount()).isEqualTo((short) 0);
    }

    @Test
    @DisplayName("unlockAt 이 지난 잠금은 풀린 것으로 본다")
    void expiredLockIsNotLocked() {
        for (int i = 0; i < 5; i++) sut.incrementAttempt("h", "P");
        store.get("h:P").setUnlockAt(Instant.now().minusSeconds(1));
        assertThat(sut.isLocked("h", "P")).isFalse();
    }

    @Test
    @DisplayName("잠금 키는 identifierHash:providerCode — 제공자가 다르면 별개")
    void keyIsPerProvider() {
        for (int i = 0; i < 5; i++) sut.incrementAttempt("h", "P");
        assertThat(sut.isLocked("h", "P")).isTrue();
        assertThat(sut.isLocked("h", "Q")).isFalse();
        assertThat(sut.isLocked("other", "P")).isFalse();
    }
}
