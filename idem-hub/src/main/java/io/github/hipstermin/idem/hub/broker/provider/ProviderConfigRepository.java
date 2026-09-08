package io.github.hipstermin.idem.hub.broker.provider;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ido.provider_config 테이블 접근 레포지토리
 *
 * <p>V3 + V6 마이그레이션으로 생성된 {@code ido.provider_config} 에서 설정을 로드.
 * Spring Cache(@Cacheable)로 TTL 기반 캐싱 — DB 조회 최소화.
 *
 * <p>캐시 키: {@code provider-config::{providerCode}}
 * TTL: {@code application.yml ido.qim.agency-meta-ttl-seconds} (기본 3600s)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ProviderConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * providerCode로 provider 설정 조회
     *
     * @param providerCode 인증 수단 코드 (예: KAKAO_OIDC, PASS)
     * @return {@link ProviderConfig} 또는 empty (설정 없음)
     */
    @Cacheable(value = "provider-config", key = "#providerCode", unless = "#result.isEmpty()")
    public Optional<ProviderConfig> findByCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) return Optional.empty();
        try {
            return jdbcTemplate.query("""
                    SELECT provider_code, display_name, auth_level,
                           broker_mode, idp_hint, provider_type, active
                    FROM ido.provider_config
                    WHERE provider_code = ? AND active = TRUE
                    """,
                    rs -> {
                        if (rs.next()) {
                            return Optional.of(ProviderConfig.builder()
                                    .providerCode(rs.getString("provider_code"))
                                    .displayName(rs.getString("display_name"))
                                    .authLevel(rs.getString("auth_level"))
                                    .brokerMode(rs.getString("broker_mode"))
                                    .idpHint(rs.getString("idp_hint"))
                                    .providerType(ProviderConfig.ProviderType.fromString(
                                            rs.getString("provider_type")))
                                    .active(rs.getBoolean("active"))
                                    .build());
                        }
                        return Optional.<ProviderConfig>empty();
                    },
                    providerCode
            );
        } catch (Exception e) {
            log.warn("[ProviderConfigRepository] provider_config 조회 실패: providerCode={} err={}",
                    providerCode, e.getMessage());
            return Optional.empty();
        }
    }
}
