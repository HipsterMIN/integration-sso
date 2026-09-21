-- ──────────────────────────────────────────────────────────────────────────────
-- 통합인증 플랫폼 — PostgreSQL 스키마 네임스페이스 초기화
--
-- ⚠️  이 파일은 postgres 컨테이너 최초 기동 시 1회만 실행됩니다.
--     (docker-entrypoint-initdb.d — pg-data 볼륨이 비어있을 때만 동작)
--
-- [역할 제한]
--   스키마(네임스페이스) 생성만 담당합니다.
--   테이블·인덱스·시드 데이터는 각 서비스 Flyway 마이그레이션이 전담합니다:
--     · idem-hub/src/main/resources/db/migration/V*.sql
--     · idem-gate/src/main/resources/db/migration/V*.sql
--     · idem-tenant-sample/src/main/resources/db/migration/V*.sql
--     · idem-registry/src/main/resources/db/migration/postgresql/V*.sql (D1 부터 PostgreSQL)
--
-- [DB 구조] PostgreSQL 16 하나: qsign / ido / qim / agency_stub / keycloak 스키마 (D1: MariaDB 제거)
-- ──────────────────────────────────────────────────────────────────────────────

-- Q-Sign 스키마 (인증 SoR)
CREATE SCHEMA IF NOT EXISTS qsign;

-- IdO 스키마 (정책 SoR)
CREATE SCHEMA IF NOT EXISTS ido;

-- Agency-Stub 스키마
CREATE SCHEMA IF NOT EXISTS agency_stub;

-- Keycloak 스키마 (§10 — 테이블은 Keycloak이 자동 생성)
CREATE SCHEMA IF NOT EXISTS keycloak;

-- Q-IM(idem-registry) 스키마 — D1 부터 PostgreSQL. Flyway(create-schemas) 도 만들지만 권한·순서 문제를 피해 여기서도 만든다
CREATE SCHEMA IF NOT EXISTS qim;
