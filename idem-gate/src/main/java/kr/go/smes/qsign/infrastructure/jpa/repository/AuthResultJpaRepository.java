package kr.go.smes.qsign.infrastructure.jpa.repository;

import kr.go.smes.qsign.infrastructure.jpa.entity.AuthResultJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Q-Sign AuthResult Spring Data JPA Repository
 * 설계서 §9.3 — 인증 결과 SoR 조회
 */
public interface AuthResultJpaRepository extends JpaRepository<AuthResultJpaEntity, String> {

    Optional<AuthResultJpaEntity> findByCorrelationId(String correlationId);
}
