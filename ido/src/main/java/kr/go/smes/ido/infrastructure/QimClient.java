package kr.go.smes.ido.infrastructure;

import kr.go.smes.common.domain.UserStatus;

import java.util.Map;

/**
 * Q-IM HTTP 클라이언트 인터페이스 (IdO → Q-IM 조회)
 * 설계서 10.4 / 11.5절 참조
 *
 * <p><b>GAP-QIM-01 (v1.9.4)</b>: getUserById() 추가
 * — needsSync=true 수신 시 사용자 전체 정보(상태+프로필)를 Pull하여
 *   UserStatusCache 및 부가 캐시를 완전 갱신한다.
 */
public interface QimClient {
    /** 사용자 상태 조회 (Cache miss 시) */
    UserStatus getUserStatus(String qimUserId, String correlationId);

    /**
     * GAP-QIM-01: 사용자 전체 정보 조회 (needsSync=true Pull용)
     * GET /api/v1/internal/users/{qimUserId}
     *
     * <p>반환 맵 키 (Q-IM UserResponse 필드):<br>
     * {@code qimUserId}, {@code status}, {@code nameMasked},
     * {@code mobileMasked}, {@code nationalityType}, {@code birthYear},
     * {@code gender}, {@code createdAt}, {@code updatedAt}
     *
     * @return UserResponse 필드 맵, 조회 실패 시 null
     */
    Map<String, Object> getUserById(String qimUserId, String correlationId);

    /** 기관별 DI 조회/생성 (agencySubjectId 연계용) */
    String getDi(String qimUserId, String agencyCode, String correlationId);
}
