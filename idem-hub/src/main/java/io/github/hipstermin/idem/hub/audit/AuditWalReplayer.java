package io.github.hipstermin.idem.hub.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1.1: 감사 WAL 재생기 — {@link AuditWal} 에 남은 항목을 DB 가 돌아오면 {@code idem_hub.audit_log} 에 다시 넣는다.
 *
 * <ul>
 *   <li>주기 {@code idem.hub.audit.wal.replay-interval-ms}(기본 60초). 한 주기에 파일 단위로 처리</li>
 *   <li>재삽입은 {@code ON CONFLICT (audit_id) DO NOTHING} — 부분 실패 후 재시도해도 중복이 없다</li>
 *   <li>첫 삽입이 실패하면(DB 아직 다운) 그 파일은 그대로 두고 다음 주기로 — 순서 보존, 폭주 없음</li>
 *   <li>재삽입된 항목은 {@code kafka_published=false} 라 기존 Kafka 재발행 스케줄러가 이어서 처리한다</li>
 * </ul>
 * 메트릭: {@code audit.wal.appended.total}(publisher)·{@code audit.wal.replayed.total}·{@code audit.wal.pending.lines}(게이지).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "idem.hub.audit.wal.enabled", havingValue = "true", matchIfMissing = true)
public class AuditWalReplayer {

    public static final String METRIC_REPLAYED = "audit.wal.replayed.total";
    public static final String METRIC_PENDING  = "audit.wal.pending.lines";

    private final AuditWal wal;
    private final AuditLogPublisher publisher;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AuditWalReplayer(AuditWal wal, AuditLogPublisher publisher, MeterRegistry meterRegistry) {
        this.wal = wal;
        this.publisher = publisher;
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge(METRIC_PENDING, wal, w -> (double) Math.max(0, w.pendingLines()));
        }
    }

    @Scheduled(fixedDelayString = "${idem.hub.audit.wal.replay-interval-ms:60000}",
               initialDelayString = "${idem.hub.audit.wal.replay-initial-delay-ms:20000}")
    public void replay() {
        if (!running.compareAndSet(false, true)) return;   // 이전 주기가 아직 도는 중
        try {
            replayOnce();
        } catch (Exception e) {
            log.warn("[AuditWalReplayer] 재생 주기 실패: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** 한 주기: 회전 → 파일별 재삽입. 처리한 줄 수를 돌려준다. */
    public int replayOnce() throws Exception {
        List<Path> files = wal.rotateAndListReplayFiles();
        if (files.isEmpty()) return 0;
        int replayed = 0;
        for (Path file : files) {
            List<AuditWal.Line> lines = wal.read(file);
            List<AuditWal.Line> remaining = new ArrayList<>();
            boolean dbDown = false;
            for (AuditWal.Line line : lines) {
                if (dbDown) { remaining.add(line); continue; }
                if (publisher.reinsertFromWal(line)) {
                    replayed++;
                } else {
                    // 첫 실패 = DB 아직 불가 — 나머지는 순서대로 남긴다
                    dbDown = true;
                    remaining.add(line);
                }
            }
            wal.finish(file, remaining);
            if (dbDown) {
                log.warn("[AuditWalReplayer] DB 아직 불가 — {} 에 {}건 남김(다음 주기 재시도)", file.getFileName(), remaining.size());
                break;   // 뒤 파일도 같은 DB 라 지금은 의미 없다
            }
        }
        if (replayed > 0) {
            if (meterRegistry != null) meterRegistry.counter(METRIC_REPLAYED).increment(replayed);
            log.info("[AuditWalReplayer] WAL 재생 완료: {}건 → idem_hub.audit_log", replayed);
        }
        return replayed;
    }
}
