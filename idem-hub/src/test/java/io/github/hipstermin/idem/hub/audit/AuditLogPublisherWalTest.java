package io.github.hipstermin.idem.hub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 1.1: DB 저장 실패 → WAL 폴백, DB 복구 → 재생기가 재삽입(멱등). */
class AuditLogPublisherWalTest {

    private static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule());

    @SuppressWarnings("unchecked")
    private AuditLogPublisher publisher(JdbcTemplate jdbc, AuditWal wal, SimpleMeterRegistry reg) {
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        AuditLogPublisher p = new AuditLogPublisher(jdbc, kafka, OM, wal, reg);
        ReflectionTestUtils.setField(p, "dbSaveEnabled", true);
        ReflectionTestUtils.setField(p, "kafkaPublishEnabled", false);
        return p;
    }

    private static AuditLogPublisher.AuditEntry entry() {
        return AuditLogPublisher.AuditEntry.builder()
                .eventCategory("HANDOFF").eventAction("HANDOFF_ISSUED").actorType("USER").actorId("u1")
                .resourceType("TICKET").resourceId("t1").agencyCode("AG1").correlationId("cid").outcome("SUCCESS").build();
    }

    @Test
    @DisplayName("DB INSERT 실패 → WAL 에 한 줄 남고 appended 메트릭 증가, 서비스 흐름은 예외 없음")
    void dbFailureFallsBackToWal(@TempDir Path dir) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("db down"));
        AuditWal wal = new AuditWal(OM, true, dir.toString());
        SimpleMeterRegistry reg = new SimpleMeterRegistry();

        publisher(jdbc, wal, reg).publish(entry());

        assertThat(wal.pendingLines()).isEqualTo(1);
        assertThat(reg.counter(AuditLogPublisher.METRIC_WAL_APPENDED).count()).isEqualTo(1.0);
        assertThat(reg.find(AuditLogPublisher.METRIC_LOST).counter()).isNull();
    }

    @Test
    @DisplayName("DB 정상 → WAL 은 비어 있다")
    void dbOkNoWal(@TempDir Path dir) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AuditWal wal = new AuditWal(OM, true, dir.toString());
        publisher(jdbc, wal, new SimpleMeterRegistry()).publish(entry());
        assertThat(wal.pendingLines()).isZero();
    }

    @Test
    @DisplayName("WAL 비활성 + DB 실패 → lost 메트릭 (유실을 숨기지 않는다)")
    void walDisabledCountsLoss(@TempDir Path dir) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("db down"));
        AuditWal wal = new AuditWal(OM, false, dir.toString());
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        publisher(jdbc, wal, reg).publish(entry());
        assertThat(reg.counter(AuditLogPublisher.METRIC_LOST).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("재생기: DB 복구 후 ON CONFLICT DO NOTHING 으로 재삽입하고 원래 occurred_at 을 보존, 파일은 삭제")
    void replayReinsertsAndDeletes(@TempDir Path dir) throws Exception {
        AuditWal wal = new AuditWal(OM, true, dir.toString());
        wal.append("a1", entry(), null);
        wal.append("a2", entry(), "{\"x\":1}");

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        AuditWalReplayer replayer = new AuditWalReplayer(wal, publisher(jdbc, wal, reg), reg);

        assertThat(replayer.replayOnce()).isEqualTo(2);
        assertThat(wal.pendingLines()).isZero();
        assertThat(reg.counter(AuditWalReplayer.METRIC_REPLAYED).count()).isEqualTo(2.0);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("ON CONFLICT (audit_id) DO NOTHING"), args.capture());
        Object[] first = args.getAllValues().get(0);
        assertThat(first[0]).isEqualTo("a1");
        assertThat(first[first.length - 1]).isInstanceOf(java.sql.Timestamp.class);
        assertThat(((java.sql.Timestamp) first[first.length - 1]).toInstant()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("재생기: DB 아직 불가면 첫 실패에서 멈추고 나머지 줄을 순서대로 남긴다")
    void replayKeepsRemainingWhenDbStillDown(@TempDir Path dir) throws Exception {
        AuditWal wal = new AuditWal(OM, true, dir.toString());
        wal.append("a1", entry(), null);
        wal.append("a2", entry(), null);
        wal.append("a3", entry(), null);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenReturn(1)
                .thenThrow(new DataAccessResourceFailureException("still down"));
        AuditWalReplayer replayer = new AuditWalReplayer(wal, publisher(jdbc, wal, new SimpleMeterRegistry()), new SimpleMeterRegistry());

        assertThat(replayer.replayOnce()).isEqualTo(1);
        List<Path> files = wal.rotateAndListReplayFiles();
        assertThat(files).hasSize(1);
        assertThat(wal.read(files.get(0))).extracting(AuditWal.Line::auditId).containsExactly("a2", "a3");
    }

    @Test
    @DisplayName("재생기: 대기 항목이 없으면 DB 를 건드리지 않는다")
    void replayNoop(@TempDir Path dir) throws Exception {
        AuditWal wal = new AuditWal(OM, true, dir.toString());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditWalReplayer replayer = new AuditWalReplayer(wal, publisher(jdbc, wal, new SimpleMeterRegistry()), null);
        assertThat(replayer.replayOnce()).isZero();
        verifyNoInteractions(jdbc);
    }
}
