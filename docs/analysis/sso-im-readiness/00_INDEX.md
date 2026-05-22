# SSO/IM 본질 기능 운영 적합성 심층 분석 보고서

> **목적**: 운영 배포 직전 단계에서, 현재까지 개발된 SSO/IM 기능이 실제 사용 시 발생할 수 있는 문제를 사전에 식별하고 우선순위화한다.
>
> **배경**: Sprint A/B를 통해 운영 관리 포인트(Health/Metric/Alert)는 충분히 정리되었다. 이제는 "운영을 위한 인프라"가 아니라 **"본질 기능이 실제로 동작하는가"**를 검증해야 한다.
>
> **방법**: 정찰(reconnaissance) → 코드 레벨 검증 → 시나리오 추적 → 리스크 매트릭스 → 우선순위화 → 실행 로드맵.
>
> **작성**: 2026-05-22 (Sprint B 종료 직후, 운영 배포 직전 단계)

---

## SSO/IM 본질 4축

OnePass는 4개의 본질 책임을 갖는다 (`docs/OPERATION_INVENTORY.md §1`):

| 본질 | 책임 모듈 | 핵심 질문 |
|------|----------|----------|
| **사용자가 로그인할 수 있는가** | q-sign | 인증이 끝까지 완결되는가? 토큰이 검증 가능한가? |
| **세션이 안전하게 유지되는가** | ido (sso, ticket) | 세션이 의도대로 만료/연장되는가? Redis 장애 시 어떻게 되는가? |
| **기관 연계가 정상 작동하는가** | q-im, ido (handoff, gateway) | Handoff가 기관에 정확히 전달되는가? 식별자가 정합하는가? |
| **개인정보가 안전한가** | ido (crypto, audit) | 평문 PII가 흘러나갈 통로는 없는가? KMS 장애 시 안전하게 거부하는가? |

본 분석은 위 4축 각각에 대해 **"기능 자체가 깨질 수 있는 경로"**를 찾는다.

---

## 분석 단계 (Phase별 산출물)

| Phase | 주제 | 산출 파일 | 상태 | 주요 발견 |
|-------|------|----------|------|----------|
| 1 | 전체 아키텍처 + 도메인 경계 재파악 | `01_architecture_recon.md` | ✅ 완료 | 8개 리스크 (R1-R8) |
| 2 | 인증 플로우 (Q-Sign) — 로그인/세션/토큰 | `02_authentication_flow.md` | ✅ 완료 | 11개 발견 (F2.1-F2.11) |
| 3 | 식별·매핑 (Q-IM) — CI/DI/존재적 식별 | `03_identity_mapping.md` | ✅ 완료 | 14개 발견 + 시나리오 D-G |
| 4 | 핸드오프 (IdO) — Issue/Verify/Webhook | `04_handoff_flow.md` | ✅ 완료 | 19개 발견 + 시나리오 H-L |
| 5 | 개인정보 보호 — KMS/감사/PII | `05_privacy_kms_audit.md` | ✅ 완료 | 16개 발견 + 시나리오 M-P |
| 6 | End-to-End 시나리오 — Agency 연계 시뮬레이션 | `06_e2e_scenarios.md` | ✅ 완료 | 19개 시나리오 + 결함 매트릭스 |
| 7 | 종합 리스크 매트릭스 + 우선순위 + 실행 로드맵 | `07_risk_matrix_roadmap.md` | ✅ 완료 | Sprint α/β/γ/δ 로드맵 + 3개 결정 옵션 |

### 분석 통계 (최종)

- **총 결함 수**: 60개 (Phase 2-5 합산)
  - Critical: 17개
  - High: 21개
  - Medium: 17개
  - Low: 5개
- **E2E 시나리오**: 19개 (정상 4 + 열화 6 + 파국 5 + 보안 4)
- **즉시 발현 가능 시나리오**: 5개 ("prod 진입 즉시 사용자가 막히는" 경로)
- **문서 총량**: 약 165KB, 8개 파일

### 가장 시급한 Critical 결함 (운영 진입 차단 사항)

