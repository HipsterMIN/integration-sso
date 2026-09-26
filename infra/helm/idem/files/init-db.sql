-- ──────────────────────────────────────────────────────────────────────────────
-- 통합인증 플랫폼 — PostgreSQL 스키마 네임스페이스 초기화
--
-- ⚠️  compose: postgres 컨테이너 최초 기동 시 1회만 실행됩니다 (docker-entrypoint-initdb.d — pg-data 볼륨이 비어있을 때만).
--     Helm:    pre-install/pre-upgrade Job 이 매번 실행합니다 (infra/helm/idem/files/init-db.sql — 사본, CI 가 대조).
--
-- [역할 제한]
--   스키마(네임스페이스) 생성만 담당합니다.
--   테이블·인덱스·시드 데이터는 각 서비스 Flyway 마이그레이션이 전담합니다:
--     · idem-hub/src/main/resources/db/migration/V*.sql
--     · idem-gate/src/main/resources/db/migration/V*.sql
--     · idem-tenant-sample/src/main/resources/db/migration/V*.sql
--     · idem-registry/src/main/resources/db/migration/postgresql/V*.sql (D1 부터 PostgreSQL)
--
-- [DB 구조] PostgreSQL 16 하나: idem_gate / idem_hub / idem_registry / agency_stub / keycloak 스키마 (D1: MariaDB 제거, S9 PR-2 개명 5단계)
--
-- [업그레이드 가드 — 1.0.1, 3차 점검 H7]
--   구 이름(0.x: ido / qsign / qim / authz)의 스키마가 아직 있으면 새 이름의 스키마를 **만들지 않는다**.
--   새 스키마가 먼저 생기면 앱의 LegacySchemaRename 이 rename 을 건너뛰고 빈 스키마에 새 테이블을 만들어 구 데이터가 고아가 된다.
--   구 스키마는 앱 첫 기동(LegacySchemaRename) 또는 scripts/upgrade/rename-db-1.0.sh 가 새 이름으로 옮긴다.
-- ──────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
  pair TEXT[];
BEGIN
  FOREACH pair SLICE 1 IN ARRAY ARRAY[['idem_gate','qsign'], ['idem_hub','ido'], ['idem_registry','qim'], ['idem_authz','authz']] LOOP
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = pair[2]) AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = pair[1]) THEN
      RAISE NOTICE '[Idem 개명] 구 스키마 % 가 있어 % 를 만들지 않습니다 — 앱 첫 기동(LegacySchemaRename) 또는 scripts/upgrade/rename-db-1.0.sh 가 옮깁니다', pair[2], pair[1];
    ELSE
      EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', pair[1]);
    END IF;
  END LOOP;
END $$;

-- Agency-Stub 스키마
CREATE SCHEMA IF NOT EXISTS agency_stub;

-- Keycloak 스키마 (§10 — 테이블은 Keycloak이 자동 생성)
CREATE SCHEMA IF NOT EXISTS keycloak;
