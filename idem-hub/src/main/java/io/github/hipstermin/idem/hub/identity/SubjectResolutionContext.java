package io.github.hipstermin.idem.hub.identity;

import lombok.Builder;

/**
 * 기관향 주체 식별자 해석에 필요한 최소 컨텍스트 (S4).
 *
 * @param qimUserId     플랫폼 내부 사용자 ID
 * @param serviceCode    기관 코드 (PAIRWISE_HMAC 의 파생 입력)
 * @param correlationId 추적 ID
 */
@Builder
public record SubjectResolutionContext(String qimUserId, String serviceCode, String correlationId) {}
