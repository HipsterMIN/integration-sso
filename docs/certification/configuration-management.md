# Idem 형상·결함 관리 (GS 제출용 초안, 1.1.1)

> 대상: 시험원·QA. GS 인증이 묻는 "형상 관리와 결함 관리를 어떻게 하는가"를 저장소의 실제 운용으로 답한다. 수치는 2026-10-09(1.1.1 동결) 기준이며, 결함 밀도 표는 G1-1(시험 항목 전수 완주)과 GS 시험 결과로 채운다. 일정·제출물 상태는 `gs-kickoff.md`.

## 1. 형상 식별

| 항목 | 값 |
|---|---|
| 저장소 | GitHub `HipsterMIN/integration-sso` (단일 모노레포 — hub·gate·registry·authz·relay·SDK·콘솔·에디션·설치본·문서) |
| 기본 브랜치 | `main` — 릴리스 가능한 상태만 들어간다 |
| 개발 브랜치 | `shipster` — 모든 변경은 여기서 PR(`shipster → main`) |
| 패치 브랜치 | `release/1.0` — 1.0.x 보안·결함 수정 전용(기능 없음). 1.1.x 패치가 필요해지면 그때 `release/1.1` |
| 버전 | 루트 `build.gradle.kts` `version` 이 단일 출처 — Helm `Chart.yaml`(version·appVersion), 콘솔 `package.json`, SDK, 웹훅 `platformVersion` 기본값, 매뉴얼 문구가 같은 값을 따른다(버전 커밋에서 한꺼번에 올린다) |
| 태그 | `vX.Y.Z` 를 **머지 커밋**에 단다(사용자가 로컬에서 push). 설치본 이미지 태그 `idem-<앱>:<X.Y.Z>-<에디션>`, 오프라인 번들 이름 `idem-<X.Y.Z>-<에디션>` |
| 변경 이력 | `CHANGELOG.md`(제품, Keep a Changelog) + `idem-sdk-java/CHANGELOG.md`(SDK). 릴리스마다 **업그레이드 메모**(DB 마이그레이션·동작 변경·새 설정) |

### 1.1 릴리스 목록

