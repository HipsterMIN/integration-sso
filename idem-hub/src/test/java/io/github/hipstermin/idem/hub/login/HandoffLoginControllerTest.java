package io.github.hipstermin.idem.hub.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.common.spi.identity.IdentityProviderRegistry;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionCookie;
import io.github.hipstermin.idem.hub.fe.session.FeSessionPolicyEnforcer;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.handoff.HandoffIssueCommand;
import io.github.hipstermin.idem.hub.handoff.HandoffService;
import io.github.hipstermin.idem.hub.handoff.validate.CallbackUrlValidator;
import io.github.hipstermin.idem.hub.identity.spi.IdentityLoginService;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/** 1.1 코어 로그인 프런트 — 진입 검증·제공자 선택·SPI 완료·발급·콜백. Redis 저장소는 메모리 스텁. */
@ExtendWith(MockitoExtension.class)
class HandoffLoginControllerTest {

    private static final String AG = "AG1";
    private static final String CB = "https://agency.example.org/cb";

    @Mock AgencyMetaRepository agencyMetaRepository;
    @Mock IdentityLoginService identityLoginService;
    @Mock FeSessionService feSessionService;
    @Mock FeSessionPolicyEnforcer feSessionPolicyEnforcer;
    @Mock HandoffService handoffService;

    /** 메모리 저장소 — Redis 없이 동작 */
    private final Map<String, HandoffLoginRequest> mem = new HashMap<>();
    private HandoffLoginRequestStore store;
    private IdentityProviderRegistry registry;
    private IdentityVerificationProvider mockProvider;
    private HandoffLoginController sut;

