package io.github.hipstermin.idem.plugin.niceoacx;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NICE 휴대폰 본인인증 설정 — {@code ido.auth.nice.*} (환경변수 {@code NICE_CLIENT_ID} 등 계약은 그대로, S5a).
 *
 * <p>자격증명은 환경변수·Secret 으로만 주입한다. {@code base-url} 은 통합 테스트(WireMock)에서만 바꾼다.
 */
@ConfigurationProperties(prefix = "ido.auth.nice")
public class NiceProperties {

    public static final String DEFAULT_BASE_URL = "https://auth.niceid.co.kr";

    private String clientId = "";
    private String clientSecret = "";
    private String returnUrl = "";
    private int timeoutSeconds = 10;
    private String baseUrl = DEFAULT_BASE_URL;

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId == null ? "" : clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret == null ? "" : clientSecret; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl == null ? "" : returnUrl; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds <= 0 ? 10 : timeoutSeconds; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl; }

    public boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }
}
