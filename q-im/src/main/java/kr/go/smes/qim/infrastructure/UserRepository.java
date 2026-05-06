package kr.go.smes.qim.infrastructure;

import kr.go.smes.qim.domain.QimUser;

import java.util.Optional;

/**
 * Q-IM 사용자 저장소 인터페이스 (정본 보존)
 * 설계서 10.2절 참조
 */
public interface UserRepository {
    void save(QimUser user);
    Optional<QimUser> findById(String qimUserId);
    Optional<QimUser> findByIdentifierHash(String identifierHash);
}
