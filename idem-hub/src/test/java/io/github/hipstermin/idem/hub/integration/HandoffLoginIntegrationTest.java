package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionBindCodeStore;
import io.github.hipstermin.idem.hub.fe.session.FeSessionCookie;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * 1.1 코어 로그인 프런트 끝-끝 — 실제 hub(PG·Redis) + Mock 본인인증 플러그인 + registry(WireMock).
 *
 * <p>브라우저처럼 리다이렉트를 <b>따라가지 않고</b> 한 홉씩 검사한다: 진입 → MOCK initiate 302 → continue(complete·FE 세션·쿠키·발급) 302
 * → 기관 콜백 {@code ?ticketId=&state=} → 기관 서버 간 verify → 재검증 409. 시험 항목 D-16.
 */
@DisplayName("1.1 코어 로그인 프런트 — 브라우저 Handoff 진입 통합 테스트")
class HandoffLoginIntegrationTest extends IntegrationTestBase {

    /** hub 가 브라우저를 되돌릴 자기 주소 — 기동 시 고정되므로(무작위 포트는 그 뒤에 정해진다) 자리표시자를 두고 테스트가 홉마다 바꿔 끼운다. */
    private static final String PUBLIC_URL = "http://hub.public.test";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("idem.plugins.mock-auth.enabled", () -> "true");
        registry.add("idem.hub.public-url", () -> PUBLIC_URL);
    }

    @LocalServerPort int port;
    @Autowired AgencyMetaJpaRepository agencyMetaJpaRepository;
    @Autowired FeSessionService feSessionService;
    @Autowired FeSessionBindCodeStore bindCodeStore;

    private static final String AGENCY = "LOGIN_FRONT_001";
    private static final String KEY    = "login-front-agency-key";
    private static final String CB     = "https://agency.example.org/cb";
    private static final String USER   = "qim-login-front-0001";
    private final RestTemplate http = noRedirect();
    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        wireMockServer.resetAll();
        WireMock.configureFor("localhost", wireMockServer.port());
        stubFor(get(urlPathMatching("/api/v1/users/.*")).willReturn(json(200, "{\"status\":\"ACTIVE\"}")));
        stubFor(post(urlPathEqualTo("/api/v1/internal/users/register-subject"))
                .willReturn(json(201, "{\"qimUserId\":\"" + USER + "\",\"status\":\"ACTIVE\",\"isNew\":true}")));
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER))
                .willReturn(json(200, "{\"qimUserId\":\"" + USER + "\",\"status\":\"ACTIVE\",\"nameMasked\":\"홍*동\",\"subjectScheme\":\"EXTERNAL_SUB\"}")));
        stubFor(get(urlPathMatching("/api/v1/internal/users/[^/]+/di"))
                .willReturn(json(200, "{\"qimUserId\":\"" + USER + "\",\"agencyCode\":\"" + AGENCY + "\",\"di\":\"di-login-front\",\"isNew\":false,\"scheme\":\"PAIRWISE_HMAC\"}")));
        agencyMetaJpaRepository.findById(AGENCY).ifPresent(agencyMetaJpaRepository::delete);
        agencyMetaJpaRepository.save(AgencyMetaJpaEntity.builder()
                .agencyCode(AGENCY).officialName("로그인 프런트 기관").minAuthLevel("L1").policyVersion("1.0")
                .apiKeyHash(CryptoProviders.current().sha256Hex(KEY))
                .callbackWhitelist("[\"" + CB + "\"]").allowedAttributes("[\"name_masked\"]")
                .integrationType(IntegrationType.DIRECT).active(true).build());
    }

    @Test
    @DisplayName("진입 → MOCK → 발급 → 콜백(ticketId·state) → verify APPROVED → 재검증 409; FE 세션 쿠키가 브라우저에 발급된다")
    void browserFlow() {
        // ① 진입 (provider 지정) → MOCK initiate 의 리다이렉트: …/continue?req=…&mockTxId=…
        // RestTemplate 의 문자열 URL 은 템플릿으로 다시 인코딩된다(%3A → %253A) — 브라우저처럼 완성된 URI 로 보낸다
        ResponseEntity<String> r1 = http.getForEntity(URI.create(base + "/api/v1/handoff/login?service=" + AGENCY
                + "&callback=https%3A%2F%2Fagency.example.org%2Fcb&provider=MOCK&state=s1"), String.class);
        assertThat(r1.getStatusCode().value()).as("body=%s", r1.getBody()).isEqualTo(302);
        String hop = r1.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(hop).startsWith(PUBLIC_URL + "/api/v1/handoff/login/continue?req=").contains("mockTxId=mock-");
        hop = base + hop.substring(PUBLIC_URL.length());   // 공개 주소 → 이 테스트 서버

        // ② 복귀 → complete → registry 등록 → FE 세션 + 쿠키 → 발급 → 302 콜백
        ResponseEntity<String> r2 = http.getForEntity(URI.create(hop), String.class);
        assertThat(r2.getStatusCode().value()).as("body=%s", r2.getBody()).isEqualTo(302);
        String cb = r2.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(cb).startsWith(CB + "?ticketId=").endsWith("&state=s1");
        String setCookie = String.join(";", r2.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
        assertThat(setCookie).contains(FeSessionCookie.NAME + "=").contains("HttpOnly");
        String ticketId = cb.substring(cb.indexOf("ticketId=") + 9, cb.indexOf("&state"));

        // ③ 기관 서버 간 verify → APPROVED(주체 식별자 = 기관별 DI), 재검증 409 (1회 소비)
        ResponseEntity<String> v1 = verify(ticketId);
        assertThat(v1.getStatusCode().value()).as("body=%s", v1.getBody()).isEqualTo(200);
        assertThat(v1.getBody()).contains("\"state\":\"APPROVED\"").contains("di-login-front");
        ResponseEntity<String> v2 = verify(ticketId);
        assertThat(v2.getStatusCode().value()).isEqualTo(409);
        assertThat(v2.getBody()).contains("E-IDO-102");

        // ④ 같은 브라우저(쿠키)로 다시 진입 → 로그인 없이 바로 발급
        String cookie = setCookie.substring(setCookie.indexOf(FeSessionCookie.NAME), setCookie.indexOf(';', setCookie.indexOf(FeSessionCookie.NAME)));
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> r3 = http.exchange(URI.create(base + "/api/v1/handoff/login?service=" + AGENCY
                + "&callback=https%3A%2F%2Fagency.example.org%2Fcb"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r3.getStatusCode().value()).as("body=%s", r3.getBody()).isEqualTo(302);
        assertThat(r3.getHeaders().getFirst(HttpHeaders.LOCATION)).startsWith(CB + "?ticketId=");

        // ⑤ 상태는 1회 — 같은 continue URL 재요청은 410
        ResponseEntity<String> again = http.getForEntity(URI.create(hop), String.class);
        assertThat(again.getStatusCode().value()).isEqualTo(410);
    }

    @Test
    @DisplayName("화이트리스트 밖 콜백은 403 오류 화면 — 콜백으로 되돌리지 않는다; OIDC_RP 서비스는 400 E-IDO-121")
    void entryRejections() {
        ResponseEntity<String> bad = http.getForEntity(URI.create(base + "/api/v1/handoff/login?service=" + AGENCY
                + "&callback=https%3A%2F%2Fevil.example.org%2Fcb"), String.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(403);
        assertThat(bad.getHeaders().getContentType()).isNotNull();
        assertThat(bad.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(bad.getBody()).contains("E-AGENCY-304");
        assertThat(bad.getHeaders().getFirst(HttpHeaders.LOCATION)).isNull();

        AgencyMetaJpaEntity e = agencyMetaJpaRepository.findById(AGENCY).orElseThrow();
        e.setIntegrationType(IntegrationType.OIDC_RP);
        agencyMetaJpaRepository.save(e);
        ResponseEntity<String> oidc = http.getForEntity(URI.create(base + "/api/v1/handoff/login?service=" + AGENCY
                + "&callback=https%3A%2F%2Fagency.example.org%2Fcb"), String.class);
        assertThat(oidc.getStatusCode().value()).isEqualTo(400);
        assertThat(oidc.getBody()).contains("E-IDO-121");
    }

    @Test
    @DisplayName("FE 세션 바인드 코드: GET /api/v1/fe-session/bind?code= → 쿠키 + 302 returnUrl, 두 번째는 401 (qsign 모드 쿠키 전달)")
    void bindCode() {
        FeSession fe = feSessionService.create(USER, UUID.randomUUID().toString(), "L1", null);
        String code = bindCodeStore.issue(fe.getFeSessionId(), "http://localhost:3000/after");
        ResponseEntity<String> r = http.getForEntity(base + "/api/v1/fe-session/bind?code=" + code, String.class);
        assertThat(r.getStatusCode().value()).as("body=%s", r.getBody()).isEqualTo(302);
        assertThat(r.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("http://localhost:3000/after");
        assertThat(String.join(";", r.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE))).contains(FeSessionCookie.NAME + "=" + fe.getFeSessionId());
        ResponseEntity<String> r2 = http.getForEntity(base + "/api/v1/fe-session/bind?code=" + code, String.class);
        assertThat(r2.getStatusCode().value()).isEqualTo(401);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<String> verify(String ticketId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Agency-Code", AGENCY);
        h.set("X-Agency-Key", KEY);
        h.set("X-Correlation-Id", UUID.randomUUID().toString());
        return http.exchange(base + "/api/v1/handoff/verify", HttpMethod.POST,
                new HttpEntity<>("{\"ticketId\":\"" + ticketId + "\"}", h), String.class);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** 리다이렉트를 따라가지 않고 4xx/5xx 도 응답으로 받는 클라이언트 — 브라우저 홉을 하나씩 검사한다. */
    private static RestTemplate noRedirect() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        f.setConnectTimeout(5_000);
        f.setReadTimeout(30_000);
        RestTemplate t = new RestTemplate(f);
        t.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
        });
        return t;
    }
}
