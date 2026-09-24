package io.github.hipstermin.idem.hub.protocol.oidcrp;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.broker.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimMemberInfo;
import io.github.hipstermin.idem.hub.infrastructure.ServiceAccess;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.policy.rule.PolicyContext;
import io.github.hipstermin.idem.hub.policy.rule.PolicyDecision;
import io.github.hipstermin.idem.hub.policy.rule.PolicyEvaluation;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 표준 OIDC(OIDC_RP) 경로의 정책 강제 (S6).
 *
 * <p>기관 RP 가 gate 를 통해 Keycloak 에서 code 를 token 으로 바꾸는 순간, gate 가 이 서비스에 묻고 Idem 정책
 * (점검·인증수준·허용 제공자·사용자 상태·할당)을 Handoff 와 **같은 엔진**으로 평가한다. 표준 프로토콜로 붙어도
 * 정책을 우회할 수 없다는 뜻이며, 결과 어휘(state·agencySubjectId·roles·assigned)도 Handoff 와 같다.
 *
 * <p>사용자는 registry 에 {@code (sub, providerCode)} 로 찾거나 새로 등록한다 — OIDC_RP 로그인은 gate/hub 콜백을
 * 거치지 않으므로 이 자리가 registry 가 사용자를 처음 보는 자리다. registry 장애는 거부(E-IDO-116, 안전 우선).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OidcRpAccessService {

    private final OidcRpProperties props;
    private final ServiceProfileService serviceProfileService;
    private final KeycloakProperties keycloakProperties;
    private final QimClient qimClient;
    private final QAuthzClient qAuthzClient;
    private final PolicyEngine policyEngine;

    public OidcRpAccessResponse evaluate(OidcRpAccessRequest req, String correlationId) {
        // 1. client → service
        Optional<String> code = props.serviceCodeFor(req.clientId());
        if (code.isEmpty()) {
            return deny(PlatformErrorCode.IDO_OIDC_CLIENT_UNKNOWN, "Idem 이 프로비저닝한 client 가 아닙니다: " + req.clientId(), "CLIENT", null, correlationId);
        }
        String serviceCode = code.get();
        ServiceProfile profile = serviceProfileService.find(serviceCode).orElse(null);
        if (profile == null || profile.protocol() == null || profile.protocol().type() != IntegrationType.OIDC_RP) {
            return deny(PlatformErrorCode.IDO_OIDC_CLIENT_UNKNOWN, "OIDC_RP 프로파일이 없습니다: " + serviceCode, "CLIENT", serviceCode, correlationId);
        }
        if (!profile.isActive()) {
            return deny(PlatformErrorCode.AGENCY_NOT_REGISTERED, "비활성 서비스: " + serviceCode, "CLIENT", serviceCode, correlationId);
        }

        // 2. 인증 컨텍스트 (Keycloak 클레임 → 플랫폼 어휘)
        String providerCode = keycloakProperties.resolveProviderCode(req.identityProvider());
        AuthResult.AuthLevel authLevel = AuthResult.AuthLevel.parse(keycloakProperties.resolveAuthLevel(req.acr()))
                .orElse(AuthResult.AuthLevel.L1);

        // 3. registry 사용자 — 찾거나 등록. 실패는 거부
        String qimUserId;
        try {
            qimUserId = findOrRegister(req.sub(), providerCode, correlationId);
        } catch (Exception e) {
            log.error("[OidcRpAccess] registry 조회/등록 실패 → 거부: service={} provider={} cid={} err={}",
                    serviceCode, providerCode, correlationId, e.getMessage());
            return deny(PlatformErrorCode.IDO_DEPENDENCY_UNAVAILABLE, "registry 조회 실패", "IDENTITY", serviceCode, correlationId);
        }

        // 4. 정책 평가 — Handoff 발급과 같은 규칙 집합
        Supplier<ServiceAccess> access = memoize(() -> qAuthzClient.getServiceAccess(qimUserId, serviceCode, correlationId));
        PolicyContext ctx = PolicyContext.builder()
                .serviceCode(serviceCode).profile(profile)
                .authLevel(authLevel).providerCode(providerCode)
                .userStatus(() -> policyEngine.resolveUserStatus(qimUserId, correlationId))
                .serviceAccess(access)
                .correlationId(correlationId)
                .build();
        try {
            PolicyEvaluation evaluation = policyEngine.evaluate(ctx, true);
            Optional<PolicyDecision> denial = evaluation.firstDenial();
            if (denial.isPresent()) {
                PolicyDecision d = denial.get();
                return deny(d.errorCode() != null ? d.errorCode() : PlatformErrorCode.IDO_POLICY_REJECTED,
                        d.reason(), d.rule(), serviceCode, correlationId);
            }

            // 5. 상태·주체·역할 — buildHandoffPayload 와 같은 의미
            ServiceAccess sa = access.get();
            String agencySubjectId = policyEngine.resolveAgencySubjectId(profile, qimUserId, serviceCode, correlationId);
            ServiceProfile.Assignment assignment = profile.policy() != null ? profile.policy().assignment() : null;
            HandoffPayload.HandoffState state;
            if (assignment != null && assignment.requiresAssignment()) {
                if (!sa.authzEnabled()) {
                    return deny(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, "할당 필수 프로파일인데 idem-authz 가 비활성", "ASSIGNMENT", serviceCode, correlationId);
                }
                state = sa.assigned() ? HandoffPayload.HandoffState.APPROVED : HandoffPayload.HandoffState.GUEST;
            } else {
                state = agencySubjectId == null ? HandoffPayload.HandoffState.GUEST : HandoffPayload.HandoffState.APPROVED;
            }
            String scheme = profile.identity() != null ? profile.identity().subjectSchemeOrDefault().name()
                    : io.github.hipstermin.idem.common.identity.SubjectScheme.DEFAULT.name();
            log.info("[OidcRpAccess] 허용: service={} state={} provider={} level={} roles={} cid={}",
                    serviceCode, state, providerCode, authLevel, sa.roles().size(), correlationId);
            return new OidcRpAccessResponse(true, null, null, null, serviceCode, qimUserId, state,
                    agencySubjectId, agencySubjectId != null ? scheme : null, sa.roles(),
                    sa.authzEnabled() ? sa.assigned() : null, authLevel.name(), providerCode);
        } catch (PlatformException e) {
            // 규칙 평가·주체 해석 중 의존 장애(authz·registry) — 거부로 돌려 gate 가 OAuth 오류로 바꾼다
            log.error("[OidcRpAccess] 의존 장애 → 거부: service={} code={} cid={} err={}", serviceCode, e.getErrorCode().getCode(), correlationId, e.getMessage());
            return deny(e.getErrorCode(), e.getMessage(), "DEPENDENCY", serviceCode, correlationId);
        }
    }

    private String findOrRegister(String sub, String providerCode, String correlationId) {
        Optional<QimMemberInfo> existing = qimClient.findBySocialSub(sub, providerCode, correlationId);
        if (existing.isPresent()) return existing.get().getQimUserId();
        String identifierHash = CryptoProviders.current().sha256Hex(sub);
        String created = qimClient.registerSocialUser(sub, providerCode, identifierHash, correlationId).getQimUserId();
        log.info("[OidcRpAccess] registry 신규 등록: provider={} cid={}", providerCode, correlationId);
        return created;
    }

    private static OidcRpAccessResponse deny(PlatformErrorCode code, String message, String rule, String serviceCode, String cid) {
        log.warn("[OidcRpAccess] 거부: service={} rule={} code={} cid={} — {}", serviceCode, rule, code.getCode(), cid, message);
        return OidcRpAccessResponse.denied(code, message, rule, serviceCode);
    }

    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private T value;
            private boolean done;
            @Override public synchronized T get() {
                if (!done) { value = delegate.get(); done = true; }
                return value;
            }
        };
    }
}
