package io.github.hipstermin.idem.hub.serviceprofile;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.identity.MaskingRule;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * Service Profile — 연동기관(테넌트)별 선언적 설정의 단일 원천 (S2, {@code docs/generalization-plan.md} §2.1).
 *
 * <p>JSON Schema {@code service-profile/service-profile.v1.schema.json} 과 1:1 로 대응하며, 저장은
 * {@code agency_meta.profile JSONB} 에 한다. 기존 컬럼(official_name·min_auth_level·integration_type·…)은
 * 이 문서의 <b>투영</b>이다 — 쓰기는 프로파일을 거쳐 컬럼으로 내려가고, 읽기는 아직 컬럼이 런타임 진실이다
 * (S3·S4 에서 읽기 경로를 프로파일로 옮긴다).
 *
 * <p>동작을 갖는 필드: 컬럼 대응 항목(S2) · {@code policy.*}(S3 정책 엔진) · {@code identity.*}(S4 식별자·속성 계약).
 * {@code protocol.security}, {@code policy.session}, {@code limits.tps}, {@code ui} 는 아직 저장·조회만 되고
 * 뒤 단계(S6·S7)에서 의미를 갖는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder(toBuilder = true)
public record ServiceProfile(
        Integer schemaVersion,
        Service service,
        Protocol protocol,
        Identity identity,
        Policy policy,
        Limits limits,
        Ui ui) {

    public static final int SCHEMA_VERSION = 1;
    /** 설치본의 기본 Tenant(Realm) — V22 시드 */
    public static final String DEFAULT_TENANT = "DEFAULT";

    public enum ServiceStatus { ACTIVE, INACTIVE }

    /**
     * Service(기관·클라이언트) 식별 (S4b — 종전 {@code tenant} 블록).
     *
     * @param tenant 소속 Tenant(Realm) 코드 — null 이면 {@link #DEFAULT_TENANT}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Service(String code, String name, ServiceStatus status, String tenant) {
        public Service(String code, String name, ServiceStatus status) {
            this(code, name, status, null);
        }

        @JsonIgnore
        public String tenantOrDefault() {
            return tenant == null || tenant.isBlank() ? DEFAULT_TENANT : tenant;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Protocol(IntegrationType type, Endpoints endpoints, Security security, Oidc oidc) {
        @JsonIgnore
        public IntegrationType typeOrDefault() {
            return type != null ? type : IntegrationType.DEFAULT;
        }
    }

    /**
     * OIDC_RP 전용 설정 (S6) — Keycloak client 프로비저닝의 입력. PKCE S256 은 플랫폼 규칙이라 항목이 없다.
     *
     * @param redirectUris           기관 RP 의 redirect_uri (정확 일치)
     * @param postLogoutRedirectUris RP-Initiated Logout 후 복귀 URI
     * @param backchannelLogoutUri   Back-Channel Logout 수신 URL (선택)
     * @param clientAuthMethod       token endpoint 인증 방식 — null 이면 CLIENT_SECRET_BASIC
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Oidc(List<String> redirectUris, List<String> postLogoutRedirectUris,
                       String backchannelLogoutUri, String clientAuthMethod) {
        public static final String AUTH_BASIC = "CLIENT_SECRET_BASIC";
        public static final String AUTH_POST  = "CLIENT_SECRET_POST";

        @JsonIgnore
        public String clientAuthMethodOrDefault() {
            return clientAuthMethod == null || clientAuthMethod.isBlank() ? AUTH_BASIC : clientAuthMethod;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    /** D3: {@code ssoEntry} — CAST(기관 간 SSO) 진입점. 종전에는 코드가 기관 코드로 URL 을 지어냈다(고정 도메인). */
    public record Endpoints(List<String> callbackWhitelist, String bridge, String apacheGate, String ssoDomain, String ssoEntry) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Security(Boolean mtlsRequired, List<String> ipAllowlist) {}

    /**
     * 식별자·속성 계약 (S4).
     *
     * @param subjectScheme    Handoff {@code subject.agencySubjectId} 의 종류 — null 이면 {@link SubjectScheme#DEFAULT}
     * @param attributes       기관에 전달하는 속성 선택 — null·빈 목록은 "전달 없음"(최소 권한)
     * @param attributeMapping 속성 이름 → 기관 측 필드명
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Identity(SubjectScheme subjectScheme, List<AttributeSelection> attributes,
                           Map<String, String> attributeMapping) {

        @JsonIgnore
        public SubjectScheme subjectSchemeOrDefault() {
            return subjectScheme != null ? subjectScheme : SubjectScheme.DEFAULT;
        }

        /** 선언한 이름 목록 (컬럼 {@code allowed_attributes} 투영용). null 이면 null. */
        @JsonIgnore
        public List<String> attributeNames() {
            return attributes == null ? null : attributes.stream().map(AttributeSelection::name).toList();
        }

        /** 이름 목록만으로 만든다 (레거시 컬럼 → 프로파일). */
        public static List<AttributeSelection> selectionsOf(List<String> names) {
            return names == null ? null : names.stream().map(AttributeSelection::of).toList();
        }
    }

    /**
     * 속성 선택 항목 — JSON 에서는 이름 문자열({@code "name_masked"}) 또는 옵션 객체
     * ({@code {"name":"email","required":true,"masking":"NONE"}}) 둘 다 받는다. 옵션이 없으면 문자열로 내보낸다.
     *
     * @param name     카탈로그 정규 이름 또는 별칭 — 출력 키의 기본값이기도 하다
     * @param required 값을 얻지 못하면 Handoff 를 거부할지 (기본 false)
     * @param masking  카탈로그 기본 마스킹을 덮어쓸 규칙 (기본 null = 카탈로그 값)
     */
    @JsonSerialize(using = AttributeSelection.Serializer.class)
    @JsonDeserialize(using = AttributeSelection.Deserializer.class)
    public record AttributeSelection(String name, Boolean required, MaskingRule masking) {

        public static AttributeSelection of(String name) {
            return new AttributeSelection(name, null, null);
        }

        @JsonIgnore
        public boolean isRequired() {
            return Boolean.TRUE.equals(required);
        }

        static final class Serializer extends JsonSerializer<AttributeSelection> {
            @Override
            public void serialize(AttributeSelection v, JsonGenerator gen, SerializerProvider sp) throws IOException {
                if (v.required() == null && v.masking() == null) {
                    gen.writeString(v.name());
                    return;
                }
                gen.writeStartObject();
                gen.writeStringField("name", v.name());
                if (v.required() != null) gen.writeBooleanField("required", v.required());
                if (v.masking() != null) gen.writeStringField("masking", v.masking().name());
                gen.writeEndObject();
            }
        }

        static final class Deserializer extends JsonDeserializer<AttributeSelection> {
            @Override
            public AttributeSelection deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                JsonNode n = p.getCodec().readTree(p);
                if (n.isTextual()) return of(n.asText());
                if (n.isObject()) {
                    String name = n.hasNonNull("name") ? n.get("name").asText() : null;
                    Boolean required = n.hasNonNull("required") ? n.get("required").asBoolean() : null;
                    MaskingRule masking = n.hasNonNull("masking")
                            ? MaskingRule.valueOf(n.get("masking").asText().trim().toUpperCase(java.util.Locale.ROOT))
                            : null;
                    return new AttributeSelection(name, required, masking);
                }
                throw ctx.weirdNativeValueException(n, AttributeSelection.class);
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Policy(AuthResult.AuthLevel minAuthLevel, String policyVersion, List<String> allowedProviders,
                         Session session, List<MaintenanceWindow> maintenance, List<RuleRef> rules,
                         Assignment assignment) {}
    // 주의: 호환용 보조 생성자를 두지 않는다 — Jackson 이 레코드의 정식 생성자 대신 그것을 골라 assignment 를 조용히 버렸다(S8-b 통합 테스트로 발견)

    /**
     * S8-b 할당 정책. {@code required=true} 면 idem-authz 에 이 Service 할당이 있는 사용자만 발급받는다.
     * 미할당은 {@code selfSignup=true} 일 때만 GUEST 로 통과(기관이 가입·연결 후 할당을 보고), 아니면 E-IDO-120 거부.
     * 블록이 없으면(기본) 할당을 보지 않는다 — 레거시 GUEST 의미(주체 식별자 없음) 유지.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Assignment(Boolean required, Boolean selfSignup) {
        // 헬퍼 이름을 getter 규칙(isXxx/getXxx)으로 짓지 않는다 — @JsonIgnore 를 붙이면 Jackson 이 'required' 속성 자체를
        // 무시해 역직렬화에서 값이 사라진다(S8-b 통합 테스트로 발견)
        public boolean requiresAssignment() { return Boolean.TRUE.equals(required); }
        public boolean allowsSelfSignup() { return Boolean.TRUE.equals(selfSignup); }
    }

    /** 프로파일이 지정하는 규칙 — 내장 규칙의 파라미터 또는 커스텀 규칙(에디션 플러그인) 활성화 (S3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record RuleRef(String type, Map<String, Object> params) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Session(Integer idleMinutes, Integer absoluteMinutes, Integer concurrent) {}

    /** 키 이름은 기존 {@code agency_meta.maintenance_windows} JSON 과 같다 (dayOfWeek/startTime/endTime). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record MaintenanceWindow(String dayOfWeek, String startTime, String endTime) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Limits(Integer tps, Integer daily) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Ui(String brandName, String logoUrl, String locale) {}

    /** 활성 여부 — status 가 없으면 ACTIVE 로 본다. (파생 값 — JSON 에는 싣지 않는다) */
    @JsonIgnore
    public boolean isActive() {
        return service == null || service.status() == null || service.status() == ServiceStatus.ACTIVE;
    }
}
