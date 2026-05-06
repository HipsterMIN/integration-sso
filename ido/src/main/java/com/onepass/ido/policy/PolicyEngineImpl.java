package com.onepass.ido.policy;

import com.onepass.common.domain.AuthResult;
import com.onepass.common.domain.HandoffPayload;
import com.onepass.common.domain.HandoffTicket;
import com.onepass.common.domain.UserStatus;
import com.onepass.common.error.PlatformErrorCode;
import com.onepass.common.error.PlatformException;
import com.onepass.ido.domain.AgencyMeta;
import com.onepass.ido.infrastructure.QimClient;
import com.onepass.ido.infrastructure.UserStatusCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * IdO 정책 엔진 구현체
 * 설계서 11.4 / 11.5 / 16.5절 참조
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyEngineImpl implements PolicyEngine {

    private final UserStatusCache userStatusCache;
    private final QimClient       qimClient;

    @Override
    public UserStatus resolveUserStatus(String qimUserId, String correlationId) {
        // 캐시 조회 (TTL ≤5분)
        return userStatusCache.get(qimUserId)
                .orElseGet(() -> {
                    // 캐시 미스 → Q-IM 직접 조회
                    try {
                        UserStatus status = qimClient.getUserStatus(qimUserId, correlationId);
                        userStatusCache.put(qimUserId, status);
                        return status;
                    } catch (Exception e) {
                        // Q-IM 조회 실패 → 안전 우선 원칙으로 거부 (설계서 11.5.4절)
                        log.error("[IdO] Q-IM 조회 실패 → 안전 우선 거부 qimUserId={}", qimUserId, e);
                        throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
                    }
                });
    }

    @Override
    public boolean meetsMinAuthLevel(AuthResult.AuthLevel actual, AuthResult.AuthLevel required) {
        // L3 > L2 > L1
        return actual.ordinal() >= required.ordinal();
    }

    @Override
    public boolean isUnderMaintenance(AgencyMeta agency) {
        if (agency.getMaintenanceWindows() == null || agency.getMaintenanceWindows().isEmpty()) {
            return false;
        }
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        String today = java.time.DayOfWeek.from(
                java.time.LocalDate.now(ZoneId.of("Asia/Seoul"))).name();

        return agency.getMaintenanceWindows().stream().anyMatch(w -> {
            if (!w.getDayOfWeek().equalsIgnoreCase(today)) return false;
            LocalTime start = LocalTime.parse(w.getStartTime());
            LocalTime end   = LocalTime.parse(w.getEndTime());
            return !now.isBefore(start) && now.isBefore(end);
        });
    }

    @Override
    public HandoffPayload buildHandoffPayload(HandoffTicket ticket, String correlationId) {
        // TODO: Q-IM에서 사용자 속성 조회 후 기관 허용 속성만 필터링
        // agencySubjectId = Q-IM 정본 기반 기관향 Projection (설계서 5.3절)
        String agencySubjectId = generateAgencySubjectId(ticket.getQimUserId(), ticket.getAgencyCode());

        Map<String, Object> allowedAttributes = new HashMap<>();
        // TODO: 기관 허용 속성 목록(AgencyMeta.allowedAttributes) 기준 필터링

        return HandoffPayload.builder()
                .ticketId(ticket.getTicketId())
                .correlationId(ticket.getCorrelationId())
                .agencyCode(ticket.getAgencyCode())
                .policyVersion("1.0")  // TODO: AgencyMeta.policyVersion 참조
                .state(HandoffPayload.HandoffState.APPROVED)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(agencySubjectId)
                        .qimUserId(ticket.getQimUserId())
                        .status(UserStatus.ACTIVE)
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(ticket.getAuthLevel())
                        .authResultId(ticket.getAuthResultId())
                        .authenticatedAt(ticket.getIssuedAt())
                        .build())
                .attributes(allowedAttributes)
                .issuedAt(ticket.getIssuedAt())
                .expiresAt(ticket.getExpiresAt())
                .build();
    }

    /**
     * agencySubjectId 생성 — Q-IM qimUserId + agencyCode 기반 결정론적 해시
     * 실제 구현 시 HMAC 또는 UUID v5 적용
     */
    private String generateAgencySubjectId(String qimUserId, String agencyCode) {
        // TODO: HMAC(qimUserId + agencyCode, secretKey) → Base64URL
        return "AGENCY_SUBJ_" + qimUserId.substring(0, 8) + "_" + agencyCode;
    }
}
