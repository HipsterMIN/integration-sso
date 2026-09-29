package io.github.hipstermin.idem.hub.policy.rule;

import io.github.hipstermin.idem.common.domain.AuthResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.1: 규칙 할당(ATTRIBUTE) 판정에 쓰라고 hub 가 authz 로 보내는 <b>발급 컨텍스트</b>.
 *
 * <p>키는 이 두 개뿐이다 — {@code authLevel}(L1~L3), {@code providerCode}. 둘 다 플랫폼이 이미 아는 비-PII 값이고
 * 프로파일 identity 속성(이름·연락처 등)은 <b>보내지 않는다</b>(PII 를 인가 서비스에 흘리지 않는다). 키를 늘리려면 여기와
 * {@code docs/sso-agency-operations-guide.md} 규칙 할당 절을 같이 고친다.
 */
public final class AssignmentContext {

    public static final String AUTH_LEVEL    = "authLevel";
    public static final String PROVIDER_CODE = "providerCode";

    private AssignmentContext() {}

    public static Map<String, String> of(AuthResult.AuthLevel authLevel, String providerCode) {
        return of(authLevel != null ? authLevel.name() : null, providerCode);
    }

    public static Map<String, String> of(String authLevel, String providerCode) {
        Map<String, String> m = new LinkedHashMap<>();
        if (authLevel != null && !authLevel.isBlank()) m.put(AUTH_LEVEL, authLevel);
        if (providerCode != null && !providerCode.isBlank()) m.put(PROVIDER_CODE, providerCode);
        return m;
    }
}
