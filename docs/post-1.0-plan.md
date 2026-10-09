# 1.0 이후 실행 플랜 — GS 인증 → 1.1 → 2.0

> 작성 2026-09-27 · 기준 `main` 30928db (v1.0.1 + 문서 정정 PR #247·#248·#249) · 상위 문서 [`execution-plan.md`](execution-plan.md)(P3 GS 단계의 상세), [`certification/gs-kickoff.md`](certification/gs-kickoff.md)(제출물·일정 초안), [`generalization-plan.md`](generalization-plan.md)(S1~S9 완료 기록과 "남긴 것").
>
> 목표: **GS 1등급 → 조달청 종합쇼핑몰 등록**을 1.0.x 로 끝내고, 그 뒤 1.1 에서 기능 공백을, 2.0 에서 개명 완료·SAML·CC 준비를 한다. 작업 방식은 지금과 같다 — `shipster` 브랜치, PR 단위, CI 초록, 머지는 사용자 지시("머지"), 자동 점검·예약 없음.

---

## 0. 한 장 요약

| 단계 | 기간 | 핵심 | 완료 기준 |
|---|---|---|---|
| **G0 결정** | 이번 주 | 사용자 결정 7건, 태그·키 회전 | §1 표의 결정이 기록됨, `v1.0.1` 태그 |
| **G1 시험 준비 (1.0.2 → 1.1.0 뒤에는 1.1.1)** | 3~4주 | 시험 항목 70 전수 1회 완주, 오프라인 설치·백업 복구 리허설, 콘솔 보완 3건, 웹훅 비밀 API, hub 지표 | 결함 목록·밀도 산출, 리허설 결과가 매뉴얼에, CI 초록 |
| **G2 제출물** | 2주 (G1 후반 병행) | 매뉴얼 양식 변환·스크린샷, 사용자 매뉴얼 요약, 설치본 tar·체크섬, 성능 재측정 | 제출 문서 세트 v1, 설치본 tar |
| **G3 신청·시험** | 8~16주 | 시험원 사전 검토 → 신청 → 시험 → 보완(1.0.x 패치) | GS 1등급, 조달 등록 |
| **1.1** | G3 와 병행 시작, 8~12주 | 에이전트 결정 실행, SCIM 아웃바운드, 코어 로그인 프런트(Handoff 브라우저 진입), 남긴 것 5건, K8s 실배포, AI 운영 보조(선택 컨테이너) | 1.1.0 태그, 시험 항목표 갱신 |
| **2.0** | 1.1 후 | API 경로·오류 코드 개명 완료, 호환 계층 제거, SAML SP, CC 산출물 착수 | `execution-plan.md` P4 |

---

## 1. G0 — 결정과 정리 (이번 주, 사용자)

| # | 결정·작업 | 기본 제안 | 왜 지금 |
|---|---|---|---|
| 1 | 신청 법인·담당자·예산(시험비·컨설팅) | — | 시험원 견적의 전제 |
| 2 | 시험원 | TTA SW시험인증연구소 (공공 조달 실적) | 사전 검토 일정 확보 |
| 3 | 시험 환경 | 신청기관 반입 서버 1대(Compose). 시험원 제공 시 오프라인 설치본 | G1 의 리허설 대상이 정해진다 |
| 4 | 대상 에디션 | **core**, kr 은 부가 모듈로 기술 (벤더 SDK 라이선스가 시험원에 못 들어감) | 제품 설명서 확정 |
| 5 | 할당 관리 화면을 1.0.2 에 넣을지 | **넣는다** — 사용성 시험에서 "인가" 기능을 화면으로 보여야 한다 | G1 범위 |
| 6 | Java 에이전트 처리 | **제품 설명서·요구사항 체크리스트에서 1.0 연동 수단에서 제외**(이미 문서 반영). hub 검증 API 는 1.1 에서 결정 → **2026-09-29 결정: 저장소 분리(§5 #1 (b))**, 1.1 PR-4 | 시험 범위 밖으로 명확히 |
| 7 | Handoff 브라우저 진입 시연 방식 | GS 기능 시험은 **표준 OIDC(OIDC_RP)** 경로로. Handoff 는 `idem-tenant-sample` 시뮬레이터 + D-10 으로 시연 | core 에 Handoff 발급 로그인 프런트가 없다 → **1.1 PR-3 로 해소**(코어 로그인 프런트, D-16·스모크 ⑦b) — 1.1 이후에는 Handoff 도 브라우저로 시연 가능 |
| 8 | 태그 `v1.0.1` | `a81b0cf`(버전 커밋) 또는 `30928db`(현재 main) 중 택일 | **2026-10-08 결정: 건너뜀** — 원격 태그는 `v1.0.0`·`v1.1.0`. 1.0.1 은 CHANGELOG `[1.0.1]` 과 `release/1.0` 으로만 식별한다 |
| 9 | 벤더 개발 키 회전 (NICE·Any-ID) | 벤더에 요청 | 소스 tar 반출 전 필수 |
| 10 | 소개서 공유 핀을 v3 로 | 아티팩트 Share 메뉴 | 공개 링크가 구판을 보여 준다 |

---

## 2. G1 — 시험 준비, 1.0.2 (3~4주)

PR 단위로 나눈다. 순서는 의존 관계 순이며 병행 가능한 것은 표시했다.

### 2.1 PR-G1-1 · 시험 항목 70 전수 1회 완주 (1주, 사용자 환경 + AI)

- 자동 61 은 CI 결과로 갈음하되 **로컬 IT 7건**(B-3·B-5·E-1·E-3·D-18·F-7·G-10)은 사용자 로컬에서 `git push` 훅으로 1회 실행 기록.
- **수동 9건**(A-2 필수 비밀 없이 기동, A-5 0.x→1.0 업그레이드, B-8 테넌트 범위, C-7 승인, D-8 SLO, E-6 KR 기업회원 이관, G-2 관측 — G-3 지표는 1.1.1 에서 자동, G-5 백업 복구, G-6 오프라인 설치)을 시험 환경 방식(§1 #3)과 같은 구성에서 순서대로 수행하고 결과·소요 시간·스크린샷을 `docs/manuals/test-items.md` 에 열(실행일·결과)로 추가.
- 발견 결함은 이슈로 만들고 심각도별로 PR-G1-2 이하에 배정. **결함 밀도**(결함 수 / 항목 수)를 gs-kickoff §2 에 기록.
- 완료 기준: 70/70 실행 기록(시험 항목표 1.1.1 집계), 미통과 항목 0 또는 수정 PR 배정.

### 2.2 PR-G1-2 · 오프라인 설치본 + 백업·복구 (1주, AI 스크립트 + 사용자 리허설)

- `scripts/release/make-offline-bundle.sh`: 이미지 6종(gate·hub·registry·authz·console-admin·keycloak/postgres/redis 는 공식 이미지) `docker save` → tar, 소스 tar(`git archive v1.0.x`), `SHA256SUMS`, 크기 기록. compose 는 `image:` 태그 고정.
- `scripts/ops/backup.sh`·`restore.sh`: `pg_dump`(DB `idem` 전체 스키마) + Redis 는 재로그인으로 갈음 + `install.env` 제외 안내. 복구 후 A-1·B-2 재확인 절차.
- 리허설 결과(시간·용량·문제)를 `docs/manuals/installation-manual.md` §3.3·§6 "검증한 것" 으로 옮기고 "미실시" 문구 제거.
- 완료 기준: G-5·G-6 통과 기록.
- **2026-10-08 상태**: 스크립트 4종(`scripts/release/make-offline-bundle.sh`·`load-offline-bundle.sh`, `scripts/ops/backup.sh`·`restore.sh`) + CI(스모크 "백업·복구" 단계, `offline-bundle-check` 잡) + 매뉴얼 §3.3·§6·§8 반영. CI 첫 통과(PR #261): 번들 1.4 GB(이미지 8종)·소스 14 MB·차트 24 KB, 생성→삭제→복원→대조 OK; 스모크 DB(스키마 4개 75표) 백업 244 KB → 새 DB 복구 → 표 수·행 수 대조 OK. 남은 것은 사용자 환경 리허설 — 운영 DB 복구(앱 정지→복구→재기동, G-5)와 폐쇄망 반입·설치(G-6). 1.1.0 이 나갔으므로 G1 동결 버전은 1.0.2 가 아니라 **1.1.1**.

### 2.3 PR-G1-3 · 관리 콘솔 보완 3건 (1~2주, AI) — 병행 가능

- **할당 관리 화면**: 서비스 상세 → 사용자·그룹 할당 목록·추가·회수(`idem-authz` 내부 API 를 hub 관리 API 로 노출, 역할·테넌트 범위 검사, 감사 기록). 시험 항목 B 절에 1행 추가.
- **기관 목록 페이징**: `GET /api/v1/admin/services` 에 `page/size`(기본 50, 최대 200), 콘솔 목록 페이징.
- **TOTP QR**: 등록 화면에 `otpauth://` QR 이미지(클라이언트 측 생성, 비밀은 서버에서 1회만).
- 완료 기준: vitest·UT 추가, E2E 1회 수동 기록(관리자 매뉴얼 스크린샷과 겸함).
- **2026-10-09 상태**: 셋 다 들어갔다 — 할당 관리 카드 + hub 관리 API(`/services/{code}/assignments·roles`, authz 위임, 감사 5종, 새 오류 코드 E-IDO-127~129), 기관 목록 `page/size/q` 봉투(DB 에서 테넌트 거름), 등록 QR(브라우저 생성). CI 스모크 ⑧c 가 실제 authz 로 끝-끝을 돈다. 남은 것은 E2E 1회 수동 기록(QR 을 인증 앱으로 찍는 부분, 관리자 매뉴얼 스크린샷)·시험 항목 B-16·B-17 완주 — G1-1 과 함께.

### 2.4 PR-G1-4 · 웹훅 서명 비밀 관리 API + hub 지표 (1주, AI) — 병행 가능

- `POST /api/v1/admin/agencies/{code}/webhook/rotate-secret`: 비밀 생성·해시 저장·응답 1회 노출, `agency_webhook_config.signing_secret_hash` 의 의미 정리(현재 원문 저장 위치로 쓰임 — 발송 시 원문이 필요하므로 KMS 봉인 저장으로 바꾼다). 운영 가이드 §3 갱신.
- hub `/actuator/prometheus` 등록(관리 포트). 시험 항목 G-3 의 "hub 미등록" 제한 해소.
- 완료 기준: UT, 설치본 스모크에 지표 200 확인 1줄 추가.
- **2026-10-09 상태**: 완료(PR-G1-4). 회전 API `POST /api/v1/admin/agencies/{code}/webhook/rotate-secret` + `GET …/webhook`, `signing_secret_sealed`(V31, KMS 봉인)·해시는 지문용, 1.0.x 원문 행은 첫 기동에 봉인. 조사에서 드러난 결함 2건도 수리(관리 API 로 만든 기관의 웹훅 설정 INSERT 가 FK(미flush)·NOT NULL 에 걸려 조용히 실패, `webhook_enabled` 를 켜는 코드 없음). Prometheus 는 **네 앱 모두** 레지스트리가 없었다(hub 만이 아니라) → 네 앱에 추가, 스모크 ⑧a·prod 단계·리허설이 200 을 본다. 경로는 플랜의 `/api/v1/admin/agencies/{code}/webhook/rotate-secret` 그대로(기존 `rotate-key` 와 나란히; 2.0 개명 때 `/services/` 로).

### 2.5 PR-G1-5 · 1.1.1 동결 (2일) — 1.1.0 이 먼저 나가 1.0.2 가 아니라 1.1.1

- CHANGELOG `[1.1.1]` + 업그레이드 메모, 버전 bump(루트·Helm·콘솔·SDK·웹훅 `platformVersion`), 시험 항목표 재집계(70항목·자동 61·수동 9 — G1-3 에서 반영), gs-kickoff §2 상태 갱신. 태그 `v1.1.1` 은 머지 커밋에 사용자(세션 프록시가 태그·타 브랜치 push 를 막는다). `release/1.0` 은 1.0.x 패치 전용이라 1.1.1 은 반영하지 않는다 — 1.1.x 패치 브랜치가 필요해지면 그때 `release/1.1`.
- **2026-10-09 상태**: 버전 커밋 PR-G1-5. G1 의 AI 몫(G1-2·G1-3·G1-4·G1-5)은 끝 — 남은 G1-1 은 사용자 환경(시험 항목 70 전수 1회 완주, 운영 DB 복구·폐쇄망 반입 리허설, 결함 밀도). 다음 AI 작업은 G2 제출물(§3) 또는 G1-1 에서 나온 결함.

---

## 3. G2 — 제출 문서 세트 (2주, G1 후반 병행)

| 산출물 | 작업 | 담당 |
|---|---|---|
| 제품 설명서 | `docs/manuals/product-spec.md` → 시험원 양식, 구성도·화면 캡처(로그인·콘솔·프로파일·감사) | AI 초안, 사용자 양식 확인 |
| 설치 매뉴얼 | PR-G1-2 결과 반영본을 양식으로, 오프라인 설치 절차·소요 시간 | AI |
| 관리자 매뉴얼 | 할당 화면 포함 스크린샷, 온보딩 4단계 화면 흐름 | AI |
| 사용자 매뉴얼 | 최종 사용자 관점 1~2쪽 요약(로그인·로그아웃·계정 연결·오류 화면) 신규 | AI |
| 시험 항목표 | 58(+G1 추가) 실행 결과 열 포함 | AI |
| 성능 보고 | 시험 환경에서 `k6/` 시나리오 재측정(Handoff p95, 토큰 교환 p95, 200 tps) | 사용자 환경 + AI 스크립트 |
| 형상·결함 관리 | PR 목록, 태그, CHANGELOG, 결함 밀도 | AI |
| 호환성 | 브라우저(Chrome·Edge·Firefox·Safari 최신) × 콘솔·로그인 화면 확인 기록 | 사용자 |

완료 기준: 시험원 사전 검토에 낼 수 있는 문서 세트 v1 과 설치본 tar.

---

## 4. G3 — 사전 검토·신청·시험 (8~16주)

1. 시험원 컨설팅·사전 검토 → 지적 사항을 이슈로 → 1.0.x 패치 PR(문서/코드).
2. 신청 접수. 이후 코드 변경은 **`release/1.0` 에 보안·결함 수정만**, 기능은 1.1 브랜치.
3. 시험 중 결함: 재현 → 수정 → `v1.0.x` 태그 → 시험원 재제출. 매 패치는 CI 초록 + 시험 항목 재실행 기록.
4. 인증서 수령 → 조달 등록 신청. 그 시점 태그를 고정하고 `release/1.0` 은 보안 수정만 받는다.

---

## 5. 1.1 — 기능 공백 해소 (G3 와 병행, 8~12주)

우선순위 순. 각 항목은 PR 1~2개.

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| 1 | Java 에이전트 결정 실행 | (a) hub 에 `POST /api/v1/agency/token/verify` + 브라우저 토큰 발급 경로 추가해 살리거나 (b) 저장소에서 `idem-agent`·테스트베드를 별도 저장소로 분리. **결정 (b), 2026-09-29 사용자** — 1.0 연동 방식(OIDC·Handoff)이 레거시 WAS 도 콜백 서블릿 하나로 덮는다. 1.1 PR-4: 모노레포에서 제거, 코드·테스트베드·문서를 단일 커밋 번들로 새 저장소에 | 개발자 레퍼런스 §10 |
| 2 | 코어 로그인 프런트 | Handoff 유형 서비스의 브라우저 진입(로그인 → 발급 → 콜백)을 core 가 제공. 지금은 KR 포털만 | gs-kickoff 결정 #7 의 후속 |
| 3 | SCIM 아웃바운드 | Idem → 기관 프로비저닝(사용자·그룹) | requirements-checklist §3 |
| 4 | 남긴 것 5건 | 감사 경로 WAL 폴백, SLO 재시도 큐, 그룹·속성 규칙 할당, 할당 변경 이벤트 전파, authz fail-open 잔여 경로 | generalization-plan "남긴 것" |
| 5 | K8s 실배포 리허설 | Helm 차트를 실제 클러스터(kind 또는 운영기관 K8s)에 배포·업그레이드·롤백 1회, 매뉴얼 §3.2 갱신. **1.1 PR-6**: `scripts/k8s/rehearsal.sh` + CI `k8s-rehearsal`(kind, PR·main 마다). 기관 클러스터 1회는 사용자 환경에서 `CLUSTER=existing` | installation-manual §8 |
| 6 | AI 운영 보조 (선택 컨테이너) | 관리 콘솔: 자연어 → 프로파일 초안(스키마 검증 필수), 감사 요약, 장애 요약. 온프레미스 LLM, 기본 설치에서 제외. 인증 경로에는 넣지 않는다. **1.1 PR-7**: `/api/v1/admin/ai/**` + 콘솔 카드·메뉴 + compose `--profile ai`/Helm `ai.*` | AI 도입 검토(2026-09-27 대화) |
| 7 | 감사 로그 이상 탐지 (관찰 모드) | 아웃박스 이후 비동기 점수, 감사 플래그만. 기준선 3개월 뒤 경보 승격 결정. **1.1 PR-8**: `AuditAnomalyScorer` + 규칙 5개 + `audit_anomaly_flag`(V30) + 검토 API·콘솔 "이상 징후" + `docs/audit-anomaly.md` §4 승격 기준 | 같은 검토 |
| 8 | 동의 카탈로그 | S8 에서 남긴 것. **1.1 PR-9**: registry 카탈로그 범위(플랫폼 공통/서비스 전용)·발행·종료·미동의 API + hub 관리 API·콘솔 "동의 항목"·기관 상세 카드 + 프로파일 `consent` + 코어 로그인 프런트 동의 단계(거부 `E-IDO-125`). OIDC_RP 는 기관 RP 화면 | requirements-checklist §3 |

완료 기준: `v1.1.0`, 시험 항목표에 신규 기능 행 추가, 설치본 스모크 갱신. **2026-10-08 상태**: 8건 모두 머지(#251~#259), 시험 항목표 1.1 행 9개(A-8·D-16·D-17·D-18·F-7·F-8·G-8·G-9·G-10), 설치본 스모크는 PR-3 ⑦b(로그인 프런트)·PR-6(관리 포트)까지 갱신 — 동의 단계는 스모크 밖(결정 대기). 버전 커밋(1.1.0) PR 뒤 태그 `v1.1.0` 은 사용자가 만든다.

---

## 6. 2.0 — 개명 완료·SAML·CC 준비

- API 경로·오류 코드 개명(`E-IDO-1xx` → `E-IDEM-…`, `/admin/agencies` → `/admin/services`), `LegacyNames` 호환 계층·`LegacySchemaRename` 제거, 에이전트 런타임 이름 정리는 분리된 저장소의 몫.
- SAML SP(`protocol.type=SAML_SP`, Keycloak SAML client 활용) — `saml-sp-design.md`.
- CC: `execution-plan.md` P4 — ST·기능명세·지침서, KCMVP 모듈 교체(P2 잔여), 평가기관 계약. GS 인증서·조달 등록이 선행.

---

## 7. 병행 트랙 (상시)

- **오픈소스 공개 절차**: 벤더 키 회전 확인 → 이력 정리 방식 결정(스냅샷 저장소) → `vendor-plugin-plan.md` P5.
- **문서 정합성 검사 자동화**: 이번 주에 사람이 찾은 불일치(스키마 이름·수치·구명)를 CI 에서 grep 규칙으로 막는 `docs-lint` 스텝(NamingGuard 의 문서판).
- **의존성·이미지 취약점**: OWASP 수동 실행 월 1회, Trivy 결과 검토.

---

## 8. 위험

| 위험 | 영향 | 대응 |
|---|---|---|
| 시험 환경에서 처음 도는 수동 항목 10건에서 결함 발견 | G1 지연 | G1-1 을 가장 먼저, 결함은 G1-2~4 에 흡수 |
| 오프라인 설치·백업 복구가 예상보다 어려움(이미지 크기·볼륨) | G2 지연 | 스크립트를 AI 가 먼저 만들고 사용자 환경에서 1회 리허설 |
| 시험원이 에이전트·SDK·Handoff 를 제품 기능으로 봄 | 범위 논쟁 | 제품 설명서 §1·§6 에 "도구"와 "1.0 연동 수단 아님"이 이미 명시. 사전 검토에서 확인 |
| 벤더 키 회전 지연 | 소스 반출 불가 | G0 에서 즉시 요청 |
| 1.1 기능 개발이 1.0.x 패치와 충돌 | 시험 중 회귀 | `release/1.0` 은 보안·결함만, 기능은 `shipster`(1.1) |

---

## 9. 진행 기록

| 날짜 | 내용 |
|---|---|
| 2026-09-27 | 플랜 작성. main 30928db. G0 결정 대기 |
| 2026-09-28 | 1.1 착수(사용자 지시). PR-1: §5 #4 중 authz fail-open 잔여(단일 해석기·prod 가드)·할당 변경 이벤트 전파(authz 아웃박스 → 피드 → hub 폴러 → 웹훅 `ASSIGNMENT_CHANGED`)·SLO IdP 재시도 큐(gate 502·`slo_idp_logout_retry`). 감사 WAL 폴백·규칙 할당은 PR-2 |
| 2026-09-28 | PR-1 = #251(CI 통과, 머지 대기). PR-2: 감사 WAL 폴백(`AuditWal`·`AuditWalReplayer`, prod 가드, compose 볼륨·Helm `hub.auditWal`) + 그룹·속성 규칙 할당(authz V6 `authz_assignment_rule`, `POST /users/{id}/access` 실체화, 재평가·비활성화 회수, hub `AssignmentContext` = authLevel·providerCode 만). authz 실기동으로 규칙 생성→실체화→회수→피드 확인. 이로써 §5 #4 "남긴 것 5건" 마감 |
| 2026-09-29 | PR-5 = §5 #3 SCIM 아웃바운드: 프로파일 `protocol.scim`(토큰은 참조만) → `scim_outbox`(V29) → 릴레이 → 기관 SCIM 서버(Users·Groups 부분집합), registry 정지·탈퇴 전파, 전체 동기화 관리 API, authz 할당 목록 API, 샘플 SCIM 서버. 부수: PR-1 감사 분류 `AUTHZ` 가 CHECK 에 없어 INSERT 가 조용히 실패하던 결함 수리 |
| 2026-09-29 | #253 머지(main 1842677). 사용자 결정: Java 에이전트 저장소 분리 → PR-4: `idem-agent`·`idem-agent-testbed`·에이전트 문서 7건 제거, settings/build/Dockerfile/githook/notice 정리, 새 저장소용 번들(`idem-agent.bundle`, 이력 없이 단일 커밋 — 옛 설정 파일 비밀값 정리 이력을 끌고 가지 않기 위해) 전달 |
| 2026-09-29 | #251·#252 머지(main a27ef20). PR-3 = §5 #2 코어 로그인 프런트: `GET /api/v1/handoff/login` 진입 → SPI/브로커 로그인 → hub 내부 발급 → `callback?ticketId&state`. 조사에서 드러난 결함 셋도 수리: FE 세션 쿠키 이름 불일치(발급 API·CAST 가 `Fe-Session-Id` 를 읽어 브라우저 발급 불가), 발급 API 기관 바인딩 없음, qsign 모드 쿠키 미전달(바인드 코드). 샘플 기관 `/agency/login`·`/agency/callback`, 스모크 ⑦b, D-16·D-17 |
| 2026-09-29 | #254·#255 머지(main bf399bd). PR-6 = §5 #5 K8s 실배포 리허설: `scripts/k8s/rehearsal.sh`(up·install·smoke·upgrade·rollback·down, kind 또는 기존 클러스터, 이미지 local/archive/registry) + 리허설 값·인프라 매니페스트 + CI `k8s-rehearsal` 잡(PR 은 차트·스크립트 변경 시 docker-build-check 아카이브로, main 은 GHCR :sha 로). 스모크에 `*_MGMT_URL`(관리 포트 분리 배포), 비밀 생성기 `scripts/lib/gen-install-env.sh` 로 CI 스모크와 공용. 기관 클러스터 1회는 사용자 몫. CI 첫 통과(4회차): up 65s · install 84s · smoke 10s · upgrade 45s · rollback 40s · down 13s = 4m17s. 드러난 결함: Keycloak production 모드 auto-build OOMKilled(1536Mi) → 한도 2Gi + `keycloak.optimized` + `KC_PROXY_HEADERS` |
| 2026-10-07 | #256 머지(main f57718d). PR-7 = §5 #6 AI 운영 보조: hub `ai` 패키지(OpenAI 호환 LLM 클라이언트, 프로파일 초안 + 스키마 검증, 감사 집계 요약, 운영 스냅샷 장애 요약, 공개 엔드포인트 가드, 감사 `AI_*`), 콘솔(AI 초안 카드·감사 AI 요약·AI 운영 메뉴), compose `--profile ai`(Ollama)·Helm `ai.*`. 끝-끝 LLM 검증은 설치본에서 운영자 1회(모델 품질은 모델 몫) |
| 2026-10-08 | #257 머지(main 23d2977). PR-8 = §5 #7 감사 이상 탐지(관찰 모드): 커서 기반 비동기 점수기(SKIP LOCKED, 소급 없음, 행 occurred_at 기준), 규칙 5개(로그인 실패 버스트·새 IP·업무 외 쓰기·기관 실패 버스트(7일 기준선)·티켓 재검증 반복), `audit_anomaly_flag` + 검토(정탐/오탐/모름) → 규칙별 정밀도 → 3개월 뒤 승격 판단(콘솔 "판단" 열). 인가 매트릭스에 anomalies 행(AUDITOR 검토 가능). 운영 기준선은 설치 뒤 쌓인다 |
| 2026-10-08 | PR-8 = #258(CI 통과, 머지 대기). PR-9 = §5 #8 동의 카탈로그: registry `consent_version.service_code`(V2) + 발행/종료/미동의 내부 API, hub `ConsentRegistryClient`·관리 API(`/api/v1/admin/consents`·`/services/{code}/consents`)·콘솔, 프로파일 `consent {enabled, includePlatform}`, 코어 로그인 프런트 동의 단계(필수 미동의 때만 화면, form POST `…/login/consent`, CSP form-action 'self' 그 경로만, 거부 `E-IDO-125`, registry 장애는 발급 거부). 이로써 §5 1.1 항목 8건이 모두 PR 로 올라갔다 — 남은 완료 기준: `v1.1.0` 태그(사용자), 설치본 스모크에 동의 단계 추가 여부 결정. KR 포털의 구 동의 경로(`/api/v1/ext/consent*`)는 hub 에 없음 — KR 후속 |
| 2026-10-08 | #258·#259 머지(main 6abb5a7) — §5 1.1 항목 8건 완료. 버전 커밋 1.1.0(PR-10): 루트 `build.gradle.kts`·Helm Chart·콘솔 package·현재 버전 문구(CLAUDE/AGENTS/README)·제품 설명서·설치 매뉴얼·시험 항목표 제목(67항목)·GS 착수 문서(시험 대상 1.1.0)·연동 가이드 버전 표·SDK README/CHANGELOG, 웹훅 `platformVersion` 기본 1.1.0, CHANGELOG `[1.1.0]` + 업그레이드 메모. 태그 `v1.1.0` 은 머지 커밋에 사용자가 만든다(`v1.0.1` 태그 미생성 확인 — §1 #8) |
| 2026-10-08 | #260 머지(main 7eb263e) → 태그 `v1.1.0`(사용자 push, e74f494). `v1.0.1` 태그는 건너뜀(§1 #8). G1 착수 — PR-G1-2: 오프라인 설치본·백업·복구 스크립트 + CI 자동 검증(스모크 백업→새 DB 복구→대조, `offline-bundle-check`) + 매뉴얼. G1 동결은 1.1.1 |
| 2026-10-09 | #263 머지(main 9bdfc99). PR-G1-5 = 1.1.1 동결: 버전 1.1.1(루트 `build.gradle.kts`·Helm Chart·콘솔 package·SDK README/CHANGELOG·웹훅 `platformVersion` 기본값·현재 버전 문구·설치 매뉴얼 tar 이름), CHANGELOG `[1.1.1]` + 업그레이드 메모(V31·비밀 봉인·기관 목록 봉투·지표), gs-kickoff §1 시험 대상 1.1.1·§2 상태(설치본 스크립트·CI 검증, 할당 화면 결정 완료), 플랜 §2.5. 태그 `v1.1.1` 은 머지 커밋에 사용자 |
| 2026-10-09 | #262 머지(main 14973b2). PR-G1-3: 할당 관리 화면(hub 관리 API 8개 + `QAuthzClient` 관리 호출, E-IDO-127~129, 감사 `ASSIGNMENT_*`·`ROLE_*`), 기관 목록 서버 페이징·검색(봉투 응답, DB 테넌트 거름), 2단계 등록 QR(`qrcode`, 브라우저 생성). 스모크 ⑧c(실제 authz 끝-끝). 시험 항목 B-16·B-17, 집계 70·자동 61 |
| 2026-10-09 | #261 머지(main f854f67). PR-G1-4: 웹훅 서명 비밀 KMS 봉인 + 회전 API·콘솔 카드 + 네 앱 Prometheus 레지스트리(1.0.x 전부 404 였음 — G-3 정정) + 결함 2건 수리(웹훅 설정 INSERT 조용한 실패, webhook_enabled 미반영) + 스모크 ⑧a·⑧b. 시험 항목 B-15, 집계 68 |

