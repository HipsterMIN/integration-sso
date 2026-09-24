package io.github.hipstermin.idem.hub.kr.conversion;

import java.io.Serializable;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * 회원 전환 세션 — Redis에 저장되는 서버 측 전환 상태 객체
 *
 * <p>유관기관의 signed_request(JWT) 검증 후 생성된다.
 * mbrId, redirectUri 등 민감 파라미터를 서버 측에서 안전하게 보관하여
 * FE URL 파라미터를 통한 위변조를 방지한다.
 *
 * <p>Redis 키: {@code conversion:session:{sessionId}}
 * TTL: {@code ido.conversion.session-ttl-minutes} (기본 30분)
 */
@Value
@Builder
public class ConversionSession implements Serializable {

    /** 세션 식별자 (UUID v4) */
    String sessionId;

    /** 기관 코드 (agency_meta.agency_code) */
    String agencyCode;

    /**
     * 기관 회원 ID — 서버 측 보관 (FE에 노출 안 함)
     * step6 checkConversionProxy 호출 시 서버가 직접 전달
     */
    String mbrId;

    /**
     * 전환 완료 후 기관이 복귀할 URL — 서버 측 보관 (FE에 노출 안 함)
     * step8 완료 시 BE API를 통해 검증된 값으로 리다이렉트
     */
    String redirectUri;

    /**
     * IdO Handoff용 기관 client_id
     * Handoff Ticket 발급 시 agencyCode 검증에 사용
     */
    String returnClient;

    /**
     * 회원 유형 (기관이 signed_request에 지정)
     * "IND" (개인) | "ENT" (기업) | null (사용자 선택)
     */
    String userType;

    /** 세션 생성 시각 */
    Instant createdAt;

    /** 세션 만료 시각 (createdAt + session-ttl-minutes) */
    Instant expiresAt;
}
