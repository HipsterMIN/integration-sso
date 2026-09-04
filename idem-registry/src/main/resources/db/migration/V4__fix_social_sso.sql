-- =============================================================================
-- Q-IM V4: 소셜 SSO 스키마 보정 — identifier_hash 단독 UNIQUE → 복합 UNIQUE
-- =============================================================================
-- 목적:
--   1. auth_mean_mapping.identifier_hash 단독 UNIQUE 제약 제거
--      → 동일 SHA-256(sub)가 서로 다른 소셜 제공자(providerCode)에 귀속될 수 있음
--         예: Kakao sub "12345"와 Naver sub "12345"가 우연히 동일 해시를 가질 경우
--             기존 단독 UNIQUE이면 두 번째 등록 시 DB unique violation 발생
--   2. (identifier_hash, provider_code) 복합 UNIQUE 추가
--      → "동일 소셜 제공자 내 동일 sub는 하나의 qimUserId에만 귀속" 보장
--      → UserController.findByIdentifierHashAndProviderCode() 조회와 1:1 대응
--   3. (identifier_hash, provider_code) 복합 인덱스 추가
--      → findByIdentifierHashAndProviderCode JPQL 쿼리 성능 최적화
--   4. 기존 데이터 사전 검증 주석 포함
--      → 중복 데이터가 있으면 ALTER 전에 정리가 필요하므로 주석으로 검증 쿼리 제공
--
-- 영향 범위:
--   - auth_mean_mapping 테이블 제약 변경 (DDL)
--   - 데이터 변경 없음 (DML 없음)
--   - 애플리케이션 코드: UserController, QimUserJpaRepository 변경 없음 (이미 복합 키 사용)
--
-- 작성일: 2026-05-12
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- [사전 검증] 마이그레이션 실행 전 중복 데이터 확인
-- MariaDB에서 아래 쿼리로 중복 여부를 확인한 후 진행하십시오.
-- 결과가 비어 있으면 안전하게 진행 가능합니다.
--
-- SELECT identifier_hash, provider_code, COUNT(*) AS cnt
-- FROM auth_mean_mapping
-- WHERE status = 'ACTIVE'
-- GROUP BY identifier_hash, provider_code
-- HAVING cnt > 1;
-- ─────────────────────────────────────────────────────────────────────────────

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 기존 단독 UNIQUE 제약 제거
--    V1에서 생성된 CONSTRAINT uq_identifier_hash UNIQUE (identifier_hash)를 DROP.
--    MariaDB에서 UNIQUE 제약은 내부적으로 UNIQUE INDEX로 관리되므로 DROP INDEX로 제거.
--    ※ V3에서 생성된 idx_auth_mean_hash_status 인덱스는 그대로 유지.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE auth_mean_mapping
    DROP INDEX uq_identifier_hash;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. (identifier_hash, provider_code) 복합 UNIQUE 제약 추가
--    "동일 소셜 제공자 + 동일 sub → 단 하나의 qimUserId" 보장.
--    UserController.registerSocialUser()의 경합 방어 로직과 함께 동작하며,
--    만약 동시 요청으로 두 INSERT가 경쟁하면 두 번째는 DB 레벨에서 거부됨.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE auth_mean_mapping
    ADD CONSTRAINT uq_identifier_hash_provider
        UNIQUE (identifier_hash, provider_code);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. (identifier_hash, provider_code) 복합 인덱스 추가
--    JPQL: WHERE m.identifierHash = :identifierHash AND m.providerCode = :providerCode
--    AND m.status = 'ACTIVE'
--    → identifier_hash + provider_code 선두 복합 인덱스가 range scan을 지원.
--    ※ UNIQUE 제약(uq_identifier_hash_provider) 자체가 인덱스 역할을 하므로
--       status 포함 복합 인덱스를 별도로 추가하여 커버링 인덱스 효과를 높임.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_mapping_hash_provider_status
    ON auth_mean_mapping (identifier_hash, provider_code, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- [검증 쿼리] 마이그레이션 후 확인용 (주석)
-- ─────────────────────────────────────────────────────────────────────────────
-- SHOW INDEXES FROM auth_mean_mapping;
-- -- 기대 결과:
-- --   uq_identifier_hash          → 존재하지 않아야 함 (DROP됨)
-- --   uq_identifier_hash_provider → (identifier_hash, provider_code) UNIQUE 존재
-- --   idx_mapping_hash_provider_status → (identifier_hash, provider_code, status) 존재
-- --   idx_auth_mean_hash_status   → V3에서 추가된 (identifier_hash, status) 여전히 존재
