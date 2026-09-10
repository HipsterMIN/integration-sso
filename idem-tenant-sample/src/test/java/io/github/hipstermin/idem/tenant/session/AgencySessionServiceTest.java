package io.github.hipstermin.idem.tenant.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AgencySessionService — Mockito 기반 단위 테스트
 *
 * <p>JdbcTemplate을 Mock으로 주입하여 DB 없이 서비스 로직 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgencySessionService 단위 테스트")
class AgencySessionServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AgencySessionService sessionService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        sessionService = new AgencySessionService(jdbcTemplate, objectMapper);
        ReflectionTestUtils.setField(sessionService, "agencyCode", "AGENCY_TEST_001");
        ReflectionTestUtils.setField(sessionService, "idleTimeoutMinutes", 30);
        ReflectionTestUtils.setField(sessionService, "absoluteTimeoutMinutes", 480);
    }

    // ─────────────────────────────────────────────────────────────────────
    // invalidateByTicketId
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalidateByTicketId — ticketId 기준 세션 무효화 성공")
    void testInvalidateByTicketId_success() {
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(2);

        int count = sessionService.invalidateByTicketId("ticket-001", "HANDOFF_REVOKED", "corr-001");

        assertThat(count).isEqualTo(2);
        verify(jdbcTemplate, times(1)).update(
                argThat(sql -> sql.contains("ticket_id") && sql.contains("invalidated_at")),
                eq("HANDOFF_REVOKED"),
                eq("ticket-001")
        );
    }

    @Test
    @DisplayName("invalidateByTicketId — 해당 ticket 세션 없으면 0 반환")
    void testInvalidateByTicketId_noSession() {
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(0);

        int count = sessionService.invalidateByTicketId("ticket-unknown", "HANDOFF_REVOKED", "corr-001");

        assertThat(count).isEqualTo(0);
    }

    // ─────────────────────────────────────────────────────────────────────
    // invalidateByQimUserId
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalidateByQimUserId — qimUserId 기준 전체 세션 무효화")
    void testInvalidateByQimUserId() {
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(3);

        int count = sessionService.invalidateByQimUserId("qim-user-001", "MANDATORY_SECURITY_TERMINATE", "corr-001");

        assertThat(count).isEqualTo(3);
        verify(jdbcTemplate, atLeastOnce()).update(
                argThat(sql -> sql.contains("qim_user_id")),
                eq("MANDATORY_SECURITY_TERMINATE"),
                eq("qim-user-001")
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // invalidateByAgsid
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalidateByAgsid — rawAgsid null이면 아무것도 안 함")
    void testInvalidateByAgsid_nullAgsid() {
        sessionService.invalidateByAgsid(null, "LOGOUT", "corr-001");
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
    }

    @Test
    @DisplayName("invalidateByAgsid — rawAgsid가 있으면 SHA-256 해시로 DB 업데이트")
    void testInvalidateByAgsid_success() {
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(1);

        sessionService.invalidateByAgsid("rawAgsid123", "LOGOUT", "corr-001");

        // SHA-256(rawAgsid123) 값으로 업데이트 호출 확인
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).update(anyString(), eq("LOGOUT"), captor.capture());

        // SHA-256 결과는 64자 16진수
        String capturedHash = captor.getValue();
        assertThat(capturedHash).hasSize(64);
        assertThat(capturedHash).matches("[0-9a-f]{64}");
    }

    // ─────────────────────────────────────────────────────────────────────
    // findValidSession
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findValidSession — null rawAgsid는 빈 Optional 반환")
    void testFindValidSession_nullAgsid() {
        Optional<Map<String, Object>> result = sessionService.findValidSession(null);
        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("findValidSession — 공백 rawAgsid는 빈 Optional 반환")
    void testFindValidSession_blankAgsid() {
        Optional<Map<String, Object>> result = sessionService.findValidSession("   ");
        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("findValidSession — DB에 세션 없으면 빈 Optional 반환")
    void testFindValidSession_sessionNotFound() {
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<Map<String, Object>> result = sessionService.findValidSession("validAgsid");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findValidSession — 유효한 세션 조회 성공")
    void testFindValidSession_found() {
        Map<String, Object> sessionRow = Map.of(
                "session_id", "sess-001",
                "agency_user_id", "user-001",
                "auth_level", "L2",
                "qim_user_id", "qim-001"
        );
        when(jdbcTemplate.queryForMap(anyString(), anyString())).thenReturn(sessionRow);

        Optional<Map<String, Object>> result = sessionService.findValidSession("validAgsid");

        assertThat(result).isPresent();
        assertThat(result.get().get("session_id")).isEqualTo("sess-001");
        assertThat(result.get().get("auth_level")).isEqualTo("L2");
    }

    // ─────────────────────────────────────────────────────────────────────
    // touchSession
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("touchSession — null rawAgsid는 아무것도 안 함")
    void testTouchSession_nullAgsid() {
        sessionService.touchSession(null);
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("touchSession — 유효한 agsid로 sliding window 갱신 호출")
    void testTouchSession_success() {
        sessionService.touchSession("rawAgsid456");
        // last_accessed_at, idle_expires_at 업데이트 확인
        verify(jdbcTemplate, times(1)).update(
                argThat(sql -> sql.contains("last_accessed_at") && sql.contains("idle_expires_at")),
                any(), any(), anyString()
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // countProcessedEvent / markEventProcessed
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("countProcessedEvent — DB에서 처리 건수 조회")
    void testCountProcessedEvent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("event-001"), eq("consumer-group")))
                .thenReturn(1);

        Integer count = sessionService.countProcessedEvent("event-001", "consumer-group");
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("markEventProcessed — 이벤트 처리 완료 마킹 (ON CONFLICT DO NOTHING)")
    void testMarkEventProcessed() {
        sessionService.markEventProcessed("event-002", "consumer-group", "HANDOFF_REVOKED", "OK");

        verify(jdbcTemplate, times(1)).update(
                argThat(sql -> sql.contains("processed_event") && sql.contains("ON CONFLICT")),
                eq("event-002"),
                eq("consumer-group"),
                eq("HANDOFF_REVOKED"),
                eq("OK"),
                any()
        );
    }
}
