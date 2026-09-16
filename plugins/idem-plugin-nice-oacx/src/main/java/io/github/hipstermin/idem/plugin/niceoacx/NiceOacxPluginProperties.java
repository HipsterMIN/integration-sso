package io.github.hipstermin.idem.plugin.niceoacx;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 플러그인 설정 — {@code idem.plugins.nice-oacx.*}.
 *
 * <p>자격증명(NICE client-id/secret, OACX provider key 경로)은 여기 두지 않는다. 기존 {@code ido.auth.nice.*},
 * {@code ido.auth.oacx.*} 환경변수 계약을 P2 본작업에서 {@code idem.plugins.nice-oacx.*} 로 옮길 때 함께 이전한다.
 */
@ConfigurationProperties(prefix = "idem.plugins.nice-oacx")
public class NiceOacxPluginProperties {

    /** 플러그인 활성화. 코어에는 NICE 어댑터가 없으므로(S5a) 이 스위치 하나로 NICE_PHONE·OACX_EASYSIGN 제공자가 켜지고 꺼진다. */
    private boolean enabled = false;

    /** EzAuth 위젯 스크립트 URL. 플러그인 정적 자산 경로가 기본값. */
    private String widgetScriptUrl = NiceEzAuthWidget.DEFAULT_SCRIPT_URL;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWidgetScriptUrl() { return widgetScriptUrl; }
    public void setWidgetScriptUrl(String widgetScriptUrl) { this.widgetScriptUrl = widgetScriptUrl; }
}
