package kr.go.smes.ido.infrastructure.outbox;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * ido.outbox 테이블 레코드 DTO
 *
 * <p>V1 마이그레이션에서 생성된 ido.outbox 테이블 구조와 1:1 매핑.
 * KeycloakOidcService / NonOidcAuthService 가 INSERT한 PENDING 레코드를
 * {@link IdoOutboxRelay} 가 조회·발행 시 사용.
 *
 * <p>QIM-OUTBOX-SPEC-001: {@code next_retry_at} 필드 추가.
 * 지수 백오프 재시도 지원 — {@code null}이면 즉시 재시도 대상.
 */
@Getter
@Builder
public class IdoOutboxRecord {

    private final String  eventId;
    private final String  eventType;
    private final String  partitionKey;   // identifierHash
    private final String  aggregateId;    // authResultId
    private final long    eventVersion;
    private final String  payload;        // JSONB → String
    private final String  topic;          // qsign.auth.events, qim.user.events 등
    private final String  status;         // PENDING / PUBLISHED / FAILED
    private final int     retryCount;
    private final String  errorMessage;
    private final Instant createdAt;
    private final Instant publishedAt;
    /** 지수 백오프 재시도 예약 시각. null이면 즉시 재시도 대상. */
    private final Instant nextRetryAt;
}
