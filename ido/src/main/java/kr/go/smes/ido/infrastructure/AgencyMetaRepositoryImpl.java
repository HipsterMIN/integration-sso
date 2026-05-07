package kr.go.smes.ido.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.ido.domain.AgencyMeta;
import kr.go.smes.ido.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import kr.go.smes.ido.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    @Override
    public Optional<AgencyMeta> findByCode(String agencyCode) {
        return jpaRepository.findById(agencyCode)
                .map(this::toDomain);
    }

    @Override
    public void save(AgencyMeta agencyMeta) {
        AgencyMetaJpaEntity entity = toEntity(agencyMeta);
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
                .policyVersion(domain.getPolicyVersion() != null ? domain.getPolicyVersion() : "1.0")
                .apiKeyHash(domain.getApiKeyHash())
                .callbackWhitelist(callbackJson)
                .allowedAttributes(attrJson)
                .maintenanceWindows(mwJson)
                .integrationType("DIRECT")
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
