package kr.go.smes.qim.outbox;

/**
 * Q-IM Snapshot 서비스 인터페이스
 * 설계서 §11.5.6 Compacted Snapshot Topic / GAP-QIM-05
 *
 * <p>Transactional Outbox가 N개 이벤트를 발행할 때마다
 * 해당 사용자의 전체 상태 스냅샷을 {@code qim.user.snapshot} Compacted Topic에 발행한다.
 *
 * <p><b>스냅샷 발행 트리거</b>:
 * {@link OutboxServiceImpl#relayPendingEvents()} 에서 Outbox 발행 완료 후
 * 사용자별 누적 이벤트 수가 {@code qim.snapshot.interval-events}(기본 10)에
 * 도달하면 스냅샷 발행을 트리거한다.
 *
 * <p><b>Compacted Topic 특성</b>:
 * {@code qim.user.snapshot} 토픽은 cleanup.policy=compact 로 설정되어
 * 동일 partitionKey(qimUserId)에 대해 최신 스냅샷만 유지된다.
 * 다운스트림(IdO 등)이 재시작 시 최신 상태를 빠르게 복구할 수 있다.
 *
 * @see SnapshotServiceImpl
 * @see kr.go.smes.qim.infrastructure.jpa.entity.SnapshotMetaJpaEntity
 */
public interface SnapshotService {

    /**
     * 스냅샷 발행이 필요한지 판단
     *
     * <p>마지막 스냅샷 이후 발행된 이벤트 수가 {@code intervalEvents} 이상이면
     * {@code true}를 반환한다.
     *
     * @param qimUserId      Q-IM 사용자 ID
     * @param currentVersion 현재 이벤트 버전 (단조 증가)
     * @return true = 스냅샷 발행 필요
     */
    boolean shouldPublishSnapshot(String qimUserId, long currentVersion);

    /**
     * 사용자 전체 상태 스냅샷을 {@code qim.user.snapshot} 토픽에 발행
     *
     * <p>DB에서 사용자 현재 상태(status, eventVersion, authMeanMappings)를 조회하여
     * UserEvent(USER_SNAPSHOT 타입)로 직렬화 후 Kafka Compacted Topic에 발행한다.
     * 발행 결과는 {@code snapshot_meta} 테이블에 기록한다.
     *
     * @param qimUserId      Q-IM 사용자 ID
     * @param currentVersion 현재 이벤트 버전 (스냅샷 기준 버전)
     * @param correlationId  전체 흐름 추적 키 (로깅용)
     */
    void publishSnapshot(String qimUserId, long currentVersion, String correlationId);
}
