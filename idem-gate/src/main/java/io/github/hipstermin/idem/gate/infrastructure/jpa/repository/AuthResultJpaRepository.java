package io.github.hipstermin.idem.gate.infrastructure.jpa.repository;

import io.github.hipstermin.idem.gate.infrastructure.jpa.entity.AuthResultJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Q-Sign AuthResult Spring Data JPA Repository
 * 설계서 §9.3 — 인증 결과 SoR 조회
 */
public interface AuthResultJpaRepository extends JpaRepository<AuthResultJpaEntity, String> {

    Optional<AuthResultJpaEntity> findByCorrelationId(String correlationId);
}
