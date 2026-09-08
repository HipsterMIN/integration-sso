package io.github.hipstermin.idem.registry.infrastructure.jpa.repository;

import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.AuthMeanMappingJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 인증수단 매핑 JPA Repository
 *
 * <p>qim.auth_mean_mapping 테이블 조회.
 * identifierHash(= SHA-256(CI)) 기반 단방향 매핑 SoR.
 *
 * <h2>PASS/CI 계열 providerCode 규칙</h2>
 * <ul>
 *   <li>{@code PASS}  — 통신 3사 PASS 앱 CI</li>
 *   <li>{@code KICA}  — 한국인터넷진흥원 CI (공동인증서)</li>
 *   <li>{@code NICE}  — NICE 본인확인 CI</li>
 *   <li>{@code KCB}   — KCB 본인확인 CI</li>
 * </ul>
 * 위 provider들은 모두 주민등록번호 기반 동일 CI를 생성하므로 같은 identifierHash를 공유한다.
 */
public interface AuthMeanMappingJpaRepository
        extends JpaRepository<AuthMeanMappingJpaEntity, String> {

    /**
     * qimUserId + PASS/CI 계열 provider + ACTIVE 상태 매핑에서 identifierHash 조회
     *
     * <p>PASS/CI 계열 provider 우선순위: PASS → KICA → NICE → KCB 순으로 첫 번째 반환.
     *
     * @param qimUserId Q-IM 사용자 ID
     * @return Optional&lt;identifierHash&gt; — 매핑 미존재 시 empty
     */
    @Query("""
            SELECT m.identifierHash
            FROM AuthMeanMappingJpaEntity m
            WHERE m.user.qimUserId = :qimUserId
              AND m.status = 'ACTIVE'
              AND m.providerCode IN ('PASS', 'KICA', 'NICE', 'KCB')
            ORDER BY
                CASE m.providerCode
                    WHEN 'PASS' THEN 1
                    WHEN 'KICA' THEN 2
                    WHEN 'NICE' THEN 3
                    WHEN 'KCB'  THEN 4
                    ELSE 99
                END ASC
            LIMIT 1
            """)
    Optional<String> findActivePassCiHash(@Param("qimUserId") String qimUserId);
}
