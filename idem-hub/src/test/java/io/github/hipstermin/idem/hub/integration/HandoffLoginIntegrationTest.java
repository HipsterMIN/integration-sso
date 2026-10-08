package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * 1.1 코어 로그인 프런트 끝-끝 — 실제 hub(PG·Redis) + Mock 본인인증 플러그인 + registry(WireMock).
 *
 * <p>브라우저처럼 리다이렉트를 <b>따라가지 않고</b> 한 홉씩 검사한다: 진입 → MOCK initiate 302 → continue(complete·FE 세션·쿠키·발급) 302
 * → 기관 콜백 {@code ?ticketId=&state=} → 기관 서버 간 verify → 재검증 409. 시험 항목 D-16. 1.1 동의 카탈로그(D-18)는 {@link #consentFlow}.
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
    @Autowired JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.update("DELETE FROM idem_hub.agency_meta_history WHERE agency_code = ?", AGENCY);
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

    @Test
    @DisplayName("1.1 동의 카탈로그(D-18): 프로파일 consent.enabled → 필수 미동의면 동의 화면(form-action 'self') → 제출 → registry 기록 → 콜백 ticketId; 거부는 callback?error=E-IDO-125; 감사 CONSENT_AGREED·DECLINED")
    void consentFlow() {
        // 프로파일에 consent 블록 — 관리 API PUT 으로 스키마 검증까지 지난다
        HttpHeaders ah = new HttpHeaders();
        ah.setContentType(MediaType.APPLICATION_JSON);
        withAdmin(ah, http, base);
        String profile = """
                {"schemaVersion":1,"service":{"code":"%s","name":"로그인 프런트 기관"},
                 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["%s"]}},
                 "identity":{"attributes":["name_masked"]},
                 "policy":{"minAuthLevel":"L1"},
                 "consent":{"enabled":true}}
                """.formatted(AGENCY, CB);
        ResponseEntity<String> put = http.exchange(base + "/api/v1/admin/services/" + AGENCY + "/profile", HttpMethod.PUT, new HttpEntity<>(profile, ah), String.class);
        assertThat(put.getStatusCode().value()).as("body=%s", put.getBody()).isEqualTo(200);

        // registry 스텁: 미동의 2건(플랫폼 공통 필수 + 서비스 선택), 기록은 OK
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER + "/consents/missing")).willReturn(json(200,
                "[{\"versionId\":\"v-terms\",\"consentType\":\"TERMS_OF_SERVICE\",\"status\":\"ACTIVE\",\"versionTag\":\"2026-10\",\"title\":\"이용약관\",\"contentUrl\":\"https://idem.example.org/terms\",\"required\":true},"
                + "{\"versionId\":\"v-mkt\",\"consentType\":\"MARKETING\",\"serviceCode\":\"" + AGENCY + "\",\"status\":\"ACTIVE\",\"title\":\"마케팅 수신\",\"required\":false}]")));
        stubFor(post(urlPathEqualTo("/api/v1/internal/users/" + USER + "/consents"))
                .willReturn(json(200, "{\"qimUserId\":\"" + USER + "\",\"consentType\":\"TERMS_OF_SERVICE\",\"agreed\":true}")));

        // ① 진입 → MOCK → ② 복귀: 발급 대신 동의 화면 200 + FE 세션 쿠키 + CSP form-action 'self'
        ResponseEntity<String> r1 = http.getForEntity(URI.create(base + "/api/v1/handoff/login?service=" + AGENCY
                + "&callback=https%3A%2F%2Fagency.example.org%2Fcb&provider=MOCK&state=s2"), String.class);
        assertThat(r1.getStatusCode().value()).as("body=%s", r1.getBody()).isEqualTo(302);
        String hop = base + r1.getHeaders().getFirst(HttpHeaders.LOCATION).substring(PUBLIC_URL.length());
        ResponseEntity<String> r2 = http.getForEntity(URI.create(hop), String.class);
        assertThat(r2.getStatusCode().value()).as("body=%s", r2.getBody()).isEqualTo(200);
        assertThat(r2.getBody()).contains("name=\"agree\" value=\"v-terms\"").contains("이용약관").contains("(필수)")
                .contains("value=\"v-mkt\"").contains("(선택)").contains("https://idem.example.org/terms")
                .contains("action=\"" + PUBLIC_URL + "/api/v1/handoff/login/consent\"");
        assertThat(r2.getHeaders().getFirst("Content-Security-Policy")).contains("form-action 'self'");
        String setCookie = String.join(";", r2.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
        assertThat(setCookie).contains(FeSessionCookie.NAME + "=");
        String cookie = setCookie.substring(setCookie.indexOf(FeSessionCookie.NAME), setCookie.indexOf(';', setCookie.indexOf(FeSessionCookie.NAME)));
        String req = hiddenReq(r2.getBody());

        // ③ 필수를 빼고 제출 → 동의 화면 재표시(기록 없음); 전부 제출 → registry 기록 2건 → 302 콜백 ticketId&state; 상태는 1회
        HttpHeaders fh = new HttpHeaders();
        fh.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        fh.add(HttpHeaders.COOKIE, cookie);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("req", req);
        form.add("agree", "v-mkt");
        ResponseEntity<String> r3 = http.postForEntity(URI.create(base + "/api/v1/handoff/login/consent"), new HttpEntity<>(form, fh), String.class);
        assertThat(r3.getStatusCode().value()).as("body=%s", r3.getBody()).isEqualTo(200);
        assertThat(r3.getBody()).contains("필수 항목에 모두 동의해야");
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/users/" + USER + "/consents")));

        form.add("agree", "v-terms");
        ResponseEntity<String> r4 = http.postForEntity(URI.create(base + "/api/v1/handoff/login/consent"), new HttpEntity<>(form, fh), String.class);
        assertThat(r4.getStatusCode().value()).as("body=%s", r4.getBody()).isEqualTo(302);
        assertThat(r4.getHeaders().getFirst(HttpHeaders.LOCATION)).startsWith(CB + "?ticketId=").endsWith("&state=s2");
        WireMock.verify(2, postRequestedFor(urlPathEqualTo("/api/v1/internal/users/" + USER + "/consents")));
        WireMock.verify(postRequestedFor(urlPathEqualTo("/api/v1/internal/users/" + USER + "/consents"))
                .withRequestBody(containing("\"versionId\":\"v-terms\"")).withRequestBody(containing("LOGIN_FRONT:" + AGENCY)));
        ResponseEntity<String> again = http.postForEntity(URI.create(base + "/api/v1/handoff/login/consent"), new HttpEntity<>(form, fh), String.class);
        assertThat(again.getStatusCode().value()).isEqualTo(410);

        // ④ 거부: 같은 브라우저로 다시 진입(세션 경로, 스텁은 여전히 미동의) → 동의 화면 → 거부 → callback?error=E-IDO-125
        HttpHeaders ch = new HttpHeaders();
        ch.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> r5 = http.exchange(URI.create(base + "/api/v1/handoff/login?service=" + AGENCY
                + "&callback=https%3A%2F%2Fagency.example.org%2Fcb&state=s3"), HttpMethod.GET, new HttpEntity<>(ch), String.class);
        assertThat(r5.getStatusCode().value()).as("body=%s", r5.getBody()).isEqualTo(200);
        MultiValueMap<String, String> decline = new LinkedMultiValueMap<>();
        decline.add("req", hiddenReq(r5.getBody()));
        decline.add("decline", "1");
        ResponseEntity<String> r6 = http.postForEntity(URI.create(base + "/api/v1/handoff/login/consent"), new HttpEntity<>(decline, fh), String.class);
        assertThat(r6.getStatusCode().value()).as("body=%s", r6.getBody()).isEqualTo(302);
        assertThat(r6.getHeaders().getFirst(HttpHeaders.LOCATION)).startsWith(CB + "?error=E-IDO-125&error_description=").endsWith("&state=s3");

        // ⑤ 감사(비동기): MEMBER/CONSENT_AGREED 1건·CONSENT_DECLINED 1건, actor = 사용자, source_ip 있음
        Integer n = 0;
        for (int i = 0; i < 50 && n < 2; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_log WHERE agency_code = ? AND event_category = 'MEMBER' "
                    + "AND event_action IN ('CONSENT_AGREED','CONSENT_DECLINED') AND actor_type = 'USER' AND actor_id = ?", Integer.class, AGENCY, USER);
        }
        assertThat(n).isEqualTo(2);
    }

    /** 동의 화면의 숨은 입력 {@code name="req" value="…"} */
    private static String hiddenReq(String html) {
        String marker = "name=\"req\" value=\"";
        int i = html.indexOf(marker);
        assertThat(i).as("동의 화면에 req 숨은 입력").isPositive();
        int j = html.indexOf('"', i + marker.length());
        return html.substring(i + marker.length(), j);
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
