#!/usr/bin/env python3
"""
KR 에디션 — 기존 회원 일회성 이관 도구 (S9 PR-3, generalization-plan §4 "일회성 이관 도구")

구 플랫폼(SMES 회원 등)의 회원을 Idem registry(idem-kr-registry) 에 등록한다. DB 에 직접 쓰지 않고 registry 의
내부 API 만 부른다 — CI 암호화·identifierHash·DI·tenant 규칙이 registry 안에서 그대로 적용되고, 같은 CI 는
같은 qimUserId 로 합쳐진다(registerOrGet, 멱등). 두 번 돌려도 안전하다.

입력 CSV (UTF-8, 헤더 필수). 열:
  source_id     구 시스템 식별자 (mbrUuid 등) — 출력 매핑에만 쓴다, registry 로 보내지 않는다
  scheme        CI | EMAIL | PHONE | EXTERNAL_SUB   (비면 CI)
  subject_key   주체 키 — CI 원문 / 이메일 / 전화 / 외부 sub
  name          이름 (registry 가 마스킹해 저장)                     [선택]
  mobile        휴대전화 (마스킹 저장)                                [선택]
  birth_year    출생연도 (4자리)                                     [선택]
  gender        registry 값 그대로 (예: M / F)                       [선택]
  nationality   registry nationalityType 값 그대로 (예: DOMESTIC)     [선택]
  auth_level    L1 | L2 | L3 (비면 --auth-level)                     [선택]
  tenant_code   소속 Tenant (비면 --tenant)                           [선택]
  member_type   INDIVIDUAL | BIZ  (BIZ 면 아래 기업 열로 기업회원 전환)  [선택, 기본 INDIVIDUAL]
  biz_reg_no, company_name, rep_name, biz_type                      [BIZ 일 때]

사용
  export IDEM_REGISTRY_URL=http://localhost:8082
  export IDEM_REGISTRY_INTERNAL_API_KEY=…        # install.env 의 값 (셸 히스토리에 남기지 말 것)
  python3 import_members.py members.csv --out mapping.csv [--provider LEGACY_SMES] [--tenant DEFAULT]
                          [--auth-level L2] [--dry-run] [--resume] [--rps 20] [--verify]

출력
  --out 매핑 CSV: source_id, qim_user_id, is_new, biz_converted, error   (구 ID → 새 ID. 기관에 전달하거나 보관)
  마지막 줄 요약: rows / registered(new) / existing / biz converted / errors.  오류가 하나라도 있으면 종료 코드 2.
  --resume: 기존 --out 파일에 qim_user_id 가 있는 source_id 는 건너뛴다(중단 뒤 이어 돌리기).
  --verify: 이관 뒤 매핑의 qim_user_id 를 GET /api/v1/internal/users/{id} 로 다시 확인한다.
  --dry-run: CSV 만 검사하고 registry 를 부르지 않는다.

주의
  · 입력 CSV 에는 CI 원문이 들어 있다 — 이관 뒤 즉시 파기하고, 도구는 CI 를 로그·출력에 남기지 않는다.
  · registry 가 KR 에디션(idem-kr-registry)이어야 BIZ 전환(/api/v1/internal/biz-members/convert)이 있다. 코어 registry 에는 404.
  · 표준 라이브러리만 쓴다(추가 설치 없음).
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
import uuid
from urllib import error, request

SCHEMES = {"CI", "EMAIL", "PHONE", "EXTERNAL_SUB"}
AUTH_LEVELS = {"L1", "L2", "L3"}
OUT_FIELDS = ["source_id", "qim_user_id", "is_new", "biz_converted", "error"]


class Registry:
    def __init__(self, base: str, api_key: str, timeout: float = 10.0):
        self.base = base.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def _call(self, method: str, path: str, body: dict | None, cid: str) -> tuple[int, dict]:
        data = json.dumps(body).encode() if body is not None else None
        req = request.Request(self.base + path, data=data, method=method)
        req.add_header("X-Internal-Api-Key", self.api_key)
        req.add_header("X-Correlation-Id", cid)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        for attempt in range(3):
            try:
                with request.urlopen(req, timeout=self.timeout) as r:
                    raw = r.read()
                    return r.status, (json.loads(raw) if raw else {})
            except error.HTTPError as e:
                raw = e.read()
                try:
                    payload = json.loads(raw) if raw else {}
                except ValueError:
                    payload = {"raw": raw[:200].decode(errors="replace")}
                if e.code >= 500 and attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                return e.code, payload
            except (error.URLError, TimeoutError) as e:
                if attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise RuntimeError(f"registry 연결 실패: {e}") from e
        raise RuntimeError("unreachable")

    def register(self, body: dict, cid: str) -> tuple[int, dict]:
        return self._call("POST", "/api/v1/internal/users/register-subject", body, cid)

    def convert_biz(self, body: dict, cid: str) -> tuple[int, dict]:
        return self._call("POST", "/api/v1/internal/biz-members/convert", body, cid)

    def get_biz(self, qim_user_id: str, cid: str) -> tuple[int, dict]:
        return self._call("GET", f"/api/v1/internal/biz-members/{qim_user_id}", None, cid)

    def get_user(self, qim_user_id: str, cid: str) -> tuple[int, dict]:
        return self._call("GET", f"/api/v1/internal/users/{qim_user_id}", None, cid)


def validate_row(i: int, row: dict, default_level: str) -> list[str]:
    errs = []
    if not (row.get("source_id") or "").strip():
        errs.append("source_id 없음")
    scheme = (row.get("scheme") or "CI").strip().upper()
    if scheme not in SCHEMES:
        errs.append(f"scheme 잘못됨: {scheme}")
    if not (row.get("subject_key") or "").strip():
        errs.append("subject_key 없음")
    level = (row.get("auth_level") or default_level).strip().upper()
    if level not in AUTH_LEVELS:
        errs.append(f"auth_level 잘못됨: {level}")
    by = (row.get("birth_year") or "").strip()
    if by and not (by.isdigit() and len(by) == 4):
        errs.append(f"birth_year 잘못됨: {by}")
    mt = (row.get("member_type") or "INDIVIDUAL").strip().upper()
    if mt not in {"INDIVIDUAL", "BIZ"}:
        errs.append(f"member_type 잘못됨: {mt}")
    if mt == "BIZ":
        for k in ("biz_reg_no", "company_name"):
            if not (row.get(k) or "").strip():
                errs.append(f"BIZ 인데 {k} 없음")
    return [f"행 {i}: {e}" for e in errs]


def build_register_body(row: dict, provider: str, default_level: str, default_tenant: str, cid: str) -> dict:
    scheme = (row.get("scheme") or "CI").strip().upper()
    body = {
        "scheme": scheme,
        "subjectKey": row["subject_key"].strip(),
        "providerCode": provider,
        "authLevel": (row.get("auth_level") or default_level).strip().upper(),
        "tenantCode": (row.get("tenant_code") or default_tenant).strip(),
        "correlationId": cid,
    }
    opt = {
        "rawName": row.get("name"),
        "rawMobile": row.get("mobile"),
        "gender": row.get("gender"),
        "nationalityType": row.get("nationality"),
    }
    for k, v in opt.items():
        if v and v.strip():
            body[k] = v.strip()
    if (row.get("birth_year") or "").strip():
        body["birthYear"] = int(row["birth_year"].strip())
    return body


def load_done(path: str) -> dict[str, str]:
    done: dict[str, str] = {}
    if not os.path.exists(path):
        return done
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r.get("qim_user_id") and not r.get("error"):
                done[r["source_id"]] = r["qim_user_id"]
    return done


def main() -> int:
    ap = argparse.ArgumentParser(description="KR 회원 일회성 이관 → idem-kr-registry")
    ap.add_argument("csv")
    ap.add_argument("--out", required=True, help="매핑 CSV (source_id → qim_user_id)")
    ap.add_argument("--provider", default="LEGACY_IMPORT", help="auth_mean_mapping.provider_code 로 남는 제공자 코드")
    ap.add_argument("--tenant", default="DEFAULT")
    ap.add_argument("--auth-level", default="L2")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--rps", type=float, default=20.0, help="초당 요청 수 상한")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--registry-url", default=os.environ.get("IDEM_REGISTRY_URL", ""))
    args = ap.parse_args()

    api_key = os.environ.get("IDEM_REGISTRY_INTERNAL_API_KEY", "")
    with open(args.csv, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        print("입력 CSV 에 행이 없습니다", file=sys.stderr)
        return 1
    problems = [p for i, r in enumerate(rows, 2) for p in validate_row(i, r, args.auth_level)]
    if problems:
        print("\n".join(problems[:50]), file=sys.stderr)
        print(f"CSV 검사 실패 {len(problems)}건", file=sys.stderr)
        return 1
    print(f"CSV 검사 통과: {len(rows)}행 (BIZ {sum(1 for r in rows if (r.get('member_type') or '').strip().upper() == 'BIZ')}건)")
    if args.dry_run:
        return 0
    if not args.registry_url or not api_key:
        print("IDEM_REGISTRY_URL / IDEM_REGISTRY_INTERNAL_API_KEY 가 필요합니다", file=sys.stderr)
        return 1

    reg = Registry(args.registry_url, api_key)
    done = load_done(args.out) if args.resume else {}
    mode = "a" if args.resume and os.path.exists(args.out) else "w"
    stats = {"rows": len(rows), "new": 0, "existing": 0, "skipped": 0, "biz": 0, "errors": 0}
    min_interval = 1.0 / args.rps if args.rps > 0 else 0.0
    results: list[dict] = []
    with open(args.out, mode, newline="", encoding="utf-8") as out:
        w = csv.DictWriter(out, fieldnames=OUT_FIELDS)
        if mode == "w":
            w.writeheader()
        for row in rows:
            sid = row["source_id"].strip()
            if sid in done:
                stats["skipped"] += 1
                continue
            t0 = time.monotonic()
            cid = f"kr-import-{uuid.uuid4()}"
            rec = {"source_id": sid, "qim_user_id": "", "is_new": "", "biz_converted": "", "error": ""}
            try:
                status, body = reg.register(build_register_body(row, args.provider, args.auth_level, args.tenant, cid), cid)
                if status not in (200, 201) or not body.get("qimUserId"):
                    rec["error"] = f"register {status}: {body.get('message') or body.get('error') or body}"[:300]
                else:
                    rec["qim_user_id"] = body["qimUserId"]
                    rec["is_new"] = str(bool(body.get("isNew") if body.get("isNew") is not None else status == 201)).lower()
                    stats["new" if rec["is_new"] == "true" else "existing"] += 1
                    if (row.get("member_type") or "").strip().upper() == "BIZ":
                        bstatus, bbody = reg.convert_biz({
                            "qimUserId": body["qimUserId"],
                            "bizRegNo": row["biz_reg_no"].strip(),
                            "companyName": row["company_name"].strip(),
                            "repName": (row.get("rep_name") or "").strip() or None,
                            "bizType": (row.get("biz_type") or "").strip() or None,
                            "correlationId": cid,
                        }, cid)
                        if bstatus in (200, 201):
                            rec["biz_converted"] = "true"
                            stats["biz"] += 1
                        elif bstatus == 409 or (bstatus == 400 and "이미" in json.dumps(bbody, ensure_ascii=False)):
                            gstatus, _ = reg.get_biz(body["qimUserId"], cid)
                            rec["biz_converted"] = "exists" if gstatus == 200 else "conflict"
                            if gstatus == 200:
                                stats["biz"] += 1
                            else:
                                rec["error"] = f"biz {bstatus}: {bbody}"[:300]
                        elif bstatus == 404:
                            rec["error"] = "biz 404 — registry 가 KR 에디션(idem-kr-registry)이 아닙니다"
                        else:
                            rec["error"] = f"biz {bstatus}: {bbody.get('message') or bbody}"[:300]
            except RuntimeError as e:
                rec["error"] = str(e)[:300]
            if rec["error"]:
                stats["errors"] += 1
            w.writerow(rec)
            out.flush()
            results.append(rec)
            wait = min_interval - (time.monotonic() - t0)
            if wait > 0:
                time.sleep(wait)

    if args.verify:
        bad = 0
        for rec in results:
            if not rec["qim_user_id"]:
                continue
            status, _ = reg.get_user(rec["qim_user_id"], f"kr-import-verify-{uuid.uuid4()}")
            if status != 200:
                bad += 1
                print(f"VERIFY 실패: source_id={rec['source_id']} qimUserId={rec['qim_user_id']} → {status}", file=sys.stderr)
        print(f"VERIFY {'OK' if bad == 0 else 'FAIL'}: {len(results) - bad}/{len(results)} 확인")
        if bad:
            stats["errors"] += bad

    print(f"rows={stats['rows']} new={stats['new']} existing={stats['existing']} skipped={stats['skipped']} "
          f"biz={stats['biz']} errors={stats['errors']} → {args.out}")
    return 2 if stats["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
