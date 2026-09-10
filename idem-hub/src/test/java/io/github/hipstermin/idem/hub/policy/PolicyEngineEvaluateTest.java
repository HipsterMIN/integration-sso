package io.github.hipstermin.idem.hub.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.identity.HandoffAttributeAssembler;
import io.github.hipstermin.idem.hub.identity.SubjectIdentifierResolver;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.UserStatusCache;
import io.github.hipstermin.idem.hub.policy.rule.AllowedProvidersRule;
import io.github.hipstermin.idem.hub.policy.rule.MaintenanceRule;
import io.github.hipstermin.idem.hub.policy.rule.MinAuthLevelRule;
import io.github.hipstermin.idem.hub.policy.rule.PolicyContext;
import io.github.hipstermin.idem.hub.policy.rule.PolicyDecision;
import io.github.hipstermin.idem.hub.policy.rule.PolicyEvaluation;
import io.github.hipstermin.idem.hub.policy.rule.PolicyRule;
import io.github.hipstermin.idem.hub.policy.rule.UserStatusRule;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyEngineImpl.evaluate — 규칙 집합 조합 (S3)")
class PolicyEngineEvaluateTest {

    @Mock UserStatusCache userStatusCache;
    @Mock QimClient qimClient;
    @Mock AgencyMetaRepository agencyMetaRepository;
    @Mock ServiceProfileService serviceProfileService;

    /** 커스텀 규칙 — params.allow 가 false 면 거부 */
    static final PolicyRule CUSTOM = new PolicyRule() {
        @Override public String type() { return "CUSTOM_FLAG"; }
        @Override public PolicyDecision evaluate(PolicyContext ctx, Map<String, Object> params) {
            return Boolean.TRUE.equals(params.get("allow"))
                    ? PolicyDecision.allow(type(), "flag on")
                    : PolicyDecision.deny(type(), "flag off", "CUSTOM_FLAG_OFF", PlatformErrorCode.IDO_POLICY_REJECTED);
        }
    };

    private PolicyEngineImpl engine() {
        SubjectIdentifierResolver resolver = new SubjectIdentifierResolver(List.of());
        return new PolicyEngineImpl(userStatusCache, qimClient, agencyMetaRepository, serviceProfileService,
                resolver, new HandoffAttributeAssembler(qimClient, resolver),
                List.of(new UserStatusRule(), new AllowedProvidersRule(), new MinAuthLevelRule(), new MaintenanceRule(), CUSTOM));
    }

    private static ServiceProfile profile(ServiceProfile.Policy policy) {
        return ServiceProfile.builder().schemaVersion(1)
                .service(new ServiceProfile.Service("AG", "기관", ServiceProfile.ServiceStatus.ACTIVE))
                .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.DIRECT).build())
                .policy(policy).build();
    }

    @Test
    @DisplayName("내장 규칙은 order 순(MAINTENANCE → MIN_AUTH_LEVEL → ALLOWED_PROVIDERS → USER_STATUS)으로 전부 평가된다")
    void builtInsEvaluatedInOrder() {
        PolicyContext ctx = PolicyContext.builder().serviceCode("AG")
                .profile(profile(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1).build()))
                .authLevel(AuthResult.AuthLevel.L2).providerCode("NICE").userStatus(() -> UserStatus.ACTIVE).build();

        PolicyEvaluation eval = engine().evaluate(ctx, false);

        assertThat(eval.allowed()).isTrue();
        assertThat(eval.decisions()).extracting(PolicyDecision::rule)
                .containsExactly("MAINTENANCE", "MIN_AUTH_LEVEL", "ALLOWED_PROVIDERS", "USER_STATUS");
    }

    @Test
    @DisplayName("stopAtFirstDenial=true 면 첫 거부에서 멈추고 뒤의(비싼) USER_STATUS 공급자는 호출되지 않는다")
    void stopsAtFirstDenial_withoutCallingLaterSuppliers() {
        AtomicInteger statusCalls = new AtomicInteger();
        PolicyContext ctx = PolicyContext.builder().serviceCode("AG")
                .profile(profile(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L3).build()))
                .authLevel(AuthResult.AuthLevel.L1)
                .userStatus(() -> { statusCalls.incrementAndGet(); return UserStatus.ACTIVE; }).build();

        PolicyEvaluation eval = engine().evaluate(ctx, true);

        assertThat(eval.firstDenial()).isPresent();
        assertThat(eval.firstDenial().get().rule()).isEqualTo("MIN_AUTH_LEVEL");
        assertThat(eval.decisions()).hasSize(2); // MAINTENANCE(SKIP) → MIN_AUTH_LEVEL(DENY) 에서 중단
        assertThat(statusCalls.get()).isZero();
    }

    @Test
    @DisplayName("프로파일 policy.rules 로 커스텀 규칙을 켜고 파라미터를 넘긴다")
    void customRule_fromProfileRules() {
        ServiceProfile.Policy on = ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1)
                .rules(List.of(new ServiceProfile.RuleRef("CUSTOM_FLAG", Map.of("allow", true)))).build();
        ServiceProfile.Policy off = ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1)
                .rules(List.of(new ServiceProfile.RuleRef("custom_flag", Map.of("allow", false)))).build();

        assertThat(engine().evaluate(PolicyContext.builder().profile(profile(on)).authLevel(AuthResult.AuthLevel.L1).build(), false).allowed()).isTrue();
        PolicyEvaluation denied = engine().evaluate(PolicyContext.builder().profile(profile(off)).authLevel(AuthResult.AuthLevel.L1).build(), false);
        assertThat(denied.firstDenial()).isPresent();
        assertThat(denied.firstDenial().get().rule()).isEqualTo("CUSTOM_FLAG");
    }

    @Test
    @DisplayName("등록되지 않은 규칙 유형을 프로파일이 요구하면 거부한다(fail-closed)")
    void unknownRuleType_denies() {
        ServiceProfile.Policy p = ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1)
                .rules(List.of(new ServiceProfile.RuleRef("NOT_REGISTERED", null))).build();

        PolicyEvaluation eval = engine().evaluate(PolicyContext.builder().profile(profile(p)).authLevel(AuthResult.AuthLevel.L1).build(), false);

        assertThat(eval.firstDenial()).isPresent();
        assertThat(eval.firstDenial().get().auditReason()).isEqualTo("UNKNOWN_RULE");
    }

    @Test
    @DisplayName("프로파일이 없으면 serviceCode 로 읽는다")
    void loadsProfileByServiceCode() {
        given(serviceProfileService.find("AG")).willReturn(Optional.of(
                profile(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L2).build())));

        PolicyEvaluation eval = engine().evaluate(
                PolicyContext.builder().serviceCode("AG").authLevel(AuthResult.AuthLevel.L1).build(), true);

        assertThat(eval.firstDenial()).isPresent();
        assertThat(eval.firstDenial().get().rule()).isEqualTo("MIN_AUTH_LEVEL");
    }
}
