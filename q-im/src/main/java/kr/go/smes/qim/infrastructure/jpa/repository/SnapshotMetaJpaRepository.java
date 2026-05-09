package kr.go.smes.qim.infrastructure.jpa.repository;

import kr.go.smes.qim.infrastructure.jpa.entity.SnapshotMetaJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Q-IM SnapshotMeta Spring Data JPA Repository
 * 설계서 §11.5.6 Compacted Snapshot 발행 이력 / GAP-QIM-05
 *
 * <p>[DB] MariaDB {@code qim.snapshot_meta} 테이블
 * (V2 마이그레이션에서 생성됨)
 *
 * @see SnapshotMetaJpaEntity
 * @see kr.go.smes.qim.outbox.SnapshotService
 */
public interface SnapshotMetaJpaRepository
        extends JpaRepository<SnapshotMetaJpaEntity, String> {

    /**
     * 특정 사용자의 최신 스냅샷 버전 조회
     *
     * <p>Compacted Topic 발행 여부 판단 기준으로 사용한다.
     * 반환값이 없으면 해당 사용자에 대해 아직 스냅샷이 발행된 적 없음을 의미한다.
     *
     * @param qimUserId Q-IM 사용자 ID
     * @return 가장 최근 발행된 스냅샷 버전 (없으면 empty)
     */
    @Query("""
            SELECT s FROM SnapshotMetaJpaEntity s
            WHERE s.qimUserId = :qimUserId
              AND s.status = 'PUBLISHED'
            ORDER BY s.snapshotVersion DESC
            LIMIT 1
            """)
    Optional<SnapshotMetaJpaEntity> findLatestPublishedByQimUserId(
            @Param("qimUserId") String qimUserId);

    /**
     * 특정 사용자 + 버전 기준 스냅샷 이미 존재 여부 확인
     *
     * <p>중복 발행 방지용 (UNIQUE(qim_user_id, snapshot_version) 제약과 병행).
     *
     * @param qimUserId       Q-IM 사용자 ID
     * @param snapshotVersion 확인할 스냅샷 버전
     * @return true = 이미 발행된 스냅샷 존재
     */
    boolean existsByQimUserIdAndSnapshotVersion(String qimUserId, Long snapshotVersion);

    /**
     * 발행 실패(FAILED) 스냅샷 수 조회 (모니터링용)
     *
     * @param qimUserId Q-IM 사용자 ID
     * @return FAILED 상태의 스냅샷 레코드 수
     */
    long countByQimUserIdAndStatus(String qimUserId, String status);
}
