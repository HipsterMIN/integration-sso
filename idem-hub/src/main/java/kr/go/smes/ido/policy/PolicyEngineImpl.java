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

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IdO 정책 엔진 구현체 (v3.0 — UUID 기반 매핑 + GUEST 정책)
 *
 * <p>v3.0 변경사항 (smep-be-develop 심층 분석 반영):
 * <ul>
 *   <li>HMAC fallback agencySubjectId 생성 제거 — DI 없으면 GUEST 반환</li>
 *   <li>smep-be는 Keycloak access token의 {@code "UUID"} claim으로 {@code tb_mbrm_mbr_m.uuid} 조회</li>
 *   <li>UUID 매핑 없는 사용자: {@code HandoffState.GUEST} — 기관이 게스트/회원가입 유도 처리</li>
 *   <li>UUID 매핑 있는 사용자: DI → agencySubjectId 연계 후 {@code APPROVED}</li>
 * </ul>
 *
 * <p>v2.0 변경사항:
 * <ul>
 *   <li>allowedAttributes 실제 필터링 구현</li>
 *   <li>Q-IM DI → agencySubjectId 연계 (DI 우선)</li>
 *   <li>사용자 속성 맵 Q-IM 조회 연동</li>
 * </ul>
 */
@Slf4j
@Component
public class PolicyEngineImpl implements PolicyEngine {

    private final UserStatusCache      userStatusCache;
    private final QimClient            qimClient;
    private final AgencyMetaRepository agencyMetaRepository;

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

        // 2. 사용자 상태 (캐시)
        UserStatus userStatus;
        try {
            userStatus = userStatusCache.get(qimUserId).orElse(UserStatus.ACTIVE);
        } catch (Exception e) {
            userStatus = UserStatus.ACTIVE;
        }

        // 3. agencySubjectId — Q-IM DI 조회 (실패 시 HMAC fallback 없이 GUEST 반환)
        //
        // [smep-be 분석 결과]:
        //   smep-be는 Keycloak access token의 "UUID" claim으로 tb_mbrm_mbr_m.uuid를 조회.
        //   UUID(=qimUserId) 매핑 없는 사용자는 RESOURCE_NOT_FOUND → 403.
        //   즉 smep-be는 사전 등록된 UUID 매핑이 없는 사용자를 인정하지 않는다.
        //
        // [정책]: DI 조회 성공 = 기관 매핑 있음 → APPROVED
        //        DI 없음/실패 = 기관 매핑 없음 → GUEST (HMAC fallback ID 발급 금지)
        String agencySubjectId = tryResolveDi(qimUserId, agencyCode, correlationId);
        if (agencySubjectId == null) {
            log.info("[PolicyEngine] 기관 매핑 없음(DI 없음) — GUEST 반환: qimUserId={} agency={} correlationId={}",
                    qimUserId, agencyCode, correlationId);

            // 3. GUEST 사용자 속성 (최소 정보만)
            Map<String, Object> guestAttributes = collectUserAttributes(ticket, qimUserId);
            Map<String, Object> filteredGuestAttrs = filterAttributes(guestAttributes, allowedAttrKeys);

            return HandoffPayload.builder()
                    .ticketId(ticket.getTicketId())
                    .correlationId(ticket.getCorrelationId())
                    .agencyCode(agencyCode)
                    .policyVersion(resolvedPolicyVersion)
                    .state(HandoffPayload.HandoffState.GUEST)
                    .subject(HandoffPayload.SubjectIdentifier.builder()
                            .agencySubjectId(null)   // GUEST는 기관 식별자 없음
                            .qimUserId(qimUserId)
                            .status(userStatus)
                            .build())
                    .authContext(HandoffPayload.AuthContext.builder()
                            .authLevel(ticket.getAuthLevel())
                            .authResultId(ticket.getAuthResultId())
                            .authenticatedAt(ticket.getIssuedAt())
                            .build())
                    .attributes(filteredGuestAttrs)
                    .issuedAt(ticket.getIssuedAt())
                    .expiresAt(ticket.getExpiresAt())
                    .build();
        }

