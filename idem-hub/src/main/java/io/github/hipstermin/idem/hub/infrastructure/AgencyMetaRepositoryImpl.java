package io.github.hipstermin.idem.hub.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import io.github.hipstermin.idem.hub.tenant.TenantProfileMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 기관 메타데이터 JPA 구현체
 * 설계서 §11.2 / §11.3 기관 정책 SoR
 *
 * <p>저장소 전략:
 * <ul>
 *   <li>PostgreSQL ido.agency_meta: 정본(SoR)</li>
 *   <li>Redis qimUserStatus / agencyMeta 캐시: TTL ≤60분 (RedisConfig 에서 관리)</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AgencyMetaRepositoryImpl implements AgencyMetaRepository {

    private final AgencyMetaJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;
    private final TenantProfileMapper tenantProfileMapper;

    /** 신규 기관 생성 시 policyVersion 기본값 (하드코딩 "1.0" 제거 — §11.2) */
    @Value("${ido.policy.default-version:1.0}")
    private String defaultPolicyVersion;

    @Override
    public Optional<AgencyMeta> findByCode(String agencyCode) {
        return jpaRepository.findById(agencyCode)
                .map(this::toDomain);
    }

    @Override
    public void save(AgencyMeta agencyMeta) {
        AgencyMetaJpaEntity entity = toEntity(agencyMeta);
        // S2: 도메인 객체에는 프로파일·한도가 없으므로 기존 행의 값을 보존한 뒤 컬럼 → 프로파일 동기화
        jpaRepository.findById(agencyMeta.getAgencyCode()).ifPresent(existing -> {
            entity.setProfile(existing.getProfile());
            entity.setProfileSchemaVersion(existing.getProfileSchemaVersion());
            entity.setDailyLookupLimit(existing.getDailyLookupLimit());
        });
        tenantProfileMapper.syncProfileColumn(entity);
        jpaRepository.save(entity);
        log.debug("[AgencyMetaRepository] 기관 메타 저장: agencyCode={}", agencyMeta.getAgencyCode());
    }

    // ── 도메인 ↔ 엔터티 변환 ─────────────────────────────────────────────────

    private AgencyMeta toDomain(AgencyMetaJpaEntity e) {
        return AgencyMeta.builder()
                .agencyCode(e.getAgencyCode())
                .officialName(e.getOfficialName())
                .minAuthLevel(parseAuthLevel(e.getMinAuthLevel()))
                .policyVersion(e.getPolicyVersion())
                .apiKeyHash(e.getApiKeyHash())
                .callbackWhitelist(parseJsonList(e.getCallbackWhitelist()))
                .allowedAttributes(parseJsonList(e.getAllowedAttributes()))
                .maintenanceWindows(parseMaintenanceWindows(e.getMaintenanceWindows()))
                .integrationType(e.getIntegrationType() != null ? e.getIntegrationType() : IntegrationType.DEFAULT)
                .bridgeEndpoint(e.getBridgeEndpoint())
                .ssoDomain(e.getSsoDomain())
                .apacheGateEndpoint(e.getApacheGateEndpoint())
                .active(e.isActive())
                .build();
    }

    private AgencyMetaJpaEntity toEntity(AgencyMeta domain) {
        // JSONB 직렬화
        String callbackJson = toJson(domain.getCallbackWhitelist());
        String attrJson     = toJson(domain.getAllowedAttributes());
        String mwJson       = toJson(domain.getMaintenanceWindows());

        return AgencyMetaJpaEntity.builder()
                .agencyCode(domain.getAgencyCode())
                .officialName(domain.getOfficialName() != null ? domain.getOfficialName() : domain.getAgencyCode())
                .minAuthLevel(domain.getMinAuthLevel() != null ? domain.getMinAuthLevel().name() : "L1")
                .policyVersion(domain.getPolicyVersion() != null ? domain.getPolicyVersion() : defaultPolicyVersion)
                .apiKeyHash(domain.getApiKeyHash())
                .callbackWhitelist(callbackJson)
                .allowedAttributes(attrJson)
                .maintenanceWindows(mwJson)
                .integrationType(domain.getIntegrationType() != null ? domain.getIntegrationType() : IntegrationType.DEFAULT)
                .bridgeEndpoint(domain.getBridgeEndpoint())
                .apacheGateEndpoint(domain.getApacheGateEndpoint())
                .ssoDomain(domain.getSsoDomain())
                .active(domain.isActive())
                .build();
    }

    // ── 파싱 유틸 ────────────────────────────────────────────────────────────

    private AuthResult.AuthLevel parseAuthLevel(String level) {
        try {
            return AuthResult.AuthLevel.valueOf(level);
        } catch (Exception e) {
            log.warn("[AgencyMetaRepository] 알 수 없는 minAuthLevel '{}' → L1 기본값 적용", level);
            return AuthResult.AuthLevel.L1;
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[AgencyMetaRepository] JSON 파싱 실패 (빈 목록 반환): {}", e.getMessage());
            return List.of();
        }
    }

    private List<AgencyMeta.MaintenanceWindow> parseMaintenanceWindows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<AgencyMeta.MaintenanceWindow>>() {});
        } catch (Exception e) {
            log.warn("[AgencyMetaRepository] MaintenanceWindow JSON 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[AgencyMetaRepository] JSON 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }
}
