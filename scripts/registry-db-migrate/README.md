# idem-registry 데이터 이관 — MariaDB → PostgreSQL (D1)

> `docs/generalization-plan.md` v0.3 D1 · §5 "D1 의 registry DB 이관은 데이터 이관 도구 + 리허설을 같이 낸다"

D1 부터 idem-registry(Q-IM)는 hub 와 같은 PostgreSQL 인스턴스의 `qim` 스키마를 쓴다. 기존 MariaDB 설치는
`--spring.profiles.active=...,mariadb` 로 1 릴리스 동안 그대로 돌 수 있고, 아래 절차로 데이터를 옮긴 뒤 프로파일을 끈다.

## 절차

1. **PostgreSQL 준비**: `onepass` DB 에 `qim` 스키마. idem-registry 를 PostgreSQL 설정으로 한 번 기동하면 Flyway 가
   `db/migration/postgresql/V1__baseline_registry.sql` 을 적용한다(테이블·인덱스·`crypto_key_version` 시드). 기동 후 내린다.
2. **MariaDB 쪽 쓰기 중단**: idem-registry(MariaDB 프로파일)·idem-relay 를 멈춘다. 아웃박스 `PENDING` 이 남아 있으면 relay 가 다 비울 때까지 기다린 뒤 멈춘다.
3. **리허설** (스테이징 또는 운영 스냅샷):
   ```bash
   pip install pymysql psycopg2-binary
   export REGISTRY_SRC_URL='mysql://qim:***@mariadb-host:3306/qim'
   export REGISTRY_DST_URL='postgresql://onepass:***@pg-host:5432/onepass'
   python3 scripts/registry-db-migrate/migrate_mariadb_to_postgresql.py --truncate
   ```
   출력 마지막 `VERIFY OK` 를 확인한다. 테이블별 행수와 PK 순 정렬 행 해시(SHA-256)를 대조하므로 `MISMATCH` 가 하나라도 있으면 종료 코드 2 다.
4. **본 이관**: 3 과 같은 명령. `crypto_key_version` 시드는 기준선이 이미 넣었으므로 `--truncate` 가 지운 뒤 MariaDB 값으로 다시 채운다(키 버전 메타는 양쪽이 같아야 한다).
5. **전환**: idem-registry·idem-relay 를 PostgreSQL 설정(`IDEM_REGISTRY_DB_HOST/PORT/NAME/SCHEMA/USERNAME/PASSWORD`, relay 는 `IDEM_REGISTRY_DB_URL` 기본값)으로 기동. `mariadb` 프로파일과 MariaDB 컨테이너·RDS 는 다음 릴리스에서 제거한다.
6. **재검증**: 전환 후 언제든 `--verify-only` 로 두 DB 를 다시 대조할 수 있다(MariaDB 를 읽기 전용으로 남겨 둔 동안).

## 변환 규칙

| MariaDB | PostgreSQL | 비고 |
|---|---|---|
| `DATETIME(6)` (UTC 숫자, V7 정책) | `timestamptz(6)` | UTC 로 해석. MariaDB 세션 `time_zone='+00:00'` 으로 읽는다 |
| `JSON` | `jsonb` | 유효 JSON 인지 확인 후 그대로 |
| `TINYINT(1)` | `boolean` | |
| `flyway_schema_history` | 옮기지 않음 | PostgreSQL 은 기준선 이력을 따로 가진다 |
| `conversion_session` | 없음 | V9 에서 삭제된 테이블 |

## 검증 방식

테이블마다 `SELECT <컬럼> ... ORDER BY <PK>` 로 양쪽을 읽어 행수와 행 해시를 비교한다. JSON 은 키 순서·공백을 정규화하고,
시각은 UTC 마이크로초 문자열로 맞춘 뒤 해시한다. 불일치 시 어느 테이블인지 표에 표시된다.

## 이 저장소 환경에서 확인한 것 / 못 한 것

- PostgreSQL 기준선 SQL 은 registry 단위·통합 테스트(245건, Hibernate `ddl-auto=validate` 포함)로 검증했다.
- **MariaDB → PostgreSQL 실데이터 리허설은 이 환경(MariaDB 없음)에서 돌리지 못했다.** 스테이징에서 3 을 먼저 수행하고 결과를 이 문서에 기록한다.
