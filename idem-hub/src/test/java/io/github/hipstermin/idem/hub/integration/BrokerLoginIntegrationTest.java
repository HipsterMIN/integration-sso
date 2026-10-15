package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.hub.broker.provider.ProviderConfig;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.fe.session.FeSessionCookie;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * 브로커(Keycloak 중계) 로그인 경로 — 설치본 기본값인 q-sign 모드로, 실제 hub(PG·Redis) + gate(WireMock) + registry(WireMock).
 *
 * <p>브라우저처럼 한 홉씩: 1.1 코어 로그인 프런트 진입({@code provider=broker:keycloak}) → 브로커 시작(hub 가 gate 에 authorization URL 을
 * 묻는다) → [Keycloak 로그인은 밖] → gate 가 보내는 완료 콜백({@code POST /api/internal/v1/oidc/complete}, 내부 서명·EXTERNAL_SUB 주체 키)
 * → 바인드(쿠키) → continue → 기관 콜백 {@code ?ticketId=} → 기관 verify.
 *
 * <p>1.1.4 G2-10(Keycloak 26 시험 중 발견): 이 경로는 세 결함이 겹쳐 1.0.0 부터 한 번도 끝까지 가지 못했다 —
 * ① {@code ProviderConfigRepository} 의 {@code @Cacheable(unless="#result.isEmpty()")} SpEL 오류로 브로커 시작이 항상 500(모든 모드),
 * ② 완료 콜백의 returnUrl 화이트리스트가 hub 자신의 continue 주소를 거부, ③ gate 가 주체 키 없이 완료를 알려 hub 가 거부(+ gate 의
 * auth_result jsonb 바인딩 실패 — gate 쪽 IT). 단위 테스트는 저장소·서비스를 Mock 으로 바꿔 캐시 프록시·설정 기본값을 지나지 않았고,
 * 설치본 스모크·k6 는 MOCK(SPI)·OIDC_RP 만 밟는다. 시험 항목 D-19.
 */
@DisplayName("브로커 로그인 경로(q-sign 모드) — 브로커 시작·완료 콜백·바인드·continue 통합 테스트")
class BrokerLoginIntegrationTest extends IntegrationTestBase {

    private static final String PUBLIC_URL = "http://hub.public.test";
    private static final String SIG_SECRET = "integration-test-internal-sig-secret";
    private static final String CACHE      = "provider-config";
    private static final String KC_AUTH    = "http://kc.test/realms/idem/protocol/openid-connect/auth?client_id=idem-gate&state=st1";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("idem.hub.broker.mode", () -> "qsign");           // 설치본 기본값 — authorization URL 은 gate 가, 완료는 gate 가 알린다
        registry.add("idem.hub.gate.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("idem.hub.gate.internal-sig-secret", () -> SIG_SECRET);
        registry.add("idem.hub.handoff.login.broker-providers", () -> "keycloak");
        registry.add("idem.hub.public-url", () -> PUBLIC_URL);
    }

