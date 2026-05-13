package kr.go.smes.qim.infrastructure.jpa.repository;

import kr.go.smes.qim.infrastructure.jpa.entity.UserProfileJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 사용자 프로필 Spring Data JPA Repository
 * — user_profile 테이블 매핑
 */
public interface UserProfileJpaRepository extends JpaRepository<UserProfileJpaEntity, String> {

    /**
     * 보호자 동의 완료 처리:
     * guardian_qim_user_id 설정 + guardian_consent_at 타임스탬프 기록
     *
     * <p>{@code clearAutomatically = true}: JPQL UPDATE 실행 후 JPA 1차 캐시(영속성 컨텍스트)를
     * 강제로 초기화하여 이후 조회 시 DB 최신 상태를 반환하도록 보장합니다.
     * {@code flushAutomatically = true}: UPDATE 실행 전 미플러시 변경사항을 먼저 DB에 반영합니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE UserProfileJpaEntity p
        SET p.guardianQimUserId = :guardianQimUserId,
            p.guardianConsentAt = :consentAt
        WHERE p.qimUserId = :qimUserId
          AND p.isMinor   = true
        """)
    int updateGuardianConsent(@Param("qimUserId")         String qimUserId,
                              @Param("guardianQimUserId") String guardianQimUserId,
                              @Param("consentAt")         LocalDateTime consentAt);
}
