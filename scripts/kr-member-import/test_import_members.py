#!/usr/bin/env python3
"""import_members.py 의 CSV 검사·본문 조립·재개 목록·--dry-run 을 검사한다 (표준 라이브러리 unittest, 네트워크 없음).

    python3 -I -m unittest discover -s scripts/kr-member-import -p 'test_*.py'

registry 를 실제로 부르는 순서(register-subject → convert → 재실행 existing/409 → GET = exists)는
editions/idem-kr-registry 의 KrMemberImportIntegrationTest 가 본다 — 시험 항목 E-6.
"""
import contextlib
import io
import os
import sys
import tempfile
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_members as im  # noqa: E402


def row(**kw):
    base = {"source_id": "mbr-1", "scheme": "CI", "subject_key": "ci-raw-0001", "name": "홍길동", "mobile": "01012345678",
            "birth_year": "1980", "gender": "M", "nationality": "DOMESTIC", "auth_level": "", "tenant_code": "",
            "member_type": "INDIVIDUAL", "biz_reg_no": "", "company_name": "", "rep_name": "", "biz_type": ""}
    base.update(kw)
    return base


class ValidateRowTest(unittest.TestCase):
    def test_valid_individual_and_biz(self):
        self.assertEqual(im.validate_row(2, row(), "L2"), [])
        self.assertEqual(im.validate_row(3, row(member_type="BIZ", biz_reg_no="123-45-67890", company_name="주식회사 예제"), "L2"), [])
        self.assertEqual(im.validate_row(4, row(scheme="", auth_level="", member_type=""), "L2"), [])   # 비면 기본값 CI·--auth-level·INDIVIDUAL

    def test_errors_name_the_row(self):
        errs = im.validate_row(7, row(source_id=" ", scheme="SSN", subject_key="", auth_level="L9", birth_year="80", member_type="CORP"), "L2")
        self.assertEqual(len(errs), 6)
        self.assertTrue(all(e.startswith("행 7: ") for e in errs))
        self.assertTrue(any("source_id" in e for e in errs) and any("scheme" in e for e in errs) and any("subject_key" in e for e in errs))
        self.assertTrue(any("auth_level" in e for e in errs) and any("birth_year" in e for e in errs) and any("member_type" in e for e in errs))

    def test_biz_requires_company_columns(self):
        errs = im.validate_row(2, row(member_type="biz"), "L2")
        self.assertEqual(errs, ["행 2: BIZ 인데 biz_reg_no 없음", "행 2: BIZ 인데 company_name 없음"])

    def test_default_level_is_validated(self):
        self.assertEqual(im.validate_row(2, row(), "L3"), [])
        self.assertEqual(im.validate_row(2, row(), "L0"), ["행 2: auth_level 잘못됨: L0"])


class BuildRegisterBodyTest(unittest.TestCase):
    def test_full_row(self):
        body = im.build_register_body(row(auth_level="l3", tenant_code="T2"), "LEGACY_SMES", "L2", "DEFAULT", "cid-1")
        self.assertEqual(body, {"scheme": "CI", "subjectKey": "ci-raw-0001", "providerCode": "LEGACY_SMES", "authLevel": "L3",
                                "tenantCode": "T2", "correlationId": "cid-1", "rawName": "홍길동", "rawMobile": "01012345678",
                                "gender": "M", "nationalityType": "DOMESTIC", "birthYear": 1980})

    def test_defaults_and_blank_optionals_are_omitted(self):
        body = im.build_register_body(row(scheme="", name="", mobile=" ", birth_year="", gender="", nationality="", auth_level="", tenant_code=""),
                                      "LEGACY_IMPORT", "L2", "DEFAULT", "cid-2")
        self.assertEqual(body, {"scheme": "CI", "subjectKey": "ci-raw-0001", "providerCode": "LEGACY_IMPORT", "authLevel": "L2",
                                "tenantCode": "DEFAULT", "correlationId": "cid-2"})
        self.assertNotIn("source_id", body)          # 구 식별자는 registry 로 보내지 않는다
        self.assertNotIn("member_type", body)

    def test_email_scheme_passes_through(self):
        body = im.build_register_body(row(scheme="email", subject_key=" a@b.org "), "P", "L1", "DEFAULT", "c")
        self.assertEqual((body["scheme"], body["subjectKey"]), ("EMAIL", "a@b.org"))


class LoadDoneTest(unittest.TestCase):
    def test_only_successful_rows_are_skipped_on_resume(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "mapping.csv")
            with open(p, "w", encoding="utf-8", newline="") as f:
                f.write("source_id,qim_user_id,is_new,biz_converted,error\n")
                f.write("mbr-1,qim-1,true,,\n")
                f.write("mbr-2,,,,register 500: boom\n")
                f.write("mbr-3,qim-3,false,exists,\n")
                f.write("mbr-4,qim-4,true,,biz 409: conflict\n")
            self.assertEqual(im.load_done(p), {"mbr-1": "qim-1", "mbr-3": "qim-3"})
        self.assertEqual(im.load_done(os.path.join(d, "missing.csv")), {})


class DryRunTest(unittest.TestCase):
    HEADER = "source_id,scheme,subject_key,name,mobile,birth_year,gender,nationality,auth_level,tenant_code,member_type,biz_reg_no,company_name,rep_name,biz_type\n"

    def run_main(self, csv_text, *extra):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "members.csv")
            with open(src, "w", encoding="utf-8", newline="") as f:
                f.write(csv_text)
            out, err = io.StringIO(), io.StringIO()
            argv = ["import_members.py", src, "--out", os.path.join(d, "mapping.csv"), "--dry-run", *extra]
            with unittest.mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                rc = im.main()
            return rc, out.getvalue(), err.getvalue(), os.path.exists(os.path.join(d, "mapping.csv"))

    def test_dry_run_validates_only_and_never_writes_mapping(self):
        rc, out, err, mapping = self.run_main(self.HEADER + "mbr-1,CI,ci-raw-0001,홍길동,01012345678,1980,M,DOMESTIC,,,INDIVIDUAL,,,,\n"
                                              + "mbr-2,,ci-raw-0002,,,,,,,,BIZ,123-45-67890,주식회사 예제,김대표,제조\n")
        self.assertEqual(rc, 0, err)
        self.assertIn("CSV 검사 통과: 2행 (BIZ 1건)", out)
        self.assertFalse(mapping)

    def test_dry_run_reports_bad_rows_and_fails(self):
        rc, out, err, _ = self.run_main(self.HEADER + "mbr-1,CI,,,,,,,,,BIZ,,,,\n")
        self.assertEqual(rc, 1)
        self.assertIn("행 2: subject_key 없음", err)
        self.assertIn("CSV 검사 실패 3건", err)

    def test_empty_csv_fails(self):
        rc, _, err, _ = self.run_main(self.HEADER)
        self.assertEqual(rc, 1)
        self.assertIn("행이 없습니다", err)


if __name__ == "__main__":
    unittest.main()
