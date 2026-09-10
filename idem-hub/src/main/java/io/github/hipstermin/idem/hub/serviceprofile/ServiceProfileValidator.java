package io.github.hipstermin.idem.hub.serviceprofile;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.AttributeCatalog;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Service Profile 검증기 — JSON Schema(S2) + 의미 규칙(S4: 속성 카탈로그·스킴).
 *
 * <p>스키마 파일은 저장소에 두고 Admin API·콘솔(S7)·마이그레이션이 같은 파일로 검증한다.
 * 스키마 버전이 늘면 {@code service-profile.v{n}.schema.json} 을 추가하고 마이그레이터 체인으로 올린다.
 */
@Component
public class ServiceProfileValidator {

    public static final String SCHEMA_PATH = "service-profile/service-profile.v1.schema.json";

    private final JsonSchema schema;
    private final String schemaText;

    public ServiceProfileValidator() {
        try (InputStream in = new ClassPathResource(SCHEMA_PATH).getInputStream()) {
            this.schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Service Profile 스키마를 읽을 수 없습니다: " + SCHEMA_PATH, e);
        }
        this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaText);
    }

    /** 위반 메시지 목록(스키마 + 의미 검증). 비어 있으면 유효. */
    public List<String> violations(JsonNode profile) {
        Set<ValidationMessage> messages = schema.validate(profile);
        List<String> out = new ArrayList<>(messages.stream().map(ValidationMessage::getMessage).sorted().toList());
        if (out.isEmpty()) out.addAll(semanticViolations(profile));
        return out;
    }

    /**
     * 스키마로는 못 잡는 규칙 (S4):
     * <ul>
     *   <li>{@code identity.attributes[]} 이름은 {@link AttributeCatalog} 에 있어야 한다 (정규 이름 또는 별칭)</li>
     *   <li>같은 속성을 두 번 선언할 수 없다 (별칭과 정규 이름 혼용 포함)</li>
     *   <li>{@code identity.attributeMapping} 의 키는 선언한 속성이어야 한다</li>
     *   <li>{@code identity.subjectScheme} 은 기관향으로 선택 가능한 스킴이어야 한다 (스키마 enum 이 1차 방어)</li>
     * </ul>
     */
    static List<String> semanticViolations(JsonNode profile) {
        List<String> out = new ArrayList<>();
        JsonNode identity = profile.get("identity");
        if (identity == null || identity.isNull()) return out;

        List<String> declared = new ArrayList<>();
        Set<String> canonicalSeen = new HashSet<>();
        JsonNode attrs = identity.get("attributes");
        if (attrs != null && attrs.isArray()) {
            for (JsonNode item : attrs) {
                String name = item.isTextual() ? item.asText() : item.path("name").asText(null);
                if (name == null) continue;
                declared.add(name);
                Optional<String> canonical = AttributeCatalog.canonicalName(name);
                if (canonical.isEmpty()) {
                    out.add("identity.attributes: 알 수 없는 속성 '" + name + "' — 카탈로그: " + AttributeCatalog.names());
                } else if (!canonicalSeen.add(canonical.get())) {
                    out.add("identity.attributes: 속성 '" + name + "'(" + canonical.get() + ") 이(가) 중복 선언되었습니다");
                }
            }
        }
        JsonNode mapping = identity.get("attributeMapping");
        if (mapping != null && mapping.isObject()) {
            Set<String> declaredCanonical = new HashSet<>();
            for (String d : declared) AttributeCatalog.canonicalName(d).ifPresent(declaredCanonical::add);
            mapping.fieldNames().forEachRemaining(key -> {
                Optional<String> canonical = AttributeCatalog.canonicalName(key);
                if (canonical.isEmpty() || !declaredCanonical.contains(canonical.get())) {
                    out.add("identity.attributeMapping: '" + key + "' 은(는) identity.attributes 에 선언되지 않은 속성입니다");
                }
            });
        }
        JsonNode scheme = identity.get("subjectScheme");
        if (scheme != null && scheme.isTextual()) {
            Optional<SubjectScheme> parsed = SubjectScheme.parse(scheme.asText());
            if (parsed.isEmpty() || !parsed.get().isTenantSelectable()) {
                out.add("identity.subjectScheme: '" + scheme.asText() + "' 은(는) 기관향 식별자로 선택할 수 없습니다");
            }
        }
        return out;
    }

    /** 유효하지 않으면 400(E-IDO-113) — 메시지에 위반 항목을 담는다. */
    public void validateOrThrow(JsonNode profile, String correlationId) {
        List<String> errors = violations(profile);
        if (!errors.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE, correlationId,
                    "기관 프로파일이 스키마(v" + ServiceProfile.SCHEMA_VERSION + ")에 맞지 않습니다: " + String.join("; ", errors));
        }
    }

    /** 원문 스키마 — 콘솔·도구가 폼 생성·클라이언트 검증에 쓴다. */
    public String schemaText() {
        return schemaText;
    }
}
