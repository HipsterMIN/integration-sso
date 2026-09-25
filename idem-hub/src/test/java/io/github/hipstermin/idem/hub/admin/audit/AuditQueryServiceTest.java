package io.github.hipstermin.idem.hub.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditQueryService — 조건은 WHERE 에만, size 는 200 으로 클램프")
class AuditQueryServiceTest {

    @Mock JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void buildsWhereAndClamps() {
        given(jdbc.queryForObject(any(String.class), eq(Long.class), any(Object[].class))).willReturn(3L);
        given(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class))).willReturn(List.of());
        AuditQueryService sut = new AuditQueryService(jdbc);
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        AuditQueryService.Page page = sut.search(new AuditQueryService.Query(from, null, "ADMIN", null, "sys", null, "FAILURE", null, 2, 9999));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        String where = sql.getValue().substring(sql.getValue().indexOf("WHERE"));
        assertThat(where).contains("occurred_at >= ?").contains("event_category = ?").contains("actor_id = ?").contains("outcome = ?")
                .doesNotContain("event_action = ?").doesNotContain("agency_code = ?").endsWith("ORDER BY occurred_at DESC LIMIT ? OFFSET ?");
        Object[] a = args.getValue();
        assertThat(a).hasSize(6);
        assertThat(a[4]).isEqualTo(200);
        assertThat(a[5]).isEqualTo(400);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(200);
    }
}
