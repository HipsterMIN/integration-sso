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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;

/**
 * IdO 정책 엔진 구현체
 * 설계서 11.4 / 11.5 / 16.5절 참조
 */
@Slf4j
@Component
public class PolicyEngineImpl implements PolicyEngine {

    private final UserStatusCache       userStatusCache;
    private final QimClient             qimClient;
    private final AgencyMetaRepository  agencyMetaRepository;

    /**
     * agencySubjectId 생성용 HMAC 비밀키
     * 설계서 §5.3, §24.4.1: 기관별 비가역 HMAC 값, qimUserId 원본 노출 금지
     * 운영: ${IDO_AGENCY_SUBJECT_SECRET} 환경변수로 주입
     */
    @Value("${ido.agency-subject-secret:default-poc-secret-change-in-production}")
    private String agencySubjectIdSecret;

    public PolicyEngineImpl(UserStatusCache userStatusCache,
                            QimClient qimClient,
                            AgencyMetaRepository agencyMetaRepository) {
        this.userStatusCache      = userStatusCache;
        this.qimClient            = qimClient;
        this.agencyMetaRepository = agencyMetaRepository;
    }

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
        // agencySubjectId = Q-IM 정본 기반 기관향 Projection (설계서 §5.3)
        // HMAC-SHA256(qimUserId + "|" + agencyCode, secret) → Base64URL (비가역, 원본 미노출)
        String agencySubjectId = generateAgencySubjectId(ticket.getQimUserId(), ticket.getAgencyCode());

        // TODO(P2): AgencyMeta.allowedAttributes 기준 사용자 속성 필터링 구현
        // 현재는 빈 맵 반환 — 운영 전 반드시 구현 필요
        Map<String, Object> allowedAttributes = new HashMap<>();

        // AgencyMeta 에서 발급 시점 정책 버전 참조 (§16.5, §24.4.1)
        // 하드코딩 제거 — 기관별 policy_version 원본 반영
        // AgencyMeta 를 주입받지 않으므로 ticket 에 policyVersion 이 없는 경우 Q-IM 캐시 조회 불가.
        // 현재 구조에서는 HandoffServiceImpl 이 AgencyMeta 를 이미 조회했으므로,
        // ticket 에 policyVersion 을 담거나 AgencyMetaRepository 를 PolicyEngine 에 추가해야 함.
        // PoC 단계: agencyMetaRepository 직접 조회로 policyVersion 반영 (§24.4.1 체크리스트 충족)
        String resolvedPolicyVersion = agencyMetaRepository
                .findByCode(ticket.getAgencyCode())
                .map(kr.go.smes.ido.domain.AgencyMeta::getPolicyVersion)
                .orElse("1.0");

        return HandoffPayload.builder()
                .ticketId(ticket.getTicketId())
                .correlationId(ticket.getCorrelationId())
                .agencyCode(ticket.getAgencyCode())
                .policyVersion(resolvedPolicyVersion)
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
                        // TODO(P2): ticket에서 providerCode 전달받아 세팅
                        .build())
                .attributes(allowedAttributes)
                .issuedAt(ticket.getIssuedAt())
                .expiresAt(ticket.getExpiresAt())
                .build();
    }

    /**
     * agencySubjectId 생성 — HMAC-SHA256 기반 비가역 기관향 식별자
     * 설계서 §5.3, §24.4.1: 원본 qimUserId 일부도 노출하지 않아야 함
     *
     * <p>운영 시 {@code ido.agency-subject-secret} 환경변수 반드시 교체 필요.
     * key = HMAC-SHA256("qimUserId|agencyCode", secret) → Base64URL (no padding)
     */
    private String generateAgencySubjectId(String qimUserId, String agencyCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    agencySubjectIdSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal((qimUserId + "|" + agencyCode)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            log.error("[PolicyEngine] agencySubjectId 생성 실패 qimUserId={} agency={}",
                    qimUserId, agencyCode, e);
            throw new RuntimeException("agencySubjectId 생성 실패", e);
        }
    }
}