| ID | 위치 | 요지 |
|----|------|------|
| F5.1 | `LocalKmsClient.java:46` | `matchIfMissing=true` — 환경변수 누락 시 KMS off 기본값 → 평문 키 |
| F5.2 | `VaultKmsClient.java:198-215` | Vault 토큰 획득 실패해도 startup 차단 안 함 |
| F5.3 | `VaultKmsClient.java:190-192` | Vault 토큰 자동 갱신 미구현 |
| F5.4 | `AuditLogPublisher.java` | `actor_id`에 `qimUserId` 평문 적재 |
| F4.1 | `HandoffCryptoService.java:161-170` | `verify()` 메서드가 dead code (호출되지 않음) |
| F4.2 | `TicketRepositoryImpl#consume` | GET→SET 사이 race condition |
| F4.3 | `WebhookDispatcherService.java:67-68` | 기본 서명 시크릿 `poc-webhook-secret-change-in-production` 하드코딩 |
| F4.4 | `CrossAgencySsoController.java:245-251` | CAST JWT를 URL query에 노출 → referer/history/log 유출 |
| F4.5 | `HandoffServiceImpl.java:199-207` | `verify()` 비원자적 — consume 후 payload 생성 실패 시 영구 차단 |
| F4.6 | `PolicyEngineImpl.java:199-213` | Q-IM 예외를 no-mapping으로 마스킹 → 대량 GUEST 오분류 |

(전체 결함 목록은 `07_risk_matrix_roadmap.md` §2 참조)

---

## 분석 원칙

1. **본질 우선**: 운영 가시성/모니터링은 이미 충분 — 기능 자체에 집중
2. **코드 레벨 검증**: 문서나 가정이 아니라 실제 소스 코드와 설정 파일을 읽고 판단
3. **시나리오 추적**: "한 명의 사용자가 로그인 → 식별 → 핸드오프 → 기관 진입"의 전체 흐름을 코드로 따라간다
4. **거짓 안심 검증**: "이 기능은 구현되어 있다"가 아니라 "이 코드가 실패 케이스를 처리하는가"
5. **운영 배포 직전 관점**: "지금 prod에 띄우면 어떤 시나리오에서 실 사용자가 막힐 것인가"

---

## 변경 이력

| 일자 | 항목 |
|------|------|
| 2026-05-22 | 분석 시작. 폴더 구조 + 인덱스 문서 작성 |
| 2026-05-22 | Phase 1 완료 — 아키텍처 정찰, 8개 리스크 식별 |
| 2026-05-22 | Phase 2 완료 — 인증 플로우, 11개 발견 |
| 2026-05-22 | Phase 3 완료 — 식별·매핑, 14개 발견 + 4개 시나리오 |
| 2026-05-22 | Phase 4 완료 — 핸드오프, 19개 발견 + 5개 시나리오 |
| 2026-05-22 | Phase 5 완료 — KMS/감사/개인정보, 16개 발견 + 4개 시나리오 |
| 2026-05-22 | Phase 6 완료 — E2E 시나리오 19개 + 결함 매트릭스 |
| 2026-05-22 | Phase 7 완료 — 리스크 매트릭스, Sprint α/β/γ/δ 로드맵, 3개 결정 옵션 제시 |
| 2026-05-22 | 인덱스 최종화 — 모든 Phase 완료 표기 및 통계 요약 추가 |

---

## 다음 액션

본 분석은 **읽기 전용 문서화**이며, 코드 수정은 포함하지 않는다.
사용자 결정 대기 항목 (자세한 내용은 `07_risk_matrix_roadmap.md §6` 참조):

- **옵션 1 (점진 수정 / 안전)**: 분석 docs 커밋 + Sprint α (P0 9건, 1주) + Sprint β (1.5주) 순차 진행 → 3-4주 후 운영 진입
- **옵션 2 (선택 수정 / 빠른 진입)**: Likelihood=A 8건만 핫픽스 (1주) → 잔존 결함 보유 채로 운영 진입
- **옵션 3 (분석 추가 / 신중 진행)**: 사용자 검토 후 재우선순위화

> **PR #173 (`shipster` 브랜치) 상태**: 의도적으로 미머지 보류. SSO/IM 본질 개발이 배포 적합 상태에 도달할 때 진행.
