# KR 회원 일회성 이관 — `import_members.py`

> S9 PR-3 · `docs/generalization-plan.md` §4 "기존 회원 계정과 자동으로 이어 달라 → 일회성 이관 도구(KR 에디션)"

구 플랫폼(SMES 회원)의 회원을 Idem registry 에 **한 번** 옮긴다. DB 에 직접 쓰지 않고 `idem-kr-registry` 의 내부 API 만 부르므로 CI 암호화·identifierHash·DI·Tenant 규칙이 registry 안에서 그대로 적용되고, 같은 CI 는 같은 `qimUserId` 로 합쳐진다(멱등). 결과는 `source_id → qim_user_id` 매핑 CSV 로 남아 기관 측 첫 로그인 연결이나 대조에 쓴다.

## 절차

1. **입력 준비**: 구 시스템에서 회원을 CSV 로 뽑는다(열은 스크립트 머리말). CI 원문이 들어 있으므로 암호화된 저장소에서만 다루고 이관 뒤 파기한다.
2. **검사**: `python3 import_members.py members.csv --out mapping.csv --dry-run` — 열·값 형식만 검사한다.
3. **리허설**: 스테이징 registry(KR 에디션, 빈 DB)에 돌려 매핑 CSV 와 요약을 본다. 두 번 돌려 `new=0 existing=N` 인지(멱등) 확인한다.
   ```bash
   export IDEM_REGISTRY_URL=https://registry.stage.example.org
   export IDEM_REGISTRY_INTERNAL_API_KEY=…      # install.env 값. 셸 히스토리에 남기지 말 것
   python3 import_members.py members.csv --out mapping.csv --provider LEGACY_SMES --tenant DEFAULT --verify
   ```
4. **본 이관**: 운영 registry 에 같은 명령. 중단되면 `--resume` 으로 이어 돌린다(매핑에 있는 source_id 는 건너뜀).
5. **대조**: 요약의 `errors=0`, `--verify` 의 `VERIFY OK`. 매핑 행수 = 입력 행수.
6. **기업회원**: `member_type=BIZ` 행은 개인 등록 뒤 `/api/v1/internal/biz-members/convert` 로 기업회원 전환까지 한다(같은 사업자번호가 이미 있으면 `exists`).
7. **파기**: 입력 CSV 를 지운다. 매핑 CSV 에는 CI 가 없다(구 ID 와 새 ID 만).

## 동작

| 단계 | API | 멱등성 |
|---|---|---|
| 개인 등록 | `POST /api/v1/internal/users/register-subject` `{scheme, subjectKey, providerCode, authLevel, rawName, rawMobile, birthYear, gender, nationalityType, tenantCode}` | 같은 `identifierHash`(스킴+주체 키) → 기존 사용자 반환(`isNew=false`) |
| 기업 전환 | `POST /api/v1/internal/biz-members/convert` `{qimUserId, bizRegNo, companyName, repName, bizType}` | 이미 있으면 `GET /api/v1/internal/biz-members/{id}` 로 확인해 `exists` |
| 확인 | `GET /api/v1/internal/users/{id}` | — |

`--provider` 는 `auth_mean_mapping.provider_code` 로 남는다(기본 `LEGACY_IMPORT`). 이관된 사용자가 나중에 실제 본인확인(예: NICE)으로 로그인하면 같은 CI 해시로 같은 사용자에 붙는다.

## 이 환경에서 확인한 것

로컬 PostgreSQL + `idem-kr-registry` 부트 jar 로: 개인 2 + 기업 1 + 중복 CI 1 행 → 첫 실행 `new=3 existing=1 biz=1`(중복 CI 두 행이 같은 `qim_user_id`), 두 번째 실행 `new=0 existing=4 biz=1(exists)`, `--resume` 는 전부 건너뜀, `--verify OK`. 코어 registry(`idem-registry`)에 BIZ 행을 보내면 전환 API 가 없어 오류(현재 코어는 500 으로 답한다)로 기록되고 종료 코드 2 — 개인 등록은 그대로 된다. 운영 규모(수십만 행)의 소요 시간은 `--rps` 로 조절한다(기본 20/s ≈ 1시간에 7만 행). 실제 SMES 데이터로는 아직 돌려 보지 못했다 — 리허설(§절차 3)이 필수다.
