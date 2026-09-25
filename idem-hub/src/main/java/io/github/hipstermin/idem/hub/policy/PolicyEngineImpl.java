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
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.ServiceAccess;
import io.github.hipstermin.idem.hub.infrastructure.UserStatusCache;
import io.github.hipstermin.idem.hub.policy.rule.MaintenanceRule;
import io.github.hipstermin.idem.hub.policy.rule.PolicyContext;
import io.github.hipstermin.idem.hub.policy.rule.PolicyDecision;
import io.github.hipstermin.idem.hub.policy.rule.PolicyEvaluation;
import io.github.hipstermin.idem.hub.policy.rule.PolicyRule;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
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

    /** D3: 점검 시간 판정 시간대 — ido.zone (기본 UTC) */
    @org.springframework.beans.factory.annotation.Value("${ido.zone:UTC}")
    private String zoneId = "UTC";

    private final UserStatusCache      userStatusCache;
    private final QimClient            qimClient;
    private final AgencyMetaRepository agencyMetaRepository;

    /** policyVersion 기본값 — DB에 값 없을 때 fallback (하드코딩 "1.0" 제거) */
    @Value("${ido.policy.default-version:1.0}")
    private String defaultPolicyVersion;

    private final ServiceProfileService serviceProfileService;
    private final SubjectIdentifierResolver subjectResolver;
    private final HandoffAttributeAssembler attributeAssembler;
    private final QAuthzClient         qAuthzClient;   // S8-b 할당·역할
    private final List<PolicyRule>     builtInRules;
    private final Map<String, PolicyRule> customRules;

    public PolicyEngineImpl(UserStatusCache userStatusCache,
                            QimClient qimClient,
                            AgencyMetaRepository agencyMetaRepository,
                            ServiceProfileService serviceProfileService,
                            SubjectIdentifierResolver subjectResolver,
                            HandoffAttributeAssembler attributeAssembler,
                            QAuthzClient qAuthzClient,
                            List<PolicyRule> rules) {
        this.userStatusCache      = userStatusCache;
        this.qimClient            = qimClient;
        this.agencyMetaRepository = agencyMetaRepository;
        this.serviceProfileService = serviceProfileService;
        this.subjectResolver      = subjectResolver;
        this.attributeAssembler   = attributeAssembler;
        this.qAuthzClient         = qAuthzClient;
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
        if (ctx.profile() == null && ctx.serviceCode() != null) {
            effective = ctx.toBuilder()
                    .profile(serviceProfileService.find(ctx.serviceCode()).orElse(null))
                    .build();
        }
        // 프로파일이 지정한 규칙 (내장 규칙의 파라미터 또는 커스텀 규칙)
        Map<String, Map<String, Object>> configured = new LinkedHashMap<>();
        ServiceProfile.Policy policy = effective.policy();
        if (policy != null && policy.rules() != null) {
            for (ServiceProfile.RuleRef ref : policy.rules()) {
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
            if (rule == null) log.error("[PolicyEngine] 미등록 규칙 유형: {} (service={})", e.getKey(), effective.serviceCode());
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
        List<ServiceProfile.MaintenanceWindow> windows = agency.getMaintenanceWindows().stream()
                .map(w -> new ServiceProfile.MaintenanceWindow(w.getDayOfWeek(), w.getStartTime(), w.getEndTime()))
                .toList();
        return MaintenanceRule.isWithin(windows, java.time.Instant.now(), java.time.ZoneId.of(zoneId));
    }

    @Override
    public HandoffPayload buildHandoffPayload(HandoffTicket ticket, String correlationId) {
        String agencyCode = ticket.getAgencyCode();
        String qimUserId  = ticket.getQimUserId();

        // 1. 기관 프로파일 (S4: 식별자 스킴·속성 계약은 프로파일이 진실) + policyVersion
        ServiceProfile profile = serviceProfileService.find(agencyCode).orElse(null);
        ServiceProfile.Identity identity = profile != null ? profile.identity() : null;
        SubjectScheme scheme = identity != null ? identity.subjectSchemeOrDefault() : SubjectScheme.DEFAULT;
        String resolvedPolicyVersion = profile != null && profile.policy() != null && profile.policy().policyVersion() != null
                ? profile.policy().policyVersion() : defaultPolicyVersion;

        // 2. 사용자 상태 — D2 fail-secure: 캐시 미스면 정본(Q-IM) 조회, 그것도 실패면 거부(IDO_QIM_UNREACHABLE).
        //    종전에는 조회 실패를 ACTIVE 로 가정해 정지·탈퇴 사용자에게 페이로드가 나갈 수 있었다.
        UserStatus userStatus = resolveUserStatus(qimUserId, correlationId);

        // 3. agencySubjectId — 프로파일이 고른 스킴으로 해석 (기본 PAIRWISE_HMAC = registry DI, 종전과 동일)
        //
        // [정책]: 식별자 해석 성공 = 기관 매핑 있음 → APPROVED
        //        식별자 없음(empty)   = 기관 매핑 없음 → GUEST (HMAC fallback ID 발급 금지 — smep-be 분석 F4.6)
        //        registry 일시 장애   = PlatformException 전파 → 503 (GUEST 로 묻지 않는다)
        String agencySubjectId = tryResolveSubject(scheme, qimUserId, agencyCode, correlationId);

        // 4. 속성 — 프로파일 identity.attributes/attributeMapping 으로 조립 (GUEST 도 같은 계약을 따른다)
        Map<String, Object> attributes = attributeAssembler.assemble(identity, ticket, correlationId);

        // 5. S8-b 할당·역할 — authz 정본. 장애는 IDO_AUTHZ_UNAVAILABLE 로 전파(verify 는 consume 전이라 티켓은 살아 있다)
        ServiceAccess access = qAuthzClient.getServiceAccess(qimUserId, agencyCode, correlationId);
        ServiceProfile.Assignment assignment = profile != null && profile.policy() != null ? profile.policy().assignment() : null;
        boolean assignmentRequired = assignment != null && assignment.requiresAssignment();
        HandoffPayload.HandoffState state;
        if (assignmentRequired) {
            // 할당 정책이 켜진 프로파일: 상태는 할당이 정한다. 미할당은 발급 단계(AssignmentRule)에서 selfSignup 일 때만 통과했다
            if (!access.authzEnabled()) {
                throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                        "할당 필수 프로파일인데 idem-authz 가 비활성");
            }
            state = access.assigned() ? HandoffPayload.HandoffState.APPROVED : HandoffPayload.HandoffState.GUEST;
            if (state == HandoffPayload.HandoffState.GUEST) {
                log.info("[PolicyEngine] 미할당(selfSignup) — GUEST 반환: qimUserId={} agency={} subjectResolved={} correlationId={}",
                        qimUserId, agencyCode, agencySubjectId != null, correlationId);
            }
        } else {
            // 레거시 의미: 주체 식별자를 해석할 수 없으면 GUEST
            state = agencySubjectId == null ? HandoffPayload.HandoffState.GUEST : HandoffPayload.HandoffState.APPROVED;
            if (state == HandoffPayload.HandoffState.GUEST) {
                log.info("[PolicyEngine] 기관 매핑 없음(scheme={}) — GUEST 반환: qimUserId={} agency={} correlationId={}",
                        scheme, qimUserId, agencyCode, correlationId);
            }
        }
        log.debug("[PolicyEngine] 속성 조립: scheme={} attrs={} roles={}", scheme, attributes.size(), access.roles().size());

        return HandoffPayload.builder()
                .ticketId(ticket.getTicketId())
                .correlationId(ticket.getCorrelationId())
                .agencyCode(agencyCode)
                .policyVersion(resolvedPolicyVersion)
                .state(state)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(agencySubjectId)   // 해석되지 않으면 null (S8-b: 미할당 GUEST 도 해석되면 실린다)
                        .subjectScheme(agencySubjectId != null ? scheme : null)
                        .qimUserId(qimUserId)
                        .status(userStatus)
                        .assigned(access.authzEnabled() ? access.assigned() : null)
                        .build())
                .roles(access.roles())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(ticket.getAuthLevel())
                        .authResultId(ticket.getAuthResultId())
                        .authenticatedAt(ticket.getIssuedAt())
                        .build())
                .attributes(attributes)
                .sessionPolicy(sessionPolicyOf(profile))
                .issuedAt(ticket.getIssuedAt())
                .expiresAt(ticket.getExpiresAt())
                .build();
    }

    /** D3: 프로파일 {@code policy.session} → 페이로드 세션 정책. 블록이 없거나 전부 비면 null (종전에는 매핑만 되고 어디에도 안 나갔다). */
    public static HandoffPayload.SessionPolicy sessionPolicyOf(ServiceProfile profile) {
        ServiceProfile.Session s = profile != null && profile.policy() != null ? profile.policy().session() : null;
        if (s == null) return null;
        HandoffPayload.SessionPolicy sp = new HandoffPayload.SessionPolicy(s.idleMinutes(), s.absoluteMinutes(), s.concurrent());
        return sp.isEmpty() ? null : sp;
    }

    @Override
    public String resolveAgencySubjectId(ServiceProfile profile, String qimUserId, String serviceCode, String correlationId) {
        ServiceProfile.Identity identity = profile != null ? profile.identity() : null;
        SubjectScheme scheme = identity != null ? identity.subjectSchemeOrDefault() : SubjectScheme.DEFAULT;
        return tryResolveSubject(scheme, qimUserId, serviceCode, correlationId);
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
                    .qimUserId(qimUserId).serviceCode(agencyCode).correlationId(correlationId).build();
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
