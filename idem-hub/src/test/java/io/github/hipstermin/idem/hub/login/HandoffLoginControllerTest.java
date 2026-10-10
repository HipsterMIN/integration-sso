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
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.consent.ConsentItem;
import io.github.hipstermin.idem.hub.consent.ConsentRegistryClient;
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
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
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
    @Mock ServiceProfileService serviceProfileService;
    @Mock ConsentRegistryClient consentClient;
    @Mock AuditLogPublisher auditLogPublisher;

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
                serviceProfileService, consentClient, auditLogPublisher, "http://hub.test/", "");
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
                identityLoginService, feSessionService, feSessionPolicyEnforcer, handoffService,
                serviceProfileService, consentClient, auditLogPublisher, "http://hub.test", "keycloak");
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
                identityLoginService, feSessionService, feSessionPolicyEnforcer, handoffService,
                serviceProfileService, consentClient, auditLogPublisher, "http://hub.test", "keycloak");
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
                identityLoginService, feSessionService, feSessionPolicyEnforcer, handoffService,
                serviceProfileService, consentClient, auditLogPublisher, "http://hub.test", "keycloak");
        agency(IntegrationType.DIRECT, true);
        sut.entry(AG, CB, null, null, null, new MockHttpServletRequest());
        String req = mem.keySet().iterator().next();
        sut.start(req, "broker:keycloak");
        ResponseEntity<String> r = sut.resume(req, Map.of("req", req), new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("E-IDO-107");
    }

    // ── 1.1 동의 카탈로그 (플랜 §5 #8) ─────────────────────────────────────────

    private static final ConsentItem TERMS = new ConsentItem("v-terms", "TERMS_OF_SERVICE", null, "ACTIVE", "2026-10", "이용약관", "https://idem.example.org/terms", true, Instant.EPOCH);
    private static final ConsentItem MARKETING = new ConsentItem("v-mkt", "MARKETING", AG, "ACTIVE", "1", "마케팅 수신", "javascript:alert(1)", false, Instant.EPOCH);

    private void consentProfile(boolean enabled, Boolean includePlatform) {
        ServiceProfile profile = ServiceProfile.builder().consent(new ServiceProfile.Consent(enabled, includePlatform)).build();
        given(serviceProfileService.find(AG)).willReturn(Optional.of(profile));
    }

    private MockHttpServletRequest withCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(FeSessionCookie.NAME, "fe-1"));
        request.setRemoteAddr("203.0.113.9");
        return request;
    }

    @Test
    @DisplayName("동의 켜짐 + 필수 미동의 → 발급 대신 동의 화면 200 (form action=…/login/consent, agree=versionId, 요청 상태 유지, 링크는 http(s) 만)")
    void consent_pageWhenRequiredMissing() {
        agency(IntegrationType.DIRECT, true);
        consentProfile(true, null);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L1")));
        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willReturn(List.of(TERMS, MARKETING));

        ResponseEntity<String> r = sut.entry(AG, CB, null, null, "st-c", withCookie());

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("action=\"http://hub.test/api/v1/handoff/login/consent\"")
                .contains("name=\"agree\" value=\"v-terms\"").contains("이용약관").contains("(필수)")
                .contains("value=\"v-mkt\"").contains("(선택)")
                .contains("https://idem.example.org/terms").doesNotContain("javascript:")
                .contains("name=\"decline\"");
        assertThat(mem).hasSize(1);
        verify(handoffService, never()).issue(any());
    }

    @Test
    @DisplayName("동의 화면은 필터가 넣은 CSP form-action 'self' 에 기관 콜백 출처를 더한다 — Chromium 이 form POST 뒤 콜백 302 를 막지 않도록 (G2-4)")
    void consent_pageAllowsCallbackOriginInFormAction() {
        agency(IntegrationType.DIRECT, true);
        consentProfile(true, null);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L1")));
        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willReturn(List.of(TERMS));
        MockHttpServletRequest request = withCookie();
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        response.setHeader("Content-Security-Policy", "default-src 'none'; form-action 'self'; base-uri 'none'");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request, response));
        try {
            ResponseEntity<String> r = sut.entry(AG, CB, null, null, "st-c", request);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeader("Content-Security-Policy"))
                    .isEqualTo("default-src 'none'; form-action 'self' https://agency.example.org; base-uri 'none'");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("선택 항목만 미동의면 화면 없이 바로 발급; 동의가 꺼진 서비스는 registry 를 부르지 않는다")
    void consent_optionalOnlyOrDisabledIssuesDirectly() {
        agency(IntegrationType.DIRECT, true);
        consentProfile(true, null);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L1")));
        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willReturn(List.of(MARKETING));
        given(handoffService.issue(any())).willReturn(HandoffTicket.builder().ticketId("t-1").agencyCode(AG).qimUserId("u1").build());
        assertThat(location(sut.entry(AG, CB, null, null, null, withCookie()))).isEqualTo(CB + "?ticketId=t-1");

        consentProfile(false, null);
        assertThat(location(sut.entry(AG, CB, null, null, null, withCookie()))).isEqualTo(CB + "?ticketId=t-1");
        verify(consentClient, org.mockito.Mockito.times(1)).missing(any(), any(), any());
    }

    @Test
    @DisplayName("includePlatform=false 면 플랫폼 공통 항목은 묻지 않는다")
    void consent_includePlatformFalse() {
        agency(IntegrationType.DIRECT, true);
        consentProfile(true, false);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L1")));
        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willReturn(List.of(TERMS, MARKETING));
        given(handoffService.issue(any())).willReturn(HandoffTicket.builder().ticketId("t-2").agencyCode(AG).qimUserId("u1").build());
        assertThat(location(sut.entry(AG, CB, null, null, null, withCookie()))).isEqualTo(CB + "?ticketId=t-2");
    }

    @Test
    @DisplayName("동의 제출: 서버가 다시 계산한 미동의 목록 안의 항목만 기록(LOGIN_FRONT:<service>, IP) → 감사 CONSENT_AGREED → 발급 → 콜백, 상태 1회 소비")
    void consent_submitRecordsAndIssues() {
        agency(IntegrationType.DIRECT, true);
        consentProfile(true, null);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L1")));
        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willReturn(List.of(TERMS, MARKETING));
        sut.entry(AG, CB, null, null, "st-c", withCookie());
        String req = mem.keySet().iterator().next();
        given(handoffService.issue(any())).willReturn(HandoffTicket.builder().ticketId("t-3").agencyCode(AG).qimUserId("u1").build());

        ResponseEntity<String> r = sut.consent(req, List.of("v-terms", "v-forged"), null, withCookie());

        assertThat(r.getStatusCode().value()).isEqualTo(302);
        assertThat(location(r)).isEqualTo(CB + "?ticketId=t-3&state=st-c");
        verify(consentClient).agree(eq("u1"), eq("v-terms"), eq("TERMS_OF_SERVICE"), eq("LOGIN_FRONT:" + AG), eq("203.0.113.9"), anyString());
        verify(consentClient, never()).agree(any(), eq("v-forged"), any(), any(), any(), any());
        verify(consentClient, never()).agree(any(), eq("v-mkt"), any(), any(), any(), any());
        ArgumentCaptor<AuditLogPublisher.AuditEntry> audit = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(auditLogPublisher).publish(audit.capture());
        assertThat(audit.getValue().eventAction()).isEqualTo("CONSENT_AGREED");
        assertThat(audit.getValue().actorId()).isEqualTo("u1");
        assertThat(audit.getValue().agencyCode()).isEqualTo(AG);
        assertThat(audit.getValue().metadata()).containsEntry("versionIds", List.of("v-terms"));
        assertThat(mem).isEmpty();
    }

    @Test
    @DisplayName("필수 항목을 빼고 제출하면 오류 문구와 함께 동의 화면을 다시 보이고 기록·발급하지 않는다")
    void consent_submitWithoutRequiredRerenders() {
        agency(IntegrationType.DIRECT, true);
        consentProfile(true, null);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L1")));
        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willReturn(List.of(TERMS, MARKETING));
        sut.entry(AG, CB, null, null, null, withCookie());
        String req = mem.keySet().iterator().next();

        ResponseEntity<String> r = sut.consent(req, List.of("v-mkt"), null, withCookie());

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("필수 항목에 모두 동의해야").contains("value=\"v-terms\"");
        verify(consentClient, never()).agree(any(), any(), any(), any(), any(), any());
        verify(handoffService, never()).issue(any());
        assertThat(mem).containsKey(req);
    }

    @Test
    @DisplayName("동의 거부 → 상태 삭제, 감사 CONSENT_DECLINED, 302 callback?error=E-IDO-125&error_description&state")
    void consent_declineRedirectsError() {
        agency(IntegrationType.DIRECT, true);
        consentProfile(true, null);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L1")));
        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willReturn(List.of(TERMS));
        sut.entry(AG, CB, null, null, "st-d", withCookie());
        String req = mem.keySet().iterator().next();

        ResponseEntity<String> r = sut.consent(req, null, "1", withCookie());

        assertThat(r.getStatusCode().value()).isEqualTo(302);
        assertThat(location(r)).startsWith(CB + "?error=E-IDO-125&error_description=").endsWith("&state=st-d");
        ArgumentCaptor<AuditLogPublisher.AuditEntry> audit = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(auditLogPublisher).publish(audit.capture());
        assertThat(audit.getValue().eventAction()).isEqualTo("CONSENT_DECLINED");
        verify(handoffService, never()).issue(any());
        verify(consentClient, never()).agree(any(), any(), any(), any(), any(), any());
        assertThat(mem).isEmpty();
    }

    @Test
    @DisplayName("동의 제출: 만료 요청 410, 세션 쿠키 없음 401; registry 장애는 오류 화면(503) — 콜백으로 보내지 않고 발급하지 않는다")
    void consent_expiredNoSessionAndRegistryDown() {
        assertThat(sut.consent("nope", null, null, withCookie()).getStatusCode().value()).isEqualTo(410);

        agency(IntegrationType.DIRECT, true);
        consentProfile(true, null);
        given(feSessionService.findById("fe-1")).willReturn(Optional.of(fe("L1")));
        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willReturn(List.of(TERMS));
        sut.entry(AG, CB, null, null, null, withCookie());
        String req = mem.keySet().iterator().next();
        ResponseEntity<String> noCookie = sut.consent(req, List.of("v-terms"), null, new MockHttpServletRequest());
        assertThat(noCookie.getStatusCode().value()).isEqualTo(401);
        assertThat(noCookie.getBody()).contains("E-IDO-107");

        given(consentClient.missing(eq("u1"), eq(AG), anyString())).willThrow(new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, "cid", "down"));
        ResponseEntity<String> down = sut.entry(AG, CB, null, null, null, withCookie());
        assertThat(down.getStatusCode().value()).isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE.getHttpStatus().value());
        assertThat(down.getBody()).contains("E-IDO-106");
        assertThat(location(down)).isNull();
        verify(handoffService, never()).issue(any());
    }

    @Test
    @DisplayName("appendQuery: 기존 쿼리가 있어도 ticketId·state 를 올바르게 붙인다")
    void appendQuery() {
        assertThat(HandoffLoginController.appendQuery("https://a.example/cb?x=1", Map.of("ticketId", "t"), "s t"))
                .isEqualTo("https://a.example/cb?x=1&ticketId=t&state=s%20t");
        assertThat(ReflectionTestUtils.getField(sut, "publicUrl")).isEqualTo("http://hub.test");
    }
}
