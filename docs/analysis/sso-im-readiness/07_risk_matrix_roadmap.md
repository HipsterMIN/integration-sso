# Phase 7 — Risk Matrix + Remediation Roadmap

**분석 일자**: 2026-05-22
**분석 범위**: Phase 1~6 의 모든 결함을 통합 risk matrix 로 정리 + 운영 진입 전 필수 작업 roadmap
**기준 브랜치**: `shipster` @ `90956a7` (PR #173 OPEN, NOT merged)

---

## 0. 분석 결과 요약

### 발견된 결함 총계 (Phase 2~5)

| Phase | Critical | High | Medium | Low | 합계 |
|-------|----------|------|--------|-----|------|
| Phase 2 (Q-Sign Auth Flow) | 3 | 4 | 3 | 1 | 11 |
| Phase 3 (Q-IM Identity)    | 4 | 5 | 4 | 1 | 14 |
| Phase 4 (IdO Handoff)      | 6 | 6 | 5 | 2 | 19 |
| Phase 5 (KMS/Privacy/Audit)| 4 | 6 | 5 | 1 | 16 |
| **합계** | **17** | **21** | **17** | **5** | **60** |

### E2E 시나리오 (Phase 6)
- 정상: 4개
- Degraded: 6개 (사용자 차단 1~수백명)
- Catastrophic: 5개 (전체 사용자 영향 가능)
- Security: 4개

### 운영 진입 시 즉시 발현 가능성이 높은 시나리오: **5개** (S-C1, S-D6, S-D1, S-D2, S-C5)

---

## 1. Risk Matrix

### 1.1 평가 차원

**Likelihood (발생 확률)** :
- **A** (Almost Certain) : 운영 진입 시 즉시 발현 (>90%)
- **B** (Likely)         : 일상 운영 중 발현 (30~90%)
- **C** (Possible)       : 특정 조건에서 발현 (10~30%)
- **D** (Unlikely)       : 다중 전제 조건 충족 시만 (<10%)

**Impact (영향도)** :
- **5** (Catastrophic) : 전체 사용자 차단 또는 데이터 유실
- **4** (Major)        : 1,000+ 명 차단 또는 보안 침해
- **3** (Moderate)     : 100~1,000명 차단
- **2** (Minor)        : 1~100명 차단 또는 컴플라이언스 위반
- **1** (Negligible)   : 운영 효율 저하

**Risk Score** = Impact × Likelihood weight (A=4, B=3, C=2, D=1) → 1~20

---

### 1.2 Critical 결함 — Risk Matrix (Top 20)

| 순위 | ID | 결함 | Phase | Likelihood | Impact | Score | 즉시 차단 |
|------|----|------|-------|-----------|--------|-------|----------|
| 1 | F3.1 | SHA-256 hex vs Base64URL 인코딩 불일치 → 회원 조회 영구 실패 | 3 | A | 5 | **20** | ✗ |
| 2 | F3.3 | CI AES key default `AAA...=` → 기존 사용자 전체 차단 | 3 | A | 5 | **20** | ✗ |
| 3 | F5.1 | LocalKmsClient `matchIfMissing=true` → 환경변수 누락 시 평문 키 운영 | 5 | A | 5 | **20** | ✗ |
| 4 | F4.3 | Webhook signing secret default 평문 하드코딩 | 4 | A | 4 | **16** | ✗ |
| 5 | F4.1 | `HandoffCryptoService.verify()` dead code → 서명 검증 미실행 | 4 | C | 5 | **10** | ✗ |
| 6 | F4.6 | Q-IM 일시 장애를 미매핑으로 마스킹 → 대규모 GUEST 오분류 | 4 | A | 4 | **16** | ✗ |
| 7 | F4.5 | Verify 비-원자 다단계 → 1회 Q-IM 지연으로 ticket 영구 consumed | 4 | B | 3 | **9** | ✗ |
| 8 | F4.4 | CAST JWT URL 쿼리스트링 leak (referer/history/log) | 4 | B | 4 | **12** | ✗ |
| 9 | F4.2 | TicketRepositoryImpl.consume() non-atomic CAS → ticket 이중 소비 | 4 | C | 4 | **8** | ✗ |
| 10 | F2.1 | Keycloak callback default secret 하드코딩 | 2 | C | 5 | **10** | ✗ |
| 11 | F4.8 | `/api/v1/fe-session` 인증 부재 → 임의 사용자 가장 가능 | 4 | C | 5 | **10** | ✗ |
| 12 | F3.2 | `findByIdentifierHash` Optional 의미 모호 (V4 마이그레이션 잔재) | 3 | A | 3 | **12** | ✗ |
| 13 | F3.4 | DI Secret default `default-di-secret-change-in-production` | 3 | C | 4 | **8** | ✗ |
| 14 | F5.2 | Vault 토큰 미획득 시 startup 차단 부재 | 5 | C | 4 | **8** | ✗ |
| 15 | F5.3 | Vault 토큰 자동 갱신 부재 → TTL 만료 시 동시 차단 | 5 | B | 3 | **9** | ✗ |
| 16 | F5.4 | Audit log actor_id 에 qimUserId 평문 → 컴플라이언스 회색 영역 | 5 | A | 2 | **8** | ⚠️ |
| 17 | F2.x | Q-Sign 추가 Critical (Phase 2 참조) | 2 | varies | varies | — | — |

### 1.3 색상 코딩 (Risk Heatmap)

```
            Impact →
         1    2    3    4    5
   A   [Low][Med][Hi ][Hi ][CRI]  ← 즉시 발현
   B   [Low][Low][Med][Hi ][Hi ]
   C   [Low][Low][Low][Med][Hi ]
   D   [Low][Low][Low][Low][Med]
```

**Critical zone (Score ≥ 12)** : 16개 결함 (위 표의 1, 2, 3, 4, 6, 8, 12)
**운영 진입 차단 합당 사유**: ✓ (Critical zone 16개 결함 중 8개가 Likelihood=A)

---

## 2. 운영 진입 차단 결정 근거

### 2.1 SHOULD NOT MERGE PR #173 (현재 상태)

**근거**:
1. **즉시 발현 가능 시나리오 5개** 모두 P0 (Critical) 결함과 직결.
2. **17개 Critical** 중 8개가 Likelihood=A — **운영 진입 직후 24시간 내 incident 발생 거의 확실**.
3. PR #173 자체는 모니터링 정리 (16→8 알람)로 정상 작업이나, 이를 머지하면 "현재 SSO/IM 가 안정적이다"는 잘못된 신호로 해석될 위험.
4. 사용자 요청 (verbatim): "운영부분은 SSO/IM 본질적인 개발이 배포에 적합할 때에 진행하기로 하고. 기본 기능에 충실하자."

**머지 조건**:
- 아래 **P0 17개 결함** 중 최소 **Likelihood=A 인 8개** 모두 수정 + 통합 테스트 통과 후.
- 또는 사용자가 명시적으로 "PR #173 만 먼저 머지" 결정 시.

---

## 3. Remediation Roadmap

### 3.1 Sprint Layout (제안)

#### **Sprint α (1주, 운영 진입 전 필수)**
**목표**: 운영 진입 즉시 발현 가능한 결함 차단

| Task | 결함 | 소요 (estimate) | 책임 모듈 | 상태 | PR |
|------|------|----------------|----------|------|-----|
| α-1 | F3.1 SHA-256 인코딩 통일 (hex 또는 Base64URL 중 택일, 전체 일관) | 1d | Q-IM | ⏳ 대기 | — |
| α-2 | F3.3 CiCryptoServiceImpl default key 제거 + startup validation | 0.5d | Q-IM | ⏳ 대기 | — |
| α-3 | F3.4 DiGenerationService default secret 제거 + startup validation | 0.5d | Q-IM | ⏳ 대기 | — |
| **α-4** | **F5.1 LocalKmsClient `matchIfMissing=false` + 운영 프로파일 fail-fast** | 0.5d | IdO | ✅ **완료** | **#TBD** |
| **α-4b** | **F5.2 VaultKmsClient 토큰 미획득 시 startup 차단 (β-6에서 조기 진행)** | 0.5d | IdO | ✅ **완료** | **#TBD** |
| **α-5** | **F4.3 Webhook default secret 제거 + @PostConstruct 부팅 검증 + escape hatch** | 2d | IdO | ✅ **완료** | **shipster (Sprint α-3)** |
| **α-6** | **F4.6 PolicyEngine tryResolveDi 예외 타입 구분 (장애 vs 미매핑) + QimClient.getDi() 동반 수정** | 1d | IdO | ✅ **완료** | **shipster (Sprint α-3)** |
| α-7 | F2.1 Keycloak callback default secret 제거 | 0.5d | Q-Sign | ⏳ 대기 | — |
| α-8 | F3.2 findByIdentifierHash 명확화 (provider 별 조회로 변경) | 1d | Q-IM | ⏳ 대기 | — |
| α-9 | 환경변수 startup validation 통합 (모든 default 금지) | 1d | 전체 | ⏳ 대기 | — |
| α-10 | α-1~α-9 통합 테스트 (Testcontainers) + smoke test | 2d | QA | ⏳ 대기 | — |

**소요**: ~10 man-day. 2명 1주 가능.

**진행 노트** (Sprint α-1 — KMS 안전망, 2026-05-22):
- F5.1, F5.2 우선 처리. KMS는 다른 결함의 의존성 (β-6 → β-7)이며, 평문 키 모드가 prod 진입 절대 차단 사항이므로 가장 먼저 격리.
- 회귀 테스트 추가: `LocalKmsClientTest$ProdGuard` 8건 + `VaultKmsClientTest$StartupGuard` 4건.
- 호환성: 기존 dev/local/test 환경은 `IDO_KMS_ENABLED:false` 기본값으로 동일하게 작동.

---

#### **Sprint β (2주, 운영 진입 직전)**
**목표**: Handoff 보안 핵심 결함 + KMS 안정화

| Task | 결함 | 소요 | 책임 모듈 | 상태 | PR |
|------|------|------|----------|------|-----|
| **β-1** | **F4.1 HandoffServiceImpl.verify() 에 handoffCryptoService.verify() 호출 추가** | 0.5d | IdO | ✅ **완료** | **#177 (shipster)** |
| **β-2** | **F4.2 TicketRepositoryImpl.consume() Lua atomic CAS 구현** | 2d | IdO | ✅ **완료** | **#177 (shipster)** |
| **β-3** | **F4.5 verify 순서 변경 (payload 먼저, consume 나중) + 통합 테스트** | 1.5d | IdO | ✅ **완료 (단위)** / ⏳ Testcontainers 통합테스트 잔여 | **#177 (shipster)** |
| **β-4** | **F4.4 CAST JWT URL leak 제거 (POST 자동 제출 폼 + HTML escape)** | 2d | IdO | ✅ **완료** | **shipster (Sprint α-3)** |
| β-5 | F4.8 FeSessionController 인증 강화 + NetworkPolicy | 1d | IdO | ⏳ 대기 | — |
| β-6 | F5.2 Vault 토큰 미획득 시 startup 차단 | 0.5d | IdO | ✅ 완료 (Sprint α-1 으로 조기 진행) | #176 (merged) |
| β-7 | F5.3 Vault 토큰 백그라운드 갱신 (renew-self) | 2d | IdO | ⏳ 대기 | — |
| β-8 | F5.4 Audit log qimUserId hash 화 | 1d | IdO | ⏳ 대기 | — |
| β-9 | β-1~β-8 통합 테스트 + 보안 침투 테스트 (Redis 변조, race condition) | 3d | QA | ⏳ 대기 (β-3 Testcontainers 포함) | — |

**소요**: ~14 man-day. 2명 1.5주 + QA 0.5주.

**진행 노트** (Sprint α-2 — Handoff 무결성, 2026-05-22):
- β-1 (F4.1), β-2 (F4.2), β-3 (F4.5) 를 한 묶음으로 처리. 세 결함은 verify 경로에서 연쇄적으로 작용하므로 분리 처리 시 회귀 위험.
- 신규 에러 코드 `IDO_TICKET_SIGNATURE_INVALID` (E-IDO-108, HTTP 401) 추가.
- 신규 Kafka 이벤트 타입 `SIGNATURE_INVALID` 추가 (`ido.handoff.events` 토픽, SIEM 연계용).
- 회귀 테스트 15건 신규 + 1건 보정 (`HandoffServiceImplTest$SignatureVerification` 3 + `HandoffServiceImplTest$VerifyOrdering` 3 + `TicketRepositoryImplTest$CasBranchHandling` 9 + 기존 verify 정상경로 mock 보정).
- 호환성: 정상 issue→verify 경로는 응답 동일. 변조 시도 / Q-IM 장애 / 동시 verify race 시에만 새로 차단 응답 반환.
- 잔여: 실 Redis 기반 Lua atomic 성 통합 테스트(Testcontainers) 는 별도 PR 로 진행.
- 상세 문서: `09_sprint_alpha2_handoff_integrity.md`
- 통합 PR: shipster→main `#177` 머지 (squash → `aa18aa8`).

**진행 노트** (Sprint α-3 — 경계 영역 보안 강화, 2026-05-22):
- α-5 (F4.3 Webhook default secret), α-6 (F4.6 PolicyEngine 예외 구분), β-4 (F4.4 CAST URL leak) 세 결함을 한 묶음으로 처리. 모두 "fail-safe 기본값 위반" 패턴이므로 동일 회귀 가드 전략 적용.
- **F4.3**: `@Value("${ido.webhook.signing-secret:}")` 기본값 제거 + `@PostConstruct validateSigningSecret()` 부팅 검증 + `ido.webhook.allow-empty-secret` escape hatch (테스트 전용). `application.yml` / `application-local.yml` / 테스트 yml 4개 모두 갱신.
- **F4.4**: `CastIssueResponse` 에 `ssoEntryUrl`(JWT 미포함) + `formHtml`(POST 자동 제출 HTML) 신규 필드 추가. `buildRedirectUrl()` 제거 → `buildSsoEntryUrl()` + `buildAutoSubmitForm()` + `htmlEscape()` 신규. 기존 `redirectUrl` 필드는 legacy 호환 alias로 유지(JWT 미포함).
- **F4.6**: 신규 에러 코드 추가 없이 기존 `IDO_QIM_UNREACHABLE (E-IDO-106, 503)` 재사용. `QimClientImpl.getDi()` 가 404→null / 5xx·네트워크→`PlatformException` 으로 분기. `PolicyEngineImpl.tryResolveDi()` 는 `PlatformException` 전파 + 예상 외 예외는 안전 우선 거부로 변환.
- 회귀 테스트 27건 신규 (`ValidateSigningSecretTests` 4 + 갱신된 HMAC 3 + `CrossAgencySsoControllerTest` 전체 9 + `PolicyEngineImplTest` 전체 6 + `QimClientGetDiTest` 전체 8).
- 호환성: 운영 환경은 `IDO_WEBHOOK_SIGNING_SECRET` 환경변수 주입 의무화 (CrashLoopBackOff 발생 가능 — 사전 공지 필요). 프론트엔드는 `redirectUrl` 대신 `formHtml` 사용 필요 (마이그레이션 노트 §7.2).
- SLO 영향: Q-IM 장애 시 200 OK + GUEST 응답이 503 응답으로 바뀜 → 기존엔 숨겨져 있던 장애가 가시화됨.
- 상세 문서: `10_sprint_alpha3_perimeter_hardening.md`
- 통합 PR: shipster 누적 → 신규 release PR 생성 예정.

---

#### **Sprint γ (2주, 운영 진입 + 1개월 내)**
**목표**: High 결함 정리 + 운영 모니터링 보강

| Task | 결함 | 소요 |
|------|------|------|
| γ-1 | F2.x ~ F4.x 의 High 21개 결함 일괄 처리 | 10d |
| γ-2 | F5.x 의 High 6개 결함 | 4d |
| γ-3 | 운영 모니터링 (Prometheus 신규 메트릭 + Grafana 대시보드) | 3d |
| γ-4 | 운영 RUNBOOK 작성 (incident response) | 2d |

**소요**: ~19 man-day. 3명 2주.

---

#### **Sprint δ (운영 진입 + 3개월 내)**
**목표**: Medium/Low 결함 정리 + 컴플라이언스 강화

| Task | 결함 | 소요 |
|------|------|------|
| δ-1 | Medium 17개 결함 | 8d |
| δ-2 | Low 5개 결함 (코드 품질) | 2d |
| δ-3 | 개인정보보호법 컴플라이언스 자문 (qimUserId 가명정보 처리) | 외부 |
| δ-4 | 4년 누적 audit log 의 KMS 암호화 백필 | 5d |

---

### 3.2 의존성 그래프

```
α-1 (SHA-256 통일) ─┬──> β-1 (verify 호출) ──> β-9 (통합 테스트)
                    │
α-2 (CI 키) ────────┼──> α-9 (startup validation)
α-3 (DI 키) ────────┤
α-7 (KC 키) ────────┘

α-4 (KMS fail-safe) ──> β-6 (Vault startup) ──> β-7 (토큰 갱신)
α-5 (webhook secret) ──> γ-1 (High 정리)

α-6 (Q-IM 장애 구분) ──> β-3 (verify 순서)
β-2 (Lua atomic) ─────> β-3
β-4 (CAST 토큰) ──────> γ-3 (모니터링)
β-5 (FeSession 인증) ──> γ-3
```

**Critical Path**: α-1 → β-1 → β-9 → 운영 진입 (~3.5주)

---

### 3.3 KPI / Definition of Done

#### **Sprint α DoD** (운영 진입 절대 조건)
- [ ] 모든 default secret 제거 (코드 grep 으로 검증)
- [ ] startup 시 환경변수 누락 검출 → 즉시 fail (Spring `@PostConstruct` validator)
- [ ] SHA-256 인코딩이 전체 모듈에서 통일됨 (단위 테스트 + 통합 테스트)
- [ ] Q-IM 일시 장애 시 GUEST 가 아닌 503 응답 (e2e 테스트)
- [ ] PR #173 머지 가능 (단, **Sprint β 완료 후로 보류** 권장)

#### **Sprint β DoD** (보안 핵심)
- [ ] Handoff verify 가 signature 미검증 시 100% 차단 (보안 테스트)
- [ ] Redis 직접 변조 후 verify 호출 시 SIGNATURE_INVALID 반환
- [ ] 200 동시 verify 요청 시 1건만 성공 (consume race 테스트)
- [ ] CAST JWT 가 어떤 URL/log/referer 에도 노출되지 않음 (DAST)
- [ ] Vault 토큰 만료 simulation 시 사용자 차단 0건 (chaos test)

#### **Sprint γ DoD** (운영 안정성)
- [ ] Prometheus 신규 메트릭 10개 이상 (handoff_signature_verified_total 등)
- [ ] Grafana 대시보드 4개 (SSO 흐름, KMS, Audit, Webhook)
- [ ] RUNBOOK 시나리오 별 대응 절차 작성

---

## 4. 운영 진입 의사결정 트리

```
┌───────────────────────────────────────────────┐
│ Q1. Sprint α 의 9개 P0 결함 모두 수정 완료?   │
└──────┬────────────────────────┬───────────────┘
       │ NO                     │ YES
       ▼                        ▼
   ┌────────┐         ┌──────────────────────┐
   │운영 진입│         │Q2. Sprint β P0 5개?  │
   │ 절대 NO │         └──────┬───────────────┘
   └────────┘                │
                              │ YES
                              ▼
                    ┌─────────────────────┐
                    │ Q3. Critical path 통│
                    │  합 테스트 통과?     │
                    └──────┬──────────────┘
                           │ YES
                           ▼
                    ┌─────────────────────┐
                    │ Q4. 운영 모니터링     │
                    │  메트릭 + 알람 준비? │
                    └──────┬──────────────┘
                           │ YES
                           ▼
                    ┌─────────────────────┐
                    │  운영 진입 GO        │
                    │  (Sprint γ/δ 병행)  │
                    └─────────────────────┘
```

---

## 5. PR #173 처리 권고

### 옵션 A : 즉시 머지 (NOT RECOMMENDED)
- **이유**: PR #173 자체는 모니터링 정리 (16→8 알람) → 정상 변경.
- **위험**: "shipster 가 운영 준비됨" 잘못된 신호. 다른 결정에 영향.

### 옵션 B : Sprint α 완료 후 머지 (**RECOMMENDED**)
- Sprint α (1주) 완료 시점에 PR #173 의 알람 8개를 신규 메트릭 (β/γ에서 추가될) 까지 포함하여 재정리.
- 운영 진입 직전 한 번에 PR #173 + Sprint α 변경 사항 통합 머지.

### 옵션 C : Sprint β 완료 후 머지
- 가장 안전. 단, PR #173 의 monitoring 변경이 다른 작업과 conflict 가능 (rebase 비용).

**권고**: **옵션 B** — Sprint α 결함 8개 수정 + PR #173 의 알람 정리를 함께 머지.

---

## 6. 사용자 의사결정 옵션

본 분석을 토대로 사용자가 결정할 수 있는 옵션:

### 옵션 1: **점진 수정 (안전)**
- 본 문서 (Phase 1~7) 를 commit + PR #173 와 함께 검토.
- Sprint α 부터 순차 진행.
- 운영 진입은 Sprint β 완료 후.
- **장점**: 안전, 컴플라이언스 명확.
- **단점**: 3~4주 소요.

### 옵션 2: **선택 수정 (즉시 운영 진입 시도)**
- Likelihood=A 인 8개 결함만 핫픽스 (1주 내).
- 나머지는 운영 진입 후 수정.
- **장점**: 빠른 진입.
- **단점**: F4.1 / F4.2 / F4.4 등 Critical 보안 결함이 남아있음 → 운영 중 incident 시 사후 대응.

### 옵션 3: **분석 추가 + 신중 진행**
- Phase 2~7 의 결함 중 일부를 사용자가 직접 검증 후 우선순위 재조정.
- **장점**: 사용자 시각 반영.
- **단점**: 추가 시간 소요.

---

## 7. 추가 권고사항

### 7.1 본 분석에서 누락된 영역 (후속 분석 권장)
- **K6 부하 테스트** (디렉토리 존재 확인됨) — 실제 race condition 발현 여부 측정.
- **Ingress / Service Mesh 정책** — `/api/v1/fe-session` 외부 노출 여부 (F4.8 의 전제).
- **Helm values.yaml + ConfigMap** — 운영 환경변수 누락 점검 (F5.1 의 전제).
- **agency-stub / onepass-agent-testbed** — 기관 SDK 동작 검증.
- **OutboxRelay 의 ido vs qim 비교** — Phase 1 의 R3 risk 확인.

### 7.2 코드 외 권고사항
- **컴플라이언스 자문**: qimUserId 의 audit log 평문 저장이 개인정보보호법상 안전한지 외부 자문.
- **침투 테스트**: Sprint β 완료 후 외부 보안 업체의 침투 테스트.
- **DR 시나리오 훈련**: Vault down, Redis down, Q-IM down 각각의 상황에서 운영팀 대응 절차 훈련.
- **on-call 체제**: 운영 진입 시 24/7 on-call 인력 배치 (최소 1개월).

---

## 8. 결론

### 종합 평가
- SSO/IM 본질 기능 (Q-Sign auth, Q-IM identity, IdO handoff, KMS) 의 **인프라 골격은 우수**.
- 그러나 **fail-safe 설계 부재** + **default secret** + **dead code** + **race condition** 등 운영 진입에 부적합한 결함 다수.
- **17개 Critical** 중 **8개가 즉시 발현 가능** — 운영 진입 시 사용자 incident 거의 확실.

### 결정
- **PR #173 머지 보류 유지** (사용자 디렉티브 준수).
- **Sprint α + β (총 ~3주)** 완료 후 운영 진입 재검토.
- 본 문서 (`docs/analysis/sso-im-readiness/00~07`) 를 모든 향후 SSO/IM 작업의 표준 참조 문서로 활용.

### 핵심 원칙 (사용자 verbatim):
> "완벽한 SSO/IM 서비스를 향해서 진행해보자."

본 분석은 그 목표를 향한 **첫 단계** — 현재 코드의 정확한 상태 파악.
다음 단계는 사용자가 옵션 1/2/3 중 선택하여 진행.

---

**Phase 7 분석 종료**.
**전체 SSO/IM Readiness 분석 (Phase 1-7) 완료**.
**총 결함 60개 + 시나리오 19개 + Risk Matrix + Roadmap 산출**.

다음 액션은 사용자의 옵션 선택을 기다림.
