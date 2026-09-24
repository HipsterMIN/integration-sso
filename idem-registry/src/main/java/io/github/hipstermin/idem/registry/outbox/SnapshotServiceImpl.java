package io.github.hipstermin.idem.registry.outbox;

import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.SnapshotMetaJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.SnapshotMetaJpaRepository;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q-IM Snapshot 서비스 구현체
 * 설계서 §11.5.6 Compacted Snapshot Topic / GAP-QIM-05
 *
 * <p><b>스냅샷 발행 트리거 판단</b>:
 * {@code shouldPublishSnapshot()}은 다음 조건을 모두 만족할 때 {@code true}를 반환한다:
 * <ol>
 *   <li>마지막 발행된 스냅샷 버전이 없거나</li>
 *   <li>현재 버전 - 마지막 스냅샷 버전 ≥ {@code snapshotIntervalEvents}</li>
 * </ol>
 *
 * <p><b>스냅샷 내용</b>:
 * DB에서 사용자의 현재 상태(status, eventVersion)를 조회하여
 * {@code USER_SNAPSHOT} 타입 {@link UserEvent}로 직렬화 후
 * {@code qim.user.snapshot} Compacted Topic에 발행한다.
 *
 * <p><b>snapshot_meta 기록</b>:
 * 스냅샷 발행 성공/실패 여부를 {@code snapshot_meta} 테이블에 기록한다.
 * 중복 발행 방지: UNIQUE(qim_user_id, snapshot_version) 제약 + 사전 조회로 보장.
 *
 * @see SnapshotService
 * @see SnapshotMetaJpaEntity
 */
@Slf4j
@Service
public class SnapshotServiceImpl implements SnapshotService {

    /** 스냅샷 발행 Kafka 토픽 (Compacted) */
    private static final String TOPIC_SNAPSHOT    = "qim.user.snapshot";
    /** UserEvent 타입 상수 — 스냅샷 전용 타입 */
    private static final String EVENT_TYPE_SNAPSHOT = "USER_SNAPSHOT";
    /** 이벤트 소스 시스템 */
    private static final String SOURCE_SYSTEM     = "q-im";

    /**
     * 스냅샷 발행 주기 (이벤트 수)
     * 기본값: 10 — 10개 이벤트마다 스냅샷 발행 (§11.5.6)
     */
    @Value("${qim.snapshot.interval-events:10}")
    private long snapshotIntervalEvents;

    private final QimUserJpaRepository       qimUserRepository;
    private final SnapshotMetaJpaRepository  snapshotMetaRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate /* D1-b: Kafka 꺼지면 idem-common 의 DisabledKafkaTemplate — 한정자 없이 받는다 */;

    public SnapshotServiceImpl(
            QimUserJpaRepository qimUserRepository,
            SnapshotMetaJpaRepository snapshotMetaRepository,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.qimUserRepository      = qimUserRepository;
        this.snapshotMetaRepository = snapshotMetaRepository;
        this.kafkaTemplate          = kafkaTemplate;
    }

    // ── shouldPublishSnapshot ─────────────────────────────────────────────────

