package kr.go.smes.qim.infrastructure.jpa.repository;

import kr.go.smes.qim.infrastructure.jpa.entity.QimUserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Q-IM 사용자 Spring Data JPA Repository
 * — identifierHash 기반 조회는 auth_mean_mapping JOIN 필요
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 */
public interface QimUserJpaRepository extends JpaRepository<QimUserJpaEntity, String> {

    /**
     * identifierHash 로 사용자 조회 (Q-Sign 인증 후 식별 단계)
     * auth_mean_mapping.identifier_hash → qim_user JOIN
     */
    @Query("""
        SELECT u FROM QimUserJpaEntity u
        JOIN u.authMeanMappings m
        WHERE m.identifierHash = :identifierHash
          AND m.status = 'ACTIVE'
        """)
    Optional<QimUserJpaEntity> findByIdentifierHash(@Param("identifierHash") String identifierHash);

    /**
     * identifierHash + providerCode 복합 조회 (소셜 로그인 SSO 전용)
     *
     * <p>Keycloak 소셜 콜백에서 IdO가 SHA-256(sub)를 identifierHash로,
     * 소셜 제공자 코드(KAKAO_OIDC 등)를 providerCode로 전달한다.
     * identifierHash + providerCode 쌍이 일치하는 사용자만 반환하여
     * 서로 다른 소셜 계정이 우연히 동일 hash를 가지는 경우를 방지한다.
     * (SHA-256 충돌 가능성은 극히 낮으나 방어적 설계)
     */
    @Query("""
        SELECT u FROM QimUserJpaEntity u
        JOIN u.authMeanMappings m
        WHERE m.identifierHash = :identifierHash
          AND m.providerCode   = :providerCode
          AND m.status         = 'ACTIVE'
        """)
    Optional<QimUserJpaEntity> findByIdentifierHashAndProviderCode(
            @Param("identifierHash") String identifierHash,
            @Param("providerCode")   String providerCode);

    /**
     * WITHDRAWAL_SCHEDULED 상태이고 예약 일시가 지난 사용자 조회
     * (스케줄러 → processExpiredScheduledWithdrawals 용)
     */
    @Query("""
        SELECT u FROM QimUserJpaEntity u
        WHERE u.status = 'WITHDRAWAL_SCHEDULED'
          AND u.withdrawalScheduledAt <= :now
        """)
    List<QimUserJpaEntity> findExpiredScheduledWithdrawals(@Param("now") Instant now);

    /**
     * 사용자 상태 즉시 갱신 (탈퇴 처리용 — JPA flush 없이 직접 UPDATE)
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE QimUserJpaEntity u
        SET u.status            = :status,
            u.withdrawnAt       = :withdrawnAt,
            u.withdrawalReason  = :reason,
            u.withdrawalType    = :type,
            u.updatedAt         = :now,
            u.eventVersion      = u.eventVersion + 1
        WHERE u.qimUserId = :qimUserId
        """)
    int markWithdrawn(@Param("qimUserId")   String qimUserId,
                      @Param("status")      String status,
                      @Param("withdrawnAt") Instant withdrawnAt,
                      @Param("reason")      String reason,
                      @Param("type")        String type,
                      @Param("now")         Instant now);
}
