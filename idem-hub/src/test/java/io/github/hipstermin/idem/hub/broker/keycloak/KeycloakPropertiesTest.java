package io.github.hipstermin.idem.hub.broker.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 1.1.5 G2-12: 매핑 없는 브로커 이름은 kc_idp_hint 를 만들지 않는다 (Keycloak 26 이 로그인마다 WARN 을 남기던 값) */
@DisplayName("KeycloakProperties.resolveIdpHint — idp-hint-mapping 규칙")
class KeycloakPropertiesTest {

    private KeycloakProperties props() {
        KeycloakProperties p = new KeycloakProperties();
        p.setIdpHintMapping(Map.of("kakao", "social-kakao", "naver", "social-naver"));
        return p;
    }

    @Test
    @DisplayName("매핑된 이름은 별칭으로, 대문자 코드는 소문자로 다시 찾는다")
    void mapped() {
        assertThat(props().resolveIdpHint("kakao")).isEqualTo("social-kakao");
        assertThat(props().resolveIdpHint("NAVER")).isEqualTo("social-naver");
    }

    @Test
    @DisplayName("매핑 없는 이름(keycloak 등)·빈 값·null 은 null — 호출자가 kc_idp_hint 를 붙이지 않는다")
    void unmapped() {
        assertThat(props().resolveIdpHint("keycloak")).isNull();
        assertThat(props().resolveIdpHint("")).isNull();
        assertThat(props().resolveIdpHint(null)).isNull();
    }
}
