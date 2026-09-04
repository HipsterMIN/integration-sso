package kr.go.smes.qim.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Q-IM 스냅샷 메타 JPA 엔터티
 * 설계서 §11.5.6 Compacted Snapshot Topic 발행 이력 / GAP-QIM-05
 *
 * <p>Flyway V2 마이그레이션({@code V2__add_idempotent_consumer.sql})에
 * {@code snapshot_meta} 테이블이 이미 생성되어 있다.
 *
 * <p><b>스냅샷 발행 전략</b>:
 * Q-IM Outbox가 N개 이벤트를 발행할 때마다 전체 사용자 상태 스냅샷을
 * {@code qim.user.snapshot} Compacted Topic으로 발행한다.
 * 이 테이블은 어느 event_version 기준으로 스냅샷이 발행되었는지 추적한다.
 *
 * <p><b>DB 매핑</b>:
 * <ul>
 *   <li>{@code snapshot_id}      : UUID (PK)</li>
 *   <li>{@code qim_user_id}      : FK → qim_user.qim_user_id</li>
 *   <li>{@code snapshot_version} : 스냅샷 기준 event_version (UQ: qim_user_id + version)</li>
 *   <li>{@code topic}            : Kafka 토픽명 (기본: qim.user.snapshot)</li>
 *   <li>{@code status}           : PUBLISHED / FAILED</li>
 *   <li>{@code created_at}       : 스냅샷 발행 시각</li>
 * </ul>
 *
 * <p>[DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 *
 * @see kr.go.smes.qim.outbox.SnapshotService
 * @see kr.go.smes.qim.infrastructure.jpa.repository.SnapshotMetaJpaRepository
 */
@Entity
@Table(name = "snapshot_meta")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SnapshotMetaJpaEntity {

    /** 스냅샷 고유 ID (UUIDv4) */
    @Id
    @Column(name = "snapshot_id", length = 36, nullable = false, updatable = false)
    private String snapshotId;

    /**
     * 스냅샷 대상 사용자 ID
     * FK → qim_user.qim_user_id (ON DELETE RESTRICT)
     */
    @Column(name = "qim_user_id", length = 36, nullable = false, updatable = false)
    private String qimUserId;

    /**
     * 스냅샷 기준 event_version
     * 해당 버전까지의 이벤트를 집계하여 발행된 스냅샷임을 의미한다.
     * UNIQUE(qim_user_id, snapshot_version) — 동일 버전 중복 발행 방지.
     */
    @Column(name = "snapshot_version", nullable = false, updatable = false)
    private Long snapshotVersion;

    /**
     * Kafka 스냅샷 토픽명
     * 기본값: {@code qim.user.snapshot} (Compacted Topic)
     */
    @Column(name = "topic", length = 200, nullable = false)
    @Builder.Default
    private String topic = "qim.user.snapshot";

    /**
     * 발행 상태 (§11.5.6)
     * <ul>
     *   <li>{@code PUBLISHED}: Kafka 발행 완료</li>
     *   <li>{@code FAILED}: 발행 실패 (재시도 대상)</li>
     * </ul>
     */
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private String status = SnapshotStatus.PUBLISHED.name();

    /** 스냅샷 발행 시각 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (topic == null || topic.isBlank()) topic = "qim.user.snapshot";
        if (status == null || status.isBlank()) status = SnapshotStatus.PUBLISHED.name();
    }

    /**
     * 스냅샷 발행 상태 (§11.5.6)
     */
    public enum SnapshotStatus {
        /** Kafka Compacted Topic 발행 완료 */
        PUBLISHED,
        /** Kafka 발행 실패 (재시도 또는 운영 조치 필요) */
        FAILED
    }
}
