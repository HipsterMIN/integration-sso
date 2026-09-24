package io.github.hipstermin.idem.hub.policy.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.ServiceAccess;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("S3 내장 정책 규칙")
class PolicyRulesTest {

    private static ServiceProfile profile(ServiceProfile.Policy policy) {
        return ServiceProfile.builder().schemaVersion(1)
                .service(new ServiceProfile.Service("AG", "기관", ServiceProfile.ServiceStatus.ACTIVE))
                .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.DIRECT).build())
                .policy(policy).build();
    }

    private static PolicyContext ctx(ServiceProfile.Policy policy, AuthResult.AuthLevel level, String provider, UserStatus status, Instant now) {
        return PolicyContext.builder().serviceCode("AG").profile(profile(policy)).authLevel(level).providerCode(provider)
                .userStatus(status == null ? null : () -> status).now(now).correlationId("cid").build();
    }

    @Nested
    @DisplayName("MIN_AUTH_LEVEL")
    class MinAuthLevel {
        final MinAuthLevelRule rule = new MinAuthLevelRule();

        @Test void meets_allows() {
            var d = rule.evaluate(ctx(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L2).build(), AuthResult.AuthLevel.L3, null, null, null), Map.of());
            assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }

        @Test void below_denies_withHandoffErrorCode() {
            var d = rule.evaluate(ctx(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L2).build(), AuthResult.AuthLevel.L1, null, null, null), Map.of());
            assertThat(d.denied()).isTrue();
            assertThat(d.errorCode()).isEqualTo(PlatformErrorCode.IDO_AUTH_LEVEL_INSUFFICIENT);
            assertThat(d.auditReason()).isEqualTo("AUTH_LEVEL_INSUFFICIENT");
        }

        @Test void missingLevel_denies_failClosed() {
            var d = rule.evaluate(ctx(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1).build(), null, null, null, null), Map.of());
            assertThat(d.denied()).isTrue();
        }

        @Test void noPolicy_defaultsToL1() {
            var d = rule.evaluate(ctx(null, AuthResult.AuthLevel.L1, null, null, null), Map.of());
            assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }
    }

    @Nested
    @DisplayName("ALLOWED_PROVIDERS")
    class AllowedProviders {
        final AllowedProvidersRule rule = new AllowedProvidersRule();

        @Test void noRestriction_skips() {
            assertThat(rule.evaluate(ctx(ServiceProfile.Policy.builder().build(), null, "NICE", null, null), Map.of()).outcome())
                    .isEqualTo(PolicyDecision.Outcome.SKIP);
        }

        @Test void listed_allows_caseInsensitive() {
            var p = ServiceProfile.Policy.builder().allowedProviders(List.of("NICE", "OACX_EASYSIGN")).build();
            assertThat(rule.evaluate(ctx(p, null, "nice", null, null), Map.of()).outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }

        @Test void notListed_orMissing_denies() {
            var p = ServiceProfile.Policy.builder().allowedProviders(List.of("NICE")).build();
            var d1 = rule.evaluate(ctx(p, null, "MOCK", null, null), Map.of());
            var d2 = rule.evaluate(ctx(p, null, null, null, null), Map.of());
            assertThat(d1.denied()).isTrue();
            assertThat(d1.errorCode()).isEqualTo(PlatformErrorCode.IDO_POLICY_REJECTED);
            assertThat(d2.denied()).isTrue();
        }
    }

    @Nested
    @DisplayName("MAINTENANCE")
    class Maintenance {
        final MaintenanceRule rule = new MaintenanceRule();
        // 2026-09-10 은 목요일. Asia/Seoul 03:00 = UTC 전날 18:00
        final Instant thuSeoul0300 = Instant.parse("2026-09-09T18:00:00Z");
        final Instant thuSeoul0500 = Instant.parse("2026-09-09T20:00:00Z");

        ServiceProfile.Policy windows(String day) {
            return ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1)
                    .maintenance(List.of(new ServiceProfile.MaintenanceWindow(day, "02:00", "04:00"))).build();
        }

        @Test void within_denies_bothDayFormats() {
            assertThat(rule.evaluate(ctx(windows("THU"), null, null, null, thuSeoul0300), Map.of()).denied()).isTrue();
            assertThat(rule.evaluate(ctx(windows("THURSDAY"), null, null, null, thuSeoul0300), Map.of()).denied()).isTrue();
            assertThat(rule.evaluate(ctx(windows("thu"), null, null, null, thuSeoul0300), Map.of()).errorCode())
                    .isEqualTo(PlatformErrorCode.AGENCY_MAINTENANCE);
        }

        @Test void outsideTime_orOtherDay_allows() {
            assertThat(rule.evaluate(ctx(windows("THU"), null, null, null, thuSeoul0500), Map.of()).outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
            assertThat(rule.evaluate(ctx(windows("MON"), null, null, null, thuSeoul0300), Map.of()).outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }

        @Test void noWindows_skips() {
            assertThat(rule.evaluate(ctx(ServiceProfile.Policy.builder().build(), null, null, null, thuSeoul0300), Map.of()).outcome())
                    .isEqualTo(PolicyDecision.Outcome.SKIP);
        }
    }

    @Nested
    @DisplayName("USER_STATUS")
    class UserStatusRuleTest {
        final UserStatusRule rule = new UserStatusRule();

        @Test void active_allows_suspendedWithdrawn_denyWithSpecificCodes() {
            assertThat(rule.evaluate(ctx(null, null, null, UserStatus.ACTIVE, null), Map.of()).outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
            var s = rule.evaluate(ctx(null, null, null, UserStatus.SUSPENDED, null), Map.of());
            var w = rule.evaluate(ctx(null, null, null, UserStatus.WITHDRAWN, null), Map.of());
            assertThat(s.errorCode()).isEqualTo(PlatformErrorCode.IM_USER_SUSPENDED);
            assertThat(s.auditReason()).isEqualTo("USER_SUSPENDED");
            assertThat(w.errorCode()).isEqualTo(PlatformErrorCode.IM_USER_WITHDRAWN);
            assertThat(w.auditReason()).isEqualTo("USER_WITHDRAWN");
        }

        @Test void noSupplier_skips() {
            assertThat(rule.evaluate(ctx(null, null, null, null, null), Map.of()).outcome()).isEqualTo(PolicyDecision.Outcome.SKIP);
        }

        @Test void params_overrideDenySet() {
            var d = rule.evaluate(ctx(null, null, null, UserStatus.WITHDRAWAL_SCHEDULED, null), Map.of("deny", List.of("WITHDRAWAL_SCHEDULED")));
            assertThat(d.denied()).isTrue();
            assertThat(d.errorCode()).isEqualTo(PlatformErrorCode.IDO_POLICY_REJECTED);
            // 기본 집합에서는 통과
            assertThat(rule.evaluate(ctx(null, null, null, UserStatus.WITHDRAWAL_SCHEDULED, null), Map.of()).outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }
    }

    @Nested
    @DisplayName("ASSIGNMENT (S8-b)")
    class Assignment {
        final AssignmentRule rule = new AssignmentRule();

        private PolicyContext ctxWith(ServiceProfile.Assignment cfg, ServiceAccess access) {
            var policy = ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1).assignment(cfg).build();
            return PolicyContext.builder().serviceCode("AG").profile(profile(policy))
                    .serviceAccess(access == null ? null : () -> access).correlationId("cid").build();
        }

        @Test void noPolicy_allows_withoutTouchingAuthz() {
            var d = rule.evaluate(ctxWith(null, null), Map.of());
            assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }
        @Test void required_assigned_allows() {
            var d = rule.evaluate(ctxWith(new ServiceProfile.Assignment(true, false), new ServiceAccess(true, true, "SCIM", List.of())), Map.of());
            assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }
        @Test void required_unassigned_denies_E120() {
            var d = rule.evaluate(ctxWith(new ServiceProfile.Assignment(true, false), new ServiceAccess(true, false, null, List.of())), Map.of());
            assertThat(d.denied()).isTrue();
            assertThat(d.errorCode()).isEqualTo(PlatformErrorCode.IDO_ASSIGNMENT_REQUIRED);
            assertThat(d.auditReason()).isEqualTo("ASSIGNMENT_REQUIRED");
        }
        @Test void required_unassigned_selfSignup_allows() {
            var d = rule.evaluate(ctxWith(new ServiceProfile.Assignment(true, true), new ServiceAccess(true, false, null, List.of())), Map.of());
            assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
            assertThat(d.reason()).contains("GUEST");
        }
        @Test void required_authzDisabled_denies_failClosed() {
            var d = rule.evaluate(ctxWith(new ServiceProfile.Assignment(true, true), ServiceAccess.disabled()), Map.of());
            assertThat(d.denied()).isTrue();
            assertThat(d.errorCode()).isEqualTo(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE);
        }
        @Test void required_noAccessSupplier_skips() {
            var d = rule.evaluate(ctxWith(new ServiceProfile.Assignment(true, false), null), Map.of());
            assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.SKIP);
        }
        @Test void params_overrideProfile() {
            var d = rule.evaluate(ctxWith(null, new ServiceAccess(true, false, null, List.of())), Map.of("required", true));
            assertThat(d.denied()).isTrue();
        }
    }
}
