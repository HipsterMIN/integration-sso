package io.github.hipstermin.idem.hub.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuthorizationEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("1.1 AuthzEventConsumer — 할당·역할 변경을 기관 웹훅 ASSIGNMENT_CHANGED 로")
class AuthzEventConsumerTest {

    @Mock IdempotentEventStore idempotent;
    @Mock ServiceProfileService profiles;
    @Mock PolicyEngine policyEngine;
    @Mock WebhookDispatcherService webhook;
    @Mock AuditLogPublisher audit;
    @InjectMocks AuthzEventConsumer sut;

    private static final ServiceProfile PROFILE = ServiceProfile.builder().build();

    @Test
    @DisplayName("해제 이벤트 → 기관별 식별자를 해석해 웹훅 적재 + 감사 + 멱등 기록 (qimUserId 는 페이로드에 없다)")
    void unassigned_enqueuesWebhookWithAgencySubjectId() {
        given(profiles.find("AG1")).willReturn(Optional.of(PROFILE));
        given(policyEngine.resolveAgencySubjectId(PROFILE, "u1", "AG1", "cid-1")).willReturn("pw_abc");
        given(webhook.enqueueForAssignmentChanged(any(), any(), any(), any(), any(), any(), any())).willReturn(1);
        AuthorizationEvent ev = AuthorizationEvent.unassigned("u1", "AG1", "admin", "탈퇴", "cid-1");

        assertThat(sut.handle(ev)).isEqualTo("OK");

        verify(webhook).enqueueForAssignmentChanged(eq("AG1"), eq("pw_abc"), eq("UNASSIGNED"), isNull(), any(), eq(ev.getEventId()), eq("cid-1"));
        ArgumentCaptor<AuditLogPublisher.AuditEntry> a = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(a.capture());
        assertThat(a.getValue().eventAction()).isEqualTo("ASSIGNMENT_CHANGED");
        assertThat(a.getValue().metadata()).containsEntry("accessLoss", true).containsEntry("change", "UNASSIGNED");
        verify(idempotent).markProcessed(ev.getEventId(), AuthzEventConsumer.CONSUMER_GROUP, AuthorizationEvent.TYPE_UNASSIGNED, "OK");
    }

    @Test
    @DisplayName("역할 부여 이벤트는 change=ROLE_GRANTED, roleCode 포함, accessLoss=false")
    void granted_mapsChange() {
        given(profiles.find("AG1")).willReturn(Optional.of(PROFILE));
        given(policyEngine.resolveAgencySubjectId(any(), any(), any(), any())).willReturn("pw_1");
        AuthorizationEvent ev = AuthorizationEvent.granted("u1", "AG1", "MANAGER", "admin", null, "API", "r", "cid");
        sut.handle(ev);
        verify(webhook).enqueueForAssignmentChanged(eq("AG1"), eq("pw_1"), eq("ROLE_GRANTED"), eq("MANAGER"), any(), any(), any());
        ArgumentCaptor<AuditLogPublisher.AuditEntry> a = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(a.capture());
        assertThat(a.getValue().metadata()).containsEntry("accessLoss", false);
    }

    @Test
    @DisplayName("이미 처리한 이벤트는 DUP — 아무것도 하지 않는다")
    void duplicateSkipped() {
        given(idempotent.isAlreadyProcessed(anyString(), anyString())).willReturn(true);
        assertThat(sut.handle(AuthorizationEvent.assigned("u1", "AG1", "a", null, "API", "r", "c"))).isEqualTo("DUP");
        verify(webhook, never()).enqueueForAssignmentChanged(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("프로파일이 없는 기관은 통보할 곳이 없다 → SKIPPED 로 멱등 기록")
    void noProfileSkipped() {
        given(profiles.find("GONE")).willReturn(Optional.empty());
        AuthorizationEvent ev = AuthorizationEvent.assignmentExpired("u1", "GONE", "만료");
        assertThat(sut.handle(ev)).isEqualTo("SKIPPED");
        verify(idempotent).markProcessed(ev.getEventId(), AuthzEventConsumer.CONSUMER_GROUP, AuthorizationEvent.TYPE_ASSIGNMENT_EXPIRED, "SKIPPED");
        verify(webhook, never()).enqueueForAssignmentChanged(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("기관별 식별자 해석 실패(registry 장애)는 예외로 올린다 — 폴러가 재시도, qimUserId 로 대체하지 않는다")
    void subjectResolutionFailurePropagates() {
        given(profiles.find("AG1")).willReturn(Optional.of(PROFILE));
        given(policyEngine.resolveAgencySubjectId(any(), any(), any(), any()))
                .willThrow(new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, "cid"));
        assertThatThrownBy(() -> sut.handle(AuthorizationEvent.revoked("u1", "AG1", "R", "a", "r", "cid")))
                .isInstanceOf(PlatformException.class);
        verify(webhook, never()).enqueueForAssignmentChanged(any(), any(), any(), any(), any(), any(), any());
        verify(idempotent, never()).markProcessed(any(), any(), any(), any());
    }
}
