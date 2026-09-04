-- ============================================================
-- Agency-Stub E2E 통합 테스트 초기화 스크립트 (Testcontainers PostgreSQL)
-- ============================================================
-- agency-stub Flyway 마이그레이션은 default-schema=agency_stub 이지만,
-- Flyway 가 첫 마이그레이션에서 자체적으로 CREATE SCHEMA 를 수행하므로
-- 본 init 스크립트는 PostgreSQL 의 pgcrypto 확장(gen_random_uuid)만 보장한다.
--
-- gen_random_uuid() 는 PostgreSQL 13+ 에서 기본 활성이지만,
-- alpine 이미지에 따라 명시적 활성화가 필요한 경우가 있어 안전망으로 둔다.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
