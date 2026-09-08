package io.github.hipstermin.idem.registry.application;

import io.github.hipstermin.idem.registry.domain.QimUser;
import java.util.Optional;

/**
 * Q-IM 사용자 서비스 인터페이스
 * 설계서 10.1 / 10.4절 참조
 */
public interface UserService {

    /**
     * identifierHash로 사용자 조회 (Q-Sign 인증 후 식별 단계)
     */
    Optional<QimUser> findByIdentifierHash(String identifierHash, String correlationId);

    /**
     * qimUserId로 사용자 조회 (IdO / 기관 조회)
     */
    QimUser findById(String qimUserId, String correlationId);

    /**
     * 신규 사용자 등록 (첫 인증 시 자동 생성)
     */
    QimUser registerUser(String identifierHash, String providerCode, String correlationId);

    /**
     * 사용자 정지
     */
    QimUser suspendUser(String qimUserId, String reason, String correlationId);

    /**
     * 사용자 탈퇴
     */
    QimUser withdrawUser(String qimUserId, String reason, String correlationId);

    /**
     * 인증수단 추가 매핑
     */
    QimUser addAuthMeanMapping(String qimUserId, String identifierHash,
                                String providerCode, String correlationId);
}
