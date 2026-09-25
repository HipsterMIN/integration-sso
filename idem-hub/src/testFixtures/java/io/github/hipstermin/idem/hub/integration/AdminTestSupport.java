package io.github.hipstermin.idem.hub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.admin.auth.AdminProperties;
import io.github.hipstermin.idem.hub.admin.auth.TotpService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestOperations;

/**
 * S7: 통합 테스트용 관리자 로그인 — 부트스트랩 계정(테스트 yml)으로 실제 로그인·TOTP 등록/검증을 거쳐 세션 쿠키를 얻는다.
 * TOTP 비밀은 첫 등록 때 JVM 안에 기억한다(같은 DB 컨테이너를 쓰는 다음 컨텍스트가 재사용).
 */
public final class AdminTestSupport {

    public static final String USERNAME = "admin";
    public static final String PASSWORD = "Idem-Ops-Test-1!";
    public static final String CSRF = "X-Requested-With";

    private static final Map<String, String> TOTP_SECRETS = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdminTestSupport() {}

    /** 로그인 → 세션 쿠키 값 */
    public static String login(TestRestTemplate rest, String baseUrl, String username, String password) {
        return login(rest.getRestTemplate(), baseUrl, username, password);
    }

    /** 로그인 → 세션 쿠키 값 (4xx 를 예외로 던지지 않는 RestOperations) */
    public static String login(RestOperations rest, String baseUrl, String username, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set(CSRF, "test");
        ResponseEntity<String> res = rest.exchange(baseUrl + "/api/v1/admin/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", h), String.class);
        if (res.getStatusCode().value() != 200) throw new IllegalStateException("admin login " + res.getStatusCode() + ": " + res.getBody());
        JsonNode body = read(res.getBody());
        String status = body.path("status").asText();
        if ("OK".equals(status)) return cookieOf(res.getHeaders());
        String secret;
        if ("MFA_ENROLL_REQUIRED".equals(status)) {
            secret = body.path("secret").asText();
            TOTP_SECRETS.put(username, secret);
        } else {
            secret = TOTP_SECRETS.get(username);
            if (secret == null) throw new IllegalStateException("TOTP 비밀을 모른다 — 이 JVM 에서 등록된 적 없음 (username=" + username + ")");
        }
        TotpService totp = new TotpService(new AdminProperties());
        String code = totp.currentCode(secret, Instant.now());
        ResponseEntity<String> mfa = rest.exchange(baseUrl + "/api/v1/admin/auth/mfa", HttpMethod.POST,
                new HttpEntity<>("{\"mfaToken\":\"" + body.path("mfaToken").asText() + "\",\"code\":\"" + code + "\"}", h), String.class);
        if (mfa.getStatusCode().value() != 200) throw new IllegalStateException("admin mfa " + mfa.getStatusCode() + ": " + mfa.getBody());
        return cookieOf(mfa.getHeaders());
    }

    public static String cookieOf(HttpHeaders headers) {
        for (String c : headers.getOrEmpty(HttpHeaders.SET_COOKIE)) {
            if (c.startsWith("idemAdminSid=")) {
                String v = c.substring("idemAdminSid=".length());
                int semi = v.indexOf(';');
                return semi > 0 ? v.substring(0, semi) : v;
            }
        }
        throw new IllegalStateException("세션 쿠키 없음: " + headers.getOrEmpty(HttpHeaders.SET_COOKIE));
    }

    /** 부트스트랩 관리자로 로그인한 요청 헤더 (쿠키 + CSRF 헤더) */
    public static HttpHeaders headers(TestRestTemplate rest, String baseUrl) {
        return headers(rest.getRestTemplate(), baseUrl);
    }

    public static HttpHeaders headers(RestOperations rest, String baseUrl) {
        String sid = login(rest, baseUrl, USERNAME, PASSWORD);
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.COOKIE, "idemAdminSid=" + sid);
        h.set(CSRF, "test");
        return h;
    }

    public static String totpSecret(String username) { return TOTP_SECRETS.get(username); }

    private static JsonNode read(String s) {
        try { return MAPPER.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
