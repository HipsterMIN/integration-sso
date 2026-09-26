package io.github.hipstermin.idem.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RequestPath — 원본 URI 정규화 (3차 점검 H1·H2 회귀)")
class RequestPathTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "/api/v1/admin/admins,            /api/v1/admin/admins",
            "/api/v1/admin;x/admins,          /api/v1/admin/admins",
            "/api/v1/admin;jsessionid=1/a,    /api/v1/admin/a",
            "/api/v1/%61dmin/admins,          /api/v1/admin/admins",
            "/actuator;x/flyway,              /actuator/flyway",
            "/resources/../admin/master/,     /admin/master",
            "/resources/%2e%2e/admin/,        /admin",
            "/realms/idem/../master/x,        /realms/master/x",
            "/realms//idem/./x,               /realms/idem/x",
            "/a/b/,                           /a/b",
            "/,                               /",
    })
    void canonical(String raw, String expected) {
        assertThat(RequestPath.canonical(raw)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/../etc", "/a/../../b", "/a/%2e%2e/%2e%2e/b", "/a%2", "/a%zz", "/a\\b", "/a\u0000b", "relative", ""})
    void unsafe_isEmpty(String raw) {
        assertThat(RequestPath.canonical(raw)).isEmpty();
    }

    @Test
    void isCanonical_onlyForPlainPaths() {
        assertThat(RequestPath.isCanonical("/api/v1/admin/admins")).isTrue();
        assertThat(RequestPath.isCanonical("/realms/idem/protocol/openid-connect/auth")).isTrue();
        assertThat(RequestPath.isCanonical("/api/v1/admin;x/admins")).isFalse();
        assertThat(RequestPath.isCanonical("/api/v1/%61dmin/admins")).isFalse();
        assertThat(RequestPath.isCanonical("/resources/../admin/")).isFalse();
        assertThat(RequestPath.isCanonical("/a//b")).isFalse();
        assertThat(RequestPath.isCanonical("/a/./b")).isFalse();
        assertThat(RequestPath.isCanonical("/a/")).isFalse();
        assertThat(RequestPath.isCanonical(null)).isFalse();
    }

    @Test
    void canonicalIfSafe() {
        assertThat(RequestPath.canonicalIfSafe("/x/y")).contains("/x/y");
        assertThat(RequestPath.canonicalIfSafe("/x;a/y")).isEmpty();
    }
}
