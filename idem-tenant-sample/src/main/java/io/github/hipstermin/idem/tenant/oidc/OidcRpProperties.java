package io.github.hipstermin.idem.tenant.oidc;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 기관 샘플의 표준 OIDC RP 설정 (S6 PR-2) — {@code idem.sample.oidc.*}.
 *
 * <p>기관이 Idem 에서 받는 것은 {@code issuer·clientId·clientSecret} 셋뿐이다. 나머지는 {@code {issuer}/.well-known/openid-configuration} 이 알려 준다.
 * {@code idem.sample.protocol=OIDC_RP} 일 때만 {@code /agency/oidc/**} 가 뜻을 가진다(Handoff 진입은 그대로 남아 있다).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "idem.sample.oidc")
public class OidcRpProperties {
    private String issuer = "http://localhost:8081/realms/idem";
    private String clientId = "";
    private String clientSecret = "";
    /** Idem 프로파일 {@code protocol.oidc.redirectUris} 에 등록한 값과 정확히 같아야 한다. */
    private String redirectUri = "http://localhost:8084/agency/oidc/callback";
    private String postLogoutRedirectUri = "http://localhost:8084/";
    private List<String> scopes = List.of("openid", "profile", "email");
    private int stateTtlSeconds = 300;
    private int httpTimeoutMs = 5000;
}
