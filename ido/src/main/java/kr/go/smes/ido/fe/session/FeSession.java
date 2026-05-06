package kr.go.smes.ido.fe.session;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Onepass FE 세션 객체 (IdO 관리)
 * 설계서 §12.5 참조
 *
 * <p>onepass-fe 가 순수 React SPA 로 전환됨에 따라
 * FE 세션 생명주기를 IdO(정책 오케스트레이터)가 담당.
 *
 * <p>Redis 저장 구조:
 * <ul>
 *   <li>key  : fe:session:{feSessionId}</li>
 *   <li>TTL  : sliding 30min / absolute 8h</li>
 * </ul>
 */
@Getter
@Builder
public class FeSession {

    /** 외부 채널 세션 ID (충분 엔트로피 ≥128bit, SecureRandom) */
    private final String feSessionId;

    /** Q-IM 사용자 ID (인증 완료 후 설정) */
    private final String qimUserId;

    /** 최종 인증 결과 ID */
    private final String authResultId;

    /** 인증 수준 (L1 / L2 / L3) */
    private final String authLevel;

    /** 세션 생성 시각 */
    private final Instant createdAt;

    /** 마지막 활동 시각 (sliding TTL 기준) */
    private final Instant lastActivityAt;

    /** 절대 만료 시각 (설계서 §12.4 — 기본 8h) */
    private final Instant absoluteExpiresAt;

    /** 딥링크 진입 시 복귀할 returnUrl (화이트리스트 검증 완료) */
    private final String returnUrl;

    /**
     * Advisory 플래그: 다음 요청 시 로그아웃 안내 여부
     * SessionAdvisoryEvent.TYPE_SESSION_LOGOUT_HINT 수신 시 true
     */
    private boolean advisoryFlag;

    /** 절대 만료 여부 */
    public boolean isAbsoluteExpired() {
        return Instant.now().isAfter(absoluteExpiresAt);
    }

    /** Advisory 플래그 설정 (immutable 대신 단일 변경 허용) */
    public FeSession withAdvisoryFlag(boolean flag) {
        return FeSession.builder()
                .feSessionId(feSessionId)
                .qimUserId(qimUserId)
                .authResultId(authResultId)
                .authLevel(authLevel)
                .createdAt(createdAt)
                .lastActivityAt(lastActivityAt)
                .absoluteExpiresAt(absoluteExpiresAt)
                .returnUrl(returnUrl)
                .advisoryFlag(flag)
                .build();
    }
}
