package io.github.hipstermin.idem.authz.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.hipstermin.idem.authz.api.AuthzErrorCode;
import io.github.hipstermin.idem.authz.api.AuthzException;
import io.github.hipstermin.idem.authz.api.dto.AssignRequest;
import io.github.hipstermin.idem.authz.api.dto.AssignmentRuleRequest;
import io.github.hipstermin.idem.authz.domain.AssignmentRuleType;
import io.github.hipstermin.idem.authz.domain.AssignmentSource;
import io.github.hipstermin.idem.authz.domain.AssignmentStatus;
import io.github.hipstermin.idem.authz.domain.AuditEvent;
import io.github.hipstermin.idem.authz.domain.AuthzAssignmentEntity;
import io.github.hipstermin.idem.authz.domain.AuthzAssignmentRuleEntity;
import io.github.hipstermin.idem.authz.domain.AuthzUserRoleEntity;
import io.github.hipstermin.idem.authz.domain.GrantSource;
import io.github.hipstermin.idem.authz.infrastructure.AuthzAssignmentRepository;
import io.github.hipstermin.idem.authz.infrastructure.AuthzAssignmentRuleRepository;
import io.github.hipstermin.idem.authz.infrastructure.AuthzUserRoleRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 1.1 규칙 할당 — 생성 검증·평가(실체화·재평가 회수)·비활성화 회수. */
@ExtendWith(MockitoExtension.class)
class AssignmentRuleServiceTest {

    private static final String USER = "user-1";
    private static final String AG = "AG1";

    @Mock AuthzAssignmentRuleRepository ruleRepository;
    @Mock AuthzAssignmentRepository assignmentRepository;
    @Mock AuthzUserRoleRepository userRoleRepository;
    @Mock AuthzService authzService;
    @Mock AuthzAuditService auditService;
    @InjectMocks AssignmentRuleService sut;

    private static AuthzAssignmentRuleEntity rule(AssignmentRuleType type, String key, String values, Integer days) {
        return AuthzAssignmentRuleEntity.builder().id(UUID.randomUUID()).agencyCode(AG).ruleType(type)
                .matchKey(key).matchValues(values).expiresDays(days).enabled(true)
                .createdBy("ops").createdAt(Instant.now()).build();
    }

    private static AuthzAssignmentEntity assignment(AssignmentSource source, UUID ruleId) {
        return AuthzAssignmentEntity.builder().id(UUID.randomUUID()).qimUserId(USER).agencyCode(AG)
                .status(AssignmentStatus.ACTIVE).source(source).grantedAt(Instant.now()).grantedBy("x").ruleId(ruleId).build();
    }

    // ── 생성 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ATTRIBUTE 규칙은 matchValues 가 필요하고, 콤마가 든 값은 거부")
    void create_attributeValidation() {
        assertThatThrownBy(() -> sut.create(new AssignmentRuleRequest(AG, "ATTRIBUTE", "authLevel", List.of(), null, null), "ops", null, null))
                .isInstanceOf(AuthzException.class)
                .extracting(e -> ((AuthzException) e).getErrorCode()).isEqualTo(AuthzErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> sut.create(new AssignmentRuleRequest(AG, "ATTRIBUTE", "authLevel", List.of("L2,L3"), null, null), "ops", null, null))
                .isInstanceOf(AuthzException.class);
        assertThatThrownBy(() -> sut.create(new AssignmentRuleRequest(AG, "WHATEVER", "k", List.of("v"), null, null), "ops", null, null))
                .isInstanceOf(AuthzException.class);
    }

    @Test
    @DisplayName("GROUP 규칙은 'agency:role' 형식이어야 하고, 생성 시 RULE_CREATED 감사")
    void create_group() {
        assertThatThrownBy(() -> sut.create(new AssignmentRuleRequest(AG, "group", "no-colon", null, null, null), "ops", null, null))
                .isInstanceOf(AuthzException.class);

        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuthzAssignmentRuleEntity r = sut.create(new AssignmentRuleRequest(AG, "group", "HR:STAFF", null, 30, "인사 직원"), "ops", "10.0.0.1", "cid");
        assertThat(r.getRuleType()).isEqualTo(AssignmentRuleType.GROUP);
        assertThat(r.getMatchValues()).isNull();
        assertThat(r.getExpiresDays()).isEqualTo(30);
        assertThat(r.isEnabled()).isTrue();
        verify(auditService).record(eq(AuditEvent.RULE_CREATED), isNull(), eq(AG), isNull(), eq("ops"), eq("10.0.0.1"), anyString(), eq("cid"));
    }

