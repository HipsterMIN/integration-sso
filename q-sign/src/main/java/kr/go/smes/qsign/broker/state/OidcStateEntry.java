package kr.go.smes.qsign.broker.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

/**
 * OIDC Authorization Code Flow 에서 Redis 에 저장하는 state 엔트리
 *
 * <p>Authorization URL 발급 시 생성 → callback 수신 시 검증 후 소비
 */
@Getter
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class OidcStateEntry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CSRF 방어용 opaque 랜덤 값 (redirect_uri query param) */
    private final String state;

    /** idToken replay 방지 nonce (id_token claims 검증 시 사용) */
    private final String nonce;

    /** 전체 흐름 추적 ID */
    private final String correlationId;

    /** 인증 완료 후 이동할 기관 URL */
    private final String returnUrl;

    /** 요청한 인증 수준 (L1 / L2 / L3) */
    private final String requestedLevel;

    // ── JSON 직렬화 유틸 ────────────────────────────────────────────────────

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OidcStateEntry 직렬화 실패", e);
        }
    }

    public static OidcStateEntry fromJson(String json) {
        try {
            return MAPPER.readValue(json, OidcStateEntry.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OidcStateEntry 역직렬화 실패: " + json, e);
        }
    }
}