        // 4. APPROVED — 사용자 속성 수집 후 allowedAttributes 필터링
        Map<String, Object> rawAttributes = collectUserAttributes(ticket, qimUserId);
        Map<String, Object> filteredAttributes = filterAttributes(rawAttributes, allowedAttrKeys);
        log.debug("[PolicyEngine] 속성 필터링: total={} allowed={} filtered={}",
                rawAttributes.size(), allowedAttrKeys.size(), filteredAttributes.size());

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
     * Q-IM DI 조회 시도.
     *
     * <p>DI가 있으면 반환 (= 기관 매핑 있음 → APPROVED).
     * DI가 없으면 {@code null} 반환 (= 기관 매핑 없음 → GUEST).
     *
     * <p><b>Sprint α-3 / F4.6 변경</b> — Q-IM 일시 장애와 영구 미매핑 구분:
     * <ul>
     *   <li>{@code null} 반환 (정당한 GUEST) — Q-IM이 명시적으로 "DI 없음"을 응답한 경우:
     *       <ul>
     *         <li>{@link QimClient#getDi} 가 {@code null} 반환 (DI 필드 없음 또는 404)</li>
     *       </ul>
     *   </li>
     *   <li>{@link PlatformException} 재전파 (안전 우선 거부) — Q-IM 호출 자체가 실패한 경우:
     *       <ul>
     *         <li>{@link PlatformErrorCode#IDO_QIM_UNREACHABLE} (5xx / 네트워크 / 타임아웃)</li>
     *         <li>→ {@link #buildHandoffPayload} 가 예외를 그대로 전파 → 클라이언트 503 응답 → 재시도 유도</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <p>이전 구현({@code catch (Exception e) { return null; }})은 Q-IM 장애를 정상 미매핑으로
     * 오인하여 모든 사용자가 GUEST로 떨어지는 데이터 무결성 위험이 있었음
     * (분석: 04_handoff_flow.md F4.6).
     *
     * <p>이전 HMAC fallback({@code generateAgencySubjectId}) 완전 제거:
     * <ul>
     *   <li>smep-be는 UUID 매핑 없는 사용자를 RESOURCE_NOT_FOUND(403)으로 거부</li>
     *   <li>HMAC으로 임의 ID를 발급하면 기관은 실제 회원이 아닌 사용자를 받게 됨</li>
     *   <li>매핑 없으면 GUEST를 반환하여 기관이 직접 처리하도록 위임</li>
     * </ul>
     *
     * @return DI 문자열 (기관 매핑 있음), 또는 null (기관 매핑 없음 — 정당한 GUEST)
     * @throws PlatformException Q-IM 일시 장애 (IDO_QIM_UNREACHABLE) — 안전 우선 거부
     */
    private String tryResolveDi(String qimUserId, String agencyCode, String correlationId) {
        try {
            String di = qimClient.getDi(qimUserId, agencyCode, correlationId);
            if (di != null && !di.isBlank()) {
                log.debug("[PolicyEngine] agencySubjectId = Q-IM DI: agency={}", agencyCode);
                return di;
            }
            // getDi()가 null/blank 반환 = 기관 DI 없음 → GUEST (정당)
            log.info("[PolicyEngine][F4.6] Q-IM DI 없음 — GUEST 정당: qimUserId={} agency={}", qimUserId, agencyCode);
            return null;
        } catch (PlatformException e) {
            // F4.6: Q-IM 일시 장애 (IDO_QIM_UNREACHABLE 등) — GUEST로 swallow 금지.
            // 그대로 전파하여 호출자(IdO 컨트롤러)가 503 응답 → 클라이언트가 재시도하도록 유도.
            log.error("[PolicyEngine][F4.6] Q-IM 일시 장애 → 안전 우선 거부: agency={} code={} err={}",
                    agencyCode, e.getErrorCode().getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            // 예상 외 예외 — 안전 우선 거부로 변환 (NPE 등으로 GUEST가 새는 사고 방지)
            log.error("[PolicyEngine][F4.6] Q-IM DI 조회 예상 외 예외 → 안전 우선 거부: agency={} err={}",
                    agencyCode, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
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

}
