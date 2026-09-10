package io.github.hipstermin.idem.hub.policy;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.identity.HandoffAttributeAssembler;
import io.github.hipstermin.idem.hub.identity.SubjectIdentifierResolver;
import io.github.hipstermin.idem.hub.identity.SubjectResolutionContext;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.UserStatusCache;
import io.github.hipstermin.idem.hub.policy.rule.MaintenanceRule;
import io.github.hipstermin.idem.hub.policy.rule.PolicyContext;
import io.github.hipstermin.idem.hub.policy.rule.PolicyDecision;
import io.github.hipstermin.idem.hub.policy.rule.PolicyEvaluation;
import io.github.hipstermin.idem.hub.policy.rule.PolicyRule;
import io.github.hipstermin.idem.hub.tenant.TenantProfile;
import io.github.hipstermin.idem.hub.tenant.TenantProfileService;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 *   <li>속성 필터링 — S4 부터 {@link HandoffAttributeAssembler}(프로파일 identity 계약) 가 담당</li>
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

    private final TenantProfileService tenantProfileService;
    private final SubjectIdentifierResolver subjectResolver;
    private final HandoffAttributeAssembler attributeAssembler;
    private final List<PolicyRule>     builtInRules;
    private final Map<String, PolicyRule> customRules;

    public PolicyEngineImpl(UserStatusCache userStatusCache,
                            QimClient qimClient,
                            AgencyMetaRepository agencyMetaRepository,
                            TenantProfileService tenantProfileService,
                            SubjectIdentifierResolver subjectResolver,
                            HandoffAttributeAssembler attributeAssembler,
                            List<PolicyRule> rules) {
        this.userStatusCache      = userStatusCache;
        this.qimClient            = qimClient;
        this.agencyMetaRepository = agencyMetaRepository;
        this.tenantProfileService = tenantProfileService;
        this.subjectResolver      = subjectResolver;
        this.attributeAssembler   = attributeAssembler;
        this.builtInRules = rules.stream().filter(PolicyRule::builtIn)
                .sorted(Comparator.comparingInt(PolicyRule::order)).toList();
        Map<String, PolicyRule> custom = new LinkedHashMap<>();
        for (PolicyRule r : rules) {
            if (!r.builtIn() && custom.putIfAbsent(r.type().toUpperCase(Locale.ROOT), r) != null) {
                throw new IllegalStateException("PolicyRule 유형 중복: " + r.type());
            }
        }
        this.customRules = Collections.unmodifiableMap(custom);
        log.info("[PolicyEngine] 내장 규칙 {} · 커스텀 규칙 {}",
                builtInRules.stream().map(PolicyRule::type).toList(), customRules.keySet());
    }

    // ── S3: 규칙 집합 평가 ────────────────────────────────────────────────────

    @Override
    public PolicyEvaluation evaluate(PolicyContext ctx, boolean stopAtFirstDenial) {
        PolicyContext effective = ctx;
        if (ctx.profile() == null && ctx.tenantCode() != null) {
            effective = ctx.toBuilder()
                    .profile(tenantProfileService.find(ctx.tenantCode()).orElse(null))
                    .build();
        }
        // 프로파일이 지정한 규칙 (내장 규칙의 파라미터 또는 커스텀 규칙)
        Map<String, Map<String, Object>> configured = new LinkedHashMap<>();
        TenantProfile.Policy policy = effective.policy();
        if (policy != null && policy.rules() != null) {
            for (TenantProfile.RuleRef ref : policy.rules()) {
                if (ref != null && ref.type() != null) {
                    configured.put(ref.type().trim().toUpperCase(Locale.ROOT),
                            ref.params() != null ? ref.params() : Map.of());
                }
            }
        }
        List<PolicyDecision> decisions = new ArrayList<>();
        for (PolicyRule rule : builtInRules) {
            PolicyDecision d = rule.evaluate(effective, configured.getOrDefault(rule.type(), Map.of()));
            decisions.add(d);
            if (stopAtFirstDenial && d.denied()) return new PolicyEvaluation(decisions);
        }
        for (Map.Entry<String, Map<String, Object>> e : configured.entrySet()) {
            if (builtInRules.stream().anyMatch(r -> r.type().equals(e.getKey()))) continue;
            PolicyRule rule = customRules.get(e.getKey());
            PolicyDecision d = rule != null
                    ? rule.evaluate(effective, e.getValue())
                    : PolicyDecision.deny(e.getKey(), "등록되지 않은 규칙 유형 — 프로파일이 존재하지 않는 규칙을 요구함",
                            "UNKNOWN_RULE", PlatformErrorCode.IDO_POLICY_REJECTED);
            if (rule == null) log.error("[PolicyEngine] 미등록 규칙 유형: {} (tenant={})", e.getKey(), effective.tenantCode());
            decisions.add(d);
            if (stopAtFirstDenial && d.denied()) return new PolicyEvaluation(decisions);
        }
        return new PolicyEvaluation(decisions);
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
        return actual != null && actual.meets(required);
    }

    @Override
    public boolean isUnderMaintenance(AgencyMeta agency) {
        if (agency.getMaintenanceWindows() == null || agency.getMaintenanceWindows().isEmpty()) return false;
        // S3: MaintenanceRule 과 같은 판정 (MON/MONDAY 모두 인식 — 종전에는 "MON" 이 한 번도 걸리지 않았다)
        List<TenantProfile.MaintenanceWindow> windows = agency.getMaintenanceWindows().stream()
                .map(w -> new TenantProfile.MaintenanceWindow(w.getDayOfWeek(), w.getStartTime(), w.getEndTime()))
                .toList();
        return MaintenanceRule.isWithin(windows, java.time.Instant.now(), MaintenanceRule.DEFAULT_ZONE);
    }

    @Override
    public HandoffPayload buildHandoffPayload(HandoffTicket ticket, String correlationId) {
        String agencyCode = ticket.getAgencyCode();
        String qimUserId  = ticket.getQimUserId();

        // 1. 기관 프로파일 (S4: 식별자 스킴·속성 계약은 프로파일이 진실) + policyVersion
        TenantProfile profile = tenantProfileService.find(agencyCode).orElse(null);
        TenantProfile.Identity identity = profile != null ? profile.identity() : null;
        SubjectScheme scheme = identity != null ? identity.subjectSchemeOrDefault() : SubjectScheme.DEFAULT;
        String resolvedPolicyVersion = profile != null && profile.policy() != null && profile.policy().policyVersion() != null
                ? profile.policy().policyVersion() : defaultPolicyVersion;

        // 2. 사용자 상태 (캐시)
        UserStatus userStatus;
        try {
            userStatus = userStatusCache.get(qimUserId).orElse(UserStatus.ACTIVE);
        } catch (Exception e) {
            userStatus = UserStatus.ACTIVE;
        }

        // 3. agencySubjectId — 프로파일이 고른 스킴으로 해석 (기본 PAIRWISE_HMAC = registry DI, 종전과 동일)
        //
        // [정책]: 식별자 해석 성공 = 기관 매핑 있음 → APPROVED
        //        식별자 없음(empty)   = 기관 매핑 없음 → GUEST (HMAC fallback ID 발급 금지 — smep-be 분석 F4.6)
        //        registry 일시 장애   = PlatformException 전파 → 503 (GUEST 로 묻지 않는다)
        String agencySubjectId = tryResolveSubject(scheme, qimUserId, agencyCode, correlationId);

        // 4. 속성 — 프로파일 identity.attributes/attributeMapping 으로 조립 (GUEST 도 같은 계약을 따른다)
        Map<String, Object> attributes = attributeAssembler.assemble(identity, ticket, correlationId);

        HandoffPayload.HandoffState state = agencySubjectId == null
                ? HandoffPayload.HandoffState.GUEST : HandoffPayload.HandoffState.APPROVED;
        if (state == HandoffPayload.HandoffState.GUEST) {
            log.info("[PolicyEngine] 기관 매핑 없음(scheme={}) — GUEST 반환: qimUserId={} agency={} correlationId={}",
                    scheme, qimUserId, agencyCode, correlationId);
        } else {
            log.debug("[PolicyEngine] 속성 조립: scheme={} attrs={}", scheme, attributes.size());
        }

        return HandoffPayload.builder()
                .ticketId(ticket.getTicketId())
                .correlationId(ticket.getCorrelationId())
                .agencyCode(agencyCode)
                .policyVersion(resolvedPolicyVersion)
                .state(state)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(agencySubjectId)   // GUEST 는 null
                        .subjectScheme(agencySubjectId != null ? scheme : null)
                        .qimUserId(qimUserId)
                        .status(userStatus)
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(ticket.getAuthLevel())
                        .authResultId(ticket.getAuthResultId())
                        .authenticatedAt(ticket.getIssuedAt())
                        .build())
                .attributes(attributes)
                .issuedAt(ticket.getIssuedAt())
                .expiresAt(ticket.getExpiresAt())
                .build();
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * 기관향 주체 식별자 해석 (S4 — 종전 {@code tryResolveDi} 의 일반화).
     *
     * <p><b>Sprint α-3 / F4.6 원칙</b> — registry 일시 장애와 영구 미매핑 구분:
     * <ul>
     *   <li>{@code null} 반환 (정당한 GUEST) — 스킴 구현이 empty 를 돌려준 경우 (DI 없음·404·스킴 불일치)</li>
     *   <li>{@link PlatformException} 재전파 (안전 우선 거부) — {@code IDO_QIM_UNREACHABLE} 등 호출 자체가 실패한 경우.
     *       호출자가 503 으로 응답해 클라이언트 재시도를 유도한다</li>
     *   <li>예상 외 예외는 {@code IDO_QIM_UNREACHABLE} 로 변환 — NPE 등으로 GUEST 가 새는 사고 방지</li>
     * </ul>
     *
     * <p>이전 HMAC fallback({@code generateAgencySubjectId}) 은 완전히 제거된 상태를 유지한다 — 매핑 없는 사용자에게
     * 임의 ID 를 발급하면 기관은 실제 회원이 아닌 사용자를 받게 된다.
     */
    private String tryResolveSubject(SubjectScheme scheme, String qimUserId, String agencyCode, String correlationId) {
        try {
            SubjectResolutionContext ctx = SubjectResolutionContext.builder()
                    .qimUserId(qimUserId).tenantCode(agencyCode).correlationId(correlationId).build();
            return subjectResolver.resolve(scheme, ctx).orElse(null);
        } catch (PlatformException e) {
            log.error("[PolicyEngine][F4.6] 주체 식별자 해석 실패 → 안전 우선 거부: agency={} scheme={} code={} err={}",
                    agencyCode, scheme, e.getErrorCode().getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[PolicyEngine][F4.6] 주체 식별자 해석 예상 외 예외 → 안전 우선 거부: agency={} scheme={} err={}",
                    agencyCode, scheme, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId);
        }
    }


}
