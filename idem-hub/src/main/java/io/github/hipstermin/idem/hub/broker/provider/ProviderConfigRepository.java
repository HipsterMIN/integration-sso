package io.github.hipstermin.idem.hub.broker.provider;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * idem_hub.provider_config 테이블 접근 레포지토리
 *
 * <p>V3 + V6 마이그레이션으로 생성된 {@code idem_hub.provider_config} 에서 설정을 로드.
 * Spring Cache(@Cacheable)로 TTL 기반 캐싱 — DB 조회 최소화.
 *
 * <p>캐시 키: {@code provider-config::{providerCode}}
 * TTL: {@code application.yml idem.hub.registry.agency-meta-ttl-seconds} (기본 3600s)
 *
 * <p><b>1.1.4 G2-10 (Keycloak 26 시험 중 발견)</b>: {@code Optional} 을 돌려주는 메서드의 {@code @Cacheable} 은 Spring 이
 * {@code #result} 를 <b>껍질을 벗긴 값</b>으로 넘긴다(비어 있으면 {@code null}). 종전 {@code unless = "#result.isEmpty()"} 는
 * 비어 있으면 {@code null.isEmpty()}(EL1011E), 있으면 {@code ProviderConfig.isEmpty()} 없음(EL1004E)으로 <em>항상</em> 던져
 * 브로커 인증 시작({@code GET /api/v1/broker/{provider}/authorize})이 1.0.0 부터 500 이었다. 빈 결과를 캐시하지 않는다는 뜻은
 * {@code #result == null} 이다. 캐시 값의 역직렬화는 {@link ProviderConfig} 의 {@code @Jacksonized} 빌더와
 * {@code RedisConfig} 의 타입 지정 직렬화기가 맡는다(종전에는 타입 정보 없이 저장돼 두 번째 호출부터 {@code LinkedHashMap} 이 나왔다).
 * 회귀 검사: {@code BrokerLoginIntegrationTest}.
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
    @Cacheable(value = "provider-config", key = "#providerCode", unless = "#result == null")
    public Optional<ProviderConfig> findByCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) return Optional.empty();
        try {
            return jdbcTemplate.query("""
                    SELECT provider_code, display_name, auth_level,
                           broker_mode, idp_hint, provider_type, active
                    FROM idem_hub.provider_config
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
