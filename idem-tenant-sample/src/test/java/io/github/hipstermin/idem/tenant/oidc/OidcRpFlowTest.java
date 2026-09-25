package io.github.hipstermin.idem.tenant.oidc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.tenant.session.AgencySessionService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S6 PR-2 완료 기준의 절반: 기관 샘플이 {@code protocol=OIDC_RP} 로 표준 OIDC 로그인·로그아웃·Back-Channel Logout 을 통과한다.
 * OP(Idem gate 프런트)는 WireMock 이고 id_token·logout_token 은 테스트가 만든 RSA 키로 서명한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("tenant-sample 표준 OIDC RP — 로그인 → 세션 → RP-Initiated Logout → Back-Channel Logout")
class OidcRpFlowTest {

    static WireMockServer op = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    static KeyPair keyPair;
    static String issuer;
    @Mock AgencySessionService sessions;
    OidcRpProperties props;
    OidcLoginController controller;
    MockMvc mvc;
    ObjectMapper om = new ObjectMapper();

    @BeforeAll
    static void start() throws Exception {
        op.start();
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        issuer = "http://localhost:" + op.port() + "/realms/idem";
    }

    @AfterAll static void stop() { op.stop(); }

    @BeforeEach
    void setUp() {
        op.resetAll();
        props = new OidcRpProperties();
        props.setIssuer(issuer);
        props.setClientId("idem-svc-AG_SAMPLE");
        props.setClientSecret("s3cr3t");
        props.setRedirectUri("http://localhost:8084/agency/oidc/callback");
        OidcRelyingPartyClient rp = new OidcRelyingPartyClient(props, om);
        controller = new OidcLoginController(rp, props, sessions);
        ReflectionTestUtils.setField(controller, "protocol", "OIDC_RP");
        ReflectionTestUtils.setField(controller, "idleTimeoutMinutes", 30);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String oc = "/realms/idem/protocol/openid-connect";
        op.stubFor(WireMock.get(urlEqualTo("/realms/idem/.well-known/openid-configuration")).willReturn(json(
                "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/protocol/openid-connect/auth\","
                + "\"token_endpoint\":\"" + issuer + "/protocol/openid-connect/token\",\"userinfo_endpoint\":\"" + issuer + "/protocol/openid-connect/userinfo\","
                + "\"jwks_uri\":\"" + issuer + "/protocol/openid-connect/certs\",\"end_session_endpoint\":\"" + issuer + "/protocol/openid-connect/logout\"}")));
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        op.stubFor(WireMock.get(urlEqualTo(oc + "/certs")).willReturn(json("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"alg\":\"RS256\",\"n\":\""
                + b64(pub.getModulus().toByteArray()) + "\",\"e\":\"" + b64(pub.getPublicExponent().toByteArray()) + "\"}]}")));
        given(sessions.createSession(any(), anyString(), anyString(), any(), any()))
                .willReturn(new AgencySessionService.SessionCreateResult("sess-1", "raw-agsid", "au-1", "pw-1", "L2"));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    static String sign(String payloadJson) throws Exception {
        String h = b64("{\"alg\":\"RS256\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8));
        String p = b64(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(keyPair.getPrivate());
        s.update((h + "." + p).getBytes(StandardCharsets.US_ASCII));
        return h + "." + p + "." + b64(s.sign());
    }

    @Test
    @DisplayName("login → 302(PKCE S256·state·nonce) → callback → code 교환(Basic, code_verifier) → id_token·userinfo 검증 → AGSID 세션")
    void loginFlow() throws Exception {
        MvcResult r = mvc.perform(get("/agency/oidc/login")).andExpect(status().isFound()).andReturn();
        URI auth = URI.create(r.getResponse().getHeader("Location"));
        Map<String, String> q = UriComponentsBuilder.fromUri(auth).build().getQueryParams().toSingleValueMap();
        assertThat(auth.toString()).startsWith(issuer + "/protocol/openid-connect/auth");
        assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", "idem-svc-AG_SAMPLE")
                .containsEntry("code_challenge_method", "S256").containsKeys("state", "nonce", "code_challenge");
        String state = q.get("state"), nonce = q.get("nonce");
        long exp = System.currentTimeMillis() / 1000 + 300;
        String idToken = sign("{\"iss\":\"" + issuer + "\",\"aud\":\"idem-svc-AG_SAMPLE\",\"sub\":\"kc-sub\",\"sid\":\"sid-1\",\"nonce\":\"" + nonce
                + "\",\"exp\":" + exp + ",\"acr\":\"2\",\"idem_service\":\"AG_SAMPLE\"}");
        op.stubFor(WireMock.post(urlEqualTo("/realms/idem/protocol/openid-connect/token"))
                .withHeader("Authorization", containing("Basic "))
                .withRequestBody(containing("grant_type=authorization_code")).withRequestBody(containing("code_verifier="))
                .willReturn(json("{\"access_token\":\"at-1\",\"id_token\":\"" + idToken + "\",\"expires_in\":300}")));
        op.stubFor(WireMock.get(urlEqualTo("/realms/idem/protocol/openid-connect/userinfo"))
                .withHeader("Authorization", WireMock.equalTo("Bearer at-1"))
                .willReturn(json("{\"sub\":\"kc-sub\",\"idem_service\":\"AG_SAMPLE\",\"idem_state\":\"APPROVED\",\"idem_subject\":\"pw-1\",\"idem_user_id\":\"qim-1\",\"idem_roles\":[\"VIEWER\"]}")));

        mvc.perform(get("/agency/oidc/callback").param("code", "c1").param("state", state))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("AGSID=raw-agsid")))
                .andExpect(jsonPath("$.protocol").value("OIDC_RP")).andExpect(jsonPath("$.idemState").value("APPROVED"));
        ArgumentCaptor<HandoffPayload> payload = ArgumentCaptor.forClass(HandoffPayload.class);
        verify(sessions).createSession(payload.capture(), eq("sid-1"), anyString(), any(), any());
        assertThat(payload.getValue().getSubject().getAgencySubjectId()).isEqualTo("pw-1");
        assertThat(payload.getValue().getSubject().getQimUserId()).isEqualTo("qim-1");
        assertThat(payload.getValue().getAuthContext().getAuthLevel().name()).isEqualTo("L2");
        op.verify(1, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token")));

        // RP-Initiated Logout: 세션의 id_token 으로 end_session_endpoint 에 id_token_hint 를 붙여 보낸다
        given(sessions.findValidSession("raw-agsid")).willReturn(java.util.Optional.of(Map.of("session_id", "sess-1")));
        mvc.perform(get("/agency/oidc/logout").cookie(new jakarta.servlet.http.Cookie("AGSID", "raw-agsid")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(issuer + "/protocol/openid-connect/logout?id_token_hint=")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("post_logout_redirect_uri=")));
        verify(sessions).invalidateByAgsid(eq("raw-agsid"), eq("USER_LOGOUT"), anyString());

        // 같은 state 재사용은 거부
        mvc.perform(get("/agency/oidc/callback").param("code", "c1").param("state", state)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Idem 정책 거부(403 access_denied) 와 nonce 불일치는 세션을 만들지 않는다")
    void denied_and_badNonce() throws Exception {
        String state = UriComponentsBuilder.fromUri(URI.create(mvc.perform(get("/agency/oidc/login")).andReturn().getResponse().getHeader("Location")))
                .build().getQueryParams().getFirst("state");
        op.stubFor(WireMock.post(urlEqualTo("/realms/idem/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"access_denied\",\"error_description\":\"E-IDO-120 미할당\"}")));
        mvc.perform(get("/agency/oidc/callback").param("code", "c1").param("state", state))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("TOKEN_EXCHANGE_FAILED"));

        String state2 = UriComponentsBuilder.fromUri(URI.create(mvc.perform(get("/agency/oidc/login")).andReturn().getResponse().getHeader("Location")))
                .build().getQueryParams().getFirst("state");
        String badNonce = sign("{\"iss\":\"" + issuer + "\",\"aud\":\"idem-svc-AG_SAMPLE\",\"sub\":\"kc-sub\",\"nonce\":\"wrong\",\"exp\":" + (System.currentTimeMillis() / 1000 + 300) + "}");
        op.stubFor(WireMock.post(urlEqualTo("/realms/idem/protocol/openid-connect/token"))
                .willReturn(json("{\"access_token\":\"at\",\"id_token\":\"" + badNonce + "\"}")));
        mvc.perform(get("/agency/oidc/callback").param("code", "c2").param("state", state2)).andExpect(status().isForbidden());
        verify(sessions, org.mockito.Mockito.never()).createSession(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Back-Channel Logout: 유효한 logout_token 의 sid 로 기관 세션을 끊는다; 위조·events 없음은 400")
    void backchannelLogout() throws Exception {
        given(sessions.invalidateByTicketId(eq("sid-9"), eq("BACKCHANNEL_LOGOUT"), anyString())).willReturn(1);
        String lt = sign("{\"iss\":\"" + issuer + "\",\"aud\":\"idem-svc-AG_SAMPLE\",\"sub\":\"kc-sub\",\"sid\":\"sid-9\",\"iat\":1,"
                + "\"events\":{\"http://schemas.openid.net/event/backchannel-logout\":{}}}");
        mvc.perform(post("/agency/oidc/backchannel-logout").contentType("application/x-www-form-urlencoded").param("logout_token", lt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invalidated").value(1));

        String noEvents = sign("{\"iss\":\"" + issuer + "\",\"aud\":\"idem-svc-AG_SAMPLE\",\"sub\":\"kc-sub\",\"sid\":\"sid-9\"}");
        mvc.perform(post("/agency/oidc/backchannel-logout").contentType("application/x-www-form-urlencoded").param("logout_token", noEvents))
                .andExpect(status().isBadRequest());
        String forged = lt.substring(0, lt.lastIndexOf('.') + 1) + b64("nope".getBytes());
        mvc.perform(post("/agency/oidc/backchannel-logout").contentType("application/x-www-form-urlencoded").param("logout_token", forged))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("protocol=HANDOFF 이면 표준 OIDC 로그인 진입은 409 — 프로파일 protocol.type 과 같은 스위치")
    void protocolMismatch() throws Exception {
        ReflectionTestUtils.setField(controller, "protocol", "HANDOFF");
        mvc.perform(get("/agency/oidc/login")).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PROTOCOL_MISMATCH"));
    }
}
