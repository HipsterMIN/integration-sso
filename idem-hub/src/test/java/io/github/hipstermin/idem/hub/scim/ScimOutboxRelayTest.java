package io.github.hipstermin.idem.hub.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.infrastructure.AgencyCredentialStore;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 1.1 SCIM 릴레이 — 대상 해석(프로파일·토큰), op 디스패치, 재시도/종결 판정, 감사. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScimOutboxRelayTest {

    @Mock JdbcTemplate jdbc;
    @Mock ScimClient client;
    @Mock ServiceProfileService profiles;
    @Mock AgencyCredentialStore credentials;
    @Mock AuditLogPublisher audit;
    ScimOutboxRelay sut;

    @BeforeEach
    void setUp() {
        sut = new ScimOutboxRelay(jdbc, client, profiles, credentials, audit);
        ReflectionTestUtils.setField(sut, "batchSize", 20);
        ReflectionTestUtils.setField(sut, "backoffBaseSeconds", 10L);
        ServiceProfile p = ServiceProfile.builder()
                .service(ServiceProfile.Service.builder().code("AG1").name("기관").build())
                .protocol(ServiceProfile.Protocol.builder().scim(ServiceProfile.Scim.builder().enabled(true)
                        .baseUrl("https://a.example/scim/v2").credentialRef("secrets/agency/AG1/scim-token").build()).build())
                .build();
        when(profiles.find("AG1")).thenReturn(Optional.of(p));
        when(credentials.findSecret("secrets/agency/AG1/scim-token")).thenReturn("tok");
    }

    private static Map<String, Object> row(String op, String role, int retry, int max) {
        Map<String, Object> r = new HashMap<>();
        r.put("scim_id", "s-1"); r.put("agency_code", "AG1"); r.put("op", op); r.put("agency_subject_id", "pw-1");
        r.put("role_code", role); r.put("source_event_id", "e-1"); r.put("source_event_type", "AUTHZ_ASSIGNED");
        r.put("correlation_id", "cid"); r.put("retry_count", retry); r.put("max_retry", max);
        return r;
    }

    @Test
    @DisplayName("ENSURE_USER → client.ensureUser, DISPATCHED + 감사 SCIM_DISPATCHED")
    void dispatchOk() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row("ENSURE_USER", null, 0, 5)));
        assertThat(sut.relayOnce()).isEqualTo(1);
        verify(client).ensureUser(any(), eq("pw-1"), eq(true));
        verify(jdbc).update(contains("SET status = ?"), eq("DISPATCHED"), eq(200), eq(null), eq("DISPATCHED"), eq("s-1"));
        ArgumentCaptor<AuditLogPublisher.AuditEntry> a = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(a.capture());
        assertThat(a.getValue().eventAction()).isEqualTo("SCIM_DISPATCHED");
        assertThat(a.getValue().eventCategory()).isEqualTo("SCIM");
    }

    @Test
    @DisplayName("5xx 는 지수 백오프 재시도(10s<<retry), 401·403·400·501 은 즉시 FAILED, 재시도 초과도 FAILED")
    void retryAndFail() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row("ADD_GROUP_MEMBER", "R", 1, 5)));
        doThrow(new ScimClient.ScimException(503, "down")).when(client).addGroupMember(any(), eq("R"), eq("pw-1"));
        sut.relayOnce();
        verify(jdbc).update(contains("next_retry_at"), eq(2), eq(503), eq("down"), eq("20"), eq("s-1"));

        doThrow(new ScimClient.ScimException(401, "bad token")).when(client).addGroupMember(any(), eq("R"), eq("pw-1"));
        sut.relayOnce();
        verify(jdbc).update(contains("SET status = ?"), eq("FAILED"), eq(401), eq("bad token"), eq("FAILED"), eq("s-1"));

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row("ADD_GROUP_MEMBER", "R", 4, 5)));
        doThrow(new ScimClient.ScimException(503, "down")).when(client).addGroupMember(any(), eq("R"), eq("pw-1"));
        sut.relayOnce();
        verify(jdbc).update(contains("SET status = ?"), eq("FAILED"), eq(503), eq("down"), eq("FAILED"), eq("s-1"));
    }

    @Test
    @DisplayName("프로파일이 꺼졌거나 토큰이 없으면 SKIPPED(재시도 없음), HTTP 호출 없음")
    void skippedWithoutTarget() {
        when(credentials.findSecret(anyString())).thenReturn(null);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row("DELETE_USER", null, 0, 5)));
        sut.relayOnce();
        verify(client, never()).deleteUser(any(), anyString());
        verify(jdbc).update(contains("SET status = ?"), eq("SKIPPED"), eq(null), contains("자격증명"), eq("SKIPPED"), eq("s-1"));
    }

    @Test
    @DisplayName("PENDING 없음 → 0, 호출 없음")
    void empty() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertThat(sut.relayOnce()).isZero();
        verify(client, never()).ensureUser(any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
