package io.github.hipstermin.idem.gate.oidcfront;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.hipstermin.idem.gate.keycloak.KeycloakJwksVerifier;
import io.github.hipstermin.idem.gate.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakIdTokenClaims;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

/**
 * gate OIDC 프런트 끝-끝(서블릿 계층): Keycloak 과 hub 를 WireMock 으로 대신하고, JWKS 검증기만 목이다.
 * 정책 강제 지점(토큰 교환)·userinfo 보강·사전검사·투명 프록시(302/쿠키/Location 재작성)를 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OidcFrontController — Keycloak 을 숨긴 표준 OIDC 프런트 (S6)")
class OidcFrontControllerTest {

    static WireMockServer kc = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    static WireMockServer hub = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Mock KeycloakJwksVerifier jwksVerifier;
    @Mock AccessDecisionCache cache;

    MockMvc mvc;
    ObjectMapper om = new ObjectMapper();
    static final String ISSUER = "https://sso.example.org/realms/onepass";
    static final String TOKEN_BODY = "{\"access_token\":\"at.x.y\",\"id_token\":\"h.p.s\",\"refresh_token\":\"rt-1\",\"expires_in\":300,\"token_type\":\"Bearer\"}";

    @BeforeAll static void start() { kc.start(); hub.start(); }
    @AfterAll static void stop() { kc.stop(); hub.stop(); }

    @BeforeEach
    void setUp() {
        kc.resetAll();
        hub.resetAll();
        KeycloakProperties kcProps = new KeycloakProperties();
        kcProps.setBaseUrl("http://localhost:" + kc.port());
        kcProps.setRealm("onepass");
        OidcFrontProperties props = new OidcFrontProperties();
        props.setIssuer(ISSUER);
        RestTemplate proxyRt = new OidcFrontConfig().keycloakProxyRestTemplate(props);
        KeycloakProxy proxy = new KeycloakProxy(proxyRt, kcProps, props);
        HubAccessClient hubClient = new HubAccessClient(new RestTemplate(), om);
        ReflectionTestUtils.setField(hubClient, "idoBaseUrl", "http://localhost:" + hub.port());
        ReflectionTestUtils.setField(hubClient, "internalSigSecret", "0123456789abcdef0123456789abcdef");
        HubSessionClient hubSession = new HubSessionClient(new RestTemplate());
        ReflectionTestUtils.setField(hubSession, "idoBaseUrl", "http://localhost:" + hub.port());
        ReflectionTestUtils.setField(hubSession, "internalSigSecret", "0123456789abcdef0123456789abcdef");
        OidcRpPolicyGate gate = new OidcRpPolicyGate(jwksVerifier, hubClient, hubSession, cache, kcProps, props, om, proxyRt);
        mvc = MockMvcBuilders.standaloneSetup(new OidcFrontController(kcProps, props, proxy, gate, hubSession, cache)).build();
        given(cache.get(anyString(), anyString())).willReturn(Optional.empty());
    }

    private static KeycloakIdTokenClaims claims(String sub, String azp, String idp, String acr) throws Exception {
        String json = "{\"sub\":\"" + sub + "\",\"azp\":\"" + azp + "\",\"identity_provider\":\"" + idp + "\",\"acr\":\"" + acr + "\",\"sid\":\"sid-1\"}";
        return new ObjectMapper().readValue(json, KeycloakIdTokenClaims.class);
    }

    private static String jwtWithPayload(String payloadJson) {
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJSUzI1NiJ9." + b64 + ".sig";
    }

    // ── discovery ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Discovery 는 두 경로에서 같은 정직한 문서를 내고, 다른 realm 은 404")
    void discovery() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.token_endpoint").value(ISSUER + "/protocol/openid-connect/token"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
        mvc.perform(get("/realms/onepass/.well-known/openid-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER));
        mvc.perform(get("/realms/other/.well-known/openid-configuration")).andExpect(status().isNotFound());
    }

    // ── authorize ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("auth 사전검사: Idem client 아님·code 아님·PKCE 없음은 Keycloak 에 가기 전에 거부")
    void authorize_preChecks() throws Exception {
        mvc.perform(get("/realms/onepass/protocol/openid-connect/auth").param("client_id", "q-sign-client")
                        .param("response_type", "code").param("code_challenge", "abc").param("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unauthorized_client"));
        mvc.perform(get("/realms/onepass/protocol/openid-connect/auth").param("client_id", "idem-svc-AG1")
                        .param("response_type", "token").param("code_challenge", "abc").param("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unsupported_response_type"));
        mvc.perform(get("/realms/onepass/protocol/openid-connect/auth").param("client_id", "idem-svc-AG1").param("response_type", "code"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(get("/realms/onepass/protocol/openid-connect/auth").param("client_id", "idem-svc-AG1").param("response_type", "code")
                        .param("code_challenge", "abc").param("code_challenge_method", "plain"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(kc.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("auth 통과: 쿼리·쿠키·X-Forwarded 가 Keycloak 에 전달되고, 302·Set-Cookie 는 그대로, Location 의 내부 주소는 공개 URL 로")
    void authorize_forwardsAndRewritesLocation() throws Exception {
        kc.stubFor(WireMock.get(urlPathEqualTo("/realms/onepass/protocol/openid-connect/auth"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "http://localhost:" + kc.port() + "/realms/onepass/login-actions/authenticate?x=1")
                        .withHeader("Set-Cookie", "AUTH_SESSION_ID=abc; Path=/realms/onepass/; HttpOnly")));
        mvc.perform(get("/realms/onepass/protocol/openid-connect/auth").param("client_id", "idem-svc-AG1").param("response_type", "code")
                        .param("code_challenge", "abc").param("code_challenge_method", "S256").param("redirect_uri", "https://rp/cb")
                        .header("Cookie", "KEYCLOAK_SESSION=zzz"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://sso.example.org/realms/onepass/login-actions/authenticate?x=1"))
                .andExpect(header().string("Set-Cookie", containsStr("AUTH_SESSION_ID=abc")));
        var ev = kc.getAllServeEvents().get(0).getRequest();
        assertThat(ev.getUrl()).contains("client_id=idem-svc-AG1").contains("code_challenge_method=S256");
        assertThat(ev.getHeader("Cookie")).isEqualTo("KEYCLOAK_SESSION=zzz");
        assertThat(ev.getHeader("X-Forwarded-Host")).isEqualTo("sso.example.org");
        assertThat(ev.getHeader("X-Forwarded-Proto")).isEqualTo("https");
    }

    private static org.hamcrest.Matcher<String> containsStr(String s) { return org.hamcrest.Matchers.containsString(s); }

    // ── token ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 교환 허용: Keycloak 응답을 그대로 돌려주고 hub 판정을 캐시한다 (X-Internal-Sig 로 hub 호출)")
    void token_allowed() throws Exception {
        kc.stubFor(WireMock.post(urlEqualTo("/realms/onepass/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(TOKEN_BODY)));
        given(jwksVerifier.verify(eq("h.p.s"), anyString())).willReturn(claims("kc-sub", "idem-svc-AG1", "social-kakao", "2"));
        hub.stubFor(WireMock.post(urlEqualTo("/api/internal/v1/oidc-rp/access"))
                .withHeader("X-Internal-Sig", matching("[0-9a-f]{64}"))
                .withRequestBody(containing("\"clientId\":\"idem-svc-AG1\"")).withRequestBody(containing("\"sub\":\"kc-sub\""))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":true,\"serviceCode\":\"AG1\",\"qimUserId\":\"qim-1\",\"state\":\"APPROVED\",\"agencySubjectId\":\"pw-1\",\"subjectScheme\":\"PAIRWISE_HMAC\",\"roles\":[\"VIEWER\"],\"assigned\":true}")));

        MvcResult res = mvc.perform(post("/realms/onepass/protocol/openid-connect/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("idem-svc-AG1:sec".getBytes()))
                        .content("grant_type=authorization_code&code=c1&redirect_uri=https%3A%2F%2Frp%2Fcb&code_verifier=v"))
                .andExpect(status().isOk()).andReturn();
        assertThat(res.getResponse().getContentAsString()).isEqualTo(TOKEN_BODY);
        hub.verify(1, postRequestedFor(urlEqualTo("/api/internal/v1/oidc-rp/access")));
        ArgumentCaptor<AccessDecision> cached = ArgumentCaptor.forClass(AccessDecision.class);
        org.mockito.Mockito.verify(cache).put(eq("idem-svc-AG1"), eq("kc-sub"), cached.capture(), eq(300L));
        assertThat(cached.getValue().roles()).containsExactly("VIEWER");
        // Keycloak 에는 Basic 인증과 본문이 그대로 갔다
        var ev = kc.getAllServeEvents().get(0).getRequest();
        assertThat(ev.getHeader("Authorization")).startsWith("Basic ");
        assertThat(ev.getBodyAsString()).contains("code_verifier=v");
    }

    @Test
    @DisplayName("토큰 교환 거부(E-IDO-120): 403 access_denied, 토큰은 돌려주지 않고 Keycloak 세션을 refresh_token 으로 끊는다")
    void token_denied() throws Exception {
        kc.stubFor(WireMock.post(urlEqualTo("/realms/onepass/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(TOKEN_BODY)));
        kc.stubFor(WireMock.post(urlEqualTo("/realms/onepass/protocol/openid-connect/logout")).willReturn(aResponse().withStatus(204)));
        given(jwksVerifier.verify(eq("h.p.s"), anyString())).willReturn(claims("kc-sub", "idem-svc-AG1", "social-kakao", "1"));
        hub.stubFor(WireMock.post(urlEqualTo("/api/internal/v1/oidc-rp/access"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":false,\"denyCode\":\"E-IDO-120\",\"denyMessage\":\"미할당\",\"rule\":\"ASSIGNMENT\"}")));

        MvcResult res = mvc.perform(post("/realms/onepass/protocol/openid-connect/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&client_id=idem-svc-AG1&client_secret=sec&code=c1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"))
                .andExpect(jsonPath("$.error_description").value(org.hamcrest.Matchers.startsWith("E-IDO-120")))
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain("access_token");
        kc.verify(1, postRequestedFor(urlEqualTo("/realms/onepass/protocol/openid-connect/logout"))
                .withRequestBody(containing("refresh_token=rt-1")).withRequestBody(containing("client_id=idem-svc-AG1")));
        org.mockito.Mockito.verify(cache).evict("idem-svc-AG1", "kc-sub");
    }

    @Test
    @DisplayName("hub 가 닿지 않으면 503 temporarily_unavailable — 토큰은 나가지 않는다 (fail-closed)")
    void token_hubDown() throws Exception {
        kc.stubFor(WireMock.post(urlEqualTo("/realms/onepass/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(TOKEN_BODY)));
        kc.stubFor(WireMock.post(urlEqualTo("/realms/onepass/protocol/openid-connect/logout")).willReturn(aResponse().withStatus(204)));
        given(jwksVerifier.verify(eq("h.p.s"), anyString())).willReturn(claims("kc-sub", "idem-svc-AG1", "social-kakao", "1"));
        hub.stubFor(WireMock.post(anyUrl()).willReturn(aResponse().withStatus(500)));
        mvc.perform(post("/realms/onepass/protocol/openid-connect/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&client_id=idem-svc-AG1&code=c1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("temporarily_unavailable"));
    }

    @Test
    @DisplayName("Keycloak 의 토큰 오류(invalid_grant)는 그대로 지나가고 hub 를 부르지 않는다; Idem client 가 아니면 401")
    void token_passthroughErrors() throws Exception {
        kc.stubFor(WireMock.post(urlEqualTo("/realms/onepass/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_grant\"}")));
        mvc.perform(post("/realms/onepass/protocol/openid-connect/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&client_id=idem-svc-AG1&code=bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(hub.getAllServeEvents()).isEmpty();

        mvc.perform(post("/realms/onepass/protocol/openid-connect/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=authorization_code&client_id=ido-client&code=c1"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
    }

    // ── userinfo ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("userinfo: 캐시된 판정으로 idem_* 클레임을 보태고, 거부 판정이면 403")
    void userInfo_enriched() throws Exception {
        kc.stubFor(WireMock.get(urlEqualTo("/realms/onepass/protocol/openid-connect/userinfo"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"sub\":\"kc-sub\",\"email\":\"u@example.org\"}")));
        String bearer = jwtWithPayload("{\"sub\":\"kc-sub\",\"azp\":\"idem-svc-AG1\",\"acr\":\"1\"}");
        given(cache.get("idem-svc-AG1", "kc-sub")).willReturn(Optional.of(new AccessDecision(true, null, null, null, "AG1", "qim-1",
                "APPROVED", "pw-1", "PAIRWISE_HMAC", java.util.List.of("VIEWER", "EDITOR"), true, "L1", "KAKAO_OIDC")));

        mvc.perform(get("/realms/onepass/protocol/openid-connect/userinfo").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("u@example.org"))
                .andExpect(jsonPath("$.idem_service").value("AG1"))
                .andExpect(jsonPath("$.idem_state").value("APPROVED"))
                .andExpect(jsonPath("$.idem_subject").value("pw-1"))
                .andExpect(jsonPath("$.idem_roles[1]").value("EDITOR"))
                .andExpect(jsonPath("$.idem_assigned").value(true));
        assertThat(hub.getAllServeEvents()).as("캐시 적중이면 hub 를 다시 묻지 않는다").isEmpty();

        given(cache.get("idem-svc-AG1", "kc-sub")).willReturn(Optional.of(AccessDecision.denied("E-IDO-120", "미할당")));
        mvc.perform(get("/realms/onepass/protocol/openid-connect/userinfo").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("access_denied"));
    }

    @Test
    @DisplayName("userinfo 캐시 미스면 hub 에 다시 묻고(bearer 의 azp/sub) 허용이면 캐시한다")
    void userInfo_cacheMiss_reevaluates() throws Exception {
        kc.stubFor(WireMock.get(urlEqualTo("/realms/onepass/protocol/openid-connect/userinfo"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"sub\":\"kc-sub\"}")));
        hub.stubFor(WireMock.post(urlEqualTo("/api/internal/v1/oidc-rp/access"))
                .withRequestBody(containing("\"clientId\":\"idem-svc-AG1\""))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":true,\"serviceCode\":\"AG1\",\"state\":\"GUEST\",\"roles\":[]}")));
        String bearer = jwtWithPayload("{\"sub\":\"kc-sub\",\"azp\":\"idem-svc-AG1\"}");
        mvc.perform(get("/realms/onepass/protocol/openid-connect/userinfo").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idem_state").value("GUEST"));
        org.mockito.Mockito.verify(cache).put(eq("idem-svc-AG1"), eq("kc-sub"), org.mockito.ArgumentMatchers.any(), eq(3600L));
    }

    // ── passthrough ────────────────────────────────────────────────────────

    @Test
    @DisplayName("로그인 화면·정적 자원은 투명 프록시, 다른 realm 은 404, 동적 등록은 막힌다, Keycloak 다운은 502")
    void passthrough() throws Exception {
        kc.stubFor(WireMock.post(urlPathEqualTo("/realms/onepass/login-actions/authenticate"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "https://rp/cb?code=xyz&state=s")));
        mvc.perform(post("/realms/onepass/login-actions/authenticate").param("session_code", "sc").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("username=u&password=p"))
                .andExpect(status().isFound()).andExpect(header().string("Location", "https://rp/cb?code=xyz&state=s"));
        assertThat(kc.getAllServeEvents().get(0).getRequest().getBodyAsString()).isEqualTo("username=u&password=p");

        kc.stubFor(WireMock.get(urlEqualTo("/resources/abc/login/keycloak/css/login.css"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/css").withBody("body{}")));
        mvc.perform(get("/resources/abc/login/keycloak/css/login.css")).andExpect(status().isOk()).andExpect(content().string("body{}"));

        mvc.perform(get("/realms/master/protocol/openid-connect/auth")).andExpect(status().isNotFound());
        mvc.perform(post("/realms/onepass/clients-registrations/openid-connect")).andExpect(status().isNotFound());

        kc.stop();
        try {
            mvc.perform(get("/realms/onepass/protocol/openid-connect/certs")).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error").value("temporarily_unavailable"));
        } finally {
            kc.start();
        }
    }

    @Test
    @DisplayName("RP-Initiated Logout(S6 PR-2): id_token_hint 의 sub·sid 로 판정 캐시를 비우고 hub 에 알린 뒤 Keycloak 에 전달한다")
    void rpInitiatedLogout_cleansIdemSideThenForwards() throws Exception {
        kc.stubFor(WireMock.get(urlPathEqualTo("/realms/onepass/protocol/openid-connect/logout"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "https://rp.example.org/")));
        given(jwksVerifier.verify(eq("hint.p.s"), anyString())).willReturn(claims("kc-sub", "idem-svc-AG1", "social-kakao", "1"));
        hub.stubFor(WireMock.post(urlEqualTo("/api/internal/v1/session/idp-logout"))
                .withRequestBody(containing("\"sub\":\"kc-sub\"")).withRequestBody(containing("\"sid\":\"sid-1\""))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"expired\":1}")));
        mvc.perform(get("/realms/onepass/protocol/openid-connect/logout").param("id_token_hint", "hint.p.s")
                        .param("post_logout_redirect_uri", "https://rp.example.org/"))
                .andExpect(status().isFound()).andExpect(header().string("Location", "https://rp.example.org/"));
        hub.verify(1, postRequestedFor(urlEqualTo("/api/internal/v1/session/idp-logout")).withHeader("X-Internal-Sig", matching("[0-9a-f]{64}")));
        org.mockito.Mockito.verify(cache).evictBySub("kc-sub");
        assertThat(kc.getAllServeEvents().get(0).getRequest().getUrl()).contains("id_token_hint=hint.p.s");
    }

    @Test
    void formAndBasicParsing() {
        assertThat(OidcFrontController.parseForm("a=1&b=%2Fx%20y&c".getBytes())).containsEntry("a", "1").containsEntry("b", "/x y").doesNotContainKey("c");
        assertThat(OidcFrontController.basicClientId("Basic " + Base64.getEncoder().encodeToString("idem-svc-A:s:ecret".getBytes()))).isEqualTo("idem-svc-A");
        assertThat(OidcFrontController.basicClientId("Bearer x")).isNull();
        assertThat(OidcFrontController.bearer("Bearer  tok ")).isEqualTo("tok");
    }
}