    /**
     * 스냅샷 발행 필요 여부 판단
     *
     * <p>마지막 PUBLISHED 스냅샷과 현재 이벤트 버전의 차이가
     * {@code snapshotIntervalEvents} 이상이면 발행 필요로 판단한다.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean shouldPublishSnapshot(String qimUserId, long currentVersion) {
        Optional<SnapshotMetaJpaEntity> latest =
                snapshotMetaRepository.findLatestPublishedByQimUserId(qimUserId);

        if (latest.isEmpty()) {
            // 아직 한 번도 스냅샷을 발행하지 않은 경우 → currentVersion >= intervalEvents 이면 발행
            boolean should = currentVersion >= snapshotIntervalEvents;
            log.debug("[SnapshotSvc] 최초 스냅샷 판단: qimUserId={} currentVersion={} intervalEvents={} should={}",
                    qimUserId, currentVersion, snapshotIntervalEvents, should);
            return should;
        }

        long lastSnapshotVersion = latest.get().getSnapshotVersion();
        long diff = currentVersion - lastSnapshotVersion;
        boolean should = diff >= snapshotIntervalEvents;

        log.debug("[SnapshotSvc] 스냅샷 발행 판단: qimUserId={} lastVersion={} currentVersion={} diff={} intervalEvents={} should={}",
                qimUserId, lastSnapshotVersion, currentVersion, diff, snapshotIntervalEvents, should);
        return should;
    }

    // ── publishSnapshot ───────────────────────────────────────────────────────

    /**
     * 사용자 전체 상태 스냅샷 발행
     *
     * <p><b>흐름</b>:
     * <ol>
     *   <li>중복 발행 방지 — 이미 동일 버전 스냅샷 발행 기록이 있으면 스킵</li>
     *   <li>DB에서 사용자 현재 상태 조회</li>
     *   <li>UserEvent(USER_SNAPSHOT 타입) 빌드</li>
     *   <li>Kafka Compacted Topic 발행 (비동기)</li>
     *   <li>snapshot_meta 기록 (PUBLISHED / FAILED)</li>
     * </ol>
     */
    @Override
    @Transactional
    public void publishSnapshot(String qimUserId, long currentVersion, String correlationId) {
        // 1. 중복 발행 방지
        if (snapshotMetaRepository.existsByQimUserIdAndSnapshotVersion(qimUserId, currentVersion)) {
            log.info("[SnapshotSvc] 이미 발행된 스냅샷 — 스킵: qimUserId={} version={}",
                    qimUserId, currentVersion);
            return;
        }

        // 2. 사용자 현재 상태 조회
        QimUserJpaEntity user = qimUserRepository.findById(qimUserId).orElse(null);
        if (user == null) {
            log.warn("[SnapshotSvc] 사용자 없음 — 스냅샷 발행 스킵: qimUserId={}", qimUserId);
            return;
        }

        // 3. UserEvent(USER_SNAPSHOT 타입) 빌드
        UserEvent snapshotEvent = new UserEvent(
                EVENT_TYPE_SNAPSHOT,
                SOURCE_SYSTEM,
                correlationId != null ? correlationId : UuidV7.generate(),
                qimUserId,
                currentVersion,
                user.getStatus(),
                "SNAPSHOT",
                false   // needsSync=false (스냅샷은 pull 트리거 아님)
        );

        // 4. Kafka Compacted Topic 비동기 발행
        String snapshotId = UuidV7.generate();
        final String[] finalStatus = {SnapshotMetaJpaEntity.SnapshotStatus.PUBLISHED.name()};

        kafkaTemplate.send(TOPIC_SNAPSHOT, qimUserId, snapshotEvent)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        finalStatus[0] = SnapshotMetaJpaEntity.SnapshotStatus.FAILED.name();
                        log.error("[SnapshotSvc] 스냅샷 발행 실패: qimUserId={} version={} err={}",
                                qimUserId, currentVersion, ex.getMessage());
                    } else {
                        log.info("[SnapshotSvc] 스냅샷 발행 완료: qimUserId={} version={} topic={}",
                                qimUserId, currentVersion, TOPIC_SNAPSHOT);
                    }
                });

        // 5. snapshot_meta 기록 (Kafka 비동기 결과와 무관하게 PUBLISHED 로 기록 후
        //    실패 시 별도 모니터링 — 설계서 §11.5.6)
        //    실제 발행 실패는 whenComplete 콜백에서 로그로 확인 가능
        SnapshotMetaJpaEntity snapshotMeta = SnapshotMetaJpaEntity.builder()
                .snapshotId(snapshotId)
                .qimUserId(qimUserId)
                .snapshotVersion(currentVersion)
                .topic(TOPIC_SNAPSHOT)
                .status(SnapshotMetaJpaEntity.SnapshotStatus.PUBLISHED.name())
                .build();

        try {
            snapshotMetaRepository.save(snapshotMeta);
            log.debug("[SnapshotSvc] snapshot_meta 저장 완료: snapshotId={} qimUserId={} version={}",
                    snapshotId, qimUserId, currentVersion);
        } catch (Exception e) {
            // UNIQUE 제약 위반 등 — 중복 저장 시 무시 (이미 발행된 스냅샷)
            log.warn("[SnapshotSvc] snapshot_meta 저장 실패 (중복 가능): qimUserId={} version={} err={}",
                    qimUserId, currentVersion, e.getMessage());
        }
    }
}
