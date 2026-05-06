package com.onepass.ido.infrastructure;

import com.onepass.common.domain.HandoffTicket;

import java.util.Optional;

/**
 * Handoff Ticket 저장소 인터페이스
 * 설계서 16.2 / 16.10절 — consumeOnce 보장, Redis 기반
 */
public interface TicketRepository {
    void save(HandoffTicket ticket);
    Optional<HandoffTicket> findById(String ticketId);

    /** ISSUED → CONSUMED (1회성 소비) */
    void consume(String ticketId);

    /** ISSUED → REVOKED */
    void revoke(String ticketId, String revokeReason);
}
