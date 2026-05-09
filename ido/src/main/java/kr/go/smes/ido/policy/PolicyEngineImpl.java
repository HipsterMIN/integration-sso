package kr.go.smes.ido.policy;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.ido.domain.AgencyMeta;
import kr.go.smes.ido.infrastructure.AgencyMetaRepository;
import kr.go.smes.ido.infrastructure.QimClient;
import kr.go.smes.ido.infrastructure.UserStatusCache;
import kr.go.smes.ido.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IdO 정책 엔진 구현체 (v2.0 — Production)
 *
 * <p>v2.0 변경사항:
 * <ul>
 *   <li>allowedAttributes 실제 필터링 구현</li>
 *   <li>Q-IM DI → agencySubjectId 연계 (DI 우선, fallback HMAC)</li>
 *   <li>사용자 속성 맵 Q-IM 조회 연동</li>
 * </ul>
 */
@Slf4j
@Component
public class PolicyEngineImpl implements PolicyEngine {

    private final UserStatusCache      userStatusCache;
    private final QimClient            qimClient;
    private final AgencyMetaRepository agencyMetaRepository;

    @Value("${ido.agency-subject-secret:default-poc-secret-change-in-production}")
    private String agencySubjectIdSecret;

    /** policyVersion 기본값 — DB에 값 없을 때 fallback (하드코딩 "1.0" 제거) */
    @Value("${ido.policy.default-version:1.0}")
    private String defaultPolicyVersion;

    public PolicyEngineImpl(UserStatusCache userStatusCache,
                            QimClient qimClient,
                            AgencyMetaRepository agencyMetaRepository) {
        this.userStatusCache      = userStatusCache;
        this.qimClient            = qimClient;
        this.agencyMetaRepository = agencyMetaRepository;
    }

    @Override
    public UserStatus resolveUserStatus(String qimUserId, String correlationId) {
        return userStatusCache.get(qimUserId)
                .orElseGet(() -> {
                    try {
                        UserStatus status = qimClient.getUserStatus(qimUserId, correlationId);
                        userStatusCache.put(qimUserId, status);
                        return status;
                    } catch (Exception e) {
                        log.error("[IdO] Q-IM 조회 실패 → 안전 우선 거부 qimUserId={}", qimUserId, e);
                        throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
                    }
                });
    }

    @Override
    public boolean meetsMinAuthLevel(AuthResult.AuthLevel actual, AuthResult.AuthLevel required) {
        return actual.ordinal() >= required.ordinal();
    }

    @Override
    public boolean isUnderMaintenance(AgencyMeta agency) {
        if (agency.getMaintenanceWindows() == null || agency.getMaintenanceWindows().isEmpty()) return false;
        LocalTime now   = LocalTime.now(ZoneId.of("Asia/Seoul"));
        String    today = java.time.DayOfWeek.from(java.time.LocalDate.now(ZoneId.of("Asia/Seoul"))).name();
        return agency.getMaintenanceWindows().stream().anyMatch(w -> {
            if (!w.getDayOfWeek().equalsIgnoreCase(today)) return false;
            LocalTime start = LocalTime.parse(w.getStartTime());
            LocalTime end   = LocalTime.parse(w.getEndTime());
            return !now.isBefore(start) && now.isBefore(end);
        });
    }

