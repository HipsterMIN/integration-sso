package io.github.hipstermin.idem.plugin.niceoacx;

import io.github.hipstermin.idem.common.spi.identity.AuthWidgetDescriptor;
import java.util.Map;

/**
 * EzAuth(간편인증) 위젯 기술자.
 *
 * <p>P2 본작업에서 {@code idem-console/frontend/public/ezauth/**}(109파일)를 이 플러그인의
 * {@code static/plugins/nice-oacx/ezauth/} 로 옮기고, FE 는 {@code index.html.ejs} 의 고정 {@code <script>} 대신
 * {@code GET /api/v1/auth/providers} 가 내려주는 이 기술자로 스크립트를 동적 로드한다({@code useAuthWidget}).
 */
public final class NiceEzAuthWidget {

    public static final String DEFAULT_SCRIPT_URL = "/plugins/nice-oacx/ezauth/js/EzAuth.bundle.js";
    public static final String GLOBAL_NAME = "EzAuth";

    private NiceEzAuthWidget() {}

    public static AuthWidgetDescriptor descriptor(String scriptUrl) {
        return new AuthWidgetDescriptor(scriptUrl == null ? DEFAULT_SCRIPT_URL : scriptUrl, GLOBAL_NAME,
                Map.of("initFn", "makeEzauthSimple"));
    }
}
