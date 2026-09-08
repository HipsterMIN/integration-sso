package io.github.hipstermin.idem.common.event;

import java.time.Instant;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

/**
 * 연합 인가(q-authz) 도메인 이벤트 — 역할 부여/회수/만료.
 *
 * <p>q-authz가 부여 상태를 변경할 때 트랜잭셔널 아웃박스에 적재하여
 * {@code authz.assignment.events} 토픽으로 발행한다. 다운스트림(기관 게이트웨이·
 * 세션 캐시·ido)이 이를 구독해 <b>역할 회수를 토큰 만료 이전에 전파</b>할 수 있다
 * (연합 인가의 회수 지연 약점 해소).
 *
 * <p><b>토픽</b>: {@code authz.assignment.events}
 * <p><b>파티션 키</b>: {@code qimUserId} (사용자 단위 순서 보장)
 */
@Getter
@SuperBuilder
public class AuthorizationEvent extends DomainEvent {

    public static final String SOURCE_SYSTEM = "q-authz";

    public static final String TYPE_GRANTED = "AUTHZ_GRANTED";
    public static final String TYPE_REVOKED = "AUTHZ_REVOKED";
    public static final String TYPE_EXPIRED = "AUTHZ_EXPIRED";

    /** 대상 기관 코드 */
    private final String agencyCode;

    /** 부여/회수된 역할 코드 */
    private final String roleCode;

    /** 행위자 (grantedBy / revokedBy / "SYSTEM") */
    private final String actor;

    /** 한시 부여 만료 시각(GRANTED 시) — 없으면 영구 */
    private final Instant expiresAt;

    /** 부여 출처(API / SCIM / ...) */
    private final String source;

    /** 사유(감사·디버깅용) */
    private final String reason;

    public AuthorizationEvent(String eventType, String correlationId, String qimUserId,
                              String agencyCode, String roleCode, String actor,
                              Instant expiresAt, String source, String reason) {
        super(eventType, SOURCE_SYSTEM, correlationId, qimUserId, null);
        this.agencyCode = agencyCode;
        this.roleCode   = roleCode;
        this.actor      = actor;
        this.expiresAt  = expiresAt;
        this.source     = source;
        this.reason     = reason;
    }

    public static AuthorizationEvent granted(String qimUserId, String agencyCode, String roleCode,
                                             String actor, Instant expiresAt, String source,
                                             String reason, String correlationId) {
        return new AuthorizationEvent(TYPE_GRANTED, correlationId, qimUserId,
                agencyCode, roleCode, actor, expiresAt, source, reason);
    }

    public static AuthorizationEvent revoked(String qimUserId, String agencyCode, String roleCode,
                                             String actor, String reason, String correlationId) {
        return new AuthorizationEvent(TYPE_REVOKED, correlationId, qimUserId,
                agencyCode, roleCode, actor, null, null, reason);
    }

    public static AuthorizationEvent expired(String qimUserId, String agencyCode, String roleCode,
                                             String reason) {
        return new AuthorizationEvent(TYPE_EXPIRED, null, qimUserId,
                agencyCode, roleCode, "SYSTEM", null, null, reason);
    }
}
