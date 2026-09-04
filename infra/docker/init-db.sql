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
--     · q-im은 MariaDB 전용 (이 파일과 무관)
--
-- [DB 분리 구조]
--   PostgreSQL 16 (이 컨테이너): Q-Sign / IdO / agency-stub / Keycloak
--   MariaDB 11    (별도 컨테이너): Q-IM 전용
-- ──────────────────────────────────────────────────────────────────────────────

-- Q-Sign 스키마 (인증 SoR)
CREATE SCHEMA IF NOT EXISTS qsign;

-- IdO 스키마 (정책 SoR)
CREATE SCHEMA IF NOT EXISTS ido;

-- Agency-Stub 스키마
CREATE SCHEMA IF NOT EXISTS agency_stub;

-- Keycloak 스키마 (§10 — 테이블은 Keycloak이 자동 생성)
CREATE SCHEMA IF NOT EXISTS keycloak;

-- ※ Q-IM 스키마 없음 — MariaDB(qim DB)로 분리됨
