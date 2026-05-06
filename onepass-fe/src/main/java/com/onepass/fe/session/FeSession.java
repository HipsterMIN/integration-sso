package com.onepass.fe.session;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Onepass FE 세션 객체
 * 설계서 12.5절 참조
 *
 * - Secure / HttpOnly / SameSite=Lax 이상 쿠키로 관리
 * - 세션 오너십: Onepass FE 단독 보유 (기관·내부SSO와 공유 불가)
 * - 모바일 앱 telemetry는 별도 저장소에 적재 (FE 세션 오염 방지)
 */
@Getter
@Builder
public class FeSession {

    /** 외부 채널 세션 ID (충분 엔트로피 ≥128bit) */
    private final String feSessionId;

    /** Q-IM 사용자 ID (인증 완료 후 설정) */
    private final String qimUserId;

    /** 최종 인증 결과 ID */
    private final String authResultId;

    /** 인증 수준 */
    private final String authLevel;

    /** 세션 생성 시각 */
    private final Instant createdAt;

    /** 마지막 활동 시각 (sliding TTL 기준) */
    private final Instant lastActivityAt;

    /** 절대 만료 시각 (설계서 12.4절) */
    private final Instant absoluteExpiresAt;

    /** 딥링크 진입 시 복귀할 returnUrl (화이트리스트 검증 완료) */
    private final String returnUrl;

    public boolean isAbsoluteExpired() {
        return Instant.now().isAfter(absoluteExpiresAt);
    }
}