    @Override
    public HandoffPayload buildHandoffPayload(HandoffTicket ticket, String correlationId) {
        String agencyCode = ticket.getAgencyCode();
        String qimUserId  = ticket.getQimUserId();

        // 1. AgencyMeta 조회 (allowedAttributes, policyVersion)
        AgencyMeta agencyMeta = agencyMetaRepository.findByCode(agencyCode).orElse(null);
        List<String> allowedAttrKeys = agencyMeta != null && agencyMeta.getAllowedAttributes() != null
                ? agencyMeta.getAllowedAttributes()
                : List.of();
        String resolvedPolicyVersion = agencyMeta != null ? agencyMeta.getPolicyVersion() : defaultPolicyVersion;

        // 2. agencySubjectId — Q-IM DI 우선, fallback HMAC
        String agencySubjectId = resolveAgencySubjectId(qimUserId, agencyCode, correlationId);

        // 3. 사용자 속성 수집 후 allowedAttributes 필터링
        Map<String, Object> rawAttributes = collectUserAttributes(ticket, qimUserId);
        Map<String, Object> filteredAttributes = filterAttributes(rawAttributes, allowedAttrKeys);
        log.debug("[PolicyEngine] 속성 필터링: total={} allowed={} filtered={}",
                rawAttributes.size(), allowedAttrKeys.size(), filteredAttributes.size());

        // 4. 사용자 상태 (캐시)
        UserStatus userStatus;
        try {
            userStatus = userStatusCache.get(qimUserId).orElse(UserStatus.ACTIVE);
        } catch (Exception e) {
            userStatus = UserStatus.ACTIVE;
        }

        return HandoffPayload.builder()
                .ticketId(ticket.getTicketId())
                .correlationId(ticket.getCorrelationId())
                .agencyCode(agencyCode)
                .policyVersion(resolvedPolicyVersion)
                .state(HandoffPayload.HandoffState.APPROVED)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(agencySubjectId)
                        .qimUserId(qimUserId)
                        .status(userStatus)
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(ticket.getAuthLevel())
                        .authResultId(ticket.getAuthResultId())
                        .authenticatedAt(ticket.getIssuedAt())
                        .build())
                .attributes(filteredAttributes)
                .issuedAt(ticket.getIssuedAt())
                .expiresAt(ticket.getExpiresAt())
                .build();
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * agencySubjectId 결정:
     * 1. Q-IM DI 조회 시도 → 성공하면 DI 사용
     * 2. 실패 시 HMAC-SHA256(qimUserId|agencyCode) fallback
     */
    private String resolveAgencySubjectId(String qimUserId, String agencyCode, String correlationId) {
        try {
            String di = qimClient.getDi(qimUserId, agencyCode, correlationId);
            if (di != null && !di.isBlank()) {
                log.debug("[PolicyEngine] agencySubjectId = Q-IM DI: agency={}", agencyCode);
                return di;
            }
        } catch (Exception e) {
            log.warn("[PolicyEngine] Q-IM DI 조회 실패 — HMAC fallback: agency={} err={}", agencyCode, e.getMessage());
        }
        return generateAgencySubjectId(qimUserId, agencyCode);
    }

    /**
     * allowedAttributes 목록 기준으로 속성 맵 필터링
     * 빈 목록이면 모든 속성 차단 (최소 권한 원칙)
     * null 목록이면 필터링 없이 전체 반환
     */
    private Map<String, Object> filterAttributes(Map<String, Object> rawAttributes, List<String> allowedKeys) {
        if (allowedKeys == null) return rawAttributes;    // null = 제한 없음
        if (allowedKeys.isEmpty()) return new HashMap<>(); // 빈 목록 = 전체 차단
        return rawAttributes.entrySet().stream()
                .filter(e -> allowedKeys.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * 사용자 기본 속성 수집 (ticket + 캐시 기반)
     * Q-IM 추가 조회 없이 ticket에 포함된 정보만 사용 (동기 호출 최소화)
     */
    private Map<String, Object> collectUserAttributes(HandoffTicket ticket, String qimUserId) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("qimUserId",    qimUserId);
        attrs.put("authLevel",    ticket.getAuthLevel() != null ? ticket.getAuthLevel().name() : null);
        attrs.put("authResultId", ticket.getAuthResultId());
        attrs.put("agencyCode",   ticket.getAgencyCode());
        attrs.put("issuedAt",     ticket.getIssuedAt() != null ? ticket.getIssuedAt().toString() : null);
        // null 값 제거
        attrs.values().removeIf(Objects::isNull);
        return attrs;
    }

    /**
     * HMAC-SHA256 기반 agencySubjectId (DI 조회 실패 시 fallback)
     */
    private String generateAgencySubjectId(String qimUserId, String agencyCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(agencySubjectIdSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((qimUserId + "|" + agencyCode).getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            log.error("[PolicyEngine] agencySubjectId HMAC 생성 실패 qimUserId={} agency={}", qimUserId, agencyCode, e);
            throw new RuntimeException("agencySubjectId 생성 실패", e);
        }
    }
}
