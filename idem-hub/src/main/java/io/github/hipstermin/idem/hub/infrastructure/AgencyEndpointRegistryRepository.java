package io.github.hipstermin.idem.hub.infrastructure;

import java.util.List;
import java.util.Optional;

/**
 * 유관기관 API 엔드포인트 레지스트리 리포지토리 인터페이스
 *
 * <p>idem_hub.agency_endpoint_registry 테이블 (V15 Flyway 생성) 접근.
 * 복합 PK: (agency_code, endpoint_type)
 *
 * <p>Sprint 14 사용 시나리오:
 * <ul>
 *   <li>프로비저닝 시 모든 활성 기관의 PROVISIONING 엔드포인트 일괄 조회</li>
 *   <li>CAST 검증 시 특정 기관의 CAST_VERIFY 엔드포인트 조회</li>
 *   <li>헬스체크 시 STATUS 엔드포인트 조회</li>
 * </ul>
 */
public interface AgencyEndpointRegistryRepository {

    /**
     * 특정 기관의 특정 엔드포인트 타입 조회
     *
     * @param agencyCode   기관 코드
     * @param endpointType 엔드포인트 타입 (PROVISIONING / CAST_VERIFY / WEBHOOK / STATUS / GATEWAY_INBOUND)
     * @return 엔드포인트 레코드 (is_active=true인 것만)
     */
    Optional<AgencyEndpointRecord> findByAgencyAndType(String agencyCode, String endpointType);

    /**
     * 특정 엔드포인트 타입을 가진 모든 활성 기관 엔드포인트 일괄 조회
     *
     * <p>프로비저닝 시 68개 기관 PROVISIONING 엔드포인트 한 번에 조회:
     * {@code findAllActiveByType("PROVISIONING")}
     *
     * @param endpointType 엔드포인트 타입
     * @return 활성 상태(is_active=true)인 모든 기관의 해당 타입 엔드포인트 목록
     */
    List<AgencyEndpointRecord> findAllActiveByType(String endpointType);

    /**
     * 특정 기관의 모든 활성 엔드포인트 조회
     *
     * @param agencyCode 기관 코드
     * @return 해당 기관의 모든 활성 엔드포인트 목록
     */
    List<AgencyEndpointRecord> findAllActiveByAgency(String agencyCode);

    /**
     * 기관 엔드포인트 등록/수정 (upsert)
     *
     * @param record 등록할 엔드포인트 레코드
     */
    void upsert(AgencyEndpointRecord record);

    /**
     * 기관 엔드포인트 비활성화 (물리 삭제 금지 — 감사 추적 보존)
     *
     * @param agencyCode   기관 코드
     * @param endpointType 엔드포인트 타입
     */
    void deactivate(String agencyCode, String endpointType);
}
