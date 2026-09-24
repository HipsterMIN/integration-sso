package io.github.hipstermin.idem.registry.kr.biz;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 기업회원 Spring Data JPA Repository
 */
public interface BizMemberJpaRepository extends JpaRepository<BizMemberJpaEntity, String> {

    /** 사업자등록번호 중복 확인 */
    boolean existsByBizRegNo(String bizRegNo);

    /** 사업자등록번호로 기업회원 조회 */
    Optional<BizMemberJpaEntity> findByBizRegNo(String bizRegNo);
}
