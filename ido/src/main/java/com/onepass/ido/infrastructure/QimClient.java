package com.onepass.ido.infrastructure;

import com.onepass.common.domain.UserStatus;

/**
 * Q-IM HTTP 클라이언트 인터페이스 (IdO → Q-IM 조회)
 * 설계서 10.4 / 11.5절 참조
 * - Cache miss 시 Q-IM 직접 조회
 * - 조회 실패 시 안전 우선 원칙으로 Handoff 거부 (E-IDO-106)
 */
public interface QimClient {
    UserStatus getUserStatus(String qimUserId, String correlationId);
}
