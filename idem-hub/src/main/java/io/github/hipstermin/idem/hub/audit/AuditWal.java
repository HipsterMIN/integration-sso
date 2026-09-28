package io.github.hipstermin.idem.hub.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 1.1: 감사 로그 <b>로컬 WAL(write-ahead log) 폴백</b> — DB 저장이 실패한 감사 항목을 JSON Lines 로 로컬 디스크에 붙여 쓴다.
 *
 * <p>종전(1.0.1)에는 {@code idem_hub.audit_log} INSERT 가 실패하면 WARN 한 줄 남기고 <b>감사 항목이 사라졌다</b>
 * (H-16, generalization-plan "남긴 것"). 이제 실패 항목은 이 WAL 에 남고 {@link AuditWalReplayer} 가 DB 가 돌아오면 재삽입한다.
 *
 * <ul>
 *   <li>한 줄 = 한 항목: {@code {"auditId":..., "occurredAt":..., "entry":{...}, "metadataJson":"..."}}</li>
 *   <li>쓰기는 append + fsync — 프로세스가 죽어도 이미 쓴 줄은 남는다</li>
 *   <li>현재 세그먼트 {@code audit-wal.jsonl}; 재생 시 {@code audit-wal.<epochMillis>.replay} 로 이름을 바꾸고 읽는다
 *       (쓰기와 회전은 같은 잠금을 쓰므로 줄이 잘리지 않는다)</li>
 *   <li>재생은 {@code audit_id} PK 로 멱등 — 같은 줄을 두 번 넣어도 한 건만 남는다</li>
 * </ul>
 *
 * <p>이 WAL 은 <b>DB 일시 장애 동안의 손실을 막는 로컬 버퍼</b>이지 장기 저장소가 아니다. 컨테이너 파일시스템에 두면
 * 파드가 삭제될 때 함께 사라지므로, 운영에서는 볼륨(Helm {@code hub.auditWal}) 에 둔다(`docs/install-inputs.md`).
 * 감사 항목에는 PII 가 없다(identifierHash·agencyCode 만) — WAL 파일도 같은 등급으로 다룬다.
 */
@Slf4j
@Component
public class AuditWal {

    static final String CURRENT_FILE   = "audit-wal.jsonl";
    static final String REPLAY_SUFFIX  = ".replay";

    private final ObjectMapper objectMapper;
    private final Path dir;
    private final boolean enabled;
    private final Object lock = new Object();

    public AuditWal(ObjectMapper objectMapper,
                    @Value("${idem.hub.audit.wal.enabled:true}") boolean enabled,
                    @Value("${idem.hub.audit.wal.dir:./data/audit-wal}") String dir) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        if (enabled) {
            try {
                Files.createDirectories(this.dir);
                log.info("[AuditWal] 감사 WAL 폴백 활성: dir={}", this.dir);
            } catch (IOException e) {
                // 기동은 막지 않는다 — append 시점에 다시 실패하면 그때 ERROR 로 남긴다(운영 알람 대상)
                log.error("[AuditWal] WAL 디렉터리 생성 실패 — DB 장애 시 감사 항목이 유실될 수 있다: dir={} err={}", this.dir, e.getMessage());
            }
        } else {
            log.warn("[AuditWal] 감사 WAL 폴백 비활성(idem.hub.audit.wal.enabled=false) — DB 저장 실패 항목은 유실된다");
        }
    }

    public boolean enabled() { return enabled; }

    public Path dir() { return dir; }

    /** WAL 한 줄 — DB 재삽입에 필요한 전부. */
    public record Line(String auditId, Instant occurredAt, AuditLogPublisher.AuditEntry entry, String metadataJson) {}

    /**
     * DB 저장 실패 항목을 WAL 에 붙여 쓴다.
     *
     * @return 기록 성공 여부 — false 면 이 항목은 유실된 것이다(호출자가 ERROR 로 남긴다)
     */
    public boolean append(String auditId, AuditLogPublisher.AuditEntry entry, String metadataJson) {
        if (!enabled) return false;
        try {
            byte[] line = (objectMapper.writeValueAsString(new Line(auditId, Instant.now(), entry, metadataJson)) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            synchronized (lock) {
                Files.createDirectories(dir);
                try (var ch = java.nio.channels.FileChannel.open(dir.resolve(CURRENT_FILE),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ch.write(java.nio.ByteBuffer.wrap(line));
                    ch.force(false);
                }
            }
            return true;
        } catch (Exception e) {
            log.error("[AuditWal] WAL 기록 실패 — 감사 항목 유실: auditId={} action={} err={}",
                    auditId, entry != null ? entry.eventAction() : null, e.getMessage());
            return false;
        }
    }

    /**
     * 현재 세그먼트를 재생 파일로 회전한다(비어 있으면 아무것도 하지 않는다).
     * 재생 파일 목록(이전에 실패해 남은 것 포함)을 오래된 순으로 돌려준다.
     */
    public List<Path> rotateAndListReplayFiles() throws IOException {
        if (!enabled || !Files.isDirectory(dir)) return List.of();
        synchronized (lock) {
            Path current = dir.resolve(CURRENT_FILE);
            if (Files.exists(current) && Files.size(current) > 0) {
                Path target = dir.resolve("audit-wal." + System.currentTimeMillis() + REPLAY_SUFFIX);
                while (Files.exists(target)) {
                    target = dir.resolve("audit-wal." + (System.currentTimeMillis() + 1) + REPLAY_SUFFIX);
                }
                Files.move(current, target, StandardCopyOption.ATOMIC_MOVE);
            }
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(REPLAY_SUFFIX))
                    .sorted()
                    .toList();
        }
    }

    /** 재생 파일을 줄 단위로 읽는다 — 깨진 줄(부분 기록)은 건너뛰고 ERROR 로 남긴다. */
    public List<Line> read(Path replayFile) throws IOException {
        List<Line> out = new ArrayList<>();
        for (String raw : Files.readAllLines(replayFile, StandardCharsets.UTF_8)) {
            if (raw == null || raw.isBlank()) continue;
            try {
                out.add(objectMapper.readValue(raw, Line.class));
            } catch (Exception e) {
                log.error("[AuditWal] WAL 줄 해석 실패 — 건너뜀(유실): file={} err={}", replayFile.getFileName(), e.getMessage());
            }
        }
        return out;
    }

    /** 재생 결과 반영: 남은 줄이 없으면 파일 삭제, 있으면 남은 줄만 다시 쓴다(다음 주기 재시도). */
    public void finish(Path replayFile, List<Line> remaining) throws IOException {
        if (remaining.isEmpty()) {
            Files.deleteIfExists(replayFile);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Line l : remaining) sb.append(objectMapper.writeValueAsString(l)).append('\n');
        Path tmp = replayFile.resolveSibling(replayFile.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, replayFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 대기 중인 줄 수(현재 세그먼트 + 재생 파일) — 메트릭·헬스용. 실패하면 -1. */
    public long pendingLines() {
        if (!enabled || !Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            long n = 0;
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (!name.equals(CURRENT_FILE) && !name.endsWith(REPLAY_SUFFIX)) continue;
                try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
                    n += lines.filter(l -> !l.isBlank()).count();
                }
            }
            return n;
        } catch (IOException e) {
            return -1;
        }
    }

    /** 테스트·진단용: 현재 세그먼트 경로. */
    Path currentFile() { return dir.resolve(CURRENT_FILE); }
}