    // ── 평가 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("직접 할당이 있으면 규칙을 보지 않는다")
    void evaluate_directAssignmentWins() {
        when(assignmentRepository.findByQimUserIdAndAgencyCode(USER, AG)).thenReturn(Optional.of(assignment(AssignmentSource.CONSOLE, null)));
        when(authzService.effectiveRoleCodes(USER, AG)).thenReturn(List.of("VIEWER"));

        AssignmentRuleService.Access a = sut.evaluate(USER, AG, Map.of("authLevel", "L1"), null, "cid");

        assertThat(a.assigned()).isTrue();
        assertThat(a.assignmentSource()).isEqualTo("CONSOLE");
        assertThat(a.roles()).containsExactly("VIEWER");
        verify(ruleRepository, never()).findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(any());
        verify(authzService, never()).assign(any(), any(), any());
    }

    @Test
    @DisplayName("명시 회수된 직접 할당(REVOKED, source≠RULE)은 규칙이 되살리지 않는다; 만료된 한시 할당은 규칙 대상")
    void evaluate_explicitRevocationWins() {
        AuthzAssignmentEntity revoked = assignment(AssignmentSource.CONSOLE, null);
        revoked.setStatus(AssignmentStatus.REVOKED);
        when(assignmentRepository.findByQimUserIdAndAgencyCode(USER, AG)).thenReturn(Optional.of(revoked));
        when(authzService.effectiveRoleCodes(USER, AG)).thenReturn(List.of());

        assertThat(sut.evaluate(USER, AG, Map.of("authLevel", "L3"), null, null).assigned()).isFalse();
        verify(ruleRepository, never()).findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(any());

        AuthzAssignmentEntity expired = assignment(AssignmentSource.CONSOLE, null);
        expired.setStatus(AssignmentStatus.EXPIRED);
        when(assignmentRepository.findByQimUserIdAndAgencyCode(USER, AG)).thenReturn(Optional.of(expired));
        when(ruleRepository.findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(AG))
                .thenReturn(List.of(rule(AssignmentRuleType.ATTRIBUTE, "authLevel", "L3", null)));
        assertThat(sut.evaluate(USER, AG, Map.of("authLevel", "L3"), null, null).assigned()).isTrue();
        verify(authzService).assign(any(), any(), any());
    }

    @Test
    @DisplayName("미할당 + ATTRIBUTE 규칙 일치 → source=RULE 로 실체화(만료 = expiresDays 뒤, ruleId 추적)")
    void evaluate_attributeRuleMaterializes() {
        when(assignmentRepository.findByQimUserIdAndAgencyCode(USER, AG)).thenReturn(Optional.empty());
        AuthzAssignmentRuleEntity r = rule(AssignmentRuleType.ATTRIBUTE, "authLevel", "L2,L3", 7);
        when(ruleRepository.findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(AG)).thenReturn(List.of(r));
        when(authzService.effectiveRoleCodes(USER, AG)).thenReturn(List.of());

        AssignmentRuleService.Access a = sut.evaluate(USER, AG, Map.of("authLevel", "L2", "providerCode", "X"), "10.0.0.2", "cid");

        assertThat(a.assigned()).isTrue();
        assertThat(a.assignmentSource()).isEqualTo("RULE");
        ArgumentCaptor<AssignRequest> req = ArgumentCaptor.forClass(AssignRequest.class);
        verify(authzService).assign(req.capture(), eq("10.0.0.2"), eq("cid"));
        assertThat(req.getValue().source()).isEqualTo("RULE");
        assertThat(req.getValue().ruleId()).isEqualTo(r.getId().toString());
        assertThat(req.getValue().grantedBy()).isEqualTo("RULE:" + r.getId());
        assertThat(req.getValue().expiresAt()).isBetween(Instant.now().plus(7, ChronoUnit.DAYS).minusSeconds(60),
                Instant.now().plus(7, ChronoUnit.DAYS).plusSeconds(60));
    }

