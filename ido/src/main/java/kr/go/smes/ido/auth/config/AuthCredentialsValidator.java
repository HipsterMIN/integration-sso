package kr.go.smes.ido.auth.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 본인인증(NICE/OACX) 자격증명 부팅 검증 — 운영(prod) 프로파일 전용.
 *
 * <p>문제: {@code NICE_CLIENT_ID/SECRET}, {@code OACX_PROVIDER_KEY_PATH}가 비어 있어도
 * 애플리케이션은 정상 기동하지만, 본인인증 호출 시점에 <b>조용히 실패</b>한다
 * (silent disable). 운영에서 이는 "되는 줄 알았는데 안 되는" 장애로 이어진다.
 *
 * <p>해결: prod 기동 시 자격증명이 비어 있으면 <b>fail-fast(기동 차단)</b>하여
 * 설정 누락을 배포 시점에 즉시 드러낸다(CrashLoopBackOff → 운영자 알림).
 *
 * <p>예외 케이스: 특정 배포가 의도적으로 본인인증을 쓰지 않는 경우
 * {@code ido.auth.allow-missing-credentials=true}로 명시적 비활성화(기동 계속).
 * (q-im {@code qim.crypto.*.allow-empty-*} escape hatch와 동일 패턴.)
 *
 * <p>본 컴포넌트는 {@code @Profile("prod")}이므로 dev/test 컨텍스트에는 로드되지 않는다.
 */
@Slf4j
@Component
@Profile("prod")
public class AuthCredentialsValidator {

    public AuthCredentialsValidator(
            AuthProperties props,
            @Value("${ido.auth.allow-missing-credentials:false}") boolean allowMissing) {
        validate(props, allowMissing);
    }

    /** 패키지 가시성(static) — 단위 테스트에서 직접 호출 가능. */
    static void validate(AuthProperties props, boolean allowMissing) {
        List<String> missing = new ArrayList<>();

        AuthProperties.Nice nice = props == null ? null : props.nice();
        if (nice == null || isBlank(nice.clientId()))     missing.add("NICE_CLIENT_ID");
        if (nice == null || isBlank(nice.clientSecret()))  missing.add("NICE_CLIENT_SECRET");

        AuthProperties.Oacx oacx = props == null ? null : props.oacx();
        if (oacx == null || isBlank(oacx.providerKeyPath())) missing.add("OACX_PROVIDER_KEY_PATH");

        if (missing.isEmpty()) {
            return;
        }
        if (allowMissing) {
            log.warn("[AuthCredentials] 운영 프로파일이나 본인인증 자격증명 미설정: {} — "
                    + "ido.auth.allow-missing-credentials=true 로 기동 계속(해당 본인인증 비활성).", missing);
            return;
        }
        throw new IllegalStateException(
                "운영(prod) 기동 차단 — 본인인증 자격증명 미설정: " + missing
                        + ". 환경변수를 설정하거나, 본인인증을 쓰지 않는 배포라면 "
                        + "ido.auth.allow-missing-credentials=true 로 명시적 비활성화하세요.");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
