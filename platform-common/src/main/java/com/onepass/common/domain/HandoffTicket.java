package com.onepass.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * IdO Handoff Ticket 구조
 * 설계서 16.2절 참조 — 1회성, TTL 60초, AEAD 암호화 + 서명
 */
@Getter
@Builder
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

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return state == TicketState.ISSUED && !isExpired();
    }
}
