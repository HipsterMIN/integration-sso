# Idem 실행 계획 — 통합 테스트 복구 이후 ~ CC·GS 인증 (확장판)

> 작성 2026-09-09 · 기준 `shipster` d93cf71 (PR #221) · 요약본은 이 문서의 §0 · 요구사항 수준의 근거는 [`certification/cc-gs-gap-analysis.md`](certification/cc-gs-gap-analysis.md)
>
> 목표: **Idem 을 GS → CC(국내용, 통합인증 SSO 유형) 인증을 통과하는 제품으로 만든다.** 오픈소스 공개는 CC 평가판 고정 이후.
> 구조 리팩토링(범용화)은 [`generalization-plan.md`](generalization-plan.md) 로 진행하며, S7(관리 콘솔·관리자 인증)은 이 문서의 P1 과 같은 작업이다.
>
> **2026-09-16 결정 (generalization-plan v0.3)**: **GS 우선** — S9 의 1.0 동결이 P3(GS) 의 입력이고 P4(CC) 는 그 뒤 별도 프로젝트. **Keycloak 유지** — 설치본 안에 숨기고 토큰 발급·세션 경계만 Idem 뒤에 둔다(P0 의 "TOE 범위 고정" 은 이 전제로). P2 의 `CryptoProvider` SPI 는 generalization-plan **D2** 에서 먼저 만들고, KCMVP 모듈 교체만 P2 에 남긴다. 순서(v0.4, 2026-09-24): D1 다이어트 → D2 → S8-a → S8-b → S6 → S7(=P1) → S9 → P3(GS).

---

## 0. 요약 (한 장)

| 단계 | 기간 | 핵심 | 완료 기준 |
|---|---|---|---|
| **지금** | 이번 주 | PR #221 마무리 | k6 초록, 로컬 Testcontainers 35건 통과, main 머지, Docker Build 6종 초록 |
| **P0 고정** | ~4주 | TOE 범위·이름·플랫폼 고정, 인증 사무국 질의, 사업 결정 | 개명 4b·5 완료, 지원 플랫폼 1조합, 질의 회신, 법인·예산·관리자 인증 방식 결정 |
| **P1 차단 해소** | 8~12주 | 관리자 인증·인가, 감사 무결성·검토, TLS, 안전 기본값, 결함 4건 | 갭표 ❌ 항목이 🟡 이상, 통합 테스트 CI 게이트 |
| **P2 암호모듈** | 8~12주 (P1 병행) | CryptoProvider SPI, KCMVP 모듈 교체, 키 제로화 | JCE 직접 호출 0건, 모듈 자체시험 통과 |
| **P3 GS** | 8~16주 (P1 후반부터) | 설치본·매뉴얼·제품 설명서, 시험 신청 | GS 1등급, 조달 등록 |
| **P4 CC** | 산출물 8~12주 + 평가 6~12개월 | ST·기능명세·지침서·형상·시험·취약성 분석, 평가 계약 | CC 인증서 |

병행 트랙: 벤더 플러그인 P2~P5, 오픈소스 차단 항목(B2·B3·라이선스), 자체 호스팅 러너 정비.

---

## 1. 지금 — PR #221 마무리 (이번 주)

| # | 작업 | 담당 | 명령/기준 |
|---|---|---|---|
| 1 | 러너 서비스 재시작 (docker 그룹 반영) | 사용자 | `cd ~/actions-runner && sudo ./svc.sh stop && sudo ./svc.sh start` |
| 2 | 로컬 Testcontainers 통합 테스트 | 사용자 | `git pull origin shipster && ./gradlew :idem-hub:integrationTest --no-daemon` → **35건 = 32 통과 + 3 skip** |
| 3 | PR #221 k6 재실행 | 사용자 | `gh run rerun 34198224388 --failed` |
| 4 | 실패 시 진단·수정 | AI | 로그 → 수정 → shipster 푸시 |
| 5 | 머지 (사용자 "머지" 지시) | AI | `shipster → main`, 이후 shipster 를 main 에 동기화 |
| 6 | main Docker Build 6종 | 사용자 | 사무실 망은 빌드 컨테이너 CA 문제로 실패 → **집 망에서** `gh run rerun <id> --failed` |
| 7 | 배포 시 Redis flush | 운영 | 4a 로 `@class` FQCN 변경 — 기존 세션 값 역직렬화 불가 |

완료 기준: main 의 CI 전부 초록, `docs/local-dev-workflow.md` 의 훅 흐름이 사용자 로컬에서 재현됨.

---

## 2. P0 — 범위·이름·플랫폼 고정 (~4주)

인증 산출물은 제품명·설정 키·구성요소 이름을 그대로 담는다. **여기서 바꾸지 않으면 문서를 두 번 쓴다.**

### 2.1 코드 (AI)
- **개명 4b** — 런타임 식별자: 설정 키 `ido.*`/`qim.*` → `idem.*`, 헤더 값 `q-sign`, Redis 접두 `ido:*`, 환경변수 `IDO_*`/`QIM_*`/`ONEPASS_*`, `spring.application.name`. 호환 기간: 구 키를 읽되 경고 로그, 1 릴리스 뒤 제거.
- **개명 5단계** — DB 스키마·테이블 접두(`ido.`, `qsign.`, `qim.`), Keycloak realm/client 이름, k8s 이름, Helm 값. Flyway 마이그레이션으로 rename, 롤백 스크립트 동봉.
- **인증수준 어휘 통일** — `AuthResult.AuthLevel(L1/L2/L3)` 와 `CastToken(LOW/MEDIUM/HIGH)` 를 하나로. 플러그인 하드코딩 `L2` 를 SPI 계약으로.
- **지원 플랫폼 1조합 고정** — JDK 21 / Spring Boot 3.5 / PostgreSQL 16 / MariaDB 11.4 / Redis 7.2 / Kafka 7.6 / Keycloak 24 / Vault 1.17. 평가 구성 `values-eval.yaml` 신설.
- **TOE 범위 문서화** — 갭 분석 §2 를 `docs/certification/toe-boundary.md` 로 확정. 벤더 플러그인·Nginx·Keycloak·DB·Redis·Kafka·Vault 는 운영환경.

### 2.2 사업·조직 (사용자)
- 신청 법인·개발기관 결정, 예산 범위(평가·컨설팅·KCMVP 모듈 라이선스·모의해킹).
- **관리자 인증 방식 결정**: ✅ 자체 계정 저장소 + TOTP 로 결정·구현(S7 PR-1, ADR-015). Keycloak 은 ADR-014 로 설치본 내부에 숨겨져 관리 콘솔을 노출하지 않으므로 realm 위임을 택하지 않았다.
- GS 선행 여부 확정.
- IT보안인증사무국 사전 질의 — 갭 분석 §8 의 8개 항목. 특히: SSO 유형 현행 판·컴포넌트 목록, Kubernetes 배포 TOE 인정 범위, Nginx TLS 종단 허용 여부, KCMVP 모듈 후보.
- 평가기관 2곳 이상 견적, 컨설팅 범위 결정.

완료 기준: 개명 잔존 0건(`docs/naming.md` §4 전부 완료), `toe-boundary.md` 확정, 질의 회신 문서화, 결정 3건 기록.

---

## 3. P1 — 보안기능 차단 항목 해소 (8~12주)

갭 분석 §3 의 ❌ 를 🟡 이상으로. 우선순위순.

### 3.1 관리자 식별·인증·인가 (FIA/FMT) — 최우선 — ✅ 완료 (generalization-plan S7 PR-1·PR-2, 2026-09-25, ADR-015)
- ✅ 서버 측 관리자 인증: 자체 계정(`ido.admin_user`) + TOTP 2단계 + Redis 세션, `AdminAuthFilter`(결정 D3 = 자체 저장소, ADR-015).
- ✅ 적용 범위: `/api/v1/admin/**`, `DELETE /api/v1/handoff/{id}`, `/actuator/**`(health·info·prometheus 제외). 관리 콘솔(`idem-console-admin`)은 별도 BFF 없이 같은 API 를 같은 출처로 부른다(PR-2).
- ✅ 서버 측 RBAC: `SYSTEM_ADMIN` / `POLICY_ADMIN` / `AUDITOR` + 테넌트 범위, 인가 매트릭스 `docs/admin-auth.md` §4.
- ✅ `X-Admin-Id` 헤더 제거, 인증된 신원으로 대체(감사 actor).
- ✅ 관리자 세션: 유휴 15분·절대 8시간, 동시 세션 1. ⏭ 마지막 로그인 표시·접근 배너(콘솔 후속).
- ✅ 관리자 계정 잠금(5회→15분)·해제, 비밀번호 정책(길이·문자종·사용자명·이력 3), 첫 로그인 변경 강제.
- ✅ 코어 관리 콘솔 `idem-console-admin` 신설(로그인 2단계·온보딩·OIDC client·감사·관리자), 구 `idem-console`(SigNoz 유래 포털)은 `editions/idem-kr-portal` 로 이동(PR-2). 포털의 미구현 화면 정리는 KR 에디션 과제.

### 3.2 보안감사 (FAU)
- **모든 관리 행위 감사**: 기관 등록·수정·활성화·키 회전, 정책 변경(✅ 인증된 관리자 actor 로, S7 PR-1) · 관리자 로그인/실패/잠금/2단계/권한 거부/계정 관리(✅ `ADMIN_*`, S7 PR-1) · ⏭ 기능 플래그 변경, 감사 기능 on/off, TOE 기동·종료.
- **유실 방지**: `AuditLogPublisher` 실패 시 로컬 파일 폴백 큐 + 재전송, 실패 카운터 메트릭·알림, 설정 가능한 "감사 불가 시 서비스 거부" 모드. VARCHAR(36) 컬럼 확장 또는 입력 길이 검증.
- **무결성**: `audit_log` 레코드 해시체인(`prev_hash`, HMAC 키는 KMS), DB 앱 계정에서 audit 테이블 UPDATE/DELETE 권한 회수 + RLS, 변조 검증 배치.
- **검토**: ✅ `/api/v1/admin/audit` 검색 API(기간·분류·사건·주체·기관·결과·상관ID, S7 PR-1) · ✅ 콘솔 감사 화면(PR-2).
- **보존**: 월 파티셔닝, 서명된 월별 아카이브 export, 보존 만료 삭제 배치.
- 감사 저장소 통합 또는 통합 조회 계층 (`ido.audit_log` / `qsign.auth_audit_log` / `authz_grant_audit` / `broker_audit_log` / `gateway_inbound_audit`).

### 3.3 안전한 채널 (FTP)
- 각 서비스 `server.ssl.*` TLS 1.2+ 직접 종단 (사무국 회신에 따라 Nginx 를 TOE 에 포함하는 대안).
- DB `sslmode=disable`·`useSSL=false` 고정 제거 → 평가 구성에서 강제 TLS.
- Redis·Kafka TLS 옵션.
- 기관 프로비저닝 mTLS 폴백 제거 — 키스토어 없으면 기동 거부.
- 내부 서명(`X-Internal-Sig`) 강제 모드(F-26) 기본 on.
- 웹훅 HTTPS 만 허용.

### 3.4 안전 기본값 (FMT_MSA.3)
- prod 프로파일에서 우회 플래그 기동 거부: `allow-empty-*`, `allow-in-prod`, `security-headers.enabled=false`, `rate-limit.enabled=false`, `audit.db-save-enabled=false`.
- CAST 키 미설정 시 임시 키 생성 금지(prod 기동 거부).
- 기관 API 키 저장을 `ApiKeyHashUtil`(PBKDF2)로 통일, 내부 호출자 키는 Vault 참조.
- Redisson 분산락 평가 구성에서 강제.

### 3.5 결함 (갭 분석에서 발견)
- idem-gate `LockRepository.isLocked(providerCode, providerCode)` — 식별자 대신 provider 전달, 잠금이 provider 전역.
- `HandoffController` `redirectUri` null 시 콜백 화이트리스트 검증 생략.
- `allowed-return-urls` `startsWith` 비교 → origin 비교.
- Kafka 장애 시 Handoff 요청 60초 블로킹 — 발행 비동기·`max.block.ms` 단축.
- registry `InternalApiKeyInterceptor` "Missing"/"Invalid" 구분 응답 → 통일.

### 3.6 세션·접근 (FTA)
- 사용자 동시 세션 한도(역인덱스 활용), 관리자 접속 허용 IP 대역.

### 3.7 품질 증적
- 통합 테스트를 CI 게이트로 (Docker 있는 자체 호스팅 러너, `DOCKER_UNAVAILABLE` 제거).
- JaCoCo 리포트 CI 아티팩트 보관.
- SAST 도입(Semgrep 또는 SpotBugs), 결과를 PR 체크로.

완료 기준: 갭표 §3 에 ❌ 없음, 통합 테스트 CI 초록, 새 기능마다 통합 테스트 존재.

---

## 4. P2 — 검증필 암호모듈 (8~12주, P1 병행)

- ~~**`CryptoProvider` SPI 신설**~~ **✅ 2026-09-21 generalization-plan D2-b 에서 완료** — idem-common `crypto/CryptoProvider`(AES-GCM/CBC·HMAC·SHA-256·PBKDF2·DRBG·상수시간 비교·Ed25519/RSA 서명), 기본 구현 `jca/JcaCryptoProvider`, `CryptoBoundaryGuardTest` 가 코어(common·gate·registry·hub·authz·relay)의 JCE 직접 호출 0건을 강제. P2 에 남는 것은 아래 KCMVP 어댑터·키 수명·키 회전.
- **호출부 교체** — ✅ 코어 63곳 완료(D2-b). 남은 것: `NiceCryptoUtil`(플러그인, 벤더 규격), `HmacSigner`(SDK, 무의존), `OnePassHttpClient`(에이전트) — KCMVP 모듈 도입 시 SDK/에이전트를 모듈 경유로 할지 별도 결정.
- **KCMVP 모듈 어댑터** — 후보 선정(JDK 21·Spring Boot 3·컨테이너 지원, 라이선스), 어댑터 구현, 모듈 자체시험 호출을 기동 시 실행.
- **키 수명 관리** — 키 재료 `byte[]`/`char[]` + 사용 후 제로화, KMS 로부터 받은 키의 메모리 체류 최소화.
- **키 회전** — CI 키 회전 재암호화 배치, 절차를 관리자 지침서에.
- Ed25519(CAST) 는 모듈 지원 여부에 따라 알고리즘 변경 검토 — 사무국 질의 결과 반영.

완료 기준: 평가 구성에서 모든 암호 연산이 모듈 경유, 모듈 자체시험 통과 로그, 인벤토리 갱신.

---

## 5. P3 — GS 인증 (8~16주, P1 후반부터 병행)

> **착수 (2026-09-26, S9 PR-4)** — 입력인 1.0 동결(`v1.0.0`)이 끝났다. 착수 문서 `docs/certification/gs-kickoff.md`(범위·제출물 상태·일정·결정 6건), 매뉴얼 초안 `docs/manuals/`(설치·관리자·제품 설명·시험 항목표 51). 아래 항목 중 설치본(compose·Helm·오프라인 절차)·문서 초안·콘솔(P1 기능 화면)은 갖췄고, 남은 것은 오프라인 설치·백업 복구 리허설, 시험 항목표 완주와 결함 밀도, 매뉴얼 양식 변환, 시험원·법인·예산 결정이다.

- **설치본**: 오프라인 설치 패키지(이미지 tar + Helm 차트 + compose 대안), 단일 설치 매뉴얼(전제·절차·검증·제거), 시험원이 그대로 따라할 수 있는 수준.
- **문서**: 제품 설명서(기능 목록·지원 플랫폼), 사용자 매뉴얼(최종 사용자·기관 개발자), 관리자 매뉴얼(콘솔 기능별).
- **품질**: 전체 회귀 1회 → 결함 밀도 산출, k6 재측정, 지원 조합 호환성 시험, 한국어 오류 메시지 정비, 백업·복구 절차 문서.
- **콘솔 완성**: P1 관리자 기능이 실제 화면으로 존재해야 함(GS 사용성 시험 대상).
- **신청**: TTA 또는 KTL, 중소기업 감면 확인, 시험 환경(시험원 제공 vs 신청기관 제공) 확정.

완료 기준: GS 1등급 인증서, 조달청 종합쇼핑몰 등록.

---

## 6. P4 — CC 산출물·평가 (산출물 8~12주 + 평가 6~12개월)

- **ASE 보안목표명세서(ST)** — TOE 개요, 위협·가정, 보안목적(TOE/OE), SFR 선택, TSS. 컨설팅 병행.
- **ADV** — 기능명세(TSFI 별 목적·파라미터·오류, `PlatformErrorCode` 기준), 설계(서브시스템·모듈, TOE 경계 기준 재정리).
- **AGD** — 준비 절차(평가 구성 설치·초기 관리자·TLS·KMS), 운영 지침(감사 검토·키 관리·계정 관리·자체시험·백업).
- **ALC** — 형상항목 목록·버전 체계·릴리스 태그·제품 CHANGELOG, 배포 무결성(이미지 다이제스트·cosign 서명), 개발환경 보안 절차, 결함 교정 절차·취약점 공지 채널.
- **ATE** — 시험 계획서(TSFI↔테스트 매핑), 결과, 커버리지.
- **AVA** — SAST 결과, 외부 모의해킹 1회, 조치 이력.
- **라이선스·SBOM** — `LICENSE`(Apache-2.0 권장)·`NOTICE`, CycloneDX SBOM.
- **평가** — 계약, 평가판 태그·해시 고정, 결함 교정 라운드 대응(개발 인력 상시 배정).

완료 기준: CC 인증서, 평가판과 공개판 동일성 관리 절차.

---

## 7. 병행 트랙

| 트랙 | 내용 | 시점 |
|---|---|---|
| 벤더 플러그인 P2~P5 | NICE/OACX 재수령 후 플러그인 완성, AnyID 분리, SPI 계약 확정 | P0~P1 |
| 오픈소스 차단 해소 | B2 벤더 SDK 바이너리 제거, B3 벤더 프런트 자산 제거, C5 라이선스 | P1 |
| 오픈소스 공개 | 스냅샷 이관(히스토리 없이), gitleaks 0건, Actions 전환 | **P4 평가판 고정 이후** |
| 러너·개발환경 | 러너 Docker 네이티브 안정화, 사무실 망 빌드 컨테이너 CA(다음 항목), Ubuntu D: 이관 | 지금~P0 |
| 사무실 망 Docker Build | 빌드 컨테이너에 회사 CA 주입(BuildKit secret 또는 base 이미지) | P0 |
| 벤더 키 교체 | `open-source-readiness.md` B1 후속 — 노출됐던 키 폐기·재발급 | 지금 |

---

## 8. 의사결정 대기 (사용자)

| # | 결정 | 영향 | 기한 |
|---|---|---|---|
| D1 | 신청 법인·개발기관 | 인증서 명의, ALC 개발환경 범위 | P0 |
| D2 | 예산 범위 | 평가기관·컨설팅·모듈·모의해킹 | P0 |
| D3 | 관리자 인증 방식 (Keycloak 위임 vs 자체) — **✅ 결정: 자체 계정 저장소 + TOTP (2026-09-25, `wiki/adr/ADR-015-admin-auth.md`)** | P1 설계 전체 | P0 초 |
| D4 | GS 선행 여부 | P3 시점 | P0 |
| D5 | TLS 종단 위치 (서비스 직접 vs Nginx 포함) | P1 §3.3, 사무국 회신 후 | P0 말 |
| D6 | KCMVP 모듈 선정 | P2 어댑터 | P0 말 |
| D7 | 라이선스 (Apache-2.0 권장) | 오픈소스·SBOM | P1 |
| D8 | 개인정보 보존 기간(현재 임시 365일) | 파기 배치 활성화 | P1 |

---

## 9. 위험

| 위험 | 영향 | 대응 |
|---|---|---|
| KCMVP 모듈이 Ed25519·AES-GCM 을 지원하지 않음 | CAST·Handoff 알고리즘 변경, 기관 SDK 호환 | P0 에 모듈 후보 사양 확인, SPI 로 알고리즘 교체 가능하게 |
| 사무국이 Kubernetes·Nginx 종단 구성을 인정하지 않음 | TOE 경계·TLS 설계 재작업 | P0 질의로 선확인, 서비스 직접 TLS 를 기본 설계로 |
| 관리자 콘솔 규모 과소평가 | P1·P3 지연 | SigNoz 잔재 제거로 범위 축소, 최소 화면(기관·정책·감사·계정) 우선 |
| 개발 인력 1~2명으로 P1·P2 병행 | 일정 2배 | P2 를 SPI 신설까지만 P1 과 병행, 모듈 교체는 P1 후 |
| 평가 중 결함 교정 라운드 | 평가 기간 연장 | 통합 테스트·SAST 를 CI 게이트로 두어 회귀 차단 |
| 오픈소스 공개와 평가판 불일치 | 인증 효력 논란 | 공개를 평가판 고정 이후로, 태그·해시 공표 |

---

## 부록 — 관련 문서

- 요구사항별 갭·판정·조치: `docs/certification/cc-gs-gap-analysis.md`
- 개명 대응표·단계: `docs/naming.md`
- 오픈소스 차단 항목: `docs/open-source-readiness.md`
- 벤더 플러그인 계획: `docs/vendor-plugin-plan.md`, `docs/identity-provider-spi.md`
- 로컬 워크플로·통합 테스트 발견 결함: `docs/local-dev-workflow.md`
- 러너·CI: `docs/ci-runner-guide.md`
