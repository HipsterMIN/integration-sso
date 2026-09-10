package io.github.hipstermin.idem.hub.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * Tenant Profile — 연동기관(테넌트)별 선언적 설정의 단일 원천 (S2, {@code docs/generalization-plan.md} §2.1).
 *
 * <p>JSON Schema {@code tenant-profile/tenant-profile.v1.schema.json} 과 1:1 로 대응하며, 저장은
 * {@code agency_meta.profile JSONB} 에 한다. 기존 컬럼(official_name·min_auth_level·integration_type·…)은
 * 이 문서의 <b>투영</b>이다 — 쓰기는 프로파일을 거쳐 컬럼으로 내려가고, 읽기는 아직 컬럼이 런타임 진실이다
 * (S3·S4 에서 읽기 경로를 프로파일로 옮긴다).
 *
 * <p>S2 에서 동작을 갖는 필드는 컬럼에 대응하는 것들뿐이다. {@code protocol.security}, {@code identity.attributeMapping},
 * {@code policy.allowedProviders}, {@code policy.session}, {@code limits.tps}, {@code ui} 는 저장·조회만 되고
 * 뒤 단계(S3·S4·S7)에서 의미를 갖는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder(toBuilder = true)
public record TenantProfile(
        Integer schemaVersion,
        Tenant tenant,
        Protocol protocol,
        Identity identity,
        Policy policy,
        Limits limits,
        Ui ui) {

    public static final int SCHEMA_VERSION = 1;

    public enum TenantStatus { ACTIVE, INACTIVE }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Tenant(String code, String name, TenantStatus status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Protocol(IntegrationType type, Endpoints endpoints, Security security) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Endpoints(List<String> callbackWhitelist, String bridge, String apacheGate, String ssoDomain) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Security(Boolean mtlsRequired, List<String> ipAllowlist) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Identity(List<String> attributes, Map<String, String> attributeMapping) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record Policy(AuthResult.AuthLevel minAuthLevel, String policyVersion, List<String> allowedProviders,
                         Session session, List<MaintenanceWindow> maintenance, List<RuleRef> rules) {}

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
        return tenant == null || tenant.status() == null || tenant.status() == TenantStatus.ACTIVE;
    }
}
