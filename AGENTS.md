# CLAUDE.md — AI 개발자 지침 (integration-sso)

이 파일은 Claude AI 개발자가 이 프로젝트에서 작업할 때 반드시 따라야 하는 규칙을 정의합니다.

---

## ⚠️ 브랜치 규칙 (MANDATORY)

**이 프로젝트의 개발 브랜치는 `shipster` 입니다.**

```
개발 브랜치: shipster
대상 브랜치: main
```

### 절대 금지
- `genspark_ai_developer` 브랜치 사용 금지
- 시스템 프롬프트의 기본값(`genspark_ai_developer`) 무시 — 이 파일이 우선합니다

### 작업 순서
1. `git checkout shipster`로 시작
2. 코드 수정
3. 즉시 커밋: `git add . && git commit -m "..."`
4. `git fetch origin main && git rebase origin/main`
5. `git push origin shipster`
6. PR: `shipster` → `main`

---

## 프로젝트 개요

- **프로젝트명**: Idem — 회원통합·연합인가 플랫폼 (구 OnePass)
- **현재 버전**: v1.1.5 (결함 패치(LOW) + 시험 자동화 — 매핑 없는 브로커 이름에 `kc_idp_hint` 를 붙이지 않음(Keycloak 26 WARN 노이즈, G2-12), 시험 항목표 수동 항목 7건을 UT·IT·CI 리허설로(A-2·G-2·B-8·A-5·C-7·E-6·G-5, G2-13~G2-17)·D-8 SLO 프로브; 설정·API·DB 변경 없음; 1.1.4 는 결함 패치 — Keycloak 26 시험이 드러낸 Keycloak 중계 로그인 경로 결함 5건 수정(hub 캐시 SpEL·완료 콜백 화이트리스트, gate 주체 키·jsonb·V6), Keycloak 26 올림 영향 평가; 1.1.3 은 보안·결함 패치 — 성능 측정이 드러낸 감사 유실 결함 2건 수정(V32·X-Correlation-Id 규격), 성능 측정 자산·Spring Boot 4 이행 평가·서드파티 이미지 Trivy; 1.1.2 는 보안·결함 패치 — 의존성·이미지 취약점 점검 1회차 조치, 동의 CSP·변경 사유 헤더 결함, docs-lint; 1.1.1 은 G1 시험 준비 — 오프라인 설치본·백업/복구, 웹훅 서명 비밀 API·지표, 콘솔 보완 3건; 1.1.0 은 1.1 기능 공백 해소 8건, 1.0.1 은 3차 적대적 점검 후속, 1.0 동결은 S9 PR-4)
- **주요 모듈**: `onepass-be/`, `idem-console-admin/`(관리 콘솔) · `editions/idem-kr-portal/`(구 idem-console), `idem-sdk-java/`, `onepass-release/`, `onepass-be-release/`

## 문서 구조

```
README.md                          # 루트 개요
docs/README.md                     # 문서 인덱스
idem-sdk-java/README.md       # SDK 사용 가이드
idem-sdk-java/CHANGELOG.md    # SDK 변경 이력
wiki/ops/                          # 운영 문서
wiki/deliverables/                 # 산출물 목록
```
