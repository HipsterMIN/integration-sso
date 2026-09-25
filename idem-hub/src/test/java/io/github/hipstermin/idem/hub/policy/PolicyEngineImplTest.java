package io.github.hipstermin.idem.hub.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.identity.CoreSubjectSchemes;
import io.github.hipstermin.idem.hub.identity.HandoffAttributeAssembler;
import io.github.hipstermin.idem.hub.identity.SubjectIdentifierResolver;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.ServiceAccess;
import io.github.hipstermin.idem.hub.infrastructure.UserStatusCache;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Sprint α-3 / F4.6 — PolicyEngineImpl Q-IM 장애 vs 미매핑 구분 회귀 테스트
 *
 * <p><b>위협 모델</b>:
 * <ul>
 *   <li>이전: {@code catch (Exception e) { return null; }} → Q-IM 5xx/timeout이 영구 미매핑과 동일 처리됨</li>
 *   <li>결과: Q-IM 장애 시 모든 사용자가 GUEST로 응답 → 데이터 무결성 위험</li>
 *   <li>수정: {@link PlatformException}({@link PlatformErrorCode#IDEM_HUB_REGISTRY_UNREACHABLE}) 전파 → 503 응답으로 재시도 유도</li>
 * </ul>
 *
 * <p><b>검증 시나리오</b>:
 * <ul>
 *   <li>{@code getDi()} 가 정상 DI 반환 → APPROVED + agencySubjectId=DI</li>
 *   <li>{@code getDi()} 가 null 반환 (영구 미매핑) → GUEST + agencySubjectId=null</li>
 *   <li>{@code getDi()} 가 PlatformException(IDEM_HUB_REGISTRY_UNREACHABLE) throw → 그대로 전파</li>
 *   <li>{@code getDi()} 가 예상 외 RuntimeException throw → IDEM_HUB_REGISTRY_UNREACHABLE 로 변환 후 전파</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("[F4.6] PolicyEngineImpl Q-IM 장애 vs 미매핑 구분")
class PolicyEngineImplTest {

    @Mock UserStatusCache      userStatusCache;
    @Mock QimClient            qimClient;
    @Mock AgencyMetaRepository agencyMetaRepository;
    @Mock ServiceProfileService serviceProfileService;
    @Mock QAuthzClient          qAuthzClient;

    PolicyEngineImpl sut;

    private static final String QIM_USER_ID    = "qim-user-001";
    private static final String AGENCY_CODE    = "AGENCY_B";
    private static final String CORRELATION_ID = "corr-policy-001";
    private static final String TICKET_ID      = "ticket-001";

    @BeforeEach
    void setUp() {
        // S4: 기본 스킴 PAIRWISE_HMAC 은 registry DI(getDi) 로 해석된다 — 종전 테스트 계약 유지
        SubjectIdentifierResolver resolver = new SubjectIdentifierResolver(
                java.util.List.of(new CoreSubjectSchemes().pairwiseHmacSubjectScheme(qimClient)));
        sut = new PolicyEngineImpl(userStatusCache, qimClient, agencyMetaRepository, serviceProfileService,
                resolver, new HandoffAttributeAssembler(qimClient, resolver), qAuthzClient, java.util.List.of());
        ReflectionTestUtils.setField(sut, "defaultPolicyVersion", "1.0");
        // 기본: 기관 프로파일 없음(S4: 스킴 PAIRWISE_HMAC · 속성 선언 없음 = 빈 attributes), userStatusCache 없음
        // (serviceProfileService.find 는 Mockito 기본값 Optional.empty)
        given(userStatusCache.get(anyString())).willReturn(Optional.empty());
        // D2: 캐시 미스면 정본(Q-IM) 조회 — 실패 시 거부. 기본 스텁은 ACTIVE
        lenient().when(qimClient.getUserStatus(anyString(), anyString())).thenReturn(UserStatus.ACTIVE);
        // S8-b: 기본은 authz 활성·미할당·역할 없음 (할당 정책이 없는 프로파일은 이 값을 상태 판정에 쓰지 않는다)
        lenient().when(qAuthzClient.getServiceAccess(anyString(), anyString(), any()))
                .thenReturn(new ServiceAccess(true, false, null, java.util.List.of()));
    }

    private HandoffTicket buildTicket() {
        Instant now = Instant.now();
        return HandoffTicket.builder()
                .ticketId(TICKET_ID)
                .correlationId(CORRELATION_ID)
                .agencyCode(AGENCY_CODE)
                .qimUserId(QIM_USER_ID)
                .authResultId("auth-001")
                .authLevel(AuthResult.AuthLevel.L2)
                .state(HandoffTicket.TicketState.ISSUED)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.6] APPROVED — Q-IM이 DI 반환
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.6] Q-IM DI 정상 반환 → APPROVED")
    class DiResolutionSuccess {

        @Test
        @DisplayName("[F4.6] DI 정상 반환 시 HandoffState=APPROVED, agencySubjectId=DI")
        void buildHandoffPayload_returnsApproved_whenDiPresent() {
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .willReturn("DI-AGENCY-B-12345");

            HandoffPayload payload = sut.buildHandoffPayload(buildTicket(), CORRELATION_ID);

            assertThat(payload.getState()).isEqualTo(HandoffPayload.HandoffState.APPROVED);
            assertThat(payload.getSubject().getAgencySubjectId()).isEqualTo("DI-AGENCY-B-12345");
            assertThat(payload.getSubject().getQimUserId()).isEqualTo(QIM_USER_ID);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.6] GUEST — Q-IM이 명시적으로 "DI 없음" 응답 (영구 미매핑)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.6] Q-IM DI null 반환 (영구 미매핑) → GUEST (정당)")
    class DiPermanentlyNotMappedReturnsGuest {

        @Test
        @DisplayName("[F4.6] getDi()가 null 반환 → HandoffState=GUEST, agencySubjectId=null")
        void buildHandoffPayload_returnsGuest_whenDiNull() {
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .willReturn(null);

            HandoffPayload payload = sut.buildHandoffPayload(buildTicket(), CORRELATION_ID);

            assertThat(payload.getState()).isEqualTo(HandoffPayload.HandoffState.GUEST);
            assertThat(payload.getSubject().getAgencySubjectId()).isNull();
            assertThat(payload.getSubject().getQimUserId()).isEqualTo(QIM_USER_ID);
        }

        @Test
        @DisplayName("[F4.6] getDi()가 blank(\"   \") 반환 → GUEST")
        void buildHandoffPayload_returnsGuest_whenDiBlank() {
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .willReturn("   ");

            HandoffPayload payload = sut.buildHandoffPayload(buildTicket(), CORRELATION_ID);

            assertThat(payload.getState()).isEqualTo(HandoffPayload.HandoffState.GUEST);
            assertThat(payload.getSubject().getAgencySubjectId()).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.6] 핵심 가드 — Q-IM 일시 장애는 GUEST가 아니라 503 전파
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.6] Q-IM 일시 장애 → PlatformException(IDO_QIM_UNREACHABLE) 전파 (GUEST swallow 금지)")
    class QimTransientFailurePropagates {

        @Test
        @DisplayName("[F4.6] getDi()가 IDO_QIM_UNREACHABLE throw → 그대로 전파 (GUEST로 묻히지 않음)")
        void buildHandoffPayload_propagates_whenQimUnreachable() {
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .willThrow(new PlatformException(
                            PlatformErrorCode.IDO_QIM_UNREACHABLE, CORRELATION_ID));

            assertThatThrownBy(() ->
                    sut.buildHandoffPayload(buildTicket(), CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> {
                        PlatformException pe = (PlatformException) ex;
                        assertThat(pe.getErrorCode())
                                .as("Q-IM 장애는 IDO_QIM_UNREACHABLE(503)로 전파되어야 GUEST 오인 차단")
                                .isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE);
                    });
        }

        @Test
        @DisplayName("[F4.6] getDi()가 다른 PlatformException(e.g. IDO_POLICY_REJECTED) throw → 동일 코드로 그대로 전파")
        void buildHandoffPayload_propagatesOtherPlatformException() {
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .willThrow(new PlatformException(
                            PlatformErrorCode.IDO_POLICY_REJECTED, CORRELATION_ID));

            assertThatThrownBy(() ->
                    sut.buildHandoffPayload(buildTicket(), CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> {
                        PlatformException pe = (PlatformException) ex;
                        assertThat(pe.getErrorCode()).isEqualTo(PlatformErrorCode.IDO_POLICY_REJECTED);
                    });
        }

        @Test
        @DisplayName("[F4.6] getDi()가 예상 외 RuntimeException throw → IDO_QIM_UNREACHABLE로 변환 후 전파")
        void buildHandoffPayload_convertsUnexpectedException_toQimUnreachable() {
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .willThrow(new IllegalStateException("unexpected NPE-like failure"));

            assertThatThrownBy(() ->
                    sut.buildHandoffPayload(buildTicket(), CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> {
                        PlatformException pe = (PlatformException) ex;
                        assertThat(pe.getErrorCode())
                                .as("예상 외 예외도 안전 우선 거부 — GUEST로 새지 않음")
                                .isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE);
                    });
        }
    }

    // ── S8-b 할당 정책 ──────────────────────────────────────────────────────
    @Nested
    @DisplayName("[D3] policy.session → 페이로드 sessionPolicy")
    class SessionPolicyInPayload {

        private ServiceProfile profileWithSession(ServiceProfile.Session session) {
            return ServiceProfile.builder().schemaVersion(1)
                    .service(new ServiceProfile.Service(AGENCY_CODE, "기관", ServiceProfile.ServiceStatus.ACTIVE))
                    .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.DIRECT).build())
                    .policy(ServiceProfile.Policy.builder().session(session).build())
                    .build();
        }

        @Test
        @DisplayName("프로파일 세션 상한이 그대로 실린다 — 종전에는 매핑만 되고 어디에도 나가지 않았다")
        void sessionPolicyCarried() {
            given(serviceProfileService.find(AGENCY_CODE)).willReturn(Optional.of(profileWithSession(new ServiceProfile.Session(20, 240, 1))));
            given(qimClient.getDi(anyString(), anyString(), anyString())).willReturn("di-1");
            HandoffPayload payload = sut.buildHandoffPayload(buildTicket(), CORRELATION_ID);
            assertThat(payload.getSessionPolicy()).isEqualTo(new HandoffPayload.SessionPolicy(20, 240, 1));
        }

        @Test
        @DisplayName("세션 블록이 없거나 전부 비면 null")
        void absentOrEmptyIsNull() {
            given(qimClient.getDi(anyString(), anyString(), anyString())).willReturn("di-1");
            given(serviceProfileService.find(AGENCY_CODE)).willReturn(Optional.of(profileWithSession(null)));
            assertThat(sut.buildHandoffPayload(buildTicket(), CORRELATION_ID).getSessionPolicy()).isNull();
            given(serviceProfileService.find(AGENCY_CODE)).willReturn(Optional.of(profileWithSession(new ServiceProfile.Session(null, null, null))));
            assertThat(sut.buildHandoffPayload(buildTicket(), CORRELATION_ID).getSessionPolicy()).isNull();
            assertThat(PolicyEngineImpl.sessionPolicyOf(null)).isNull();
        }
    }

    @Nested
    @DisplayName("[S8-b] policy.assignment.required=true — 상태는 할당이 정한다")
    class AssignmentPolicy {

        private ServiceProfile requiredProfile(boolean selfSignup) {
            return ServiceProfile.builder().schemaVersion(1)
                    .service(new ServiceProfile.Service(AGENCY_CODE, "기관", ServiceProfile.ServiceStatus.ACTIVE))
                    .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.DIRECT).build())
                    .policy(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1)
                            .assignment(new ServiceProfile.Assignment(true, selfSignup)).build())
                    .build();
        }

        @Test
        @DisplayName("할당됨 → APPROVED, roles 와 subject.assigned=true 가 페이로드에 실린다")
        void assigned_approvedWithRoles() {
            given(serviceProfileService.find(AGENCY_CODE)).willReturn(Optional.of(requiredProfile(false)));
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID)).willReturn("DI-ASSIGNED");
            given(qAuthzClient.getServiceAccess(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .willReturn(new ServiceAccess(true, true, "CONSOLE", java.util.List.of("MANAGER", "REVIEWER")));

            HandoffPayload payload = sut.buildHandoffPayload(buildTicket(), CORRELATION_ID);

            assertThat(payload.getState()).isEqualTo(HandoffPayload.HandoffState.APPROVED);
            assertThat(payload.getRoles()).containsExactly("MANAGER", "REVIEWER");
            assertThat(payload.getSubject().getAssigned()).isTrue();
            assertThat(payload.getSubject().getAgencySubjectId()).isEqualTo("DI-ASSIGNED");
        }

        @Test
        @DisplayName("미할당(selfSignup 통과) → GUEST 지만 주체 식별자는 해석되면 실린다 (기관이 가입 후 연결할 수 있게)")
        void unassigned_guestKeepsSubject() {
            given(serviceProfileService.find(AGENCY_CODE)).willReturn(Optional.of(requiredProfile(true)));
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID)).willReturn("DI-NEW");
            given(qAuthzClient.getServiceAccess(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .willReturn(new ServiceAccess(true, false, null, java.util.List.of()));

            HandoffPayload payload = sut.buildHandoffPayload(buildTicket(), CORRELATION_ID);

            assertThat(payload.getState()).isEqualTo(HandoffPayload.HandoffState.GUEST);
            assertThat(payload.getSubject().getAssigned()).isFalse();
            assertThat(payload.getSubject().getAgencySubjectId()).isEqualTo("DI-NEW");
            assertThat(payload.getRoles()).isEmpty();
        }

        @Test
        @DisplayName("authz 비활성 설치(idem.hub.authz.enabled=false)인데 할당 필수 → IDO_AUTHZ_UNAVAILABLE (평가 불가 = 거부)")
        void authzDisabled_denied() {
            given(serviceProfileService.find(AGENCY_CODE)).willReturn(Optional.of(requiredProfile(false)));
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID)).willReturn("DI-X");
            given(qAuthzClient.getServiceAccess(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID)).willReturn(ServiceAccess.disabled());

            assertThatThrownBy(() -> sut.buildHandoffPayload(buildTicket(), CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE);
        }

        @Test
        @DisplayName("할당 정책 없는 프로파일: 종전 의미 유지 — DI 없으면 GUEST, subject.assigned 는 authz 값 그대로")
        void noAssignmentPolicy_legacySemantics() {
            given(serviceProfileService.find(AGENCY_CODE)).willReturn(Optional.empty());
            given(qimClient.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID)).willReturn(null);

            HandoffPayload payload = sut.buildHandoffPayload(buildTicket(), CORRELATION_ID);

            assertThat(payload.getState()).isEqualTo(HandoffPayload.HandoffState.GUEST);
            assertThat(payload.getSubject().getAgencySubjectId()).isNull();
            assertThat(payload.getSubject().getAssigned()).isFalse();
        }
    }
}