| 버전 | 태그 | 날짜 | 내용 | PR |
|---|---|---|---|---|
| 1.0.0 | `v1.0.0` | 2026-09-26 | 1.0 동결 — 제품 CHANGELOG·매뉴얼 4종·GS 착수 문서 | #240(S9 PR-4), #241·#242 |
| 1.0.1 | (태그 없음 — 사용자 결정, 플랜 §1 #8) | 2026-09-26 | 3차 적대적 점검(HIGH 10·MED 17·LOW 20) 후속 — 보안 PR-A, 설치본 PR-B, 기능·문서 PR-C | #243~#246 |
| 1.1.0 | `v1.1.0` | 2026-10-08 | 1.1 기능 공백 해소 8건(PR-1~PR-9) + 버전 커밋 | #251~#260 |
| 1.1.1 | `v1.1.1`(머지 뒤 사용자) | 2026-10-09 | G1 시험 준비 — 오프라인 설치본·백업/복구, 웹훅 서명 비밀 API·네 앱 지표, 콘솔 보완 3건 + 버전 커밋 | #261~#264 |

### 1.2 PR 목록 (1.0 동결 이후)

| PR | 머지일 | 제목(요지) | 분류 |
|---|---|---|---|
| #240 | 2026-09-25 | S9 PR-4: 1.0 동결 — 버전 1.0.0·제품 CHANGELOG·1.0 매뉴얼 4종 초안·GS 착수 문서 | 릴리스 |
| #241 | 2026-09-25 | registry — 없는 경로는 404 표준 오류 본문(catch-all 이 500 으로 바꾸던 문제) | 결함 |
| #242 | 2026-09-25 | CI — relay·tenant-sample Dockerfile 누락 build.gradle.kts(main docker-build 실패), PR docker-build-check 전 Dockerfile 매트릭스 | 결함(CI) |
| #243 | 2026-09-26 | 1.0 적대적 점검(3차) 보고서 — HIGH 10·MED 17·LOW 20, 실기동 재현, 1.0.1 조치 계획 | 점검 |
| #244 | 2026-09-26 | 1.0.1 PR-A(보안) — H1·H2·H3 + M2·M3·M4·M15·M17, 405/415·authz 404 계약 | 결함(보안) |
| #245 | 2026-09-26 | 1.0.1 PR-B(설치본) — H4~H8 + M1·M6~M10, 로컬 마스터 키 KMS | 결함(설치) |
| #246 | 2026-09-26 | 1.0.1 PR-C — 프로파일 limits 배선(H9), 문서 정정, 개명 잔재·가드 사각, 버전 1.0.1 | 결함·문서 |
| #247 | 2026-09-27 | 1.0.1 문서 불일치 정정 — 스키마 이름·비밀 21종·시험 58항목·개명 잔재 | 문서 |
| #248 | 2026-09-27 | 기관 연동 가이드 3종 개명 잔재 정정 | 문서 |
| #249 | 2026-09-27 | 기관 연동 개발자·운영 가이드 1.0.1 기준 재작성, 에이전트·SDK 실태 정정 | 문서 |
| #250 | 2026-09-28 | 1.0 이후 실행 플랜(`post-1.0-plan.md`) | 계획 |
| #251 | 2026-09-28 | 1.1 PR-1: 연합 인가 정합성 + 할당 변경 전파 + SLO IdP 재시도 큐 | 기능 |
| #252 | 2026-09-29 | 1.1 PR-2: 감사 WAL 폴백 + 그룹·속성 규칙 할당 | 기능 |
| #253 | 2026-09-29 | 1.1 PR-3: 코어 로그인 프런트 — Handoff 브라우저 진입 + FE 세션 쿠키 수리 | 기능·결함 |
| #254 | 2026-09-29 | 1.1 PR-4: Java 에이전트 저장소 분리 | 정리 |
| #255 | 2026-09-29 | 1.1 PR-5: SCIM 2.0 아웃바운드 | 기능 |
| #256 | 2026-10-07 | 1.1 PR-6: K8s 실배포 리허설(kind·업그레이드·롤백) | 설치 검증·결함 |
| #257 | 2026-10-08 | 1.1 PR-7: AI 운영 보조(선택 컨테이너) | 기능 |
| #258 | 2026-10-08 | 1.1 PR-8: 감사 로그 이상 탐지(관찰 모드) | 기능 |
| #259 | 2026-10-08 | 1.1 PR-9: 동의 카탈로그 | 기능 |
| #260 | 2026-10-08 | 버전 1.1.0 — CHANGELOG [1.1.0]·업그레이드 메모 | 릴리스 |
| #261 | 2026-10-09 | G1-2: 오프라인 설치본 + 백업·복구 스크립트 — CI 자동 검증·매뉴얼 | 설치 검증 |
| #262 | 2026-10-09 | G1-4: 웹훅 서명 비밀 KMS 봉인·회전 API + 네 앱 Prometheus 지표 | 기능·결함 |
| #263 | 2026-10-09 | G1-3: 관리 콘솔 보완 3건 — 할당 관리 화면·기관 목록 페이징·검색·2단계 등록 QR | 기능 |
| #264 | 2026-10-09 | 버전 1.1.1 — CHANGELOG [1.1.1]·업그레이드 메모·GS 착수 상태 | 릴리스 |
| #265 | 2026-10-09 | G2-1: 사용자 매뉴얼 신규·시험 항목표 실행 결과 열·형상·결함 관리 문서 | 문서 |
| #266 | 2026-10-09 | G2-2: 문서 정합성 검사 docs-lint(CI 잡·pre-commit) | CI·문서 결함 |
| #267 | 2026-10-10 | G2-3: 관리 콘솔 화면 캡처 자동화 + 변경 사유 헤더 결함 | 문서·결함 |
| #268 | 2026-10-10 | G2-4: 사용자 매뉴얼 화면 캡처 자동화 + 동의 화면 CSP form-action 결함 | 문서·결함 |
| #269 | 2026-10-10 | G2-5: 의존성·이미지 취약점 점검 1회차 — Spring Boot 3.5.16·BOM 덮어쓰기·BC 1.86·nginx 1.30·프런트엔드 패치, 억제 3건 가드 | 보안 패치 |

## 2. 변경 통제

1. **모든 변경은 PR** — `shipster → main`, 머지는 사용자가 지시한다("머지"). 커밋 작성자는 프로젝트 계정, AI 세션은 트레일러로 남긴다.
2. **CI 게이트**(`.github/workflows/ci.yml`, PR 마다): Build & Unit Test(전 모듈, 약 1,900 테스트 실행) · Frontend Build(관리 콘솔·KR 포털, typecheck+vitest+build) · Helm Lint & Template · Docker Build Check(이미지 9종) · **k6 Smoke Test**(boot jar 실기동 — 설치본 스모크 ①~⑧c·백업/복구 단계·prod 프로파일 단계·k6) · **K8s 실배포 리허설**(kind: 설치→스모크→prod 업그레이드→롤백→제거, 차트·스크립트 변경 PR) · 오프라인 설치본 검증(스크립트·compose 변경 PR) · OWASP Dependency-Check(수동 실행). 실패하면 머지하지 않는다.
3. **로컬 훅**(`scripts/dev/install-git-hooks.sh`): pre-commit Spotless(변경 Java 파일), pre-push 변경 모듈 테스트 + Testcontainers 통합 테스트(CI 는 Docker 없음 — `DOCKER_UNAVAILABLE=true`).
4. **가드 테스트**가 명명·범용화·암호 경계 규칙을 코드로 지킨다: `NamingGuardTest`(구 식별자 금지), `GeneralizationGuardTest`(코어에 고객 고유값 금지), `CryptoBoundaryGuardTest`(암호 연산은 `CryptoProvider` 경유), `SpringAdvisoryGuardTest`(OSS 수정판이 없어 `.trivyignore` 로 억제한 Spring 권고의 전제 조건이 코드에 없음 — `docs/certification/vulnerability-review-2026-10.md` §4).
5. **동결 규칙**: 1.0 에서 오류 코드(`E-IDO-1xx`·`E-AGENCY-3xx`)와 API 경로를 동결했다(제품 설명서 §6). GS 시험 중 변경은 `release/1.0`(1.0.x) 또는 1.1.x 패치로 **보안·결함 수정만**, 매 패치는 CI 초록 + 시험 항목 재실행 기록(플랜 §4).
6. **릴리스 절차**: 버전 커밋 PR(버전 문자열·CHANGELOG·업그레이드 메모) → 머지 → 태그 → 오프라인 번들(`scripts/release/make-offline-bundle.sh`, `SHA256SUMS`) → 설치 매뉴얼의 체크섬·크기 기록.

## 3. 결함 관리

### 3.1 흐름

- **발견 경로**: ① 적대적 점검(3차까지, `docs/_archive`·#243 보고서), ② CI(스모크·리허설·가드 테스트), ③ 개발 중 조사(기능 PR 안에서 드러난 결함은 같은 PR 에서 고치고 CHANGELOG 에 "수리한 결함"으로 적는다), ④ 사용자 리허설·GS 시험(G1-1 이후 — 이슈로 등록).
- **기록**: 심각도(HIGH·MED·LOW — 3차 점검 기준), 재현 절차, 수정 PR, 회귀 테스트(UT·IT·스모크 단계) 를 CHANGELOG 와 PR 본문에 남긴다. 시험 중 결함은 GitHub 이슈 + 시험 항목표 "실행 결과" 열.
- **종결 기준**: 수정 PR 의 CI 초록 + 해당 시험 항목 재실행 통과 + CHANGELOG 반영.

### 3.2 1.0 동결 이후 수리한 결함 (제품 코드)

| 발견 | 결함 | 심각도 | 수정 | 회귀 검사 |
|---|---|---|---|---|
| 3차 적대적 점검 | HIGH 10 · MED 17 · LOW 20 (보안 H1~H3·설치 H4~H8·기능 H9·문서 H10, M1~M17) | HIGH/MED/LOW | 1.0.1 PR-A/B/C (#244~#246) | UT·IT·스모크(1.0.1 항목 A-7·B-11·B-12·C-8·D-11·D-13·D-14·G-7) |
| 1.0 동결 직후 | registry 없는 경로가 500(catch-all 이 `NoResourceFoundException` 을 삼킴) | MED | #241 | UT |
| 1.1 PR-3 조사 | FE 세션 쿠키 이름 불일치(발급 API·CAST 가 `Fe-Session-Id` 를 읽어 브라우저 발급 불가), 발급 API 기관 바인딩 없음, q-sign 모드 쿠키 미전달 | HIGH | #253 | IT `HandoffLoginIntegrationTest`, 스모크 ⑦b, D-16·D-17 |
| 1.1 PR-6 리허설 | Keycloak production 모드 auto-build OOMKilled(1536Mi) | HIGH(설치) | #256 — 한도 2Gi, `keycloak.optimized`, `KC_PROXY_HEADERS` | K8s 리허설(CI) |
| 1.1 PR-6 리허설 | 리허설 스크립트: 롤링 갱신 직후 옛 Pod 를 실패로 셈·port-forward 가 옛 Pod 에 붙음·providers 조회 창 | LOW(검증 도구) | #256 | K8s 리허설(CI) |
| G1-4 조사 | 관리 API 로 만든 기관의 웹훅 설정 INSERT 가 조용히 실패(FK 미flush·NOT NULL) — 웹훅 비밀이 저장되지 않았다 | HIGH | #262 — `saveAndFlush`, NOT NULL 해제, 실패는 오류 | IT `WebhookSecretIntegrationTest`, 스모크 ⑧b, B-15 |
| G1-4 조사 | `agency_meta.webhook_enabled` 를 아무 코드도 켜지 않아 관리 API 기관은 발송 대상이 아님 | MED | #262 | IT, 스모크 ⑧b |
| G1-4 조사 | 네 앱 모두 Prometheus 레지스트리 미등록 — `/actuator/prometheus` 404(시험 항목 G-3 의 기록이 잘못돼 있었음) | MED(운영) | #262 — `micrometer-registry-prometheus` | 스모크 ⑧a, prod 단계, K8s 리허설, G-3 |
| G1-3 조사 | 웹훅 비밀이 `signing_secret_hash` 컬럼에 원문으로 저장(이름과 달리 해시가 아님) | HIGH(보안) | #262 — KMS 봉인 + 첫 기동 자동 봉인 | IT, B-15 |
| G2-3 화면 캡처 | 콘솔이 한글 변경 사유를 `X-Change-Reason` 헤더에 원문으로 넣어 브라우저 fetch 가 거부 — 프로파일 저장 실패 | MED(기능) | #267 — percent-encoding + hub 디코드 | UT `ChangeReasonTest`, 콘솔 `api.test.ts`, 화면 캡처 |
| G2-4 화면 캡처 | 동의 화면 CSP `form-action 'self'` 를 Chromium 이 form POST 뒤 **콜백 302 에도 적용**해 "동의하고 계속"·"동의하지 않음" 뒤 서비스로 돌아가지 못함(화면이 그대로 남음, Firefox 는 통과 — 서버 단 테스트로는 안 잡힘) | HIGH(기능) | PR-G2-4 — 동의 화면 CSP 에 기관 콜백 출처 추가(`SecurityHeadersFilter.allowFormActionOrigin`) | UT, IT `consentFlow`(헤더), 브라우저 E2E(`login-front-screenshots.cjs`) |

문서 결함(수치·이름 불일치)은 #247·#248·#249 와 각 기능 PR 의 "문서" 항목에, 시험 항목표 집계 정정(68→70→75)은 `test-items.md` 집계 절에 적었다.

### 3.3 결함 밀도

- **산식**: 결함 밀도 = (시험에서 발견된 결함 수) / (시험 항목 수 75). 보조 지표로 (결함 수) / (코드 KLOC — Java·TS 본문, 테스트 제외) 를 함께 낸다. 심각도별로 나누어 적는다.
- **입력**: G1-1 전수 완주 결과(시험 항목표 "실행 결과" 열)와 GS 시험원 결과. CI 가 PR 마다 잡아 머지 전에 고친 결함은 "출시 전 결함"으로 따로 센다(제품 결함 밀도에 넣지 않는다).

| 구간 | 항목 수 | 발견 결함(HIGH/MED/LOW) | 밀도(결함/항목) | 비고 |
|---|---|---|---|---|
| 3차 적대적 점검(1.0.0, 2026-09-26) | 58(당시 표) | 10 / 17 / 20 | 0.81 | 점검은 시험 항목표 밖 공격 시나리오도 포함 — 참고치 |
| G1-1 전수 완주(1.1.1) | 75 | (기록 예정) | — | 사용자 환경, `test-items.md` 실행 결과 열 |
| GS 시험(시험원) | 75 | (기록 예정) | — | — |

## 4. 검증한 것 / 못 한 것

- §1·§2 는 저장소의 실제 설정(`ci.yml`, 훅 스크립트, 가드 테스트)과 PR 이력에서 옮겼다. §1.2 PR 목록은 GitHub API 로 2026-10-09 에 뽑았고 #265 이후는 각 PR 이 더한다.
- §3.2 는 CHANGELOG 와 PR 본문에 적힌 결함만 모았다(적대적 점검 보고서의 47건은 보고서 쪽 표가 원본).
- §3.3 의 밀도는 G1-1·GS 시험 전까지 비어 있다 — 산식과 입력만 확정했다.
