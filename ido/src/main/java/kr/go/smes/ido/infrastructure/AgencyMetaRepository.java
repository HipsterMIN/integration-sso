package kr.go.smes.ido.infrastructure;

import kr.go.smes.ido.domain.AgencyMeta;

import java.util.Optional;

/**
 * 기관 메타데이터 저장소 인터페이스
 * 설계서 11.5절 — TTL ≤60분 캐시 대상
 */
public interface AgencyMetaRepository {
    Optional<AgencyMeta> findByCode(String agencyCode);
    void save(AgencyMeta agencyMeta);
}