    @Test
    @DisplayName("미할당 + 규칙 불일치(값 없음·다른 값) → 미할당, 실체화 없음")
    void evaluate_noMatch() {
        when(assignmentRepository.findByQimUserIdAndAgencyCode(USER, AG)).thenReturn(Optional.empty());
        when(ruleRepository.findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(AG))
                .thenReturn(List.of(rule(AssignmentRuleType.ATTRIBUTE, "authLevel", "L3", null)));
        when(authzService.effectiveRoleCodes(USER, AG)).thenReturn(List.of());

        assertThat(sut.evaluate(USER, AG, Map.of("authLevel", "L1"), null, null).assigned()).isFalse();
        assertThat(sut.evaluate(USER, AG, null, null, null).assigned()).isFalse();
        verify(authzService, never()).assign(any(), any(), any());
    }

    @Test
    @DisplayName("'*' 는 값이 있으면 통과, 빈 값은 불일치")
    void evaluate_wildcard() {
        AuthzAssignmentRuleEntity r = rule(AssignmentRuleType.ATTRIBUTE, "providerCode", "*", null);
        assertThat(r.matchesAttribute("KAKAO")).isTrue();
        assertThat(r.matchesAttribute("")).isFalse();
        assertThat(r.matchesAttribute(null)).isFalse();
    }

    @Test
    @DisplayName("GROUP 규칙: 다른 기관의 유효 역할 보유 → 실체화, 만료된 역할 → 불일치")
    void evaluate_groupRule() {
        when(assignmentRepository.findByQimUserIdAndAgencyCode(USER, AG)).thenReturn(Optional.empty());
        AuthzAssignmentRuleEntity r = rule(AssignmentRuleType.GROUP, "HR:STAFF", null, null);
        when(ruleRepository.findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(AG)).thenReturn(List.of(r));
        when(authzService.effectiveRoleCodes(USER, AG)).thenReturn(List.of());
        AuthzUserRoleEntity role = AuthzUserRoleEntity.builder().id(UUID.randomUUID()).qimUserId(USER).agencyCode("HR").roleCode("STAFF")
                .status(AssignmentStatus.ACTIVE).grantedAt(Instant.now()).grantedBy("x").source(GrantSource.SCIM).build();
        when(userRoleRepository.findByQimUserIdAndAgencyCodeAndRoleCode(USER, "HR", "STAFF")).thenReturn(Optional.of(role));

        assertThat(sut.evaluate(USER, AG, Map.of(), null, null).assigned()).isTrue();
        verify(authzService).assign(any(), any(), any());

        role.setExpiresAt(Instant.now().minusSeconds(1));
        assertThat(sut.evaluate(USER, AG, Map.of(), null, null).assigned()).isFalse();
    }

    @Test
    @DisplayName("source=RULE 할당은 재평가: 규칙이 꺼졌으면 회수(UNASSIGN) 후 다른 규칙 시도, 없으면 미할당")
    void evaluate_ruleAssignmentReevaluated() {
        UUID oldRuleId = UUID.randomUUID();
        when(assignmentRepository.findByQimUserIdAndAgencyCode(USER, AG)).thenReturn(Optional.of(assignment(AssignmentSource.RULE, oldRuleId)));
        AuthzAssignmentRuleEntity disabled = rule(AssignmentRuleType.ATTRIBUTE, "authLevel", "L1", null);
        disabled.setId(oldRuleId);
        disabled.setEnabled(false);
        when(ruleRepository.findById(oldRuleId)).thenReturn(Optional.of(disabled));
        when(ruleRepository.findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(AG)).thenReturn(List.of());
        when(authzService.effectiveRoleCodes(USER, AG)).thenReturn(List.of());

        AssignmentRuleService.Access a = sut.evaluate(USER, AG, Map.of("authLevel", "L1"), "ip", "cid");

        assertThat(a.assigned()).isFalse();
        verify(authzService).unassign(eq(USER), eq(AG), eq("RULE:" + oldRuleId), eq("ip"), anyString(), eq("cid"));
    }