    @BeforeEach
    void setUp() {
        store = mock(HandoffLoginRequestStore.class, org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        org.mockito.Mockito.doAnswer(inv -> { HandoffLoginRequest r = inv.getArgument(0); mem.put(r.requestId(), r); return null; }).when(store).save(any());
        org.mockito.Mockito.lenient().when(store.find(anyString())).thenAnswer(inv -> Optional.ofNullable(mem.get(inv.<String>getArgument(0))));
        org.mockito.Mockito.lenient().doAnswer(inv -> mem.remove(inv.<String>getArgument(0))).when(store).delete(anyString());

        mockProvider = mock(IdentityVerificationProvider.class, org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        org.mockito.Mockito.lenient().when(mockProvider.code()).thenReturn("MOCK");
        org.mockito.Mockito.lenient().when(mockProvider.level()).thenReturn(AuthResult.AuthLevel.L1);
        registry = new IdentityProviderRegistry(List.of(mockProvider));

        sut = new HandoffLoginController(store, agencyMetaRepository, new CallbackUrlValidator(), registry,
                identityLoginService, feSessionService, feSessionPolicyEnforcer, handoffService,
                "http://hub.test/", "");
    }

    private void agency(IntegrationType type, boolean active) {
        AgencyMeta a = AgencyMeta.builder().agencyCode(AG).officialName("테스트 기관").active(active)
                .integrationType(type).callbackWhitelist(List.of(CB)).build();
        given(agencyMetaRepository.findByCode(AG)).willReturn(Optional.of(a));
    }

    private static FeSession fe(String level) {
        return FeSession.builder().feSessionId("fe-1").qimUserId("u1").authResultId("ar-1").authLevel(level)
                .createdAt(Instant.now()).lastActivityAt(Instant.now()).build();
    }

    private static String location(ResponseEntity<?> r) {
        return r.getHeaders().getFirst(HttpHeaders.LOCATION);
    }

    // ── 진입 검증 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("없거나 비활성 기관 → 404 오류 화면, 콜백으로 보내지 않는다")
    void entry_unknownAgency() {
        given(agencyMetaRepository.findByCode(AG)).willReturn(Optional.empty());
        ResponseEntity<String> r = sut.entry(AG, CB, null, null, null, new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).contains("E-AGENCY-307");
        assertThat(location(r)).isNull();
    }

    @Test
    @DisplayName("OIDC_RP 기관 → 400 E-IDO-121 (표준 OIDC 로 안내)")
    void entry_oidcRpAgency() {
        agency(IntegrationType.OIDC_RP, true);
        ResponseEntity<String> r = sut.entry(AG, CB, null, null, null, new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).contains("E-IDO-121");
    }

    @Test
    @DisplayName("화이트리스트 밖 콜백 → 403 오류 화면 (open redirect 방지), 진입 상태 저장 없음")
    void entry_callbackNotWhitelisted() {
        agency(IntegrationType.DIRECT, true);
        ResponseEntity<String> r = sut.entry(AG, "https://evil.example.org/cb", null, null, null, new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(r.getBody()).contains("E-AGENCY-304");
        assertThat(mem).isEmpty();
    }

    @Test
    @DisplayName("HTML 화면은 값을 이스케이프한다")
    void pagesEscape() {
        assertThat(LoginPages.error("<x>", "a&b\"c'", null)).doesNotContain("<x>").contains("&lt;x&gt;").contains("a&amp;b&quot;c&#39;");
    }

    // ── 제공자 시작 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("제공자가 하나면 자동 시작: initiate(returnUrl=…/continue?req=) 후 제공자 화면으로 302, 상태에 provider·txId 저장")
    void entry_singleProviderAutoStart() {
        agency(IntegrationType.DIRECT, true);
        given(identityLoginService.initiate(eq("MOCK"), anyString(), any(), anyString()))
                .willAnswer(inv -> new VerificationStart("MOCK", "tx-1", inv.<String>getArgument(1) + "&mockTxId=tx-1", Map.of()));

        ResponseEntity<String> r = sut.entry(AG, CB, null, "L2", "st-1", new MockHttpServletRequest());

        assertThat(r.getStatusCode().value()).isEqualTo(302);
        assertThat(location(r)).startsWith("http://hub.test/api/v1/handoff/login/continue?req=").contains("&mockTxId=tx-1");
        HandoffLoginRequest saved = mem.values().iterator().next();
        assertThat(saved.providerCode()).isEqualTo("MOCK");
        assertThat(saved.txId()).isEqualTo("tx-1");
        assertThat(saved.requestedLevel()).isEqualTo("L2");
        assertThat(saved.state()).isEqualTo("st-1");
        assertThat(saved.callbackUrl()).isEqualTo(CB);
    }

    @Test
    @DisplayName("제공자가 여럿(브로커 설정 포함)이면 선택 화면 200 — 링크는 /start?req=&provider=")
    void entry_chooser() {
        sut = new HandoffLoginController(store, agencyMetaRepository, new CallbackUrlValidator(), registry,
                identityLoginService, feSessionService, feSessionPolicyEnforcer, handoffService, "http://hub.test", "keycloak");
        agency(IntegrationType.DIRECT, true);
        ResponseEntity<String> r = sut.entry(AG, CB, null, null, null, new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("테스트 기관").contains("provider=MOCK").contains("provider=broker:keycloak");
        verify(identityLoginService, never()).initiate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("broker:<name> 시작 → /api/v1/broker/<name>/authorize?returnUrl=…/continue?req=… 로 302 (허용 목록 밖은 400)")
    void start_broker() {
        sut = new HandoffLoginController(store, agencyMetaRepository, new CallbackUrlValidator(), registry,
                identityLoginService, feSessionService, feSessionPolicyEnforcer, handoffService, "http://hub.test", "keycloak");
        agency(IntegrationType.DIRECT, true);
        sut.entry(AG, CB, null, null, null, new MockHttpServletRequest());
        String req = mem.keySet().iterator().next();

        ResponseEntity<String> r = sut.start(req, "broker:keycloak");
        assertThat(r.getStatusCode().value()).isEqualTo(302);
        assertThat(location(r)).startsWith("http://hub.test/api/v1/broker/keycloak/authorize?returnUrl=")
                .contains("continue%3Freq%3D" + req).contains("requestedLevel=L1");
        assertThat(mem.get(req).providerCode()).isEqualTo("broker:keycloak");

        ResponseEntity<String> bad = sut.start(req, "broker:other");
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("만료·미존재 요청 → 410 오류 화면")
    void start_expired() {
        assertThat(sut.start("nope", "MOCK").getStatusCode().value()).isEqualTo(410);
        assertThat(sut.resume("nope", Map.of(), new MockHttpServletRequest(), new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(410);
    }

    // ── 복귀 → 발급 → 콜백 ────────────────────────────────────────────────────

    private String startedRequest() {
        agency(IntegrationType.DIRECT, true);
        given(identityLoginService.initiate(eq("MOCK"), anyString(), any(), anyString()))
                .willReturn(new VerificationStart("MOCK", "tx-1", "http://provider/x", Map.of()));
        sut.entry(AG, CB, null, "L1", "st-9", new MockHttpServletRequest());
        return mem.keySet().iterator().next();
    }

    @Test
    @DisplayName("SPI 복귀: complete(콜백 파라미터) → registry 확정 → FE 세션 + 쿠키 → 발급 → 302 callback?ticketId&state, 상태는 1회 소비")
    void resume_spiCompletesAndIssues() {
        String req = startedRequest();
        VerifiedIdentity identity = new VerifiedIdentity("MOCK", "tx-1", "mock:abc", "홍길동", "19900101", "1", "010",
                "MOCK", AuthResult.AuthLevel.L1, Instant.now(), Map.of(), SubjectScheme.EXTERNAL_SUB);
        given(identityLoginService.complete(eq("MOCK"), eq("tx-1"), any(), anyString()))
                .willReturn(new IdentityLoginService.Completed(identity, "u1", true));
        given(feSessionService.create("u1", "tx-1", "L1", null)).willReturn(fe("L1"));
        given(handoffService.issue(any())).willReturn(HandoffTicket.builder().ticketId("t-123").agencyCode(AG).qimUserId("u1").build());
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<String> r = sut.resume(req, Map.of("req", req, "mockTxId", "tx-1"), new MockHttpServletRequest(), response);

        assertThat(r.getStatusCode().value()).isEqualTo(302);
        assertThat(location(r)).isEqualTo(CB + "?ticketId=t-123&state=st-9");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith(FeSessionCookie.NAME + "=fe-1").contains("HttpOnly");
        ArgumentCaptor<HandoffIssueCommand> cmd = ArgumentCaptor.forClass(HandoffIssueCommand.class);
        verify(handoffService).issue(cmd.capture());
        assertThat(cmd.getValue().getAgencyCode()).isEqualTo(AG);
        assertThat(cmd.getValue().getQimUserId()).isEqualTo("u1");
        assertThat(cmd.getValue().getProviderCode()).isEqualTo("MOCK");
        assertThat(cmd.getValue().getRedirectUri()).isEqualTo(CB);
        verify(feSessionPolicyEnforcer).applyForService(eq("fe-1"), eq(AG), anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(identityLoginService).complete(eq("MOCK"), eq("tx-1"), params.capture(), anyString());
        assertThat(params.getValue()).containsEntry("mockTxId", "tx-1").doesNotContainKey("req");
        assertThat(mem).isEmpty();   // 1회 소비
    }

    @Test
    @DisplayName("이미 FE 세션 쿠키가 있으면 진입에서 바로 발급 → 302 callback (제공자 호출 없음)")
    void entry_existingSessionIssuesDirectly() {
        agency(IntegrationType.DIRECT, true);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L2")));
        given(handoffService.issue(any())).willReturn(HandoffTicket.builder().ticketId("t-9").agencyCode(AG).qimUserId("u1").build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(FeSessionCookie.NAME, "fe-1"));

        ResponseEntity<String> r = sut.entry(AG, CB, null, null, null, request);

        assertThat(location(r)).isEqualTo(CB + "?ticketId=t-9");
        verify(identityLoginService, never()).initiate(any(), any(), any(), any());
        ArgumentCaptor<HandoffIssueCommand> cmd = ArgumentCaptor.forClass(HandoffIssueCommand.class);
        verify(handoffService).issue(cmd.capture());
        assertThat(cmd.getValue().getAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(cmd.getValue().getProviderCode()).isEqualTo("SESSION");
    }

    @Test
    @DisplayName("정책 거부(E-IDO-120)는 콜백으로 error·error_description·state 를 실어 302 — 오류 화면이 아니다")
    void resume_policyDenialRedirectsWithError() {
        String req = startedRequest();
        VerifiedIdentity identity = new VerifiedIdentity("MOCK", "tx-1", "mock:abc", null, null, null, null,
                null, AuthResult.AuthLevel.L1, Instant.now(), Map.of(), SubjectScheme.EXTERNAL_SUB);
        given(identityLoginService.complete(eq("MOCK"), eq("tx-1"), any(), anyString()))
                .willReturn(new IdentityLoginService.Completed(identity, "u1", false));
        given(feSessionService.create("u1", "tx-1", "L1", null)).willReturn(fe("L1"));
        given(handoffService.issue(any())).willThrow(new PlatformException(PlatformErrorCode.IDO_ASSIGNMENT_REQUIRED, "cid"));

        ResponseEntity<String> r = sut.resume(req, Map.of("req", req), new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(r.getStatusCode().value()).isEqualTo(302);
        assertThat(location(r)).startsWith(CB + "?error=E-IDO-120&error_description=").endsWith("&state=st-9");
    }

    @Test
    @DisplayName("SPI 인증 실패 → 오류 화면(콜백으로 보내지 않는다), FE 세션 없음")
    void resume_spiFailure() {
        String req = startedRequest();
        given(identityLoginService.complete(eq("MOCK"), eq("tx-1"), any(), anyString()))
                .willThrow(new PlatformException(PlatformErrorCode.IDO_AUTH_VERIFICATION_FAILED, "cid"));
        ResponseEntity<String> r = sut.resume(req, Map.of("req", req), new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).contains("E-IDO-110");
        verify(feSessionService, never()).create(any(), any(), any(), any());
        verify(handoffService, never()).issue(any());
    }

    @Test
    @DisplayName("브로커 경로 복귀인데 쿠키가 없으면 401 오류 화면")
    void resume_brokerWithoutCookie() {
        sut = new HandoffLoginController(store, agencyMetaRepository, new CallbackUrlValidator(), registry,
                identityLoginService, feSessionService, feSessionPolicyEnforcer, handoffService, "http://hub.test", "keycloak");
        agency(IntegrationType.DIRECT, true);
        sut.entry(AG, CB, null, null, null, new MockHttpServletRequest());
        String req = mem.keySet().iterator().next();
        sut.start(req, "broker:keycloak");
        ResponseEntity<String> r = sut.resume(req, Map.of("req", req), new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("E-IDO-107");
    }

    @Test
    @DisplayName("appendQuery: 기존 쿼리가 있어도 ticketId·state 를 올바르게 붙인다")
    void appendQuery() {
        assertThat(HandoffLoginController.appendQuery("https://a.example/cb?x=1", Map.of("ticketId", "t"), "s t"))
                .isEqualTo("https://a.example/cb?x=1&ticketId=t&state=s%20t");
        assertThat(ReflectionTestUtils.getField(sut, "publicUrl")).isEqualTo("http://hub.test");
    }
}
