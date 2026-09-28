package io.github.hipstermin.idem.hub.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 1.1: 감사 WAL — append·회전·읽기·부분 재기록. */
class AuditWalTest {

    private static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule());

    private static AuditLogPublisher.AuditEntry entry(String action) {
        return AuditLogPublisher.AuditEntry.builder()
                .eventCategory("AUTH").eventAction(action).actorType("USER").actorId("u1")
                .agencyCode("AG1").correlationId("cid-1").outcome("SUCCESS")
                .metadata(Map.of("k", "v")).build();
    }

    @Test
    @DisplayName("append 는 한 줄씩 붙이고, 회전하면 재생 파일이 되며 줄이 그대로 읽힌다")
    void appendRotateRead(@TempDir Path dir) throws Exception {
        AuditWal wal = new AuditWal(OM, true, dir.toString());
        assertThat(wal.append("a1", entry("X"), "{\"k\":\"v\"}")).isTrue();
        assertThat(wal.append("a2", entry("Y"), null)).isTrue();
        assertThat(wal.pendingLines()).isEqualTo(2);
        assertThat(Files.readAllLines(wal.currentFile(), StandardCharsets.UTF_8)).hasSize(2);

        List<Path> files = wal.rotateAndListReplayFiles();
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName().toString()).endsWith(AuditWal.REPLAY_SUFFIX);
        assertThat(Files.exists(wal.currentFile())).isFalse();

        List<AuditWal.Line> lines = wal.read(files.get(0));
        assertThat(lines).extracting(AuditWal.Line::auditId).containsExactly("a1", "a2");
        assertThat(lines.get(0).entry().eventAction()).isEqualTo("X");
        assertThat(lines.get(0).metadataJson()).isEqualTo("{\"k\":\"v\"}");
        assertThat(lines.get(0).occurredAt()).isNotNull();
        assertThat(lines.get(1).metadataJson()).isNull();
    }

    @Test
    @DisplayName("finish: 남은 줄이 없으면 파일 삭제, 있으면 남은 줄만 다시 쓴다")
    void finishDeletesOrRewrites(@TempDir Path dir) throws Exception {
        AuditWal wal = new AuditWal(OM, true, dir.toString());
        wal.append("a1", entry("X"), null);
        wal.append("a2", entry("Y"), null);
        Path f = wal.rotateAndListReplayFiles().get(0);
        List<AuditWal.Line> lines = wal.read(f);

        wal.finish(f, List.of(lines.get(1)));
        assertThat(wal.read(f)).extracting(AuditWal.Line::auditId).containsExactly("a2");
        assertThat(wal.pendingLines()).isEqualTo(1);

        wal.finish(f, List.of());
        assertThat(Files.exists(f)).isFalse();
        assertThat(wal.rotateAndListReplayFiles()).isEmpty();
        assertThat(wal.pendingLines()).isZero();
    }

    @Test
    @DisplayName("깨진 줄(부분 기록)은 건너뛰고 나머지는 읽힌다")
    void brokenLineSkipped(@TempDir Path dir) throws Exception {
        AuditWal wal = new AuditWal(OM, true, dir.toString());
        wal.append("a1", entry("X"), null);
        Files.writeString(wal.currentFile(), "{\"auditId\":\"trunc", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        Path f = wal.rotateAndListReplayFiles().get(0);
        assertThat(wal.read(f)).extracting(AuditWal.Line::auditId).containsExactly("a1");
    }

    @Test
    @DisplayName("비활성이면 append 는 false 이고 아무 파일도 만들지 않는다")
    void disabled(@TempDir Path dir) throws Exception {
        AuditWal wal = new AuditWal(OM, false, dir.resolve("off").toString());
        assertThat(wal.append("a1", entry("X"), null)).isFalse();
        assertThat(Files.exists(dir.resolve("off"))).isFalse();
        assertThat(wal.rotateAndListReplayFiles()).isEmpty();
    }
}