    @Test
    @DisplayName("source=RULE 할당이 여전히 규칙에 맞으면 그대로 통과(회수·재실체화 없음)")
    void evaluate_ruleAssignmentStillMatches() {
        AuthzAssignmentRuleEntity r = rule(AssignmentRuleType.ATTRIBUTE, "authLevel", "L1", null);
        when(assignmentRepository.findByQimUserIdAndAgencyCode(USER, AG)).thenReturn(Optional.of(assignment(AssignmentSource.RULE, r.getId())));
        when(ruleRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(authzService.effectiveRoleCodes(USER, AG)).thenReturn(List.of());

        AssignmentRuleService.Access a = sut.evaluate(USER, AG, Map.of("authLevel", "L1"), null, null);

        assertThat(a.assigned()).isTrue();
        assertThat(a.assignmentSource()).isEqualTo("RULE");
        verify(authzService, never()).unassign(any(), any(), any(), any(), any(), any());
        verify(authzService, never()).assign(any(), any(), any());
    }

    // ── 비활성화 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("비활성화: 규칙 끄고 RULE_DISABLED 감사, 그 규칙이 만든 ACTIVE 할당을 모두 회수")
    void disable_revokesMaterialized() {
        AuthzAssignmentRuleEntity r = rule(AssignmentRuleType.GROUP, "HR:STAFF", null, null);
        when(ruleRepository.findById(r.getId())).thenReturn(Optional.of(r));
        AuthzAssignmentEntity a1 = assignment(AssignmentSource.RULE, r.getId());
        AuthzAssignmentEntity a2 = assignment(AssignmentSource.RULE, r.getId());
        a2.setQimUserId("user-2");
        when(assignmentRepository.findByRuleIdAndStatus(r.getId(), AssignmentStatus.ACTIVE)).thenReturn(List.of(a1, a2));

        int revoked = sut.disable(r.getId(), "ops", "ip", "cid");

        assertThat(revoked).isEqualTo(2);
        assertThat(r.isEnabled()).isFalse();
        assertThat(r.getDisabledBy()).isEqualTo("ops");
        verify(auditService).record(eq(AuditEvent.RULE_DISABLED), isNull(), eq(AG), isNull(), eq("ops"), eq("ip"), anyString(), eq("cid"));
        verify(authzService).unassign(eq(USER), eq(AG), eq("ops"), eq("ip"), anyString(), eq("cid"));
        verify(authzService).unassign(eq("user-2"), eq(AG), eq("ops"), eq("ip"), anyString(), eq("cid"));
    }

    @Test
    @DisplayName("비활성화: 없는 규칙은 404 RULE_NOT_FOUND, 이미 꺼진 규칙은 감사 없이 회수만 재시도(멱등)")
    void disable_notFoundAndIdempotent() {
        UUID missing = UUID.randomUUID();
        when(ruleRepository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sut.disable(missing, "ops", null, null))
                .isInstanceOf(AuthzException.class)
                .extracting(e -> ((AuthzException) e).getErrorCode()).isEqualTo(AuthzErrorCode.RULE_NOT_FOUND);

        AuthzAssignmentRuleEntity r = rule(AssignmentRuleType.GROUP, "HR:STAFF", null, null);
        r.setEnabled(false);
        when(ruleRepository.findById(r.getId())).thenReturn(Optional.of(r));
        when(assignmentRepository.findByRuleIdAndStatus(r.getId(), AssignmentStatus.ACTIVE)).thenReturn(List.of());
        assertThat(sut.disable(r.getId(), "ops", null, null)).isZero();
        verify(auditService, never()).record(eq(AuditEvent.RULE_DISABLED), any(), any(), any(), any(), any(), any(), any());
    }
}