    @LocalServerPort int port;
    @Autowired CacheManager cacheManager;
    @Autowired AgencyMetaJpaRepository agencyMetaJpaRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String AGENCY = "BROKER_LOGIN_001";
    private static final String KEY    = "broker-login-agency-key";
    private static final String CB     = "https://agency.example.org/cb";
    private static final String USER   = "qim-broker-login-0001";
    private final RestTemplate http = noRedirect();
    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        Cache cache = cacheManager.getCache(CACHE);
        assertThat(cache).as("provider-config 캐시 구성").isNotNull();
        cache.clear();
        wireMockServer.resetAll();
        WireMock.configureFor("localhost", wireMockServer.port());
        // gate(q-sign): 브로커 authorization URL 발급
        stubFor(post(urlPathMatching("/api/v1/oidc/[^/]+/auth-url")).willReturn(json(200, "{\"authorizationUrl\":\"" + KC_AUTH + "\"}")));
        // registry: 주체 키로 조회(미등록) → 등록 → 발급 때 사용자·DI 조회
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/by-hash")).willReturn(json(404, "{\"code\":\"NOT_FOUND\"}")));
        stubFor(post(urlPathEqualTo("/api/v1/internal/users/register-subject"))
                .willReturn(json(201, "{\"qimUserId\":\"" + USER + "\",\"status\":\"ACTIVE\",\"isNew\":true}")));
        stubFor(get(urlPathMatching("/api/v1/users/.*")).willReturn(json(200, "{\"status\":\"ACTIVE\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER))
                .willReturn(json(200, "{\"qimUserId\":\"" + USER + "\",\"status\":\"ACTIVE\",\"nameMasked\":\"홍*동\",\"subjectScheme\":\"EXTERNAL_SUB\"}")));
        stubFor(get(urlPathMatching("/api/v1/internal/users/[^/]+/di"))
                .willReturn(json(200, "{\"qimUserId\":\"" + USER + "\",\"agencyCode\":\"" + AGENCY + "\",\"di\":\"di-broker-login\",\"isNew\":false,\"scheme\":\"PAIRWISE_HMAC\"}")));
        jdbcTemplate.update("DELETE FROM idem_hub.agency_meta_history WHERE agency_code = ?", AGENCY);
        agencyMetaJpaRepository.findById(AGENCY).ifPresent(agencyMetaJpaRepository::delete);
        agencyMetaJpaRepository.save(AgencyMetaJpaEntity.builder()
                .agencyCode(AGENCY).officialName("브로커 로그인 기관").minAuthLevel("L1").policyVersion("1.0")
                .apiKeyHash(CryptoProviders.current().sha256Hex(KEY))
                .callbackWhitelist("[\"" + CB + "\"]").allowedAttributes("[\"name_masked\"]")
                .integrationType(IntegrationType.DIRECT).active(true).build());
    }

    @Test
    @DisplayName("provider_config 행이 있는 제공자(KAKAO_OIDC): 302 → authorization URL, 두 번째 호출(캐시 적중)도 같은 결과")
    void providerWithConfigRow_redirectsTwice_andIsCached() {
        ResponseEntity<String> r1 = authorize("KAKAO_OIDC");
        assertThat(r1.getStatusCode().value()).as("body=%s", r1.getBody()).isEqualTo(302);
        assertThat(r1.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(KC_AUTH);

        // 캐시에 ProviderConfig 로 저장·복원된다 (종전: 타입 정보 없이 저장 → 적중 시 LinkedHashMap → ClassCastException)
        ProviderConfig cached = cacheManager.getCache(CACHE).get("KAKAO_OIDC", ProviderConfig.class);
        assertThat(cached).as("캐시 값 복원").isNotNull();
        assertThat(cached.getProviderCode()).isEqualTo("KAKAO_OIDC");
        assertThat(cached.getProviderType()).isEqualTo(ProviderConfig.ProviderType.STANDARD_OIDC);
        assertThat(cached.isActive()).isTrue();

        ResponseEntity<String> r2 = authorize("KAKAO_OIDC");   // 캐시 적중 경로
        assertThat(r2.getStatusCode().value()).as("cache hit, body=%s", r2.getBody()).isEqualTo(302);
        assertThat(r2.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(KC_AUTH);
    }

    @Test
    @DisplayName("provider_config 행이 없는 제공자(keycloak): 휴리스틱으로 302, 빈 결과는 캐시하지 않는다")
    void providerWithoutConfigRow_fallsBack_notCached() {
        ResponseEntity<String> r = authorize("keycloak");
        assertThat(r.getStatusCode().value()).as("body=%s", r.getBody()).isEqualTo(302);
        assertThat(r.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(KC_AUTH);
        assertThat(cacheManager.getCache(CACHE).get("keycloak")).as("빈 Optional 은 캐시하지 않는다").isNull();

        ResponseEntity<String> again = authorize("keycloak");
        assertThat(again.getStatusCode().value()).as("body=%s", again.getBody()).isEqualTo(302);
    }

    @Test
    @DisplayName("broker:keycloak 끝-끝: 진입 → 브로커 시작(gate auth-url) → gate 완료 콜백 → 바인드(쿠키) → continue → 콜백(ticketId) → verify APPROVED")
    void brokerLoginEndToEnd() {
        // ① 진입 → 브로커 시작 주소 (returnUrl = hub 자신의 continue)
        ResponseEntity<String> r1 = http.getForEntity(URI.create(base + "/api/v1/handoff/login?service=" + AGENCY
                + "&callback=https%3A%2F%2Fagency.example.org%2Fcb&provider=broker%3Akeycloak&state=s1"), String.class);
        assertThat(r1.getStatusCode().value()).as("body=%s", r1.getBody()).isEqualTo(302);
        String hop = r1.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(hop).startsWith(PUBLIC_URL + "/api/v1/broker/keycloak/authorize?returnUrl=").contains("&requestedLevel=L1");
        String continueUrl = URLDecoder.decode(hop.substring(hop.indexOf("returnUrl=") + 10, hop.indexOf("&requestedLevel=")), StandardCharsets.UTF_8);
        assertThat(continueUrl).startsWith(PUBLIC_URL + "/api/v1/handoff/login/continue?req=");

        // ② 브로커 시작 → hub 가 gate 에 authorization URL 을 묻고 302 (종전: provider_config 캐시 SpEL 오류로 500)
        ResponseEntity<String> r2 = http.getForEntity(URI.create(base + hop.substring(PUBLIC_URL.length())), String.class);
        assertThat(r2.getStatusCode().value()).as("body=%s", r2.getBody()).isEqualTo(302);
        assertThat(r2.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(KC_AUTH);
        verify(postRequestedFor(urlPathEqualTo("/api/v1/oidc/keycloak/auth-url")).withRequestBody(containing("\"returnUrl\":\"" + continueUrl + "\"")));

        // ③ [브라우저가 Keycloak 에서 로그인 → gate 콜백] gate 가 hub 에 완료를 알린다 — gate 와 같은 규격(내부 서명, EXTERNAL_SUB 주체 키)
        String cid = "cid-broker-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"authResultId\":\"ar-broker-1\",\"identifierHash\":\"ih-broker-1\",\"authLevel\":\"L1\",\"providerCode\":\"KAKAO_OIDC\","
                + "\"correlationId\":\"" + cid + "\",\"returnUrl\":\"" + continueUrl + "\","
                + "\"subjectScheme\":\"EXTERNAL_SUB\",\"subjectKey\":\"kc-sub-broker-1\",\"idpSub\":\"kc-sub-broker-1\",\"idpSid\":\"kc-sid-1\"}";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Correlation-Id", cid);
        h.set("X-Internal-Caller", "idem-gate");
        h.set("X-Internal-Sig", CryptoProviders.current().hmacSha256Hex(SIG_SECRET.getBytes(StandardCharsets.UTF_8),
                cid + ":" + (System.currentTimeMillis() / 1000L)));
        ResponseEntity<String> r3 = http.exchange(base + "/api/internal/v1/oidc/complete", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertThat(r3.getStatusCode().value()).as("body=%s", r3.getBody()).isEqualTo(200);   // 종전: 400 INVALID_RETURN_URL (화이트리스트)
        String bind = jsonString(r3.getBody(), "redirectUrl");
        assertThat(bind).startsWith(PUBLIC_URL + "/api/v1/fe-session/bind?code=");
        verify(postRequestedFor(urlPathEqualTo("/api/v1/internal/users/register-subject")).withRequestBody(containing("EXTERNAL_SUB")));

        // ④ 브라우저: 바인드 → FE 세션 쿠키 + continue 로 302
        ResponseEntity<String> r4 = http.getForEntity(URI.create(base + bind.substring(PUBLIC_URL.length())), String.class);
        assertThat(r4.getStatusCode().value()).as("body=%s", r4.getBody()).isEqualTo(302);
        assertThat(r4.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(continueUrl);
        String setCookie = String.join(";", r4.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
        assertThat(setCookie).contains(FeSessionCookie.NAME + "=").contains("HttpOnly");
        String cookie = setCookie.substring(setCookie.indexOf(FeSessionCookie.NAME), setCookie.indexOf(';', setCookie.indexOf(FeSessionCookie.NAME)));

        // ⑤ continue(쿠키) → 발급 → 기관 콜백 ?ticketId=&state=
        HttpHeaders ch = new HttpHeaders();
        ch.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> r5 = http.exchange(URI.create(base + continueUrl.substring(PUBLIC_URL.length())), HttpMethod.GET, new HttpEntity<>(ch), String.class);
        assertThat(r5.getStatusCode().value()).as("body=%s", r5.getBody()).isEqualTo(302);
        String cb = r5.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(cb).startsWith(CB + "?ticketId=").endsWith("&state=s1");
        String ticketId = cb.substring(cb.indexOf("ticketId=") + 9, cb.indexOf("&state"));

        // ⑥ 기관 서버 간 verify → APPROVED(주체 = 기관별 DI), 감사 이력에 완료 콜백의 authResultId
        ResponseEntity<String> v = verifyTicket(ticketId);
        assertThat(v.getStatusCode().value()).as("body=%s", v.getBody()).isEqualTo(200);
        assertThat(v.getBody()).contains("\"state\":\"APPROVED\"").contains("di-broker-login");
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT auth_result_id, state FROM idem_hub.handoff_audit WHERE ticket_id = ?", ticketId);
        assertThat(audit.get("auth_result_id")).isEqualTo("ar-broker-1");
        assertThat(audit.get("state")).isEqualTo("CONSUMED");
    }

    private ResponseEntity<String> authorize(String provider) {
        return http.getForEntity(URI.create(base + "/api/v1/broker/" + provider + "/authorize?returnUrl="
                + URLEncoder.encode("https://agency.example.org/return", StandardCharsets.UTF_8)), String.class);
    }

    private ResponseEntity<String> verifyTicket(String ticketId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Agency-Code", AGENCY);
        h.set("X-Agency-Key", KEY);
        h.set("X-Correlation-Id", UUID.randomUUID().toString());
        return http.exchange(base + "/api/v1/handoff/verify", HttpMethod.POST,
                new HttpEntity<>("{\"ticketId\":\"" + ticketId + "\"}", h), String.class);
    }

    /** 작은 JSON 응답에서 문자열 값 하나 — 테스트 전용 */
    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        assertThat(i).as("%s in %s", key, json).isGreaterThanOrEqualTo(0);
        int s = i + needle.length();
        return json.substring(s, json.indexOf('"', s));
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
