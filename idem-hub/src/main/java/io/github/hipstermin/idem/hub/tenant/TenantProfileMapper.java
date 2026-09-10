package io.github.hipstermin.idem.hub.tenant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Tenant Profile ↔ {@code agency_meta} 투영 (S2).
 *
 * <ul>
 *   <li>{@link #fromEntity} — 컬럼이 진실인 항목은 컬럼에서, 프로파일에만 있는 항목(security·attributeMapping·
 *       allowedProviders·session·limits.tps·ui)은 기존 프로파일 JSON 에서 가져와 합친다</li>
 *   <li>{@link #applyToEntity} — 프로파일을 컬럼으로 투영한다 (PUT 경로)</li>
 *   <li>{@link #syncProfileColumn} — 컬럼을 고친 레거시 쓰기 경로(Admin 서비스·도메인 저장소)가 저장 직전에 호출해
 *       {@code profile} 컬럼을 컬럼 값과 일치시킨다. 프로파일에만 있는 항목은 보존된다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantProfileMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<TenantProfile.MaintenanceWindow>> MW_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    // ── 컬럼 → 프로파일 ─────────────────────────────────────────────────────

    /** 컬럼 기반 항목은 엔티티에서, 나머지는 {@code existing} 에서 합성한다. {@code existing} 은 null 가능. */
    public TenantProfile fromEntity(AgencyMetaJpaEntity e, TenantProfile existing) {
        TenantProfile.Protocol exProtocol = existing != null ? existing.protocol() : null;
        TenantProfile.Identity exIdentity = existing != null ? existing.identity() : null;
        TenantProfile.Policy exPolicy = existing != null ? existing.policy() : null;
        TenantProfile.Limits exLimits = existing != null ? existing.limits() : null;

        return TenantProfile.builder()
                .schemaVersion(TenantProfile.SCHEMA_VERSION)
                .tenant(TenantProfile.Tenant.builder()
                        .code(e.getAgencyCode())
                        .name(e.getOfficialName())
                        .status(e.isActive() ? TenantProfile.TenantStatus.ACTIVE : TenantProfile.TenantStatus.INACTIVE)
                        .build())
                .protocol(TenantProfile.Protocol.builder()
                        .type(e.getIntegrationType() != null ? e.getIntegrationType() : IntegrationType.DEFAULT)
                        .endpoints(TenantProfile.Endpoints.builder()
                                .callbackWhitelist(readList(e.getCallbackWhitelist()))
                                .bridge(e.getBridgeEndpoint())
                                .apacheGate(e.getApacheGateEndpoint())
                                .ssoDomain(e.getSsoDomain())
                                .build())
                        .security(exProtocol != null ? exProtocol.security() : null)
                        .build())
                .identity(TenantProfile.Identity.builder()
                        .attributes(readList(e.getAllowedAttributes()))
                        .attributeMapping(exIdentity != null ? exIdentity.attributeMapping() : null)
                        .build())
                .policy(TenantProfile.Policy.builder()
                        .minAuthLevel(parseLevel(e.getMinAuthLevel()))
                        .policyVersion(e.getPolicyVersion())
                        .maintenance(readMaintenance(e.getMaintenanceWindows()))
                        .allowedProviders(exPolicy != null ? exPolicy.allowedProviders() : null)
                        .session(exPolicy != null ? exPolicy.session() : null)
                        .rules(exPolicy != null ? exPolicy.rules() : null)
                        .build())
                .limits(TenantProfile.Limits.builder()
                        .daily(e.getDailyLookupLimit())
                        .tps(exLimits != null ? exLimits.tps() : null)
                        .build())
                .ui(existing != null ? existing.ui() : null)
                .build();
    }

    /** 엔티티의 {@code profile} 컬럼을 파싱한다. 없거나 깨져 있으면 null (컬럼 합성으로 대체). */
    public TenantProfile existingProfile(AgencyMetaJpaEntity e) {
        if (e.getProfile() == null || e.getProfile().isBlank()) return null;
        try {
            return fromJson(e.getProfile());
        } catch (IllegalArgumentException ex) {
            log.warn("[TenantProfile] 저장된 프로파일 파싱 실패 — 컬럼에서 합성: agencyCode={} err={}", e.getAgencyCode(), ex.getMessage());
            return null;
        }
    }

    /** 레거시 쓰기 경로용: 컬럼 값을 프로파일 JSON 에 반영해 둘을 일치시킨다. */
    public void syncProfileColumn(AgencyMetaJpaEntity e) {
        TenantProfile merged = fromEntity(e, existingProfile(e));
        e.setProfile(toJson(merged));
        e.setProfileSchemaVersion(TenantProfile.SCHEMA_VERSION);
    }

    // ── 프로파일 → 컬럼 ─────────────────────────────────────────────────────

    /** 프로파일을 컬럼으로 투영하고 {@code profile} 컬럼에 원문을 저장한다 (PUT 경로). */
    public void applyToEntity(TenantProfile p, AgencyMetaJpaEntity e) {
        e.setAgencyCode(p.tenant().code());
        e.setOfficialName(p.tenant().name());
        e.setActive(p.isActive());

        TenantProfile.Protocol protocol = p.protocol();
        e.setIntegrationType(protocol.type() != null ? protocol.type() : IntegrationType.DEFAULT);
        TenantProfile.Endpoints ep = protocol.endpoints();
        e.setCallbackWhitelist(ep != null ? writeJson(ep.callbackWhitelist()) : null);
        e.setBridgeEndpoint(ep != null ? ep.bridge() : null);
        e.setApacheGateEndpoint(ep != null ? ep.apacheGate() : null);
        e.setSsoDomain(ep != null ? ep.ssoDomain() : null);

        e.setAllowedAttributes(p.identity() != null ? writeJson(p.identity().attributes()) : null);

        TenantProfile.Policy policy = p.policy();
        e.setMinAuthLevel(policy.minAuthLevel() != null ? policy.minAuthLevel().name() : "L1");
        if (policy.policyVersion() != null) e.setPolicyVersion(policy.policyVersion());
        e.setMaintenanceWindows(writeJson(policy.maintenance()));

        e.setDailyLookupLimit(p.limits() != null ? p.limits().daily() : null);

        e.setProfile(toJson(p));
        e.setProfileSchemaVersion(TenantProfile.SCHEMA_VERSION);
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    public TenantProfile parse(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, TenantProfile.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Tenant Profile JSON 을 해석할 수 없습니다: " + ex.getOriginalMessage(), ex);
        }
    }

    public TenantProfile fromJson(String json) {
        try {
            return objectMapper.readValue(json, TenantProfile.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Tenant Profile JSON 을 해석할 수 없습니다: " + ex.getOriginalMessage(), ex);
        }
    }

    public String toJson(TenantProfile p) {
        try {
            return objectMapper.writeValueAsString(p);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Tenant Profile 직렬화 실패", ex);
        }
    }

    public JsonNode toNode(TenantProfile p) {
        return objectMapper.valueToTree(p);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            log.warn("[TenantProfile] JSON 목록 파싱 실패 — 무시: {}", ex.getOriginalMessage());
            return null;
        }
    }

    private List<TenantProfile.MaintenanceWindow> readMaintenance(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, MW_LIST);
        } catch (JsonProcessingException ex) {
            log.warn("[TenantProfile] 점검시간대 JSON 파싱 실패 — 무시: {}", ex.getOriginalMessage());
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
