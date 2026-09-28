package io.github.hipstermin.idem.hub.slo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("1.1 SloIdpLogoutRetryRelay — 지수 백오프 재시도, 초과 시 FAILED + 감사")
class SloIdpLogoutRetryRelayTest {

    @Mock JdbcTemplate jdbc;
    @Mock IdpSessionRevoker revoker;
    @Mock AuditLogPublisher audit;
    @InjectMocks SloIdpLogoutRetryRelay sut;

    private static Map<String, Object> row(int retry, int max) {
        Map<String, Object> r = new HashMap<>();
        r.put("retry_id", "r1"); r.put("fe_session_id", "fe-1"); r.put("qim_user_id", "u1");
        r.put("idp_sub", "sub"); r.put("idp_sid", "sid"); r.put("correlation_id", "cid");
        r.put("retry_count", (short) retry); r.put("max_retry", (short) max);
        return r;
    }

    @Test
    @DisplayName("성공 → DONE")
    void successMarksDone() {
        given(jdbc.queryForList(anyString(), anyInt())).willReturn(List.of(row(0, 5)));
        given(revoker.revoke("sub", "sid", "u1", "cid")).willReturn(IdpSessionRevoker.Result.ok("REVOKED_SESSION"));
        assertThat(sut.relayOnce()).isEqualTo(1);
        verify(jdbc).update(contains("status='DONE'"), eq("r1"));
        verify(audit, never()).publish(any());
    }

    @Test
    @DisplayName("실패(횟수 남음) → retry_count+1, next_retry_at = base·2^n 뒤")
    void failureSchedulesBackoff() {
        ReflectionTestUtils.setField(sut, "backoffBaseSeconds", 10);
        given(jdbc.queryForList(anyString(), anyInt())).willReturn(List.of(row(1, 5)));
        given(revoker.revoke(any(), any(), any(), any())).willReturn(IdpSessionRevoker.Result.fail(null, "gate 502"));
        sut.relayOnce();
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("next_retry_at=NOW()"), args.capture(), args.capture(), args.capture(), args.capture());
        assertThat(args.getAllValues().get(0)).isEqualTo(2);        // retry_count
        assertThat(args.getAllValues().get(1)).isEqualTo(40L);      // 10·2^2
        verify(audit, never()).publish(any());
    }

    @Test
    @DisplayName("max_retry 도달 → FAILED + 감사 SLO_IDP_LOGOUT_FAILED")
    void exhaustedMarksFailedAndAudits() {
        given(jdbc.queryForList(anyString(), anyInt())).willReturn(List.of(row(4, 5)));
        given(revoker.revoke(any(), any(), any(), any())).willReturn(IdpSessionRevoker.Result.fail("FAILED", "still failing"));
        sut.relayOnce();
        verify(jdbc).update(contains("status='FAILED'"), eq(5), any(), eq("r1"));
        ArgumentCaptor<AuditLogPublisher.AuditEntry> a = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(a.capture());
        assertThat(a.getValue().eventAction()).isEqualTo("SLO_IDP_LOGOUT_FAILED");
        assertThat(a.getValue().resourceId()).isEqualTo("fe-1");
    }
}
