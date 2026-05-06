package kr.go.smes.ido.broker.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

/**
 * IdO OIDC state 엔트리 (문서 §5-2)
 *
 * <p>Keycloak 도입 후 q-sign의 {@code OidcStateEntry} 역할이 IdO로 이동.
 * Keycloak → ido /api/v1/broker/callback 콜백 수신 시 state로 검증·소비.
 *
 * <p>Redis 키: {@code oidc:state:{state}} → JSON (TTL: state-ttl-seconds)
 */
@Getter
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdoOidcStateEntry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CSRF 방어용 opaque 랜덤 값 (Keycloak redirect state param) */
    private final String state;

    /** idToken replay 방지 nonce (Keycloak이 idToken에 포함해야 함 — §10-5) */
    private final String nonce;

    /** 전체 흐름 추적 ID */
    private final String correlationId;

    /** 인증 완료 후 이동할 기관 URL */
    private final String returnUrl;

    /** 요청한 인증 수준 (L1 / L2 / L3) */
    private final String requestedLevel;

    /** 인증 수단 provider (kakao / naver 등 — kc_idp_hint 원본) */
    private final String provider;

    // ── JSON 직렬화 유틸 ──────────────────────────────────────────────────

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("IdoOidcStateEntry 직렬화 실패", e);
        }
    }

    public static IdoOidcStateEntry fromJson(String json) {
        try {
            return MAPPER.readValue(json, IdoOidcStateEntry.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("IdoOidcStateEntry 역직렬화 실패: " + json, e);
        }
    }
}
