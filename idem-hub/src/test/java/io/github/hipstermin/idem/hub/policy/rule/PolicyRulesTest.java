package io.github.hipstermin.idem.hub.policy.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.tenant.TenantProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("S3 내장 정책 규칙")
class PolicyRulesTest {

    private static TenantProfile profile(TenantProfile.Policy policy) {
        return TenantProfile.builder().schemaVersion(1)
                .tenant(new TenantProfile.Tenant("AG", "기관", TenantProfile.TenantStatus.ACTIVE))
                .protocol(TenantProfile.Protocol.builder().type(IntegrationType.DIRECT).build())
                .policy(policy).build();
    }

    private static PolicyContext ctx(TenantProfile.Policy policy, AuthResult.AuthLevel level, String provider, UserStatus status, Instant now) {
        return PolicyContext.builder().tenantCode("AG").profile(profile(policy)).authLevel(level).providerCode(provider)
                .userStatus(status == null ? null : () -> status).now(now).correlationId("cid").build();
    }

    @Nested
    @DisplayName("MIN_AUTH_LEVEL")
    class MinAuthLevel {
        final MinAuthLevelRule rule = new MinAuthLevelRule();

        @Test void meets_allows() {
            var d = rule.evaluate(ctx(TenantProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L2).build(), AuthResult.AuthLevel.L3, null, null, null), Map.of());
            assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }

        @Test void below_denies_withHandoffErrorCode() {
            var d = rule.evaluate(ctx(TenantProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L2).build(), AuthResult.AuthLevel.L1, null, null, null), Map.of());
            assertThat(d.denied()).isTrue();
            assertThat(d.errorCode()).isEqualTo(PlatformErrorCode.IDO_AUTH_LEVEL_INSUFFICIENT);
            assertThat(d.auditReason()).isEqualTo("AUTH_LEVEL_INSUFFICIENT");
        }

        @Test void missingLevel_denies_failClosed() {
            var d = rule.evaluate(ctx(TenantProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1).build(), null, null, null, null), Map.of());
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
            assertThat(rule.evaluate(ctx(TenantProfile.Policy.builder().build(), null, "NICE", null, null), Map.of()).outcome())
                    .isEqualTo(PolicyDecision.Outcome.SKIP);
        }

        @Test void listed_allows_caseInsensitive() {
            var p = TenantProfile.Policy.builder().allowedProviders(List.of("NICE", "OACX_EASYSIGN")).build();
            assertThat(rule.evaluate(ctx(p, null, "nice", null, null), Map.of()).outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
        }

        @Test void notListed_orMissing_denies() {
            var p = TenantProfile.Policy.builder().allowedProviders(List.of("NICE")).build();
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

        TenantProfile.Policy windows(String day) {
            return TenantProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1)
                    .maintenance(List.of(new TenantProfile.MaintenanceWindow(day, "02:00", "04:00"))).build();
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
            assertThat(rule.evaluate(ctx(TenantProfile.Policy.builder().build(), null, null, null, thuSeoul0300), Map.of()).outcome())
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
}
