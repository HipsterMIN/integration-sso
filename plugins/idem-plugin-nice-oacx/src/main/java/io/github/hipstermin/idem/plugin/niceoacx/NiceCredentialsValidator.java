package io.github.hipstermin.idem.plugin.niceoacx;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영(prod) 프로파일 부팅 검증 — 플러그인이 켜졌는데 자격증명이 없으면 기동을 막는다 (S5a, 종전 {@code AuthCredentialsValidator}).
 * {@code ido.auth.allow-missing-credentials=true} 면 경고 후 계속(해당 제공자는 호출 시 실패).
 */
@Slf4j
public final class NiceCredentialsValidator {

    private NiceCredentialsValidator() {}

    public static void validate(NiceProperties nice, OacxProperties oacx, boolean oacxAvailable, boolean allowMissing) {
        List<String> missing = new ArrayList<>();
        if (nice == null || nice.getClientId().isBlank())     missing.add("NICE_CLIENT_ID");
        if (nice == null || nice.getClientSecret().isBlank())  missing.add("NICE_CLIENT_SECRET");
        if (oacxAvailable && (oacx == null || !oacx.isConfigured())) missing.add("OACX_PROVIDER_KEY_PATH");
        if (missing.isEmpty()) return;
        if (allowMissing) {
            log.warn("[NicePlugin] 운영 프로파일이나 본인인증 자격증명 미설정: {} — ido.auth.allow-missing-credentials=true 로 기동 계속(해당 본인인증 비활성).", missing);
            return;
        }
        throw new IllegalStateException("운영(prod) 기동 차단 — 본인인증 자격증명 미설정: " + missing
                + ". 환경변수를 설정하거나, 본인인증을 쓰지 않는 배포라면 ido.auth.allow-missing-credentials=true 로 명시적 비활성화하세요.");
    }
}
