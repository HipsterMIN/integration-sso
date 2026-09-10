package io.github.hipstermin.idem.hub.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.identity.CoreSubjectSchemes;
import io.github.hipstermin.idem.hub.identity.HandoffAttributeAssembler;
import io.github.hipstermin.idem.hub.identity.SubjectIdentifierResolver;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.UserStatusCache;
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
 *   <li>수정: {@link PlatformException}({@link PlatformErrorCode#IDO_QIM_UNREACHABLE}) 전파 → 503 응답으로 재시도 유도</li>
 * </ul>
 *
 * <p><b>검증 시나리오</b>:
 * <ul>
 *   <li>{@code getDi()} 가 정상 DI 반환 → APPROVED + agencySubjectId=DI</li>
 *   <li>{@code getDi()} 가 null 반환 (영구 미매핑) → GUEST + agencySubjectId=null</li>
 *   <li>{@code getDi()} 가 PlatformException(IDO_QIM_UNREACHABLE) throw → 그대로 전파</li>
 *   <li>{@code getDi()} 가 예상 외 RuntimeException throw → IDO_QIM_UNREACHABLE 로 변환 후 전파</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("[F4.6] PolicyEngineImpl Q-IM 장애 vs 미매핑 구분")
class PolicyEngineImplTest {

    @Mock UserStatusCache      userStatusCache;
    @Mock QimClient            qimClient;
    @Mock AgencyMetaRepository agencyMetaRepository;
    @Mock ServiceProfileService serviceProfileService;

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
                resolver, new HandoffAttributeAssembler(qimClient, resolver), java.util.List.of());
        ReflectionTestUtils.setField(sut, "defaultPolicyVersion", "1.0");
        // 기본: 기관 프로파일 없음(S4: 스킴 PAIRWISE_HMAC · 속성 선언 없음 = 빈 attributes), userStatusCache 없음
        // (serviceProfileService.find 는 Mockito 기본값 Optional.empty)
        given(userStatusCache.get(anyString())).willReturn(Optional.empty());
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
}
