package io.github.hipstermin.idem.hub.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PasswordPolicy — 길이·문자 종류·사용자명·재사용")
class PasswordPolicyTest {

    final PasswordPolicy sut = new PasswordPolicy(new AdminProperties());

    @Test
    void goodPassword() {
        assertThat(sut.violations("Correct-Horse-9", "alice", List.of())).isEmpty();
        String h = sut.hash("Correct-Horse-9");
        assertThat(sut.matches("Correct-Horse-9", h)).isTrue();
        assertThat(sut.matches("wrong", h)).isFalse();
    }

    @Test
    void violations() {
        assertThat(sut.violations("short1A", "alice", null)).anyMatch(v -> v.contains("10자"));
        assertThat(sut.violations("alllowercase1", "alice", null)).anyMatch(v -> v.contains("종류"));
        assertThat(sut.violations("Alice-Passw0rd", "alice", null)).anyMatch(v -> v.contains("사용자명"));
        String old = sut.hash("Old-Password-1");
        assertThat(sut.violations("Old-Password-1", "bob", List.of(old))).anyMatch(v -> v.contains("재사용"));
        assertThat(sut.violations(null, "bob", null)).isNotEmpty();
    }

    @Test
    void temporaryPasswordSatisfiesPolicy() {
        for (int i = 0; i < 20; i++) assertThat(sut.violations(AdminUserService.temporaryPassword(), "someone", null)).isEmpty();
    }
}
