package io.github.hipstermin.idem.hub.serviceprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceProfileValidator — JSON Schema v1")
class ServiceProfileValidatorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ServiceProfileValidator validator = new ServiceProfileValidator();

    private JsonNode json(String s) throws Exception { return om.readTree(s); }

    static final String MINIMAL = """
            {"schemaVersion":1,
             "service":{"code":"AG_MIN","name":"최소 기관"},
             "protocol":{"type":"DIRECT"},
             "policy":{"minAuthLevel":"L1"}}
            """;

    @Test
    @DisplayName("필수 4개 블록만 있는 최소 프로파일은 유효하다")
    void minimalProfile_isValid() throws Exception {
        assertThat(validator.violations(json(MINIMAL))).isEmpty();
    }

    @Test
    @DisplayName("모든 블록을 채운 프로파일도 유효하다")
    void fullProfile_isValid() throws Exception {
        String full = """
                {"schemaVersion":1,
                 "service":{"code":"AG_FULL","name":"전체 기관","status":"ACTIVE"},
                 "protocol":{"type":"BRIDGE",
                             "endpoints":{"callbackWhitelist":["https://a.example.org/cb"],"bridge":"https://bridge.example.org/push","ssoDomain":".example.org"},
                             "security":{"mtlsRequired":true,"ipAllowlist":["10.0.0.0/8"]}},
                 "identity":{"attributes":["name_masked","mobile_masked"],"attributeMapping":{"name_masked":"userNm"}},
                 "policy":{"minAuthLevel":"L2","policyVersion":"1.3","allowedProviders":["NICE"],
                           "session":{"idleMinutes":30,"absoluteMinutes":480,"concurrent":1},
                           "maintenance":[{"dayOfWeek":"MON","startTime":"02:00","endTime":"04:00"}],
                           "assignment":{"required":true,"selfSignup":false}},
                 "limits":{"tps":50,"daily":100000},
                 "ui":{"brandName":"기관 A","logoUrl":"https://a.example.org/logo.png","locale":"ko"}}
                """;
        assertThat(validator.violations(json(full))).isEmpty();
    }

    @Test
    @DisplayName("필수 항목 누락·미지 속성·허용값 밖은 각각 위반으로 잡힌다")
    void violations_areReported() throws Exception {
        List<String> missingName = validator.violations(json(
                "{\"schemaVersion\":1,\"service\":{\"code\":\"AG\"},\"protocol\":{\"type\":\"DIRECT\"},\"policy\":{\"minAuthLevel\":\"L1\"}}"));
        assertThat(missingName).anySatisfy(m -> assertThat(m).contains("name"));

        List<String> unknown = validator.violations(json(
                "{\"schemaVersion\":1,\"service\":{\"code\":\"AG\",\"name\":\"x\"},\"protocol\":{\"type\":\"DIRECT\"},\"policy\":{\"minAuthLevel\":\"L1\"},\"foo\":1}"));
        assertThat(unknown).anySatisfy(m -> assertThat(m).contains("foo"));

        List<String> badType = validator.violations(json(
                "{\"schemaVersion\":1,\"service\":{\"code\":\"AG\",\"name\":\"x\"},\"protocol\":{\"type\":\"SAML\"},\"policy\":{\"minAuthLevel\":\"L1\"}}"));
        assertThat(badType).anySatisfy(m -> assertThat(m).contains("protocol.type"));

        List<String> badVersion = validator.violations(json(MINIMAL.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThat(badVersion).isNotEmpty();
    }

    @Test
    @DisplayName("S4 identity: 객체형 속성 선언·subjectScheme 은 유효, 카탈로그 밖 이름·중복·미선언 매핑·CI 스킴은 위반")
    void identityContract_semanticRules() throws Exception {
        String ok = MINIMAL.replace("\"policy\"", "\"identity\":{\"subjectScheme\":\"EMAIL\",\"attributes\":[\"name_masked\",{\"name\":\"email\",\"required\":true,\"masking\":\"NONE\"},\"qimUserId\"],\"attributeMapping\":{\"email\":\"mail\",\"qimUserId\":\"uid\"}},\"policy\"");
        assertThat(validator.violations(json(ok))).isEmpty();

        List<String> unknown = validator.violations(json(MINIMAL.replace("\"policy\"", "\"identity\":{\"attributes\":[\"not_in_catalog\"]},\"policy\"")));
        assertThat(unknown).singleElement().satisfies(m -> assertThat(m).contains("not_in_catalog").contains("name_masked"));

        List<String> dup = validator.violations(json(MINIMAL.replace("\"policy\"", "\"identity\":{\"attributes\":[\"qim_user_id\",\"qimUserId\"]},\"policy\"")));
        assertThat(dup).singleElement().satisfies(m -> assertThat(m).contains("중복"));

        List<String> mapping = validator.violations(json(MINIMAL.replace("\"policy\"", "\"identity\":{\"attributes\":[\"email\"],\"attributeMapping\":{\"phone\":\"tel\"}},\"policy\"")));
        assertThat(mapping).singleElement().satisfies(m -> assertThat(m).contains("attributeMapping").contains("phone"));

        List<String> ci = validator.violations(json(MINIMAL.replace("\"policy\"", "\"identity\":{\"subjectScheme\":\"CI\"},\"policy\"")));
        assertThat(ci).isNotEmpty();   // 스키마 enum 이 1차로 막는다

        List<String> badMasking = validator.violations(json(MINIMAL.replace("\"policy\"", "\"identity\":{\"attributes\":[{\"name\":\"email\",\"masking\":\"ROT13\"}]},\"policy\"")));
        assertThat(badMasking).isNotEmpty();
    }

    @Test
    @DisplayName("validateOrThrow 는 400(E-IDO-113) 과 위반 내용을 담아 던진다")
    void validateOrThrow_throwsPlatformException() throws Exception {
        JsonNode bad = json("{\"schemaVersion\":1,\"service\":{\"code\":\"AG\",\"name\":\"x\"},\"policy\":{\"minAuthLevel\":\"L9\"}}");

        assertThatThrownBy(() -> validator.validateOrThrow(bad, "cid-1"))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE))
                .hasMessageContaining("protocol")
                .hasMessageContaining("minAuthLevel");
    }

    @Test
    @DisplayName("스키마 원문을 노출한다 (콘솔 폼 생성용)")
    void schemaText_isExposed() {
        assertThat(validator.schemaText()).contains("Idem Service Profile v1");
    }
}
