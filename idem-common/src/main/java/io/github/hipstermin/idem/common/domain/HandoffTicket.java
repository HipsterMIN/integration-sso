package io.github.hipstermin.idem.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * IdO Handoff Ticket 구조
 * 설계서 16.2절 참조 — 1회성, TTL 60초, AEAD 암호화 + 서명
 */
@Getter
@Builder
@Jacksonized // Redis 에 JSON 으로 저장한 티켓을 다시 읽을 수 있도록 빌더 기반 역직렬화 (없으면 findById/consume 이 항상 empty)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HandoffTicket {

    private final String ticketId;
    private final String correlationId;
    private final String agencyCode;
    private final String qimUserId;
    private final String authResultId;
    private final AuthResult.AuthLevel authLevel;
    private final TicketState state;
    private final Instant issuedAt;
    private final Instant expiresAt;

    /** AEAD(AES-256-GCM) 암호화 본문 */
    private final String encryptedPayload;

    /** HMAC-SHA256 또는 EdDSA 서명 */
    private final String signature;

    public enum TicketState {
        ISSUED,
        CONSUMED,
        EXPIRED,
        REVOKED
    }

    @JsonIgnore
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    @JsonIgnore
    public boolean isUsable() {
        return state == TicketState.ISSUED && !isExpired();
    }
}
