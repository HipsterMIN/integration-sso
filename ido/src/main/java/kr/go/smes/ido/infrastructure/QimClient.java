package kr.go.smes.ido.infrastructure;

import kr.go.smes.common.domain.UserStatus;

/**
 * Q-IM HTTP 클라이언트 인터페이스 (IdO → Q-IM 조회)
 * 설계서 10.4 / 11.5절 참조
 */
public interface QimClient {
    /** 사용자 상태 조회 (Cache miss 시) */
    UserStatus getUserStatus(String qimUserId, String correlationId);

    /** 기관별 DI 조회/생성 (agencySubjectId 연계용) */
    String getDi(String qimUserId, String agencyCode, String correlationId);
}
