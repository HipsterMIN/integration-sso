package kr.go.smes.ido.infrastructure.jpa.repository;

import kr.go.smes.ido.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 기관 메타 Spring Data JPA Repository
 * 설계서 §11.2 기관 정책 조회
 *
 * [DB] PostgreSQL — ido.agency_meta 테이블
 */
public interface AgencyMetaJpaRepository extends JpaRepository<AgencyMetaJpaEntity, String> {
    // PK = agency_code → findById(agencyCode) 사용 가능
}
