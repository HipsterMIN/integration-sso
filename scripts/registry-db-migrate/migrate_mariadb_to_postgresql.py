#!/usr/bin/env python3
"""
idem-registry(Q-IM) 데이터 이관: MariaDB(qim DB) → PostgreSQL(onepass DB, qim 스키마)  — 범용화 D1

사용 전 준비
  1. PostgreSQL 쪽에 idem-registry 를 한 번 기동해 Flyway 기준선(V1__baseline_registry.sql)을 적용한다 (테이블·인덱스 생성).
     또는 psql 로 직접 적용해도 된다. 이 스크립트는 DDL 을 만들지 않는다.
  2. MariaDB 쪽 idem-registry 를 멈추거나 읽기 전용으로 둔다 (이관 중 쓰기 금지).
  3. pip install pymysql psycopg2-binary

사용
  python3 migrate_mariadb_to_postgresql.py \
      --src "mysql://qim:***@mariadb-host:3306/qim" \
      --dst "postgresql://onepass:***@pg-host:5432/onepass" --dst-schema qim \
      [--truncate] [--batch 2000] [--verify-only]

동작
  - 테이블별로 SELECT → INSERT (배치). FK 순서(qim_user 먼저)로 처리한다.
  - 타입 변환: TINYINT(1) → boolean, JSON 문자열 → jsonb, DATETIME(6)(UTC 숫자) → timestamptz(UTC 로 해석).
  - 이관 후 검증: 테이블별 행수 대조 + PK 순 정렬한 행 해시(SHA-256) 대조. 불일치가 있으면 종료 코드 2.
  - `--verify-only` 는 복사 없이 검증만 한다(리허설 후 재검증용).

주의
  - 자격증명은 인자 대신 환경변수 REGISTRY_SRC_URL / REGISTRY_DST_URL 로 줄 수 있다. 셸 히스토리에 남기지 말 것.
  - flyway_schema_history 는 옮기지 않는다 (PostgreSQL 은 자체 기준선 이력을 가진다).
  - conversion_session 은 V9 에서 삭제된 테이블이라 대상이 아니다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from urllib.parse import urlparse, unquote

# ── 대상 테이블 (FK 순서) ────────────────────────────────────────────────────
# (테이블, PK 컬럼들, 컬럼 목록, 타입 힌트)  타입 힌트: bool=TINYINT(1)→boolean, json=JSON→jsonb, ts=DATETIME(6)→timestamptz
TABLES = [
    ("qim_user", ["qim_user_id"],
     ["qim_user_id", "tenant_code", "status", "withdrawal_reason", "withdrawal_type", "withdrawal_scheduled_at",
      "event_version", "created_at", "updated_at", "withdrawn_at"],
     {"withdrawal_scheduled_at": "ts", "created_at": "ts", "updated_at": "ts", "withdrawn_at": "ts"}),
    ("auth_mean_mapping", ["mapping_id"],
     ["mapping_id", "qim_user_id", "provider_code", "identifier_hash", "status", "linked_at", "revoked_at", "revoke_reason"],
     {"linked_at": "ts", "revoked_at": "ts"}),
    ("user_profile", ["qim_user_id"],
     ["qim_user_id", "name_masked", "mobile_masked", "nationality_type", "ci", "subject_scheme", "subject_key", "di_map",
      "birth_year", "gender", "extra_attributes", "is_minor", "guardian_qim_user_id", "guardian_consent_at", "updated_at"],
     {"di_map": "json", "extra_attributes": "json", "is_minor": "bool", "guardian_consent_at": "ts", "updated_at": "ts"}),
    ("user_status_history", ["history_id"],
     ["history_id", "qim_user_id", "status_before", "status_after", "changed_by", "change_reason", "correlation_id", "occurred_at"],
     {"occurred_at": "ts"}),
    ("outbox", ["event_id"],
     ["event_id", "event_type", "partition_key", "aggregate_id", "event_version", "payload", "topic", "status",
      "retry_count", "error_message", "created_at", "published_at"],
     {"payload": "json", "created_at": "ts", "published_at": "ts"}),
    ("last_event_version", ["consumer_group", "aggregate_id"],
     ["consumer_group", "aggregate_id", "last_version", "last_event_id", "updated_at"], {"updated_at": "ts"}),
    ("processed_event", ["event_id", "consumer_group"],
     ["event_id", "consumer_group", "event_type", "result_code", "processed_at"], {"processed_at": "ts"}),
    ("snapshot_meta", ["snapshot_id"],
     ["snapshot_id", "qim_user_id", "snapshot_version", "topic", "status", "created_at"], {"created_at": "ts"}),
    ("crypto_key_version", ["key_id"],
     ["key_id", "key_type", "key_version", "active", "grace_until", "rotation_reason", "created_by", "created_at"],
     {"active": "bool", "grace_until": "ts", "created_at": "ts"}),
    ("consent_version", ["version_id"],
     ["version_id", "consent_type", "version_tag", "title", "content_url", "required", "status", "effective_at",
      "superseded_at", "created_at"],
     {"required": "bool", "effective_at": "ts", "superseded_at": "ts", "created_at": "ts"}),
    ("consent_record", ["record_id"],
     ["record_id", "qim_user_id", "version_id", "consent_type", "consent_status", "agreed_via", "client_ip", "agreed_at",
      "withdrawn_at", "withdrawal_reason", "created_at"],
     {"agreed_at": "ts", "withdrawn_at": "ts", "created_at": "ts"}),
    ("biz_member", ["qim_user_id"],
     ["qim_user_id", "biz_reg_no", "company_name", "rep_name_masked", "biz_type", "biz_status", "verified_at",
      "converted_at", "updated_at"],
     {"verified_at": "ts", "converted_at": "ts", "updated_at": "ts"}),
]


def connect_src(url: str):
    import pymysql
    u = urlparse(url)
    return pymysql.connect(host=u.hostname, port=u.port or 3306, user=unquote(u.username or ""),
                           password=unquote(u.password or ""), database=u.path.lstrip("/"), charset="utf8mb4",
                           init_command="SET time_zone = '+00:00'")


def connect_dst(url: str):
    import psycopg2
    return psycopg2.connect(url)


def convert(value, hint):
    if value is None:
        return None
    if hint == "bool":
        return bool(value)
    if hint == "json":
        # MariaDB JSON 은 문자열로 온다. jsonb 로 넣기 전에 유효성만 확인한다.
        if isinstance(value, (bytes, bytearray)):
            value = value.decode("utf-8")
        json.loads(value)
        return value
    if hint == "ts":
        # DATETIME(6) 은 타임존 없는 숫자(운영 정책: UTC 저장, V7). UTC 로 해석해 timestamptz 에 넣는다.
        if isinstance(value, datetime) and value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value
    return value


def row_hash(row) -> str:
    def norm(v):
        if v is None:
            return "\\N"
        if isinstance(v, bool):
            return "1" if v else "0"
        if isinstance(v, datetime):
            v = v if v.tzinfo else v.replace(tzinfo=timezone.utc)
            return v.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")
        if isinstance(v, (bytes, bytearray)):
            return v.decode("utf-8")
        if isinstance(v, (dict, list)):
            return json.dumps(v, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        s = str(v)
        # JSON 문자열은 키 순서·공백을 정규화해 비교한다
        if s[:1] in "{[":
            try:
                return json.dumps(json.loads(s), sort_keys=True, separators=(",", ":"), ensure_ascii=False)
            except ValueError:
                pass
        return s
    return hashlib.sha256("\x1f".join(norm(v) for v in row).encode("utf-8")).hexdigest()


def table_digest(cur, table, pk, cols, hints, quote, schema=None):
    order = ", ".join(quote(c) for c in pk)
    fq = f"{schema}.{table}" if schema else table
    cur.execute(f"SELECT {', '.join(quote(c) for c in cols)} FROM {fq} ORDER BY {order}")
    h = hashlib.sha256()
    n = 0
    for row in cur:
        h.update(row_hash([convert(v, hints.get(c)) if hints.get(c) in ("bool", "ts", "json") else v
                           for c, v in zip(cols, row)]).encode())
        n += 1
    return n, h.hexdigest()


def copy_table(src, dst, schema, table, cols, hints, batch, truncate):
    q_my = lambda c: f"`{c}`"
    q_pg = lambda c: f'"{c}"'
    with src.cursor() as sc, dst.cursor() as dc:
        if truncate:
            dc.execute(f'TRUNCATE TABLE {schema}.{table} CASCADE')
        sc.execute(f"SELECT {', '.join(q_my(c) for c in cols)} FROM `{table}`")
        placeholders = ", ".join("%s::jsonb" if hints.get(c) == "json" else "%s" for c in cols)
        sql = f'INSERT INTO {schema}.{table} ({", ".join(q_pg(c) for c in cols)}) VALUES ({placeholders})'
        total = 0
        while True:
            rows = sc.fetchmany(batch)
            if not rows:
                break
            dc.executemany(sql, [tuple(convert(v, hints.get(c)) for c, v in zip(cols, r)) for r in rows])
            total += len(rows)
        dst.commit()
    return total


def verify(src, dst, schema):
    ok = True
    print(f"{'table':24} {'src_rows':>9} {'dst_rows':>9}  result")
    for table, pk, cols, hints in TABLES:
        with src.cursor() as sc, dst.cursor() as dc:
            n1, h1 = table_digest(sc, table, pk, cols, hints, lambda c: f"`{c}`")
            n2, h2 = table_digest(dc, table, pk, cols, hints, lambda c: f'"{c}"', schema)
        same = (n1 == n2 and h1 == h2)
        ok &= same
        print(f"{table:24} {n1:>9} {n2:>9}  {'OK' if same else 'MISMATCH (rows or hash)'}")
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", default=os.environ.get("REGISTRY_SRC_URL"), help="mysql://user:pw@host:3306/qim")
    ap.add_argument("--dst", default=os.environ.get("REGISTRY_DST_URL"), help="postgresql://user:pw@host:5432/onepass")
    ap.add_argument("--dst-schema", default="qim")
    ap.add_argument("--batch", type=int, default=2000)
    ap.add_argument("--truncate", action="store_true", help="복사 전 대상 테이블 TRUNCATE (리허설 재실행용)")
    ap.add_argument("--verify-only", action="store_true")
    a = ap.parse_args()
    if not a.src or not a.dst:
        ap.error("--src/--dst (또는 REGISTRY_SRC_URL/REGISTRY_DST_URL) 필요")

    src, dst = connect_src(a.src), connect_dst(a.dst)
    try:
        if not a.verify_only:
            if a.truncate:
                # FK 역순으로 비운다
                with dst.cursor() as dc:
                    for table, *_ in reversed(TABLES):
                        dc.execute(f"TRUNCATE TABLE {a.dst_schema}.{table} CASCADE")
                dst.commit()
            for table, pk, cols, hints in TABLES:
                n = copy_table(src, dst, a.dst_schema, table, cols, hints, a.batch, truncate=False)
                print(f"copied {table:24} {n:>9}")
        ok = verify(src, dst, a.dst_schema)
        print("VERIFY", "OK" if ok else "FAILED")
        sys.exit(0 if ok else 2)
    finally:
        src.close()
        dst.close()


if __name__ == "__main__":
    main()
