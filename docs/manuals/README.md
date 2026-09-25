# Idem 1.0 매뉴얼 (초안)

> S9 PR-4 (2026-09-26). GS 인증(`docs/execution-plan.md` P3) 신청에 내는 문서 묶음의 **초안**이다. 각 문서는 이미 검증된 절차·기능만 적고, 확인하지 못한 것은 "못 한 것" 절에 남긴다. 최종본은 시험원 양식에 맞춰 옮긴다(`docs/certification/gs-kickoff.md`).

| 문서 | 대상 | 내용 |
|---|---|---|
| [`installation-manual.md`](installation-manual.md) | 설치자·시험원 | 전제 → 입력값 → 설치(compose / Helm / 오프라인) → 검증 → 업그레이드 → 백업·복구 → 제거 |
| [`administrator-manual.md`](administrator-manual.md) | 운영기관 관리자 | 관리 콘솔 기능별(로그인·2단계, 서비스·프로파일, OIDC client, 시뮬레이션, 테넌트, 관리자, 감사) + 운영 작업(비밀 회전, 장애 대응) |
| [`product-spec.md`](product-spec.md) | 시험원·구매자 | 제품 설명서 — 기능 목록, 구성, 에디션, 지원 플랫폼, 인터페이스, 한도, 보안 기능 |
| [`test-items.md`](test-items.md) | 시험원·QA | 시험 항목표 — 기능별 시험 절차·기대 결과·자동화 여부(CI 스모크·E2E·단위) |

사용자(최종 이용자·기관 개발자) 쪽 문서는 기존 가이드가 맡는다: 기관 담당자 `docs/sso-agency-integration-guide.md`, 기관 개발자 `docs/sso-agency-developer-guide.md`·`docs/idem-sdk-java-usage-guide.md`·`docs/idem-agent-integration-guide.md`, 운영기관 관리자 온보딩 `docs/onboarding-guide.md`.
