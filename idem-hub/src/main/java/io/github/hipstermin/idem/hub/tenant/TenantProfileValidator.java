package io.github.hipstermin.idem.hub.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Tenant Profile JSON Schema 검증기 (S2).
 *
 * <p>스키마 파일은 저장소에 두고 Admin API·콘솔(S7)·마이그레이션이 같은 파일로 검증한다.
 * 스키마 버전이 늘면 {@code tenant-profile.v{n}.schema.json} 을 추가하고 마이그레이터 체인으로 올린다.
 */
@Component
public class TenantProfileValidator {

    public static final String SCHEMA_PATH = "tenant-profile/tenant-profile.v1.schema.json";

    private final JsonSchema schema;
    private final String schemaText;

    public TenantProfileValidator() {
        try (InputStream in = new ClassPathResource(SCHEMA_PATH).getInputStream()) {
            this.schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Tenant Profile 스키마를 읽을 수 없습니다: " + SCHEMA_PATH, e);
        }
        this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaText);
    }

    /** 위반 메시지 목록. 비어 있으면 유효. */
    public List<String> violations(JsonNode profile) {
        Set<ValidationMessage> messages = schema.validate(profile);
        return messages.stream().map(ValidationMessage::getMessage).sorted().toList();
    }

    /** 유효하지 않으면 400(E-IDO-113) — 메시지에 위반 항목을 담는다. */
    public void validateOrThrow(JsonNode profile, String correlationId) {
        List<String> errors = violations(profile);
        if (!errors.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE, correlationId,
                    "기관 프로파일이 스키마(v" + TenantProfile.SCHEMA_VERSION + ")에 맞지 않습니다: " + String.join("; ", errors));
        }
    }

    /** 원문 스키마 — 콘솔·도구가 폼 생성·클라이언트 검증에 쓴다. */
    public String schemaText() {
        return schemaText;
    }
}
