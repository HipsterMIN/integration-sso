package io.github.hipstermin.idem.hub.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 기관 연동 유형 — Handoff 발급 이후 후처리 방식을 결정한다 (설계서 §8절).
 *
 * <p>S1(범용화, {@code docs/generalization-plan.md})에서 문자열 비교를 걷어내고 타입으로 고정했다.
 * DB 컬럼 {@code agency_meta.integration_type} 의 CHECK 제약과 이 열거형은 항상 같은 값 집합을 가져야 한다.
 * 미지 값은 더 이상 DIRECT 로 조용히 폴백하지 않고 {@link #from(String)} 에서 거부한다.
 *
 * <ul>
 *   <li>{@link #DIRECT} — 후처리 없음. 기관이 직접 verify 호출</li>
 *   <li>{@link #BRIDGE} — Bridge 서버에 Payload 를 미리 푸시 ({@code bridge_endpoint})</li>
 *   <li>{@link #APACHE_GATE} — 게이트웨이(idem-agent)에 세션 헤더 사전 등록 ({@code apache_gate_endpoint})</li>
 *   <li>{@link #INTERNAL_SSO} — 기관 SSO 도메인 쿠키 세션 사전 등록 ({@code sso_domain})</li>
 *   <li>{@link #OIDC_RP} — S6 표준 프로토콜. 기관은 OIDC Relying Party 로 Idem(gate 가 앞에 선 Keycloak)에 붙는다.
 *       Handoff 티켓을 발급하지 않으며(E-IDO-121), Keycloak client 는 Idem 이 프로파일에서 프로비저닝한다</li>
 * </ul>
 */
public enum IntegrationType {
    DIRECT,
    BRIDGE,
    APACHE_GATE,
    INTERNAL_SSO,
    OIDC_RP;

    /** 값이 지정되지 않은 기관의 기본 유형. */
    public static final IntegrationType DEFAULT = DIRECT;

    /** Handoff 티켓 발급 경로를 쓰는 유형인가 — {@link #OIDC_RP} 는 표준 OIDC 로만 로그인한다. */
    public boolean usesHandoff() {
        return this != OIDC_RP;
    }

    /**
     * 문자열 → 유형. 대소문자·양끝 공백은 허용하되 미지 값은 거부한다.
     *
     * @throws IllegalArgumentException 비어 있거나 허용 목록 밖의 값
     */
    public static IntegrationType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("integrationType 이 비어 있습니다. 허용값: " + allowedValues());
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "알 수 없는 integrationType '" + raw + "'. 허용값: " + allowedValues());
        }
    }

    /** null·공백이면 {@link #DEFAULT}, 그 외에는 {@link #from(String)} 과 같다. */
    public static IntegrationType fromOrDefault(String raw) {
        return (raw == null || raw.isBlank()) ? DEFAULT : from(raw);
    }

    public static String allowedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
