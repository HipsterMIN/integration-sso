# Idem — 변경 이력

형식: [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/). 버전은 루트 `build.gradle.kts` 와 태그(`vX.Y.Z`)를 따른다. SDK 는 `idem-sdk-java/CHANGELOG.md`.

## [Unreleased]

_(없음)_

## [1.1.2] — 2026-10-10

1.1.1 의 **보안·결함 패치**다(플랜 §4 의 패치 규칙 — 기능 추가 없음, DB·설정·API 변경 없음): PR-G2-5 #269(의존성·이미지 취약점 점검 1회차 — Spring Boot 3.5.16·BOM 덮어쓰기 7종·BouncyCastle 1.86·nginx 1.30·프런트엔드 패치, 억제·가드) · PR-G2-4 #268(동의 화면 CSP form-action 결함 HIGH) · PR-G2-3 #267(변경 사유 헤더 한글 결함) · PR-G2-2 #266(문서 정합성 검사 docs-lint). 태그 `v1.1.2`. 같이 들어간 제출물(사용자·관리자 매뉴얼 화면 캡처 27장, 형상·결함 관리 문서, 취약점 점검 기록)은 문서다.

### 1.1.2 업그레이드 메모

- **DB·설정·API 변경 없음** — Flyway 마이그레이션 없음, 새 환경변수 없음, 오류 코드·API 경로 동결 유지. 이미지 태그만 바꾼다(compose `IDEM_VERSION=1.1.2`, Helm `global.imageTag`). 1.1.1 → 1.1.2 는 재기동 외 절차가 없다.
- **hub 와 관리 콘솔은 함께 올린다** — 콘솔이 프로파일 변경 사유를 percent-encoding 으로 보내고 hub 가 `ChangeReason.decode` 로 푼다. 1.1.1 콘솔 + 1.1.2 hub 는 한글 사유 저장이 여전히 실패하고, 1.1.2 콘솔 + 1.1.1 hub 는 사유가 인코딩된 채 감사에 남는다.
- **이용자 화면 동작 변경**: 동의 화면 응답의 CSP `form-action` 에 그 요청의 기관 콜백 출처가 더해진다 — Chromium 에서 "동의하고 계속"·"동의하지 않음" 뒤 기관으로 돌아간다(1.1.1 까지는 화면이 그대로 남았다). 더해지는 출처는 화이트리스트 검증을 지난 콜백뿐이다.
- **의존성**: Spring Boot 3.5.16(Framework 6.2.19·Security 6.5.11·Data 2025.0.13·Kafka 3.3.16·Micrometer 1.15.12) + Jackson 2.21.7·Tomcat 10.1.60·Netty 4.1.139·pgjdbc 42.7.14·httpclient5 5.6.4/httpcore5 5.4.4·log4j 2.25.5·commons-lang3 3.18.0·lz4 포크(`at.yawk.lz4`) 1.11.4(#269 머지 뒤 Trivy 가 잡은 CVE-2026-106451 — 이 버전 커밋에서), hub BouncyCastle 1.86, 콘솔·KR 포털 이미지 `nginx:1.30-alpine` + nginx 단계 `apk upgrade`(베이스 태그 이후의 alpine 패키지 수정 3건 — libexpat·pcre2·tiff), KR 포털 번들 axios 1.20·dompurify 3.4.16·react-router-dom-v5-compat 6.30.6. Jackson 2.19 → 2.21 은 BOM 의 결정 — 응답 JSON·웹훅 봉투·감사 열은 단위 테스트 1,844건과 CI 스모크로 확인했다. 남는 CVE 와 근거는 `docs/certification/vulnerability-review-2026-10.md` §4(Spring Framework 6.2.x 3건·Reactor Netty 1건 — OSS 수정판이 Spring Boot 4 뿐, 전제 조건이 없음을 가드로 묶고 억제는 2027-01-31 만료).
- **동작 변경(authz)**: `idem-authz` 가 Swagger UI 화면(`/swagger-ui.html`)을 더 싣지 않는다 — 번들 DOMPurify 3.2.4 의 XSS CVE 20건 때문에 springdoc 를 API 전용 스타터(2.8.17)로 바꿨다. OpenAPI JSON `/api-docs` 는 그대로(필요하면 로컬 Swagger UI 로 연다). 매뉴얼·스모크·콘솔은 이 화면을 쓰지 않았다.
- **억제 목록 확대**: 머지 뒤 OWASP 재실행이 잡은 Spring Framework 6.2.19 의 2026-08-20 일괄 14건(전부 6.2.20 Enterprise 전용 수정)과 Spring Data JPA·Reactor Netty 1건씩을 전제 조건 부재 근거로 `infra/owasp/suppressions.xml` 에 2027-01-31 만료로 더했고, 가드 테스트 토큰도 같이 늘렸다. 억제가 17건이 된 만큼 Spring Boot 4 이행 착수 여부를 2026-11 회차에서 정한다(점검 문서 §6).
- **SDK**: `idem-sdk-java 1.1.2` — 코드 변경 없음. 선택 Apache HttpClient 어댑터의 권장 버전 `httpclient5` 5.6.4(`compileOnly`), 문서의 HMAC 필수 환경변수 이름 정정.
- **빌드 환경**: 관리 콘솔 빌드는 Node 22(이미지 빌더·CI). 설치본 이용자에게는 영향 없다.
- **검증**: 1.1.2 코드 상태의 전체 CI(Build & Unit Test·프런트엔드 두 벌·k6 스모크 ①~⑧c·Docker Build Check 9종·K8s 리허설·오프라인 설치본·문서 정합성) + 시험 항목표 자동 66 재실행 기록(`docs/manuals/test-items.md`) + 머지 뒤 main 의 Trivy(이미지 10종)·OWASP 재실행(점검 문서 §7).

### G2-5 · 의존성·이미지 취약점 점검 1회차 (플랜 §7)

- **점검**: 1.1.1 이미지 10종의 Trivy 표(main run 38017327913), OWASP Dependency-Check 수동 실행(첫 실행은 NVD API 장애로 60분 타임아웃 — CI 잡의 정리 단계가 프로세스 종료를 기다려 NVD 캐시 진행분을 보존하도록 고치고 타임아웃 90분; 재실행은 완주해 Trivy 가 못 보는 MEDIUM·CPE 매치를 더 잡았다: commons-lang3·log4j-api·httpclient5·spring-retry·reactor-netty, kotlin/OTel 오탐), 관리 콘솔 `npm audit`·KR 포털 `yarn audit`. 앱 이미지는 OS 층 0건이지만 Java 라이브러리 CRITICAL 8~13·HIGH 28~49(Tomcat 인증 우회·Netty SNI 우회·Spring Boot/Kafka 원격 코드 실행 계열 포함), 콘솔 이미지 2종은 `nginx:1.27-alpine` OS 층 43건(OpenSSL CVE-2026-31789 CRITICAL). 결과·근거·잔여 위험·다음 회차 절차는 `docs/certification/vulnerability-review-2026-10.md`.
- **올림**: Spring Boot 3.5.9 → **3.5.16**(Framework 6.2.19·Security 6.5.11·Data 2025.0.13·Kafka 3.3.16·Micrometer 1.15.12·Jackson 2.21). BOM 이 못 미치는 것은 루트 `build.gradle.kts` 에서 BOM 속성으로 덮어쓴다 — Tomcat 10.1.60, Netty 4.1.139, Jackson 2.21.7, pgjdbc 42.7.14, httpclient5 5.6.4/httpcore5 5.4.4, log4j 2.25.5(api·브리지), commons-lang3 3.18.0(lz4 는 kafka-clients 3.9.2 가 유지 관리 포크 1.10.1 로 옮김). hub BouncyCastle 1.78.1 → 1.86, SDK 선택 어댑터 httpclient5 5.3.1 → 5.6.4(Java 8 타겟 유지). 콘솔·KR 포털 이미지 베이스 `nginx:1.30-alpine`. 관리 콘솔 lockfile(source-map-js)·vitest 5(Node 22 — CI·Dockerfile 빌더·`engines`), KR 포털 번들 라이브러리(axios 1.20·dompurify 3.4.16·react-router-dom-v5-compat 6.30.6 — axios 1.20 타입에 맞춰 토큰 재발급 재요청 헤더 복사 한 곳 수정).
- **억제·가드**: OSS 수정판이 없는 Spring Framework 6.2.x 3건(CVE-2026-47884·47890·47892 — 6.2.20 은 Enterprise 전용)은 전제 조건(XsltView·SSE 조각 렌더링·WebFlux 함수형 엔드포인트)이 제품에 없음을 `SpringAdvisoryGuardTest` 로 묶고 루트 `.trivyignore` 와 `infra/owasp/suppressions.xml`(둘 다 만료 2027-01-31)로 CI 표·OWASP 게이트에서 뺀다. Reactor Netty CVE-2026-47874(서버 전용, OSS 수정판은 Spring Boot 4)는 같은 가드가 서버 전환(`spring-boot-starter-webflux`·Netty 서버 API)을 막는다. kotlin-stdlib·opentelemetry 의 CPE 오탐 2건은 OWASP 억제 파일에 근거와 함께. 2027-01 회차에서 Spring Boot 4 이행을 결정한다.
- **업그레이드 영향**: 의존성 패치만 — DB·설정·API 변경 없음. 설치본에는 다음 버전 커밋(1.1.2)으로 들어간다.

### G2-4 · 사용자 매뉴얼 화면 캡처 + 동의 화면 CSP 결함 (플랜 §3)

- `scripts/dev/login-front-screenshots.cjs`(Playwright·Chromium): 코어 로그인 프런트를 Mock 본인확인으로 끝까지 밟으며 이용자 화면 9장을 `docs/manuals/images/login/` 에 — 인증 방법 선택, 동의(거부·필수 누락·동의), 기관 콜백 복귀(스크립트가 띄운 예시 서버), 이미 로그인된 재진입, 오류 3종(E-AGENCY-307·E-AGENCY-304·E-IDO-400). 사용자 매뉴얼 §2·§5 에 그림 삽입. 시험 항목 D-16·D-18 의 브라우저 E2E 기록.
- **수리한 결함(HIGH)**: 동의 화면의 CSP `form-action 'self'` 를 **Chromium 이 form POST 뒤의 302 대상에도 적용**해, "동의하고 계속"·"동의하지 않음" 을 눌러도 기관 콜백으로 돌아가지 못하고 동의 화면이 그대로 남았다(서버는 302 를 냈고 감사도 남아 서버 단 테스트·IT 는 통과했다; Firefox 는 막지 않는다). 동의 화면 응답의 CSP 에 그 요청의 기관 콜백 출처를 더한다(`SecurityHeadersFilter.allowFormActionOrigin`, 콜백은 이미 화이트리스트 검증을 지난 값). 그 밖의 경로는 종전(`'self'`/`'none'`) 그대로. UT `SecurityHeadersFilterTest`·`HandoffLoginControllerTest`(+1), IT `consentFlow` 헤더 단언, 브라우저 E2E.

### G2-3 · 관리 콘솔 화면 캡처 자동화 + 변경 사유 헤더 결함 (플랜 §3)

- `scripts/dev/console-screenshots.cjs`(Playwright·Chromium): 실제 스택과 콘솔 앞에서 첫 로그인(2단계 등록 QR → 코드 → 비밀번호 변경 강제) → 데모 데이터 시드(기관 3·플랫폼/서비스 동의 항목·역할 2·할당 2·역할 부여·테넌트·관리자) → 화면 18장을 `docs/manuals/images/console/` 에 쓴다. 재실행(바뀐 비밀번호·2단계 비밀 파일·같은 스텝 코드 재사용 거부)도 처리. 관리자 매뉴얼 §1~§14 에 그림 삽입, 재생성 절차는 `docs/local-dev-workflow.md` §5. 시험 항목 B-2(QR 끝-끝)·B-16 의 E2E 기록.
- **수리한 결함**: 콘솔이 프로파일 저장의 변경 사유를 `X-Change-Reason` 헤더에 원문으로 넣어 **한글 사유면 브라우저 fetch 가 헤더를 거부**해 저장이 실패했다(`Invalid character in header content`). 콘솔은 percent-encoding(`changeReasonHeaders`)으로 보내고 hub 는 `ChangeReason.decode` 로 풀어 감사·이력에 원문을 남긴다(`%` 없는 ASCII 사유는 그대로, `+` 유지, 500자 절단). UT `ChangeReasonTest`, 콘솔 `api.test.ts`.

### G2-2 · 문서 정합성 검사 docs-lint (플랜 §7)

- `scripts/ci/docs-lint.py`(의존성 없음): 버전 단일 출처(루트 `build.gradle.kts` ↔ Helm 차트·콘솔 package·SDK README/CHANGELOG·웹훅 `platformVersion` 기본값·현재 버전 문구·제품 설명서·GS 착수·설치 매뉴얼 번들 이름·연동 가이드 예시 21곳), 시험 항목표 집계(표 행 수 = 제목 = 합계 = 그룹 합 = ID 목록, docs/README·gs-kickoff 수치), 운영 문서가 인용한 오류 코드의 정의 여부, 구 런타임 이름(`IDO_*`·`${ido.*}`), 상대 링크, CHANGELOG 첫 헤더 = 빌드 버전. CI 잡 `문서 정합성`(PR 마다), pre-commit 훅(문서·버전 파일 스테이징 시). 제외는 줄 끝 `<!-- docs-lint:ignore -->`.
- 첫 실행이 잡은 결함: SDK 문서·javadoc 의 `IDO_HMAC_SIG_REQUIRED`(hub 는 `IDEM_HUB_HMAC_SIG_REQUIRED` 를 읽는다), README 의 깨진 링크 4개(내부 문서 이동·분리된 테스트베드), 단계적 전환 문서의 FeatureFlags 경로. <!-- docs-lint:ignore -->

## [1.1.1] — 2026-10-09

`docs/post-1.0-plan.md` §2 "G1 — 시험 준비" 의 AI 몫 4건이 들어갔다: PR-G1-2 #261(오프라인 설치본·백업/복구 스크립트) · PR-G1-4 #262(웹훅 서명 비밀 KMS 봉인·회전 API, 네 앱 Prometheus 지표) · PR-G1-3 #263(할당 관리 화면·기관 목록 페이징·TOTP 등록 QR) · PR-G1-5(이 버전 커밋). 태그 `v1.1.1`. 남은 G1-1(시험 항목 75 전수 1회 완주 — 자동 66 은 CI, 수동 9·로컬 IT 는 사용자 환경, 운영 DB 복구·폐쇄망 반입 리허설)은 사용자 환경에서 한다. 1.0.x 패치는 `release/1.0`.

### 1.1.1 업그레이드 메모

- **DB**: 첫 기동에서 Flyway 가 hub V31(`agency_webhook_config.signing_secret_sealed`·`secret_rotated_at`, `signing_secret_hash` NOT NULL 해제)을 자동 적용한다. 되돌리는 마이그레이션은 없다 — 올리기 전 `scripts/ops/backup.sh` 로 백업(`restore.sh` 로 새 DB 에 복구해 보면 백업이 유효한지 확인된다).
- **웹훅 서명 비밀**: 1.0.x·1.1.0 이 `signing_secret_hash` 에 **원문**으로 두던 비밀을 첫 기동에 KMS 로 봉인하고 해시로 바꾼다(`idem.hub.webhook.seal-legacy-on-boot`, 기본 true). KMS 제공자(`idem.hub.kms.provider` — local 마스터키·Vault·NHN)와 키가 운영과 같아야 발송 때 풀 수 있다. 비밀이 없는 기관(관리 API 로 만든 기관 — 1.0.x 는 웹훅 설정 INSERT 가 조용히 실패했다)은 발송이 재시도 없이 FAILED(`NO_SIGNING_SECRET` 감사)로 남는다 — `POST /api/v1/admin/agencies/{code}/webhook/rotate-secret`(콘솔 "웹훅 서명 비밀" 카드)로 발급해 기관 수신기에 전달한다.
- **동작 변경**: `GET /api/v1/admin/agencies` 응답이 배열에서 봉투 `{items, page, size, total}` 로 바뀌었다(콘솔 전용 관리 API — SDK·기관 API 무관). hub 이미지와 콘솔 이미지를 **함께** 올린다(구 콘솔은 목록이 비어 보인다). 네 앱 `/actuator/prometheus` 가 200 을 낸다(1.0.x·1.1.0 은 레지스트리가 없어 404) — 스크랩 설정을 걸 수 있다. 관리 API 로 기관을 등록·수정하면 `agency_meta.webhook_enabled`·`webhook_endpoint` 가 반영된다(종전엔 아무 코드도 켜지 않았다).
- **새 것(기본 켜짐, 추가 설정 없음)**: 할당 관리 API `/api/v1/admin/services/{code}/assignments·roles`(idem-authz 필요 — authz 를 끈 SSO 단독 설치본은 `503 E-IDO-116`), 기관 목록 `page/size/q`, 콘솔 2단계 등록 QR, 오류 코드 `E-IDO-126`~`E-IDO-129`. 스크립트 `scripts/release/make-offline-bundle.sh`·`load-offline-bundle.sh`, `scripts/ops/backup.sh`·`restore.sh`(설치 매뉴얼 §3.3·§6·§8, 운영 매뉴얼 §4).
- **설치본 검증**: CI 가 PR 마다 보태진 것 — 백업→새 DB 복구→표·행 수 대조, 오프라인 번들 생성→이미지 삭제→복원→대조(`offline-bundle-check`), 네 앱 지표 200, 웹훅 비밀 회전(봉인·지문), 실제 authz 로 할당 끝-끝(스모크 ⑧c). 기관 클러스터 1회(`CLUSTER=existing`)·운영 DB 복구·폐쇄망 반입은 사용자 리허설(시험 항목 G-5·G-6).

### G1-2 · 오프라인 설치본 + 백업·복구 스크립트 (플랜 §2.2)

- **오프라인 설치본**: `scripts/release/make-offline-bundle.sh` — compose 와 같은 이름·태그의 Idem 이미지(`idem-hub:<V>-<ED>` …, `IMAGES=build|local|pull`) + 서드파티(postgres·redis·keycloak, compose 에서 읽는다) `docker save`, 소스 `git archive`(v 태그), Helm 차트 패키지, `MANIFEST.txt`·`SHA256SUMS`. `scripts/release/load-offline-bundle.sh` — 체크섬 검증 → `docker load` → MANIFEST 대조 → (`EXTRACT_SOURCE=1`) 소스 풀기 → `IDEM_VERSION` 안내. CI `offline-bundle-check`(스크립트·compose 변경 PR): 번들 생성 → 이미지 삭제 → 복원 → 대조.
- **백업·복구**: `scripts/ops/backup.sh`(컨테이너 안 `pg_dump -Fc` — `TARGET=compose|docker|k8s|direct`, `.sha256`·`.meta`(Flyway 최신 버전·주요 표 행 수), `pg_restore -l` 아카이브 확인, `KEEP=N`), `scripts/ops/restore.sh`(체크섬 → 살아 있는 접속 확인(`FORCE=1`) → DB 재생성 → `pg_restore --no-owner` → ANALYZE → 요약, `TARGET_DB`·`VERIFY_SOURCE_DB` 로 운영 DB 를 건드리지 않는 유효성 검증). CI 설치본 스모크에 "백업·복구" 단계(백업 → 새 DB 복구 → 표·행 수 대조).
- 문서: 설치 매뉴얼 §3.3·§6·§8, 운영 매뉴얼 §4, 시험 항목 G-5·G-6(자동 부분 명시), `docs/install.md` 체크리스트. 운영 DB 복구·폐쇄망 반입의 실제 리허설은 G1-1(사용자 환경).

### G1-4 · 웹훅 서명 비밀 관리 API + Prometheus 지표 (플랜 §2.4)

- **웹훅 서명 비밀을 KMS 로 봉인 저장**: `agency_webhook_config.signing_secret_sealed`(V31, `KmsClient.encrypt`) + `signing_secret_hash` 는 이제 이름대로 SHA-256(지문용). 종전에는 `signing_secret_hash` 컬럼에 **원문**이 있었다. 1.0.x 행은 첫 기동에 `WebhookSigningSecrets` 가 봉인하고 해시로 바꾼다(`idem.hub.webhook.seal-legacy-on-boot`, 기본 true). 발송 때 봉인값을 풀어 서명한다(봉인값 기준 캐시).
- **회전 API** `POST /api/v1/admin/agencies/{code}/webhook/rotate-secret`(SYSTEM·POLICY, 테넌트 범위): 32바이트 난수 → 봉인 저장, 원문은 응답에 1회, 감사 `WEBHOOK_SECRET_ROTATED`. 엔드포인트 없는 기관은 `404 E-IDO-126`. `GET …/webhook` 상태(엔드포인트·발송 여부·봉인 여부·지문·회전 시각 — 원문 없음). 콘솔 기관 상세 "웹훅 서명 비밀" 카드.
- **수리한 결함 2건**: ① 관리 API 로 기관을 만들 때 웹훅 설정 INSERT 가 조용히 실패했다(경고만) — 등록 시에는 JPA 가 아직 flush 하지 않아 FK(agency_meta) 위반, 수정 시에는 `signing_secret_hash NOT NULL` 위반 → `saveAndFlush` 뒤 INSERT, NOT NULL 해제, 실패는 오류로. ② `agency_meta.webhook_enabled` 를 아무 코드도 켜지 않아 관리 API 로 만든 기관은 발송 대상이 아니었다 → 등록·수정 때 `webhook_enabled`·`webhook_endpoint` 반영. 비밀이 없는 기관의 발송은 재시도 없이 FAILED(`NO_SIGNING_SECRET` 감사) — 비밀을 발급하면 다음 이벤트부터 나간다.
- **Prometheus 지표**: hub·gate·registry·authz 에 `micrometer-registry-prometheus` 추가 — 1.0.x 는 네 앱 모두 노출 설정만 있고 레지스트리가 없어 `/actuator/prometheus` 가 404 였다(시험 항목 G-3 의 "gate·registry·authz 는 됨" 은 잘못이었다 — 정정). CI 스모크 ⑧a(네 앱 200 + jvm 지표)·prod 단계(hub 관리 포트 200)·K8s 리허설(hub 관리 포트)에서 확인한다.
- 설치본 스모크 ⑧b: 웹훅 기관 등록 → 회전 → 상태(봉인·지문·원문 없음). `IDEM_HUB_WEBHOOK_SIGNING_SECRET` 는 기동 검증(F4.3)에만 남는다 — 2.0 에서 제거 예정(설치 입력 문서).
- 테스트: `WebhookSigningSecretsTest`, `AgencyAdminServiceTest`(+3), `WebhookDispatchOutboxRelayTest`(+2), IT `WebhookSecretIntegrationTest`(등록 → 회전 → 봉인·지문 → 실제 발송 서명 검증 → 1.0.x 행 봉인 → 404). 문서: 운영 가이드 §3, 개발자 가이드 §8.1, 관리자 매뉴얼 §9, 제품 설명서 F20·F22, 시험 항목 B-15·G-3(+집계 68), 설치 입력, 운영 매뉴얼 §13.

### G1-3 · 관리 콘솔 보완 3건 (플랜 §2.3)

- **할당 관리 화면**: 기관 상세 "할당 관리 — 사용자·역할" 카드 — 할당 목록(페이징)·직접 할당·해제, 역할(그룹) 카탈로그·생성, 사용자별 역할 부여·회수. hub 관리 API `/api/v1/admin/services/{code}/assignments`(GET 봉투 `{items,page,size,total,hasNext}` · POST 201 · DELETE 204), `…/roles`(GET · POST 201), `…/assignments/{qimUserId}/roles`(GET · POST 201 · DELETE …/{roleCode} 204) — `AssignmentAdminController`·`AssignmentAdminService`(서비스 존재 `404 E-AGENCY-307`·테넌트 범위·입력 `[A-Za-z0-9_.:-]{1,100}`), 상태는 idem-authz(`QAuthzClient` 관리 호출 8종, source `CONSOLE`, `X-Actor`). authz 거부 매핑 새 코드 `E-IDO-127`(없음)·`E-IDO-128`(중복·부여 불가)·`E-IDO-129`(그 밖 4xx), 장애 `E-IDO-117`, 꺼진 설치본 `E-IDO-116`. 감사 `ADMIN/ASSIGNMENT_GRANTED·ASSIGNMENT_REVOKED·ROLE_CREATED·ROLE_GRANTED·ROLE_REVOKED`. 인가 매트릭스는 "그 외" 규칙(GET 전 역할, 쓰기 SYSTEM·POLICY).
- **기관 목록 페이징·검색**: `GET /api/v1/admin/agencies?page&size&q` — 응답이 배열에서 봉투 `{items, page, size, total}` 로 바뀌었다(size 기본 50·최대 200, `q` 는 코드·이름 부분 일치 대소문자 무시, 테넌트 범위는 DB 에서 거른다 — 종전엔 500건을 받아 메모리에서 걸렀다). 콘솔 목록은 서버 페이징 + 300ms 지연 검색.
- **TOTP 등록 QR**: 콘솔 첫 로그인 등록 화면에 `otpauth://` QR(`qrcode` 1.5.4, 브라우저 안에서 PNG data URL — 비밀이 서버·네트워크로 다시 나가지 않는다, `otpauth://totp/` 아니면 그리지 않음). base32 비밀·URI 문구는 그대로(QR 을 못 찍을 때).
- 설치본 스모크 ⑧c(실제 authz): 목록 봉투·검색 → 직접 할당 → 역할 생성(201|재실행 409) → 부여 → 사용자 역할 → 회수 → 해제 → 없는 서비스 404, 감사에 `ASSIGNMENT_GRANTED·ROLE_GRANTED·ASSIGNMENT_REVOKED`.
- 테스트: UT `QAuthzClientAdminTest`·`AssignmentAdminServiceTest`·`AssignmentAdminControllerTest`·`AgencyAdminServiceTest.listAgencies_pagingAndSearch`·`AdminAuthorizationTest`(+행), IT `AssignmentAdminIntegrationTest`(authz WireMock), 콘솔 `assignments.test.ts`·`qr.test.ts`. 문서: 관리자 매뉴얼 §1·§2·§14, 제품 설명서 F18, 시험 항목 B-2·B-16·B-17(집계는 G2 에서 75 로 정정 — 1.1 추가 14 누락분), GS 착수, 인가 매트릭스.
- 호환: 기관 목록 API 의 응답 모양 변경은 콘솔 전용 관리 API 에 한한다(SDK·기관 API 무관). 이전 콘솔 빌드와 새 hub 를 섞어 쓰면 목록이 비어 보인다 — 콘솔 이미지를 함께 올린다.

## [1.1.0] — 2026-10-08

`docs/post-1.0-plan.md` §5 "1.1 — 기능 공백 해소" 8건이 PR 9개로 들어갔다: PR-1 #251(연합 인가 정합성·할당 변경 전파·SLO IdP 재시도) · PR-2 #252(감사 WAL 폴백·그룹·속성 규칙 할당) · PR-3 #253(코어 로그인 프런트) · PR-4 #254(Java 에이전트 저장소 분리) · PR-5 #255(SCIM 2.0 아웃바운드) · PR-6 #256(K8s 실배포 리허설) · PR-7 #257(AI 운영 보조, 선택) · PR-8 #258(감사 이상 탐지, 관찰 모드) · PR-9 #259(동의 카탈로그). 태그 `v1.1.0`. 1.0.x 패치는 `release/1.0`.

### 1.1.0 업그레이드 메모

- **DB**: 첫 기동에서 Flyway 가 hub V27~V30(shedlock·SLO 재시도·SCIM 아웃박스·이상 플래그), authz V5~V6(아웃박스 topic·피드 인덱스·규칙 할당), registry PostgreSQL V2 / MariaDB V10(동의 버전 범위)을 자동 적용한다. 되돌리는 마이그레이션은 없다 — 올리기 전 백업.
- **동작 변경**: 규칙 파라미터로 할당 필수를 풀거나 셀프 가입을 열 수 없다(PR-1). prod/stage 에서 `idem.hub.authz.enabled=false` 는 기동 거부(PR-1). gate `POST /api/v1/internal/session/logout` 은 Keycloak 실패를 502 로 낸다(PR-1). Keycloak 기본 메모리 한도 1536Mi → 2Gi(PR-6). 감사 분류 `AUTHZ`·`SCIM` 추가(PR-1·PR-5). 웹훅 `platformVersion`/`X-Platform-Version` 기본값 `1.0` → `1.1.0`(`IDEM_HUB_PLATFORM_VERSION`).
- **새 선택 구성**: 프로파일 `protocol.scim`(PR-5)·`consent`(PR-9), compose `--profile ai`/Helm `ai.*`(PR-7, 기본 꺼짐), `idem.hub.audit.anomaly.*`(PR-8, 기본 켜짐·관찰 모드 — 플래그만), 감사 WAL 디렉터리(PR-2, compose 볼륨·Helm `hub.auditWal`), `IDEM_HUB_AUTHZ_EVENTS_POLL_ENABLED`(PR-1, 기본 true).
- **제거**: `idem-agent`·`idem-agent-testbed`(PR-4, 별도 저장소 번들로 전달). Java 에이전트 사용 기관은 1.0 연동 방식(OIDC·Handoff)으로 — 개발자 가이드 §10.
- **설치본 검증**: CI 가 PR 마다 kind 에 Helm 설치→스모크→prod 전환→롤백→제거를 돈다(PR-6). 기관 클러스터 1회(`CLUSTER=existing`)와 설치본 스모크의 동의 단계는 남아 있다.

### 1.1 PR-9 · 동의 카탈로그 (플랜 §5 #8)

- **registry 카탈로그에 범위**: `consent_version.service_code`(V2, null = 플랫폼 공통). 내부 API `GET /api/v1/internal/consent-versions?serviceCode&includeInactive&catalog`(catalog = 공통 + 서비스 전용 ACTIVE), `POST …/consent-versions`(발행 — 같은 범위·유형의 ACTIVE 는 SUPERSEDED, 유형 `[A-Z][A-Z0-9_]{1,49}`, 위반 `E-IM-208`), `POST …/consent-versions/{id}/retire`, `GET …/users/{id}/consents/missing?serviceCode`(미동의 항목). 종전 활성 목록·최신 버전 조회는 플랫폼 공통만 본다(서비스 전용 버전이 공통 목록에 섞이지 않는다).
- **hub 관리 API·콘솔**: `/api/v1/admin/services/{code}/consents`(목록·발행·종료, 테넌트 범위; 종료는 그 서비스 범위의 버전만 — 아니면 `404 E-IM-207`), `/api/v1/admin/consents`(플랫폼 공통, 전역 관리자만). 감사 `ADMIN/CONSENT_VERSION_PUBLISHED·RETIRED`. 콘솔 "동의 항목" 메뉴(전역)·기관 상세 카드·프로파일 폼 체크박스. `ConsentRegistryClient`(registry 4xx 는 그 코드·본문 그대로, 장애 `E-IDO-106`).
- **코어 로그인 프런트의 동의 단계**: 프로파일 `consent {enabled, includePlatform}`(스키마 블록, 폼 밖 키처럼 보존). 켜진 서비스는 발급 전에 registry 미동의 항목을 확인해 **필수**가 남아 있으면 동의 화면(같은 경로 `POST /api/v1/handoff/login/consent` 로 form, 선택 항목도 함께; 서버가 다시 계산한 목록 안의 항목만 기록, 출처 `LOGIN_FRONT:<서비스>`·IP), 감사 `MEMBER/CONSENT_AGREED`; 거부는 `302 callback?error=E-IDO-125`(감사 `CONSENT_DECLINED`); registry 장애는 발급하지 않는다(오류 화면, 콜백 없음). 선택 항목만 남았거나 이미 동의한 사용자는 화면 없이 발급. hub CSP 는 `/api/v1/handoff/login/**` 만 `form-action 'self'`(그 밖은 종전 `'none'`). OIDC_RP·발급 API 직접 호출 경로는 적용 밖(개발자 가이드 §6.1).
- 테스트: hub UT(신규 `ConsentRegistryClientTest`·`ConsentAdminControllerTest`·`SecurityHeadersFilterTest`, `HandoffLoginControllerTest` 동의 7건, 검증기·인가 행), IT `HandoffLoginIntegrationTest.consentFlow`, registry UT(`ConsentControllerTest`·`ConsentServiceImplTest`), 콘솔 `consent.test.ts`·`profile.test.ts`. 문서: 개발자 가이드 §6.1·§11·§13.1, 관리자 매뉴얼 §13, 제품 설명서 F25, 시험 항목 D-18(+1.1 집계에 D-16·D-17 누락 정정), 요구 체크리스트 §3, 인가 매트릭스, 플랜 §5 #8·§9.
- 알려진 것: KR 회원 포털(`editions/idem-kr-portal`)이 부르는 `/api/v1/ext/consent/token`·`/api/v1/ext/consent` 는 hub 에 없다(구 경로) — KR 에디션 후속.

### 1.1 PR-8 · 감사 로그 이상 탐지 — 관찰 모드 (플랜 §5 #7)

- **아웃박스 이후 비동기 점수, 플래그만**: `AuditAnomalyScorer` 가 30초마다 커서(`audit_anomaly_cursor`, UUIDv7 순서) 뒤의 감사 행을 최대 200행 읽어 `AnomalyRules` 5개를 평가하고 `idem_hub.audit_anomaly_flag`(V30) 에 점수·심각도·축·근거를 남긴다. `audit_log` 는 불변, 경보·차단 없음, 인증 경로와 무관. 복제본은 커서 잠금(SKIP LOCKED)으로 하나만 돈다. 첫 실행은 현재 최대 `audit_id` 에 맞춘다(소급 없음), `lag-seconds` 창, WAL 재삽입 행은 제외. 기준 시각은 행의 `occurred_at`.
- **규칙** (`docs/audit-anomaly.md`): `ADMIN_LOGIN_FAILURE_BURST`(10분 5건, 50+10×초과) · `ADMIN_NEW_SOURCE_IP`(30일 기준선, 첫 로그인 제외, 40) · `ADMIN_OFF_HOURS_WRITE`(`idem.hub.zone` 22~07시·주말, 30) · `AGENCY_FAILURE_BURST`(10분 20건 이상 **그리고** 7일 창 평균의 3배, 50+비율×5) · `TICKET_REPLAY`(10분 3건, 70). 축당 창 안 플래그 하나. 설정 `idem.hub.audit.anomaly.*`.
- **검토가 산출물**: `GET/POST /api/v1/admin/anomalies/**`(목록·통계·검토, 전 역할 — AUDITOR 도 검토한다, 인가 매트릭스 별도 행; 테넌트 관리자는 `agencyCode` 필수) → 콘솔 "이상 징후"(필터·근거·정탐/오탐/모름·규칙별 정밀도·"판단"). 검토는 감사 `ADMIN/ANOMALY_REVIEWED`. **3개월 뒤**: 규칙당 검토 20건 이상에서 정밀도 0.7↑ 승격 후보, 0.3↓ 규칙 조정(§4). 지표 `audit.anomaly.scanned.total`·`audit.anomaly.flagged.total{rule}`. AI 장애 요약 스냅샷에 24시간 규칙별 플래그 수.
- 테스트: `AnomalyRulesTest`·`AnomalyAdminControllerTest`·`AdminAuthorizationTest`(+anomalies·ai 행), IT `AuditAnomalyIntegrationTest`, 콘솔 `anomaly.test.ts`. 문서: `docs/audit-anomaly.md`, 관리자 매뉴얼 §12, 제품 설명서 F24, 시험 항목 G-10, 설치 입력, 운영 매뉴얼, 인가 매트릭스.

### 1.1 PR-7 · AI 운영 보조 — 선택 컨테이너 (플랜 §5 #6)

- **세 가지 보조, 전부 관리 API 뒤·인증 경로 밖·저장 없음** (`/api/v1/admin/ai/**`, `AiAssistantService`): ① `POST /profile-draft` 자연어 → 프로파일 JSON 초안 — 서버가 `ServiceProfileValidator` 로 검증해 위반 목록을 같이 돌려주고, 기존 기관이면 `service.code`·테넌트 관리자면 `service.tenant` 를 고정한다. 콘솔 "AI 초안" 카드 → "JSON 탭에 넣기" → 저장은 관리자. ② `GET /audit-summary` 감사 요약 — `AuditDigest` 가 집계(분류·결과·행위·기관별, 실패 상위)와 표본 40행만 보낸다(IP·metadata 제외, 행위자 `ab***`), 테넌트 규칙은 감사 조회와 같다. ③ `GET /incident-summary` 장애 요약(전역 관리자) — `OpsSnapshotService` 가 health 구성요소·웹훅/SCIM 아웃박스·SLO 재시도 큐(상태별·5분 초과 PENDING·24h FAILED·마지막 오류)·감사 FAILURE 1h/24h·감사 유실/WAL 지표를 모으고 LLM 이 판정·확인 순서를 적는다. 콘솔 "AI 운영" 메뉴(스냅샷 표도 그대로 보인다).
- **LLM**: OpenAI 호환 Chat Completions(`LlmClient`, Ollama·vLLM·LM Studio). `idem.hub.ai.*`(`IDEM_HUB_AI_ENABLED` 기본 false, `_BASE_URL`, `_MODEL`, 비밀 `_API_KEY`). **사설망·루프백 밖 엔드포인트는 `_ALLOW_PUBLIC_ENDPOINT=true` 없이는 켜지지 않는다**(데이터가 설치본 밖으로 안 나가게). 꺼지면 `/status` 만 200(콘솔이 메뉴를 숨긴다), 나머지 404 `E-IDO-140`; `E-IDO-141` LLM 실패, `E-IDO-142` 응답 해석 실패, `E-IDO-143` 요청 오류. 호출마다 감사 `ADMIN/AI_PROFILE_DRAFT·AI_AUDIT_SUMMARY·AI_INCIDENT_SUMMARY`(내용 없이 모델·크기·위반 수).
- **설치본**: compose `--profile ai` 의 `idem-ai`(Ollama 0.6.8, 볼륨 `ai-models`, 포트 비공개) + hub 환경변수 pass-through; Helm `ai.*`(hub env, `ai.ollama.enabled` 면 `idem-ai` Deployment/Service, `apiKeySecret`), CI helm-lint 가 렌더·kubeconform. 콘솔 nginx 는 `/api/v1/admin/ai/` 만 읽기 타임아웃 180s.
- 문서: 관리자 매뉴얼 §11, 제품 설명서 F23, 시험 항목 G-9, 설치 입력, 운영 매뉴얼, 인가 매트릭스(`docs/admin-auth.md`).

### 1.1 PR-6 · K8s 실배포 리허설 (플랜 §5 #5)

- **`scripts/k8s/rehearsal.sh`**: Helm 차트를 실제 클러스터에 올려 끝까지 돈다 — `up`(kind + ingress-nginx + 리허설용 PostgreSQL 16·Redis 7) → `install`(비밀 한 벌 Secret, 자체 CA TLS Secret, `helm install --wait`; 리비전·Pod Ready·hub 프로파일·Service 에 관리 포트 없음(M7)·Ingress 호스트·관리 포트 health 검사) → `smoke`(TLS Ingress 경유 + registry·authz·관리 포트 port-forward 로 `install-smoke.sh` ①~⑧) → `upgrade`(운영 전환: prod 프로파일·Mock off, 리비전 2, MOCK 제공자 없음) → `rollback`(리비전 1 로, MOCK 복귀) → `down`(uninstall → Pod 0 → 클러스터 삭제). `CLUSTER=existing` 으로 기관 클러스터에도, `IMAGES=local|archive|registry` 로 이미지 출처를 고른다. 실패하면 Pod·이벤트·로그를 모은다. 코어 에디션만.
- **CI `k8s-rehearsal` 잡**: 차트·스크립트·워크플로가 바뀐 PR(`changes` 잡, 이미지는 `docker-build-check` 가 남긴 아카이브 `idem-*:pr`)과 main push(GHCR `:<sha>`)·수동 실행(`:latest`) 마다 kind 에서 위 스크립트를 돈다. 단계별 소요가 잡 요약에 남는다. 종전 "실제 클러스터 배포는 아직 못 했다"(설치 매뉴얼 §8·차트 README) 정정 — 기관 클러스터(운영 Ingress·바깥 DB) 1회는 남았다.
- **첫 통과 기록**(PR #256, GitHub 호스팅 러너, kind): up 65s · install 84s · 스모크 10s · 업그레이드 45s · 롤백 40s · 제거 13s, 합계 4분 17초 — `scripts/k8s/README.md` 결과 기록.
- **리허설이 드러낸 차트 결함 수리**: Keycloak production 모드(`start`)는 기본 이미지에 없는 빌드 옵션(db·health) 때문에 첫 기동마다 auto-build 를 하는데, 종전 한도 1536Mi 에서 OOMKilled(exit 137) 되어 CrashLoop → 기본 한도 2Gi(Keycloak Operator 기본과 같음) + `keycloak.optimized`(빌드해 둔 이미지로 `start --optimized`) + `KC_PROXY=edge`(deprecated) → `KC_PROXY_HEADERS=xforwarded`.
- 부수: 설치본 스모크에 `HUB_MGMT_URL` 등 관리 포트 주소(분리 배포에서 ① 헬스는 관리 포트, ②′ 는 앱 포트에 actuator 없음 확인). 비밀 생성기 `scripts/lib/gen-install-env.sh` 를 CI 스모크와 리허설이 공용(예시 파일 키 완전성 검사 포함). `docker-build-check` 의 태그를 차트 규칙(`<tag>-<edition>` 은 hub·registry 만)으로.

### 1.1 PR-1 · 연합 인가 정합성 + 할당 변경 전파 + SLO 재시도 (S8-b PR-2·D2 "남긴 것")

- **할당 정책 단일 해석기 (`AssignmentPolicyResolver`)**: Handoff 페이로드·OIDC 토큰 교환·CAST·`AssignmentRule` 이 같은 답을 낸다. 프로파일 `policy.rules[{type:ASSIGNMENT, params}]` 의 파라미터는 블록 `policy.assignment` 을 **조이기만** 한다(`required` OR, `selfSignup` AND). 종전에는 규칙 파라미터 `required=false` 로 규칙은 통과시키면서 상태는 할당 기준으로 계산되는 불일치가 있었다. **동작 변경**: 규칙 파라미터로 할당 필수를 풀거나 셀프 가입을 열 수 없다.
- **운영에서 authz 비활성 금지**: `FailSecureBootGuard` 가 prod/stage 에서 `idem.hub.authz.enabled=false`(항상 빈 역할) 를 기동 거부한다 — 코어 = SSO + IM. `application.yml` 의 "fail-open(빈 역할)" 주석 잔재 정정.
- **할당 변경 이벤트 전파**: authz 가 `assign`(신규·재활성)·`unassign`(상태 변경 시)·할당 만료에 아웃박스 이벤트 `AUTHZ_ASSIGNED`·`AUTHZ_UNASSIGNED`·`AUTHZ_ASSIGNMENT_EXPIRED` 를 적재한다(종전에는 역할 부여·회수·만료만). 새 읽기 전용 피드 `GET /api/v1/internal/authz/events`(키셋 `(createdAt,eventId)`, `X-Internal-Api-Key`). hub `AuthzEventPoller`(`IDEM_HUB_AUTHZ_EVENTS_POLL_ENABLED`, 기본 true, Kafka 유무 무관) → `AuthzEventConsumer` 가 `processed_event` 멱등으로 소비해 기관 웹훅 **`ASSIGNMENT_CHANGED`**(`change`=ASSIGNED·UNASSIGNED·ASSIGNMENT_EXPIRED·ROLE_GRANTED·ROLE_REVOKED·ROLE_EXPIRED, `agencySubjectId`, `roleCode`) 를 적재하고 감사(`AUTHZ/ASSIGNMENT_CHANGED`) 한다. 페이로드에 `qimUserId` 없음. authz V5: 아웃박스 topic 기본값을 코드와 맞추고 피드 인덱스 추가.
- **SLO IdP 단계 재시도 큐**: gate `POST /api/v1/internal/session/logout` 은 Keycloak 실패를 204 로 감추지 않고 **502**(+`X-Idp-Logout-Outcome: FAILED`) 로 낸다. hub `IdpSessionRevoker` 가 비 2xx·예외·FAILED 헤더를 실패로 판정해 `idem_hub.slo_idp_logout_retry`(V28) 에 적재하고 `SloIdpLogoutRetryRelay` 가 지수 백오프(10s·20s·40s·80s·160s, `IDEM_HUB_SLO_RETRY_*`) 로 재시도, 초과 시 FAILED + 감사 `SLO_IDP_LOGOUT_FAILED`. 종전에는 WARN 만 남고 Keycloak 세션이 살아 있을 수 있었다.

### 1.1 PR-5 · SCIM 2.0 아웃바운드 — Idem → 기관 프로비저닝 (플랜 §5 #3)

- **서비스별 opt-in**: 프로파일 `protocol.scim {enabled, baseUrl, credentialRef, groups, onUnassign, onWithdraw}`. 토큰은 프로파일에 없다 — `credentialRef=secrets/agency/{code}/scim-token` 이 hub 환경변수 `SECRETS_AGENCY_{CODE}_SCIM_TOKEN`(K8s Secret) 을 가리킨다(`AgencyCredentialStore`). `baseUrl` 은 백채널 로그아웃과 같은 SSRF 규칙(루프백·사설망 거부, 폐쇄망은 `IDEM_HUB_SCIM_ALLOW_PRIVATE_HOSTS=true`).
- **무엇이 가는가**: 기관향 식별자(agencySubjectId)를 SCIM `externalId`·`userName` 으로 — qimUserId·PII 는 가지 않는다. 할당 → `ENSURE_USER`(있으면 active=true, 없으면 POST /Users), 해제·만료 → `onUnassign`(DEACTIVATE 기본 / DELETE / NONE), 역할 부여·회수 → SCIM Group(displayName=roleCode) 멤버 add/remove(`groups`), registry 정지 → active=false, 탈퇴 → `onWithdraw`(DELETE 기본). 기관이 authz 인바운드 SCIM(`/scim/v2/Groups`) 으로 만든 변경(actor=SCIM)은 되돌이 방지로 밀어내지 않는다.
- **경로**: authz 이벤트(`AuthzEventConsumer`)·registry 정지·탈퇴(`QimEventConsumer` → `ScimUserLifecycleHandler`, 할당된 기관만) → `idem_hub.scim_outbox`(V29, 웹훅 아웃박스와 분리 — 그 표는 릴레이 둘·기관 피드가 읽는다) → `ScimOutboxRelay`(SKIP LOCKED, 10s·20s·40s·80s·160s 백오프, 400/401/403/501 은 즉시 FAILED, `IDEM_HUB_SCIM_*`) → `ScimClient`(RFC 7644 부분집합: `filter=externalId eq`, POST, PATCH replace active / members add·remove, DELETE, Bearer). 감사 `SCIM/SCIM_DISPATCHED·SCIM_DISPATCH_FAILED·SCIM_SYNC_REQUESTED`.
- **관리 API**: `GET /api/v1/admin/services/{code}/scim/status`(아웃박스 집계·마지막 오류), `POST …/scim/sync`(전체 동기화 — ACTIVE 할당 전부를 ENSURE_USER + 역할 그룹으로 적재; 도입·재조정용). authz 내부 API `GET /users/{id}/assignments`, `GET /agencies/{code}/assignments?page&size`. 콘솔 화면은 아직 없다(JSON 탭으로 블록 편집, 할당·규칙 화면과 같은 과제).
- **샘플 기관**: `idem-tenant-sample` 에 SCIM 서버 참조 구현(`/scim/v2/Users`·`/Groups`, 메모리, `AGENCY_SCIM_TOKEN`).
- **결함 수리**: 1.1 PR-1 의 `ASSIGNMENT_CHANGED` 감사가 쓰는 분류 `AUTHZ` 가 `audit_log` CHECK 에 없어 INSERT 가 조용히 실패하고 있었다(WAL 도입 뒤에는 재시도 반복) → V29 가 `AUTHZ`·`SCIM` 을 허용. WAL 재생기는 제약 위반 행을 재시도하지 않고 폐기·`audit.lost.total` 로 센다.

### 1.1 PR-4 · Java 에이전트 저장소 분리 (플랜 §5 #1, 사용자 결정 2026-09-29)

- `idem-agent`·`idem-agent-testbed` 와 에이전트 문서 7건(`docs/idem-agent-*.md`, `internal/architecture/idem-agent-architecture.md`·`jeus-sso-deep-dive.md`, `internal/development/idem-agent-developer-reference.md`)을 모노레포에서 **제거**하고 별도 저장소로 옮긴다. 에이전트가 부르는 검증 API(`/api/v1/agency/token/verify`)는 1.0.x 서버에 없어 1.0 연동 수단이 아니었고(제품 설명서 F7), 살리려면 hub 검증 API·브라우저 토큰 발급 설계가 먼저라 모노레포 밖에서 따로 다룬다.
- 정리: `settings.gradle.kts` include, 루트 `build.gradle.kts` 의 에이전트 제외 분기, 서비스 Dockerfile 6개의 `COPY idem-agent/build.gradle.kts`, `.githooks/pre-push` 대상 목록, `THIRD-PARTY-NOTICES.md`(Byte Buddy agent·Javassist 행), 문서 링크. **CI·설치본·런타임 동작 변경 없음**(어느 모듈도 에이전트를 의존하지 않았다).
- 새 저장소 씨앗: 단일 커밋 번들(코드·테스트베드·문서·README·settings·.gitignore, jar·비밀 없음). 이력은 끌고 가지 않는다 — 옛 `onepass-agent.properties` 의 비밀값 정리 이력(`docs/public-release-checklist.md`)이 모노레포 이력에 있어 새 저장소에 복제하지 않기 위해서다.

### 1.1 PR-3 · 코어 로그인 프런트 — Handoff 브라우저 진입 (플랜 §5 #2) + FE 세션 쿠키 수리

- **코어 로그인 프런트 (hub)**: Handoff 유형(DIRECT·BRIDGE·APACHE_GATE·INTERNAL_SSO) 서비스의 브라우저 진입을 core 가 제공한다. 기관 화면의 "Idem 으로 로그인" 은 **`GET {hub}/api/v1/handoff/login?service=&callback=[&provider=][&level=][&state=]`** 로 보내면 된다. hub 가 기관·활성·연동 유형·`callbackWhitelist` 를 검사하고(밖이면 오류 화면 — 콜백으로 되돌리지 않는다), 본인인증 SPI 제공자(플러그인)로 로그인(제공자가 하나면 자동, 여럿이면 선택 화면 `/start`, 브로커 `broker:<name>` 은 `IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS` 로 허용) → `/continue` 에서 registry 확정·FE 세션·`feSessionId` 쿠키 → **hub 안에서 발급**(API 발급과 같은 정책·레이트리밋·감사) → `302 callback?ticketId=…[&state=…]`. 정책 거부는 `302 callback?error=E-IDO-120&error_description=…[&state=…]`. 기관 API 키는 브라우저 어디에도 없고 기관은 종전대로 서버 간 `verify` 만 한다. 진입 상태는 Redis(`idem:handoff:login:*`, 10분, 1회)에만 있어 쿼리로 기관·콜백을 바꿀 수 없다. 화면은 CSP(`default-src 'none'`) 안에서 링크만 쓰는 최소 HTML — 운영기관이 자기 화면을 원하면 같은 URL 계약으로 대체한다. 진입 경로는 IP 레이트리밋(`/api/v1/auth/**` 와 같은 규칙) 대상.
- **FE 세션 쿠키 이름 불일치 수리 (결함)**: 발급 쪽(Keycloak·NonOidc 콜백, `/oidc/complete`, `/api/v1/fe-session`, KR 전환)은 `feSessionId` 를 쓰고 Handoff 발급 API 와 CAST 는 `Fe-Session-Id` 를 읽어 **브라우저에서 API 발급은 항상 `E-IDO-107`** 이었다(단위·통합 테스트가 쿠키를 직접 심어 드러나지 않았다). `FeSessionCookie.NAME`(`feSessionId`) 하나로 통일. **동작 변경**: `POST /api/v1/handoff/issue` 와 `POST /api/v1/agency/cast/issue` 가 읽는 쿠키 이름이 `feSessionId` 다(문서도 정정).
- **발급 API 기관 바인딩 (결함)**: `POST /api/v1/handoff/issue` 가 `X-Agency-Code`(키 검증된 기관) 과 본문 `agencyCode` 를 비교하지 않아 어느 기관 키로든 다른 기관 티켓을 발급할 수 있었다 → 불일치는 `403 E-AGENCY-302`.
- **qsign 모드 FE 세션 쿠키 전달 (결함)**: gate 콜백 → hub `/api/internal/v1/oidc/complete` 응답의 쿠키를 gate 가 전달하지 않아 설치본 기본(qsign) 모드에서 브라우저에 FE 세션 쿠키가 닿지 않았다. hub 가 1회용 바인드 코드 URL(`{public-url}/api/v1/fe-session/bind?code=…`, 60초, `idem:fe:bind:*`) 을 redirectUrl 로 돌려주고 브라우저가 거기서 쿠키를 받은 뒤 returnUrl 로 간다.
- **본인인증 SPI 절차 공유**: `IdentityLoginService`(initiate·complete·registry 확정·감사) 를 API(`/api/v1/auth/providers/**`)와 로그인 프런트가 함께 쓴다(동작 동일).
- **설정**: `idem.hub.public-url`(`IDEM_PUBLIC_URL_HUB`, 기본 `http://localhost:8083`) — hub 가 브라우저를 되돌릴 자기 주소(compose·Helm 반영). `idem.hub.handoff.login.broker-providers`.
- **샘플 기관 (`idem-tenant-sample`)**: `GET /agency/login` → hub 로그인 프런트 → `GET /agency/callback?ticketId&state`(state 쿠키 검증, 서버 간 verify, AGSID 세션) — 브라우저가 올 수 있는 Handoff 콜백 참조 구현(종전 `POST /agency/entry` 는 기관 키가 필요해 브라우저가 직접 올 수 없었다). `idem.sample.public-url`(`AGENCY_STUB_URL`), `idem.sample.ido.public-url`(`IDEM_PUBLIC_URL_HUB`).
- **설치본 스모크 ⑦b**: DIRECT 프로파일 PUT → `rotate-key` → 진입 → MOCK → 발급 → 콜백(ticketId·state·`feSessionId` 쿠키) → verify → 재검증 409 → 화이트리스트 밖 403. 종전 개발자 가이드의 "브라우저 경로가 시뮬레이터·D-10 으로 검증돼 있다" 는 서술은 부정확했다(시뮬레이터는 서버 간 발급만, D-10 은 API 만) — 정정.

### 1.1 PR-2 · 감사 WAL 폴백 + 그룹·속성 규칙 할당 (S8-b PR-2·H-16 "남긴 것" 마감)

- **감사 WAL 폴백 (hub)**: `idem_hub.audit_log` INSERT 가 실패하면 항목을 버리지 않고 로컬 JSON Lines WAL(`AuditWal`, `IDEM_HUB_AUDIT_WAL_DIR`, 기본 `./data/audit-wal`, append + fsync)에 남긴다. `AuditWalReplayer` 가 주기(`IDEM_HUB_AUDIT_WAL_REPLAY_INTERVAL_MS`, 기본 60초)마다 세그먼트를 회전해 `ON CONFLICT (audit_id) DO NOTHING` 으로 재삽입(원래 `occurred_at` 보존, 부분 실패 시 남은 줄만 다시 씀). 재삽입 행은 `kafka_published=false` 라 기존 Kafka 재발행 스케줄러가 이어받는다. 종전(1.0.1)에는 WARN 한 줄 남기고 감사 항목이 사라졌다. 지표 `audit.wal.appended.total`·`audit.wal.replayed.total`·`audit.wal.pending.lines`·`audit.lost.total`(WAL 마저 실패). prod/stage 에서 `IDEM_HUB_AUDIT_WAL_ENABLED=false` 는 기동 거부. compose 는 named volume `hub-audit-wal`, Helm 은 `hub.auditWal.{dir,existingClaim}`(기본 emptyDir) 에 둔다 — 파드 삭제까지 버티려면 PVC.
- **그룹·속성 규칙 할당 (authz + hub)**: authz V6 `authz_assignment_rule`(`GROUP`: `agencyCode:roleCode` 역할이 유효한 사용자 / `ATTRIBUTE`: 발급 컨텍스트 속성 `authLevel`·`providerCode` 가 허용 값 목록에 있는 사용자, `*` = 값 있으면 통과, `expiresDays` = 실체화 할당 유효 일수) + `authz_assignment.rule_id`. 규칙은 hub 발급 경로의 **접근 평가 시점에 실체화**된다: 직접 할당이 없으면 규칙을 순서대로 보고 첫 일치에서 `source=RULE` 할당을 만든다(`AUTHZ_ASSIGNED` 전파·감사 `ASSIGN`). `source=RULE` 할당은 매 평가마다 그 규칙을 재확인해 꺼졌거나 불일치면 회수(`UNASSIGNED` 전파) 후 다른 규칙을 본다. 규칙 비활성화(`DELETE /assignment-rules/{id}`)는 그 규칙이 만든 ACTIVE 할당을 즉시 회수한다. 직접 할당(CONSOLE·SCIM·API·SELF_SIGNUP·ROLE_GRANT)이 있으면 규칙은 보지 않고, 명시 회수된 직접 할당은 규칙이 되살리지 않는다(거부가 이긴다; 만료된 한시 할당은 규칙 대상). 내부 API `POST/GET /api/v1/internal/authz/assignment-rules`, `DELETE /assignment-rules/{id}`(→ `{revoked}`), **`POST /users/{id}/access`**(`{agencyCode, attributes}`, hub Handoff 발급·OIDC 토큰 교환·CAST 가 호출; verify 경로의 `GET /access` 는 읽기 전용 그대로). hub 는 `AssignmentContext` 의 두 키(`authLevel`·`providerCode`)만 보낸다 — 프로파일 identity 속성(PII)은 인가 서비스로 가지 않는다. 감사 `RULE_CREATED`·`RULE_DISABLED`, 오류 `E-AUTHZ-404-RULE`. 관리 콘솔 화면은 아직 없다(할당 관리 화면과 함께, `post-1.0-plan` §2.3).

## [1.0.1] — 2026-09-26

3차 적대적 점검(`docs/analysis/adversarial-review-1.0.md`) 후속. PR-A(보안, #244) → PR-B(설치본, #245) → PR-C(기능·문서). 태그 `v1.0.1`.

### 보안 (PR-A)
- **관리 API 인증 우회 수정 (H1)**: `AdminAuthFilter` 가 원본 URI 를 정규화(`RequestPath`)해 보호 경로를 판정하고, 경로 파라미터(`;x`)·퍼센트 인코딩(`%61`)·점 세그먼트·중복 슬래시로 위장한 요청은 세션과 무관하게 403 + 감사(`non-canonical path`). 모든 관리 엔드포인트(읽기 포함)와 Handoff 강제 취소가 `AdminPrincipal` 인자를 받아 필터를 지나쳐도 401 로 끝난다.
- **gate 프록시 경로 이탈 수정 (H2)**: `KeycloakProxy` 가 정규형 경로만 전달하고 설정된 realm 아래·`/resources/**` 만 허용 — `..`/`%2e%2e` 로 Keycloak 관리 콘솔·master realm·admin REST 에 닿을 수 없다(400 `invalid_request`). Location 재작성은 호스트·포트 비교(루프백 별칭 포함)로 내부 URL 누출을 막는다.
- **relay Flyway 제거 (H3)**: relay 가 `idem_hub` 에 `repair()` 를 돌려 hub 마이그레이션 이력을 지우던 결함. `BatchFlywayConfig`·relay `V19` 삭제, `shedlock` 은 hub `V27` 이 만든다.
- **관리자 역할·테넌트 변경 시 세션 종료 (M2)**, **TOTP 코드 1회 사용 (M3, RFC 6238 §5.2)** — 같은 스텝의 코드 재사용은 `E-IDO-134` "이미 사용한 2단계 인증 코드"(Redis `idem:admin:totp:{adminId}:{step}`), `scripts/lib/admin-login.sh` 는 다음 스텝으로 재시도.
- **Back-Channel Logout 검증 강화 (M4)**: `aud` 는 원소 정확 일치(`idem-gate-foo` 거부), `iat`·`jti` 필수, `exp`/최대 수명 검사, `jti` 재사용 거부(`idem:gate:bc-logout:jti:*`).
- **`backchannelLogoutUri` 검증 (M15)**: 공개 http(s) 호스트만 — 루프백·사설망·링크로컬·`.local/.internal`·이름만인 호스트(컨테이너 이름) 거부(SSRF).
- **gate 공개 OIDC 프런트 IP 레이트리밋 (M17)**: `/realms/**` 에 IP 당 20/s·300/min(`IDEM_GATE_FRONT_RL_*`), 초과 429 `rate_limited`, Redis 장애 시 503(fail-closed). `X-Forwarded-For` 는 `trust-forwarded-for=true` 일 때 마지막 홉만.
- hub: 지원하지 않는 메서드·Content-Type 은 500 이 아니라 405 `E-IDO-405`·415 `E-IDO-415`. authz: 없는 경로·메서드·미디어 타입도 플랫폼 본문(`E-AUTHZ-404/405/415`, 경로 반사 없음).
- 주석 정정: registry `QimWebMvcConfig`(상태 API 는 내부 키 필수), 레이트리밋 Redis 키 접두 `idem:*`, relay ShedLock 표 위치.

### 설치본 (PR-B)
- **Helm Pod 기동 (H4)**: 모든 Idem 이미지가 숫자 UID/GID 1001(`USER 1001:1001`)로 바뀌고 차트가 `runAsUser/runAsGroup/fsGroup`(앱 1001, Keycloak 1000)을 명시한다 — 종전에는 `runAsNonRoot` 가 이름 사용자를 거부해 앱 Pod 전부 `CreateContainerConfigError`.
- **`global.imageRegistry` 접두 (H5)**: Idem 이미지(짧은 이름)에만 붙는다. Keycloak·postgres 는 `image.registry: ""`.
- **관리 콘솔·KR 포털 이미지 (H6)**: `.dockerignore` 예외로 두 Dockerfile 이 빌드된다. CI `docker-build`(main, GHCR)·`docker-build-check`(PR) 매트릭스에 `idem-console-admin`·`idem-kr-portal` 추가 — `cd.yml` 이 배포하던 이미지가 이제 실제로 만들어진다.
- **업그레이드 데이터 고아 방지 (H7)**: `init-db.sql` 은 구 스키마(`ido`·`qsign`·`qim`·`authz`)가 있으면 새 스키마를 만들지 않는다(Helm 훅·compose 공통). `LegacySchemaRename` 과 `rename-db-1.0.sh` 는 구·신 스키마가 둘 다 있고 새 쪽에 Flyway 이력이 없으면 멈춘다(이력이 있으면 "구 스키마 남음" 경고). 스키마 존재는 `pg_namespace` 로 본다.
- **`rename-db-1.0.sh` (H8)**: 실행 사용자가 `onepass` 여도 임시 슈퍼유저를 만들어 역할을 옮긴다. `IDEM_DB_PASSWORD` 가 있으면 rename 뒤 비밀번호를 다시 설정(MD5 비밀번호는 rename 으로 지워진다), 없으면 경고.
- **Flyway repair 1회 (M1)**: 매 기동 `repair()` 대신 `validate` 를 먼저 하고 체크섬 불일치만 있을 때 1회 repair. 빠진·실패한 마이그레이션은 검증 오류로 드러난다. `IDEM_NAMING_LEGACY_REPAIR=false` 면 repair 하지 않는다.
- **`SPRING_PROFILES_ACTIVE=prod` (M6)**: compose(`IDEM_SPRING_PROFILE`)·Helm(`appDefaults.springProfile`)·CI 설치 스모크 모두 prod 프로파일로 뜬다. 켜 보니 hub 가 기동을 거부했다 — `prod` 에서는 KMS Off(평문 키 재료)가 금지되는데 설치본에 Vault 가 없다. 새 KMS provider **`local`**(`LocalMasterKeyKmsClient`): 설치본 비밀 `IDEM_HUB_KMS_MASTER_KEY`(base64 32바이트)로 회전된 Handoff 키 재료를 AES-256-GCM 봉인(`local:v1:` 접두). 1.0 이 남긴 평문 재료는 WARN 과 함께 읽는다(`IDEM_HUB_KMS_LOCAL_ACCEPT_LEGACY`). compose·Helm·CI 기본이 `local`, Vault 는 `provider=vault`.
- **actuator 관리 포트 (M7)**: `management.server.port=${IDEM_MANAGEMENT_PORT:${server.port}}`. Helm 은 9090 으로 분리하고 Service·Ingress 는 앱 포트만 내보낸다(프로브도 관리 포트). compose 는 앱 포트 그대로 — 리버스 프록시가 `/actuator` 를 막는다(문서).
- **compose 플러그인 플래그 (M8)**: `IDEM_PLUGINS_NICE_OACX_ENABLED`·`IDEM_PLUGINS_ANYID_ENABLED` 를 `install.env` 로 켠다.
- **Keycloak (M9·M10)**: Helm `KC_HOSTNAME_ADMIN_URL` 기본 `http://localhost:8088`(port-forward), `replicaCount>1` 은 `KC_CACHE_STACK` 없이는 렌더링 거부. realm-export 의 내부 client redirect URI·webOrigins 는 `${IDEM_PUBLIC_URL_GATE}`·`_HUB`·`_CONSOLE` 자리표시자 — compose·Helm·CI 가 Keycloak 에 그 값을 준다.
- **프로파일 `limits.tps/daily` 적용 (H9, PR-C)**: Handoff 발급(`HandoffServiceImpl`)과 표준 OIDC 토큰 교환(`OidcRpAccessService`)이 프로파일 한도를 `AgencyRateLimiter` 에 전달한다(없으면 설치본 기본 200 tps·1,000,000/일). 초과는 Handoff `429 E-AGENCY-306`, OIDC 토큰 교환 `429 temporarily_unavailable` + `Retry-After`(gate).
- **문서 정정 (PR-C)**: 온보딩 예시 `schemaVersion: 1`(정수)·모르는 키는 400·`INACTIVE` 는 authorize 단계 400·`status` 생략 시 ACTIVE·admin-login 환경변수 이름; 관리자 매뉴얼 PUT 순서(테넌트 → 스키마 → 저장 → Keycloak, 같은 트랜잭션); `install-inputs` 읽는 쪽 3건·`IDEM_HUB_INTERNAL_SIG_SECRET` 규칙·완료 판정 regex `[A-Z0-9_]`(CI 도); 제품 설명서 지표 이름(`slo.*` 등, hub prometheus 미등록)·감사 표현·K8s/Helm 버전 근거·주체 스킴 `PLATFORM_ID`·SCIM 인바운드; 시험 항목표 자동화 재집계(51 중 자동 41 = CI 37 + 로컬 IT 4, 수동 10)와 GS 착수 문서.
- **개명 잔재·가드 (PR-C)**: KR 포털 FE 의 `IDO_API_*`·`X-IDO-API-Key`·`ucube-qsign`·`onepassCli`·`QSIGN_*`, 스모크의 `AUTHZ_URL` 정리. `NamingGuardTest` 가 FE 소스(`.ts/.tsx`)·`.py`·Dockerfile·`AUTHZ_/BATCH_` 접두도 본다. <!-- docs-lint:ignore -->
- 버전 1.0.1 (루트 build, 콘솔 package, Helm Chart, 매뉴얼).
- LOW: Trivy 스캔이 `docker-build` 의 실제 이미지를 스캔한다(종전 `helm-lint` 끝에서 존재하지 않는 이미지를 스캔하고 항상 통과). `DB_SSLMODE`/`IDEM_*_DB_SSLMODE` 로 gate·hub·authz 도 JDBC TLS 를 켤 수 있다(Helm `infra.postgres.sslMode`). Helm `idem.host` 가 경로·포트 있는 URL 을 다룬다. 문서: Docker Compose ≥ 2.17.

## [1.0.0] — 2026-09-26

첫 동결 릴리스. 2026-09-10 부터의 범용화(`docs/generalization-plan.md` S1~S9·D1~D3)를 마감한다.

### 제품
- 제품 3개 재편: Idem SSO(`idem-gate`·`idem-hub`) · Idem IM(`idem-registry`·`idem-authz`) · KR 에디션(`editions/`, 플러그인). 코어는 에디션을 모른다(가드 테스트).
- 개명 OnePass → Idem 완료: 패키지 `io.github.hipstermin.idem.*`, 설정 키 `idem.*`, 환경변수 `IDEM_*`, DB `idem`·스키마 `idem_*`, Keycloak realm `idem`·client `idem-gate`/`idem-hub`. 구 이름은 1 릴리스 호환 계층(`LegacyNames`·`LegacySchemaRename`).
- 표준 OIDC 제공(OIDC_RP): issuer 는 공개 gate URL, Keycloak 은 숨김, client 자동 프로비저닝, PKCE 필수, 토큰 교환 시 정책 판정, SLO·Back-Channel Logout.
- 서비스 프로파일(JSON 스키마 v1): 프로토콜·주체 스킴·속성 카탈로그/마스킹/매핑·정책(인증 수준·제공자·세션·점검·규칙·할당)·한도·UI. 정책 시뮬레이션.
- 연합 인가: 역할 원장·할당(사용자·그룹 ↔ 서비스)·만료·회수, 미할당 거부 또는 GUEST.
- 관리자 인증·인가: 자체 계정 + TOTP 2단계, 역할 3종, 테넌트 범위, 잠금·비밀번호 정책·CSRF, 감사 검색. 관리 콘솔 `idem-console-admin`.
- 본인확인 SPI(`IdentityVerificationProvider`) 와 플러그인(Mock · KR: NICE OACX·Any-ID). 벤더 SDK·자격증명은 저장소 밖.
- registry: 없는 경로는 500 이 아니라 404 표준 오류 본문(`E-IM-404`)으로 답한다 — KR 전용 경로를 코어에 부른 경우를 도구가 구분할 수 있다.
- fail-secure: 필수 비밀 없으면 기동 거부, 의존 장애 시 거부, 시드 기관 없음, Mock 기본 off. `CryptoProvider` SPI 로 암호 모듈 교체 가능.

### 설치본
- Docker Compose 단일 설치본(PostgreSQL·Redis·숨긴 Keycloak·앱·콘솔, Kafka 없음) — CI 가 PR 마다 실기동 스모크 8단계.
- Helm 차트 `infra/helm/idem` — 같은 계약, `global.edition` core/kr, 비밀 한 벌, pre-install 스키마 Job. 이미지 `<tag>-<edition>`.
- 0.x → 1.0 업그레이드: `scripts/upgrade/rename-db-1.0.sh` + 첫 기동 자동 스키마 rename·Flyway repair.
- KR 회원 일회성 이관 도구 `scripts/kr-member-import`.

### 문서
- 설치·입력값·온보딩·요구사항 체크리스트·관리자 인증·개명 대응표, 1.0 매뉴얼 초안 4종(`docs/manuals/`), GS 착수 문서.

### 알려진 제한
- SAML SP·SCIM 아웃바운드·동의 카탈로그·Audit Sink SPI 없음. API 경로·오류 코드(`E-IDO-1xx`, `/admin/agencies`)와 에이전트 설정 키(`onepass.agent.*`, 외부 계약)는 1.0 에서 동결, 개명은 2.0.
- 실제 K8s 배포·오프라인 설치·백업 복구는 리허설 전(`docs/manuals/installation-manual.md` §8).

[1.0.0]: https://github.com/HipsterMIN/integration-sso/releases/tag/v1.0.0
