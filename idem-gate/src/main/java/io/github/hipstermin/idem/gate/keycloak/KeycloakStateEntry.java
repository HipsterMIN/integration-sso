package io.github.hipstermin.idem.gate.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

/**
 * Keycloak OIDC Authorization Code Flow — Redis state 엔트리
 *
 * <p>Authorization URL 발급 시 생성 → Keycloak callback 수신 시 검증 후 1회 소비(삭제).
 * provider 필드를 추가하여 멀티 provider(kakao/naver/pass/gpki) 흐름을 단일 callback URI 로 처리한다.
 *
 * <p>Redis 키: qsign:oidc:state:{state}
 */
@Getter
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeycloakStateEntry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CSRF 방어용 opaque 랜덤 값 (Keycloak state 파라미터와 일치해야 함) */
    private final String state;

    /** ID Token replay attack 방지 nonce */
    private final String nonce;

    /** 전체 흐름 추적 ID (correlationId) */
    private final String correlationId;

    /** 인증 완료 후 이동할 기관 returnUrl */
    private final String returnUrl;

    /** ido 가 요청한 인증 수준 (L1 / L2 / L3) */
    private final String requestedLevel;

    /**
     * 최초 auth-url 요청 시 전달받은 provider 식별자 (예: "kakao", "naver")
     * Keycloak callback 에서 providerCode 를 결정할 때 사용한다.
     */
    private final String provider;

    // ── JSON 직렬화 유틸 ────────────────────────────────────────────────────

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("KeycloakStateEntry 직렬화 실패", e);
        }
    }

    public static KeycloakStateEntry fromJson(String json) {
        try {
            return MAPPER.readValue(json, KeycloakStateEntry.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("KeycloakStateEntry 역직렬화 실패: " + json, e);
        }
    }
}
