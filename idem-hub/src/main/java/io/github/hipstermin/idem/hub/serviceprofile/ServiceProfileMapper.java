package io.github.hipstermin.idem.hub.serviceprofile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service Profile ↔ {@code agency_meta} 투영 (S2).
 *
 * <ul>
 *   <li>{@link #fromEntity} — 컬럼이 진실인 항목은 컬럼에서, 프로파일에만 있는 항목(security·subjectScheme·
 *       attributeMapping·속성 옵션·allowedProviders·session·rules·limits.tps·ui)은 기존 프로파일 JSON 에서 가져와 합친다</li>
 *   <li>{@link #applyToEntity} — 프로파일을 컬럼으로 투영한다 (PUT 경로)</li>
 *   <li>{@link #syncProfileColumn} — 컬럼을 고친 레거시 쓰기 경로(Admin 서비스·도메인 저장소)가 저장 직전에 호출해
 *       {@code profile} 컬럼을 컬럼 값과 일치시킨다. 프로파일에만 있는 항목은 보존된다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceProfileMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<ServiceProfile.MaintenanceWindow>> MW_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    // ── 컬럼 → 프로파일 ─────────────────────────────────────────────────────

    /** 컬럼 기반 항목은 엔티티에서, 나머지는 {@code existing} 에서 합성한다. {@code existing} 은 null 가능. */
    public ServiceProfile fromEntity(AgencyMetaJpaEntity e, ServiceProfile existing) {
        ServiceProfile.Protocol exProtocol = existing != null ? existing.protocol() : null;
        ServiceProfile.Identity exIdentity = existing != null ? existing.identity() : null;
        ServiceProfile.Policy exPolicy = existing != null ? existing.policy() : null;
        ServiceProfile.Limits exLimits = existing != null ? existing.limits() : null;

        return ServiceProfile.builder()
                .schemaVersion(ServiceProfile.SCHEMA_VERSION)
                .service(ServiceProfile.Service.builder()
                        .code(e.getAgencyCode())
                        .name(e.getOfficialName())
                        .status(e.isActive() ? ServiceProfile.ServiceStatus.ACTIVE : ServiceProfile.ServiceStatus.INACTIVE)
                        .tenant(e.getTenantCode() != null ? e.getTenantCode() : ServiceProfile.DEFAULT_TENANT)
                        .build())
                .protocol(ServiceProfile.Protocol.builder()
                        .type(e.getIntegrationType() != null ? e.getIntegrationType() : IntegrationType.DEFAULT)
                        .endpoints(ServiceProfile.Endpoints.builder()
                                .callbackWhitelist(readList(e.getCallbackWhitelist()))
                                .bridge(e.getBridgeEndpoint())
                                .apacheGate(e.getApacheGateEndpoint())
                                .ssoDomain(e.getSsoDomain())
                                .build())
                        .security(exProtocol != null ? exProtocol.security() : null)
                        .build())
                .identity(ServiceProfile.Identity.builder()
                        .subjectScheme(exIdentity != null ? exIdentity.subjectScheme() : null)
                        .attributes(mergeSelections(readList(e.getAllowedAttributes()),
                                exIdentity != null ? exIdentity.attributes() : null))
                        .attributeMapping(exIdentity != null ? exIdentity.attributeMapping() : null)
                        .build())
                .policy(ServiceProfile.Policy.builder()
                        .minAuthLevel(parseLevel(e.getMinAuthLevel()))
                        .policyVersion(e.getPolicyVersion())
                        .maintenance(readMaintenance(e.getMaintenanceWindows()))
                        .allowedProviders(exPolicy != null ? exPolicy.allowedProviders() : null)
                        .session(exPolicy != null ? exPolicy.session() : null)
                        .rules(exPolicy != null ? exPolicy.rules() : null)
                        .build())
                .limits(ServiceProfile.Limits.builder()
                        .daily(e.getDailyLookupLimit())
                        .tps(exLimits != null ? exLimits.tps() : null)
                        .build())
                .ui(existing != null ? existing.ui() : null)
                .build();
    }

    /** 엔티티의 {@code profile} 컬럼을 파싱한다. 없거나 깨져 있으면 null (컬럼 합성으로 대체). */
    public ServiceProfile existingProfile(AgencyMetaJpaEntity e) {
        if (e.getProfile() == null || e.getProfile().isBlank()) return null;
        try {
            return fromJson(e.getProfile());
        } catch (IllegalArgumentException ex) {
            log.warn("[ServiceProfile] 저장된 프로파일 파싱 실패 — 컬럼에서 합성: agencyCode={} err={}", e.getAgencyCode(), ex.getMessage());
            return null;
        }
    }

    /** 레거시 쓰기 경로용: 컬럼 값을 프로파일 JSON 에 반영해 둘을 일치시킨다. */
    public void syncProfileColumn(AgencyMetaJpaEntity e) {
        ServiceProfile merged = fromEntity(e, existingProfile(e));
        e.setProfile(toJson(merged));
        e.setProfileSchemaVersion(ServiceProfile.SCHEMA_VERSION);
    }

    // ── 프로파일 → 컬럼 ─────────────────────────────────────────────────────

    /** 프로파일을 컬럼으로 투영하고 {@code profile} 컬럼에 원문을 저장한다 (PUT 경로). */
    public void applyToEntity(ServiceProfile p, AgencyMetaJpaEntity e) {
        e.setAgencyCode(p.service().code());
        e.setOfficialName(p.service().name());
        e.setActive(p.isActive());
        e.setTenantCode(p.service().tenantOrDefault());

        ServiceProfile.Protocol protocol = p.protocol();
        e.setIntegrationType(protocol.type() != null ? protocol.type() : IntegrationType.DEFAULT);
        ServiceProfile.Endpoints ep = protocol.endpoints();
        e.setCallbackWhitelist(ep != null ? writeJson(ep.callbackWhitelist()) : null);
        e.setBridgeEndpoint(ep != null ? ep.bridge() : null);
        e.setApacheGateEndpoint(ep != null ? ep.apacheGate() : null);
        e.setSsoDomain(ep != null ? ep.ssoDomain() : null);

        // 컬럼에는 이름 목록만 내려간다 — required·masking·subjectScheme 은 프로파일에만 있다
        e.setAllowedAttributes(p.identity() != null ? writeJson(p.identity().attributeNames()) : null);

        ServiceProfile.Policy policy = p.policy();
        e.setMinAuthLevel(policy.minAuthLevel() != null ? policy.minAuthLevel().name() : "L1");
        if (policy.policyVersion() != null) e.setPolicyVersion(policy.policyVersion());
        e.setMaintenanceWindows(writeJson(policy.maintenance()));

        e.setDailyLookupLimit(p.limits() != null ? p.limits().daily() : null);

        e.setProfile(toJson(p));
        e.setProfileSchemaVersion(ServiceProfile.SCHEMA_VERSION);
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    public ServiceProfile parse(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, ServiceProfile.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Service Profile JSON 을 해석할 수 없습니다: " + ex.getOriginalMessage(), ex);
        }
    }

    public ServiceProfile fromJson(String json) {
        try {
            return objectMapper.readValue(json, ServiceProfile.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Service Profile JSON 을 해석할 수 없습니다: " + ex.getOriginalMessage(), ex);
        }
    }

    public String toJson(ServiceProfile p) {
        try {
            return objectMapper.writeValueAsString(p);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Service Profile 직렬화 실패", ex);
        }
    }

    public JsonNode toNode(ServiceProfile p) {
        return objectMapper.valueToTree(p);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * 컬럼의 이름 집합이 진실이고, 같은 이름의 기존 선택 항목이 있으면 그 옵션(required·masking)을 유지한다.
     * 컬럼과 프로파일이 정상 경로에서는 항상 일치하므로 이 합성은 레거시 쓰기 경로 직후에만 의미가 있다.
     */
    static List<ServiceProfile.AttributeSelection> mergeSelections(List<String> columnNames,
                                                                  List<ServiceProfile.AttributeSelection> existing) {
        if (columnNames == null) return null;
        Map<String, ServiceProfile.AttributeSelection> byName = new java.util.HashMap<>();
        if (existing != null) {
            for (ServiceProfile.AttributeSelection sel : existing) {
                if (sel != null && sel.name() != null) byName.put(sel.name(), sel);
            }
        }
        return columnNames.stream()
                .map(n -> byName.getOrDefault(n, ServiceProfile.AttributeSelection.of(n)))
                .toList();
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            log.warn("[ServiceProfile] JSON 목록 파싱 실패 — 무시: {}", ex.getOriginalMessage());
            return null;
        }
    }

    private List<ServiceProfile.MaintenanceWindow> readMaintenance(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, MW_LIST);
        } catch (JsonProcessingException ex) {
            log.warn("[ServiceProfile] 점검시간대 JSON 파싱 실패 — 무시: {}", ex.getOriginalMessage());
            return null;
        }
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 직렬화 실패", ex);
        }
    }

    private static AuthResult.AuthLevel parseLevel(String raw) {
        if (raw == null || raw.isBlank()) return AuthResult.AuthLevel.L1;
        try {
            return AuthResult.AuthLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AuthResult.AuthLevel.L1;
        }
    }
}
