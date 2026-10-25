# 운영을 위한 최종 보고서 — 주의 사항 통합본

> **문서 유형**: 운영 의사결정 보고서 (Executive · Operations)
> **대상 독자**: 운영 리드 / SRE / 보안 담당 / 릴리스 매니저 / 아키텍처 오너
> **스냅샷**: 2026-06 · git HEAD `4f113e8` (Merge PR #205)
> **본 문서의 위계**: 개별 심층 분석(§03~§12)의 결과를 **주의 사항 중심**으로 재조립한 **최종 통합본**.
> **본 문서 하나만 읽어도 운영 결정이 가능**하도록 자기 완결적으로 작성됨. 근거 소스는 §참조.

---

## 목차
- 0. 30초 요약 (Top-of-page Alert)
- 1. 시스템 스냅샷 (What we shipped)
- 2. 🔴 CRITICAL 주의사항 (즉시 결정 필요)
- 3. 🟡 MEDIUM 주의사항 (30일 내 결정)
- 4. 🟢 LOW 주의사항 (분기 내 개선)
- 5. 운영 게이트 체크리스트 (배포 전 필수 확인)
- 6. 장애 시나리오·플레이북 (Failure Mode Playbook)
- 7. 환경변수 최소 필수 집합 (Missing = 서비스 실패)
- 8. 감시 지표 (필수 모니터링 항목)
- 9. 릴리스 GO/NO-GO 결정 매트릭스
- 10. 후속 조치 우선순위 (Prioritized Backlog)
- 11. 참조

---

## 0. 30초 요약 (Top-of-page Alert)

> **결론**: 현재 시점의 시스템은 **기능적으로는 배포 가능**하나, **연합 인가(q-authz) 도입에 따른 3개의 CRITICAL 리스크**가 잔존한다. 배포 전 §2 의 3개 항목에 대한 명시적 정책 결정이 없다면 운영 GO 를 권고하지 않는다.

| 사안 | 성격 | 판정 |
|---|---|---|
| ADR-008 (FE 단일 채널) 구현 완결 | ✅ 코드/문서 완결 | GO |
| q-authz L1 코어 (역할 부여) | ✅ 코드 완결 | GO |
| q-authz L2 SCIM Groups | ✅ 코드 완결 | GO |
| JIT 만료 스케줄러 | ⚠️ ShedLock 미사용 | 조건부 GO |
| **fail-open 정책** | 🔴 **정책 결정 미완** | **NO-GO 후보** |
| **q-authz 이벤트 발행 부재** | 🔴 **SoR 확산 경로 없음** | **NO-GO 후보** |
| **ADR / 정본 스펙 부재** | 🔴 **거버넌스 이탈** | **NO-GO 후보** |

---

## 1. 시스템 스냅샷 — What We Shipped

### 1.1 12개 모듈 (settings.gradle.kts)
```
platform-common · q-sign · q-im · q-authz(★신규) · ido · onepass-support
onepass-fe · agency-stub · onepass-agency-sdk · outbox-relay-batch · onepass-agent
```

### 1.2 이번 스냅샷의 결정적 변경 (PR #205, 5 commits)
| 커밋 | 변경 |
|---|---|
| `aa375ed` | **q-authz 모듈 신설** (L1 코어) — 포트 8086, PostgreSQL RLS |
| `2329200` | IdO CastToken 에 `roles[]` 클레임 주입 |
| `75289d7` | IdO `/api/ext` PEP 인가 헤더(X-Authz-\*) 재주입 + anti-spoofing |
| `7892fde` | JIT 한시 권한 만료 스케줄러 (@Scheduled 60s) |
| `8a8b355` | SCIM 2.0 Groups 프로비저닝 (L2) |

### 1.3 5+1 축 모델 (신규)
- 축 1: 회원(Q-IM) · 축 2: 인증(Q-Sign) · 축 3: 통합(IdO)
- 축 4: 외부(agency-stub/SDK/Agent) · **축 5: 인가(q-authz) ★신규**
- +1: 공통(platform-common)

---

## 2. 🔴 CRITICAL 주의사항 — 즉시 결정 필요

### 🔴 C-1. **fail-open 인가 정책** — 인가 실패 시 통과 vs 차단

**현상**
```java
// ido/src/main/java/kr/go/smes/ido/infrastructure/QAuthzClient.java
catch (Exception e) {
    log.warn("[q-authz] fail-open, empty roles. ...");
    return List.of();       // ★비어있는 역할로 통과
}
```

**의미**
- q-authz 가 다운되거나 타임아웃 → IdO 는 **역할 없음(빈 배열)** 으로 요청을 계속 처리.
- CastToken / HandoffPayload / X-Authz-Roles 헤더 모두 빈 값.
- 다운스트림 SP 가 roles 없음을 어떻게 해석하는지는 SP 자유 → **일관성 없는 인가 판단** 위험.

**결정 옵션**
| 옵션 | 설명 | 권장 |
|---|---|---|
| A. 현행 유지 (fail-open) | 가용성 최우선. SP 는 roles=[] 에 대응해야 함 | 명시적 SP 계약 필요 |
| B. fail-closed 전환 | q-authz 다운 시 인가 필요 요청은 403 | 보안 우선 시 권장 |
| C. 하이브리드 (엔드포인트별) | 관리 API 는 fail-closed, 일반 조회는 fail-open | **권장** |

**즉시 조치**
- [ ] 옵션 선택 후 `application.yml` 에 플래그화 (`ido.authz.fail-mode: open|closed|hybrid`)
- [ ] 선택 결정을 ADR-2026-006 (신규) 로 기록
- [ ] SP 온보딩 문서에 명시 (기관이 roles=[] 를 받았을 때 정책)

**근거 문서**: §03 §6.2, §06 §6.3, §06 §8-L8

---

### 🔴 C-2. **q-authz 이벤트 발행 부재** — grant/revoke 확산 불가

**현상**
- q-authz 는 역할 부여/취소/만료 시 **Kafka 이벤트를 발행하지 않는다**.
- Outbox 테이블 자체가 없음 (`V1__create_authz_schema.sql` 확인).
- 즉, 관리자가 SCIM PATCH 로 역할 회수해도 **SP 는 알 방법이 없음**.

**의미**
- 이미 발급된 CastToken/Handoff 는 TTL 이 만료될 때까지 유효.
- Handoff TTL, Cast Token TTL, Idempotency TTL 1일 등이 실질 revoke 지연이 됨.
- 긴급 접근 차단 시나리오(임직원 퇴사, 유출 사고 등)에서 **부적절한 응답 시간**.

**결정 옵션**
| 옵션 | 설명 |
|---|---|
| A. Outbox + Kafka 도입 (`authz.grant.events`, `authz.revoke.events`) | 표준 대응, 개발량 있음 |
| B. Webhook 능동 통지 | 개별 SP 계약 필요, 재전송 복잡 |
| C. 폴링 (SP 가 q-authz 조회) | 부하 증가, 지연 큼 |
| D. 강제 세션 무효화 (IdO 세션 스토어 스캔) | 임시 대응 가능 |

**즉시 조치**
- [ ] 옵션 A 채택 시 `outbox-relay-batch/job/authz/` 잡 신규 개발
- [ ] `platform-common` 에 `AuthzGrantEvent` / `AuthzRevokeEvent` 스키마 추가
- [ ] `E-AUTHZ-*` 에러코드 접두 정의
- [ ] SP 온보딩에 revoke 도달 지연(SLA) 명시

**근거 문서**: §06 §8-L3/L4, §10 A.4-L1/L2, §10 B.5-L1

---

### 🔴 C-3. **거버넌스 이탈** — ADR·정본 스펙 없이 코드 착지

**현상**
- q-authz 는 **ADR 없이** 코드가 먼저 착지한 유일 사례.
- 정본 스펙 `docs/internal/spec/03g-module-qauthz.md` **부재**.
- 데이터플로 `docs/internal/dataflow/07-authz-flow.md` **부재**.
- 정본 `02-architecture.md` 에 축 5 반영 없음.
- 정본 `03a-module-platform-common.md` 에 `HandoffPayload.roles[]` 반영 없음.

**의미**
- 신규 온보딩 개발자·기관 개발자는 **소스 코드를 직접 읽어야만** 시스템 이해 가능.
- 스펙-코드 괴리가 계속 벌어지면 조직 지식이 소스에만 존재 → 이직·인계 리스크.
- 헌장(ADR-008) 등 헌법적 문서가 있는 조직 문화와 부조화.

**결정 옵션**
- 즉시 문서화 (권장, 1주 이내)
- 순차 문서화 (분기 내)
- 문서화 생략 (**절대 비권장** — 이 옵션은 ADR-008 정신에 위배)

**즉시 조치**
- [ ] ADR-2026-006 (연합 인가 SoR 도입) 작성 — 결정 근거·트레이드오프 기록
- [ ] `03g-module-qauthz.md` 정본 스펙 승격 (본 분석 §06 을 원본으로 활용)
- [ ] `07-authz-flow.md` 데이터플로 정본 작성 (본 분석 §12 §+1 활용)
- [ ] `02-architecture.md` 축 5 갱신
- [ ] `03a-module-platform-common.md` `HandoffPayload.roles[]` 반영
- [ ] `09-gap-and-roadmap.md` 에 q-authz 로드맵 편입

**근거 문서**: §00_INDEX 관찰 A/D, §01 §4.1, §02 §4

---

## 3. 🟡 MEDIUM 주의사항 — 30일 내 결정

### 🟡 M-1. **JIT 만료 스케줄러 ShedLock 미사용**
- `AuthzExpiryScheduler` 는 `@Scheduled fixedDelay 60s` 로만 동작.
- 다중 인스턴스 배포 시 동시 실행 → **감사 로그 이중 삽입** 가능.
- 결과 자체는 idempotent (WHERE 조건이 이중 배제) 하나 감사 무결성 저해.
- **조치**: `outbox-relay-batch` 와 동일한 ShedLock 라이브러리 도입.
- **근거**: §06 §3.2, §06 §8-L5

### 🟡 M-2. **RLS 세션 컨텍스트 `SET LOCAL` 위치 미확인**
- q-authz 는 PostgreSQL RLS 정책 3개를 사용. `current_setting('authz.agency_code')` 미설정 시 **빈 결과** 반환.
- SET LOCAL 을 어디서 하는지 소스 스캔 필요 (예상: JPA 인터셉터 또는 서비스 계층).
- **조치**: 코드 위치 확인 + 통합 테스트로 RLS 무결성 검증.
- **근거**: §06 §2.2, §06 §8-L6

### 🟡 M-3. **Idempotency-Key TTL 1일**
- Handoff 발급 재요청 방지 TTL 이 1일. 리플레이 방지 강도는 검토 여지.
- 짧으면 재시도 오탐, 길면 스토리지 압박.
- **조치**: 현재 TTL 근거 문서화 + 지표(idempotency hit ratio) 관찰.
- **근거**: §03 §9-L7

### 🟡 M-4. **정본 스펙 커밋 시점 파편화**
- `03a~e` = commit 46b1fe9, `03c-charter` = a564f36, `03f` = 50954de, main = 4f113e8.
- 58 commits 이상 격차. 사이 변경(V19, Handoff roles) 미반영.
- **조치**: 정본 갱신 PR 시리즈로 격차 소진.
- **근거**: §00_INDEX 관찰 D

### 🟡 M-5. **ADR 위치 파편화** — `docs/internal/architecture/` vs `wiki/adr/`
- 두 트리에 ADR 이 병존. ADR-001~003, 008 은 `wiki/adr/`, ADR-2026-\* 는 `docs/internal/architecture/`.
- **조치**: 한쪽 트리로 정본 이관 + INDEX 문서 갱신.
- **근거**: §01 §4.2

### 🟡 M-6. **ExtProxy `SPOOFABLE_AUTHZ_HEADERS` 확장 안전성**
- 현재 3개(X-Authz-User/Roles/Scope) 만 제거. 향후 X-Authz-Tenant 등 추가 시 누락 위험.
- **조치**: 헤더 화이트리스트 방식으로 전환(정의된 헤더만 통과) 검토.
- **근거**: §03 §6.3, §03 §9-L6

### 🟡 M-7. **onepass-fe Phase 3 미완**
- Phase 2(rename) 완료. **Phase 3(CSP 강화·CORS 축소·감사 로그)** 대기.
- **조치**: 09-gap-and-roadmap 의 Phase 3 스프린트 편성.
- **근거**: §08 §8, §17 예정 문서

### 🟡 M-8. **Q-IM 헌장 §7 인접 모듈 계약 미갱신**
- Q-IM 헌장은 q-authz 편입 전 문서. `q-authz 와 직접 통신 금지` 조항 미명시.
- **조치**: 헌장 §7 갱신 (Q-IM ↔ q-authz = 없음, IdO 경유만).
- **근거**: §04 §4.3, §04 §8-L6

### 🟡 M-9. **Keycloak SPOF**
- Q-Sign 이 Keycloak Brokering 에 의존. Keycloak 다운 = 인증 전면 불가.
- **조치**: Keycloak HA 구성 검증, 회로 차단기 확인, 재해 훈련.
- **근거**: §05 §10-L1

### 🟡 M-10. **agency-stub 운영 노출 금지 자동화**
- 시뮬레이터 특성상 운영에 노출 절대 금지.
- **조치**: Helm/K8s 프로필에 명시적 exclude, deploy pipeline gate.
- **근거**: §07 §7-L1

### 🟡 M-11. **Agent 시작 실패 시 WAS 기동 여부**
- `-javaagent` 로드 실패 시 WAS 기동 정책이 fail-open 인지 fail-closed 인지 미상.
- **조치**: 정책 결정 후 Agent 문서에 명시.
- **근거**: §11 관찰 리스크 L3

### 🟡 M-12. **SDK 응답에 roles[] 계약**
- SDK 응답 모델에 `roles[]` 노출 여부 미정. 기관 개발자가 이를 활용 가능한지 계약 문서 필요.
- **조치**: `HandoffVerifyResponse.roles()` API 정본화 + 예제.
- **근거**: §11 관찰 리스크 L4

### 🟡 M-13. **테스트 커버리지 — 특히 q-authz**
- q-authz 는 신설이며 테스트가 얕음 (실측 필요).
- **조치**: q-authz 통합 테스트(Testcontainers Postgres + RLS 검증) 추가.
- **근거**: §00_INDEX 관찰 E, §06 §8-L9

### 🟡 M-14. **onepass-support 게시판 재설계 대기**
- PR #198 계획서만 존재, 구현 대기.
- **조치**: 스프린트 편성 또는 스코프 축소 결정.
- **근거**: §09 §6-L1

### 🟡 M-15. **CI 워크플로 브랜치 커버리지 재검토**
- 현재: `main / develop / genspark_ai_developer / shipster`.
- `shipster` 가 실질 개발 브랜치인데 `develop` 도 함께 있음 — 정본화 필요.
- **조치**: `develop` 폐지 또는 역할 명시.
- **근거**: §01 §6

### 🟡 M-16. **Testcontainers `DOCKER_UNAVAILABLE=true` 우회 남용 감시**
- CI 에서 Docker 없어 `DOCKER_UNAVAILABLE=true` 로 통합 테스트 자동 스킵.
- 로컬은 무시하지만 CI 게이트가 약해질 위험.
- **조치**: 별도 통합 테스트 워크플로(nightly) 강제 실행.
- **근거**: §01 §6

---

## 4. 🟢 LOW 주의사항 — 분기 내 개선

| # | 항목 | 근거 |
|---|---|---|
| L-1 | SCIM PATCH 필터 파싱을 표준 파서로 교체 | §06 §3.3 관찰 |
| L-2 | Springdoc OpenAPI 운영 배포 시 차단 | §06 §8-L10 |
| L-3 | Feature Flag 18개 상태 대시보드 | FEATURE_FLAGS.md |
| L-4 | 최상위 대용량 HTML(login-flow.html 152KB 등) 정리 | §01 §2 |
| L-5 | 번들 사이즈 예산 회귀 감시 | §08 §9-L2 |
| L-6 | Outbox 실패 알림 채널(Slack) 운영 계약 | §10 B.5-L3 |
| L-7 | JEUS 위빙 회귀 자동화 | §11 L1 |
| L-8 | SDK 버전 호환 매트릭스 정본화 | §11 L2 |
| L-9 | ADR-2026-005(outbox-scheduler 독립화) 결정 | §02 §4 |
| L-10 | onepass-support 첨부 파일 스토리지 정책 | §09 관찰 L3 |

---

## 5. 운영 게이트 체크리스트 (배포 전 필수 확인)

배포 승인자는 아래 목록을 **전량 확인** 후 GO 판정할 것.

### 5.1 CRITICAL 게이트 (하나라도 미확인 시 NO-GO)
- [ ] **C-1** fail-open/closed/hybrid 중 하나로 정책 확정, `ido.authz.fail-mode` 플래그 설정 확인
- [ ] **C-2** 임원 결재: q-authz 이벤트 발행 부재 상태에서의 revoke SLA 를 사업 리스크로 수용
- [ ] **C-3** ADR-2026-006 초안 작성 (문서화 자체는 배포와 병행 허용, 승인은 요망)

### 5.2 환경변수 게이트 (§7 참조)
- [ ] `AUTHZ_INTERNAL_API_KEY` 운영 값 설정 및 IdO/q-authz 양측 동기 확인
- [ ] `AUTHZ_DB_PASSWORD` KMS/Secret Manager 를 통해서만 주입
- [ ] `ONEPASS_HMAC_SECRET` (SDK/Agent) 회전 절차 확인

### 5.3 DB 게이트
- [ ] Flyway 마이그레이션 V1~V19 (IdO), V1~V7 (qim), V1~V5 (qsign), **V1 (authz)** 모두 성공
- [ ] q-authz RLS 정책 3개 활성화 확인 (`\d authz.authz_role` 등)
- [ ] MariaDB `system_time_zone` 검증 (V7 회귀 방지)

### 5.4 네트워크 게이트
- [ ] q-authz (8086) 는 내부 네트워크에서만 접근 가능하도록 NetworkPolicy 확인
- [ ] agency-stub (8084) 는 운영에 배포되지 않음을 확인
- [ ] onepass-fe 는 IdO 이외 백엔드로 직접 라우팅 없음을 확인 (setupProxy.js / Nginx conf)

### 5.5 프로세스 게이트
- [ ] 브랜치 원칙 준수 확인 (`shipster` 개발 · `main` 릴리스)
- [ ] CI 그린 확인 (`ci.yml` + `nogo-full.yml`)
- [ ] Keycloak 백업·재해 훈련 기록 확인

---

## 6. 장애 시나리오·플레이북 (Failure Mode Playbook)

### F-1. q-authz 다운
- **증상**: `X-Authz-Roles` 헤더 비어있음, CastToken 의 roles 클레임 비어있음.
- **현재 정책**: fail-open → 요청 계속.
- **감지**: `[q-authz] fail-open, empty roles` WARN 로그 급증.
- **대응**:
  1. q-authz Pod 상태 확인 (K8s `kubectl get pods -l app=q-authz`)
  2. DB 접속 확인 (Postgres 5432)
  3. `X-Internal-Api-Key` 설정 확인 (401 인 경우)
  4. 임시 우회: IdO 를 fail-closed 로 전환 (권장하지 않음, 사용자 영향)

### F-2. Keycloak 다운
- **증상**: 로그인 전면 실패 (모든 5개 로그인 유형).
- **대응**:
  1. Keycloak HA 페일오버 확인
  2. Q-Sign 회로 차단기 상태 확인 (F-06/F-07)
  3. 유지보수 페이지 게시 (onepass-fe 정적 페이지)

### F-3. Kafka 다운
- **증상**: Outbox 릴레이 실패, 감사 로그 미발행.
- **대응**:
  1. Outbox 테이블 백로그 크기 확인 (`SELECT COUNT(*) FROM *_outbox WHERE published=false`)
  2. Kafka 복구 후 자동 재시도 확인 (`next_retry_at` 증가 → 시간 지나면 자동 처리)
  3. DLQ 6-필드 표준 재처리 (spec 06)

### F-4. Postgres 다운
- **증상**: IdO, Q-Sign, q-authz, agency-stub, onepass-support 전면 오류.
- **대응**:
  1. RDS/컨테이너 상태 확인
  2. 커넥션 풀 회복 확인
  3. 마이그레이션 재실행 필요 없음 (V 버전은 성공한 상태)

### F-5. MariaDB(qim) 다운
- **증상**: 회원 조회/전환/탈퇴 실패.
- **대응**:
  1. MariaDB TZ 정합 확인 (KST 문제 시 V7 재적용 검토)
  2. Q-IM 재기동
  3. Q-IM Outbox 백로그 확인

### F-6. onepass-fe 세션 폭발
- **증상**: fe-session-id 쿠키 무효 오류 급증.
- **대응**:
  1. Redis(6379) 확인
  2. Redisson 락 상태 확인 (F-08)
  3. Rate limit(F-01) 로 완화

### F-7. Agent 위빙 실패
- **증상**: 기관 WAS 기동 실패 (fail-closed 모드 시).
- **대응**:
  1. Agent 로그 확인 (`onepass-agent.log`)
  2. WAS 감지 실패 시 벤더 특화 위버 확인 (JEUS deep-dive)
  3. Agent 제거 후 SDK 통합으로 대체 검토

### F-8. Handoff 위조/리플레이 시도
- **증상**: HandoffCrypto.decrypt 실패, `E-IDO-*` 에러 급증.
- **대응**:
  1. IP/사용자 rate limit 상승 조정
  2. 감사 로그 kafka 발행 확인
  3. 키 로테이션 스케줄러(F-12) 강제 실행

---

## 7. 환경변수 최소 필수 집합 (Missing = 서비스 실패)

### 7.1 q-authz (신규, 반드시 설정)
| 변수 | 기본 | 결과 (미설정) |
|---|---|---|
| `AUTHZ_INTERNAL_API_KEY` | (빈 문자열) | **모든 요청 401 (fail-closed)** |
| `AUTHZ_DB_PASSWORD` | authz-pass (dev) | 운영 시 접속 실패 |

### 7.2 IdO
| 변수 | 목적 |
|---|---|
| `AUTHZ_INTERNAL_API_KEY` | q-authz 양측 동기 필수 |
| `IDO_AUTH_RL_ENABLED` (F-01) | 로그인 rate limit |
| `IDO_RATE_LIMIT_ENABLED` (F-02) | 기관 rate limit |
| `IDO_AUDIT_KAFKA_ENABLED` (F-03) | 감사 Kafka |
| `IDO_AUDIT_DB_ENABLED` (F-04) | 감사 DB |
| `IDO_REDISSON_ENABLED` (F-08) | 분산 락 (필수) |

### 7.3 공통
| 변수 | 목적 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | prod / stage / dev |
| `SPRING_DATASOURCE_*` | DB 접속 (모듈별) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka |
| `SPRING_REDIS_HOST` / `PORT` | Redis |

### 7.4 SDK/Agent 배포 시 (기관측)
| 변수 | 목적 |
|---|---|
| `ONEPASS_IDO_URL` | IdO 엔드포인트 |
| `ONEPASS_AGENCY_CODE` | 기관 코드 |
| `ONEPASS_API_KEY` | 기관 인증 |
| `ONEPASS_HMAC_SECRET` | 서명 |

---

## 8. 감시 지표 (필수 모니터링 항목)

### 8.1 q-authz (신설 — 새 대시보드 필요)
| 지표 | 임계치 (권장) |
|---|---|
| `authz.grant.count` per 5min | 급증 시 알림 |
| `authz.revoke.count` per 5min | 급증 시 알림 (사고 신호) |
| `authz.expiry.batch.processed` | 0 지속 시 스케줄러 확인 |
| `authz.effective_roles.p95_latency_ms` | 100ms 이하 유지 |
| `authz.effective_roles.error_rate` | 1% 이상 → C-1 정책 재점검 |
| `authz.internal_api_key.401_rate` | 0 이 아니면 키 동기 문제 |

### 8.2 IdO (기존 + 신규)
| 지표 | 신규 여부 |
|---|---|
| `ido.qauthz_client.fail_open_count` | **신규 (C-1 감시)** |
| `ido.ext_proxy.spoof_removed_count` | **신규 (anti-spoofing 감시)** |
| `ido.handoff.issued_with_roles_count` | **신규 (roles 주입 성공)** |
| `ido.handoff.idempotency.hit_ratio` | 기존 |
| `ido.session.active_count` | 기존 |

### 8.3 Outbox
| 지표 |
|---|
| `outbox.backlog.size` (per module) |
| `outbox.publish.p95_lag_ms` |
| `outbox.dlq.count` |

---

## 9. 릴리스 GO/NO-GO 결정 매트릭스

| 항목 | 상태 | 판정 |
|---|---|---|
| q-authz L1/L2 코드 | ✅ 완결 | GO |
| IdO 3중 주입점 코드 | ✅ 완결 | GO |
| ADR-008 FE 단일 채널 | ✅ 완결 | GO |
| 만료 스케줄러 (ShedLock 없음) | ⚠️ 부분 | **조건부 GO** (M-1 로 추적) |
| **fail-open 정책 결정** | ❌ 미완 | **NO-GO** 후보 |
| **q-authz 이벤트 발행** | ❌ 미완 | **NO-GO** 후보 |
| **ADR/정본 스펙 부재** | ❌ 미완 | **NO-GO** 후보 |
| 정본 스펙 시점 파편화 | ⚠️ 부분 | 조건부 GO |
| onepass-fe Phase 3 | ⚠️ 대기 | 조건부 GO |
| 테스트 커버리지 (q-authz) | ❌ 얕음 | **NO-GO** 후보 |
| 운영 감시 지표 (신규) | ❌ 미구성 | **NO-GO** 후보 |

**최종 판정 원칙**:
- CRITICAL 3개 중 하나라도 미해결 → **NO-GO**
- 감시 지표(§8) 미구성 → **NO-GO** (사고 시 감지 불가)
- 위 조건 충족 후에만 **GO**

---

## 10. 후속 조치 우선순위 (Prioritized Backlog)

### P0 — 이번 릴리스 이전 완료
1. [P0-1] **C-1**: fail-open/closed/hybrid 정책 결정 + 플래그화
2. [P0-2] **C-3**: ADR-2026-006 초안 작성 (승인 병행)
3. [P0-3] **§8**: q-authz + IdO 신규 감시 지표 대시보드 구성
4. [P0-4] q-authz 통합 테스트 최소 3건 (grant 멱등, revoke, expiry)

### P1 — 이번 릴리스 후 2주 이내
5. [P1-1] **C-2**: q-authz Outbox + Kafka 이벤트 스키마 초안
6. [P1-2] **M-1**: 만료 스케줄러 ShedLock 도입
7. [P1-3] **M-2**: RLS SET LOCAL 위치 확인 + 회귀 테스트
8. [P1-4] **M-8**: Q-IM 헌장 §7 갱신 (q-authz 조항 추가)
9. [P1-5] **§5.4** NetworkPolicy 검증 자동화

### P2 — 30일 이내
10. [P2-1] **C-3 후속**: 03g/07-authz-flow/02 축5/03a HandoffPayload 정본 갱신
11. [P2-2] **M-3**: Idempotency-Key TTL 근거 문서화
12. [P2-3] **M-5**: ADR 트리 통합
13. [P2-4] **M-6**: X-Authz 헤더 화이트리스트 방식 전환 검토
14. [P2-5] **M-7**: onepass-fe Phase 3 (CSP/CORS/감사) 스프린트

### P3 — 분기 내
15. LOW 항목 L-1 ~ L-10

---

## 11. 참조

### 심층 근거 문서
- `00_INDEX.md` — 색인 + 5가지 executive observations
- `01_repository_landscape.md` — 저장소 지형도, 12 모듈 인벤토리
- `02_architecture_deep_dive.md` — 5+1 축, ADR 계보, 3중 주입점
- `03_module_ido.md` — 27 서브패키지, 19 컨트롤러
- `04_module_qim.md` — 헌장 §6.5, §7, §8
- `05_module_qsign.md` — OIDC 브로커, AAL
- `06_module_qauthz.md` — ★신규 정본급 (본 보고서의 CRITICAL 원천)
- `07_module_agency_stub.md`
- `08_module_onepass_fe.md` — ADR-008, IDO_* rename
- `09_module_onepass_support.md`
- `10_module_common_and_outbox_relay.md`
- `11_module_onepass_agent_and_sdk.md`
- `12_data_flows_verified.md` — 6+1 흐름 (신규 인가 흐름 포함)

### 정본 문서 (미갱신 상태)
- `docs/internal/spec/03a~f`, `05`, `06`, `07`, `08`, `09`
- `docs/internal/architecture/` (ADR-2026-004, 005, EDA-2026-001, FEATURE_FLAGS)
- `docs/internal/dataflow/01~06`
- `CLAUDE.md` (v0.8.11 브랜치 규칙)

### 관련 PR 계보
- **#205**: q-authz + IdO 통합 (본 스냅샷 결정적 변경)
- **#204**: SEC-IDO-07 cross-ref 정합화
- **#203**: FE BE_* → IDO_* rename
- **#202**: ADR-008 신설 + 03f 데이터 흐름 정본
- **#201**: Q-IM §6.5 헌장 UI 금지
- **#200**: Q-IM 헌장 신설

---

## 마무리 · 운영자 최소 행동 지침

1. **오늘**: §2 CRITICAL 3건에 대해 담당자 지정 + 결정 일정 확정
2. **이번 주**: §5 게이트 체크리스트를 릴리스 프로세스에 편입, §8 감시 대시보드 스켈레톤 착수
3. **이번 스프린트**: §10 P0 항목 4건 완료 → **GO 판정 가능 상태 도달**
4. **다음 스프린트**: P1 항목 5건 → 신규 이벤트 파이프라인 안착
5. **다음 달**: P2 항목으로 스펙-코드 괴리 소진

> 이 보고서는 “**주의 사항**”만을 응축한 최종본이다. 세부 근거는 반드시 함께 첨부된 §00~§12 심층 문서를 참조할 것.
