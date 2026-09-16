package io.github.hipstermin.idem.plugin.niceoacx;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OACX 전자서명 중계모듈 설정 — {@code ido.auth.oacx.*} (환경변수 {@code OACX_PROVIDER_KEY_PATH}, {@code OACX_DEBUG_MODE}). */
@ConfigurationProperties(prefix = "ido.auth.oacx")
public class OacxProperties {

    private String providerKeyPath = "";
    private boolean debugMode = false;

    public String getProviderKeyPath() { return providerKeyPath; }
    public void setProviderKeyPath(String providerKeyPath) { this.providerKeyPath = providerKeyPath == null ? "" : providerKeyPath; }
    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }

    public boolean isConfigured() {
        return !providerKeyPath.isBlank();
    }
}
