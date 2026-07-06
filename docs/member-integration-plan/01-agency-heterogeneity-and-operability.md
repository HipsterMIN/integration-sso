# 이기종 유관기관 통합·통합회원 운영 성립성 — 심층 분석 및 실행 플랜

> **버전** v0.1 (DRAFT) · **작성일** 2026-07-06 · **기준 브랜치** `shipster`
> **선행 문서**: [`README.md`](./README.md)(회원통합 마스터 플랜) · [`../analysis/production-readiness/00-go-no-go-20260630.md`](../analysis/production-readiness/00-go-no-go-20260630.md)(GO/NO-GO)
> **다루는 질문**:
> ① *통합회원 서비스가 진짜 안정적으로 운영될 수 있는 환경이 되는가?*
> ② *플랫폼이 제각각인 유관기관들(JDK 1.5+MiPlatform 레거시 ~ 자체 SSO 보유 기관)의 니즈를 어떻게 해소하는가?*
> **방법**: 코드베이스 5축 전수 실사(연동 채널 카탈로그 / 기관 다양성 인지 증거 / onepass-agent 심층 / 회원연계 데이터 루프 / 운영 성립 조건) — 모든 판정은 file:line 근거.

---

## 0. 결론 요약 (Executive Summary)

1. **운영 성립성 — 조건부 미성립 (파일럿 가능, 전면 운영 불가).** 앱 계층(HA·관측·보안 가드)은 수준급이나, **상태 계층(Redis SPOF·DB 백업/DR 0건·Vault 토큰 갱신 미구현)** 과 **회원 데이터 루프의 반쪽 구현**(§2.3)이 전면 운영을 막는다. 최소 성립 조건 10개(§2.4)를 충족하면 성립한다 — 모두 실행 가능한 엔지니어링/운영 과제이며 연구 과제가 아니다.
2. **기관 다양성 — 절반은 이미 설계됨, 절반은 공백.** 플랫폼은 DIRECT/BRIDGE/APACHE_GATE/INTERNAL_SSO 4개 연동 패턴, 자체 SSO 대응 ADR, 고보안(L3)·폐쇄망·카오스 기관 시나리오까지 **이미 인지·구현**했다(§3.1). 그러나 ⓐ **JDK≤7 레거시(JEUS 4/5 등)는 문서상 "✅지원"과 달리 실제로는 3중 차단으로 연동 불가**, ⓑ **MiPlatform/Nexacro류 RIA 클라이언트는 저장소 전체 언급 0건**, ⓒ **비-Java(.NET/PHP) 스택 무대응**, ⓓ **68개 기관 실명 인벤토리·패턴 배정표 부재**가 확인됐다(§3.2, §4).
3. **가장 중요한 전략 전환 — "에이전트가 레거시를 해결한다"는 가정을 폐기하고, 레거시·RIA·비Java를 한꺼번에 해소하는 "기관 전면 Edge Gateway" 패턴을 신설한다**(§5.3). 기존 APACHE_GATE 전략(헤더 사전 주입)과 자연스럽게 정합하며, TLS·JDK·언어 문제를 기관 코드 밖에서 원천 해소한다.
4. **실행은 "기관 실태조사(Track 0)"가 모든 것에 선행한다.** 유형 비율(40/25/20/15%)은 추정치일 뿐, 68개 기관의 실제 스택·SSO·망·회원수·개발여력 데이터가 없다. 이 데이터 없이는 웨이브 편성도, Edge Gateway 투자 규모 산정도 불가능하다(§6 Track 0).

---

## 1. 현재 보유 자산 — 연동 채널 카탈로그 (실사 결과)

| # | 채널 | 방향 | 기관측 최소 요건 | 성숙도 (근거) |
|---|------|------|------------------|----------------|
| 1 | **Handoff 발급/검증** | 브라우저→플랫폼 / **기관 서버→플랫폼(verify)** | 아웃바운드 HTTPS + API Key(`X-Agency-Code/Key`), 콜백 엔드포인트, 로컬 세션 | **높음** — TTL 60s·1회소비 CAS·AES-GCM+HMAC (`HandoffServiceImpl.java:48,225-248`), E2E 테스트·CB 포함 레퍼런스(agency-stub) |
| 2 | **CAST 크로스기관 SSO** | 기관A 세션→기관B 진입 | `sso-entry` POST 수신부 + verify 호출 | **중간** — Ed25519·jti 1회소비 구현, 단 sso-entry URL 레지스트리 미연동 플레이스홀더·public-key 스텁 (`CrossAgencySsoController.java:229-282`) |
| 3 | **회원 전환(conversion)** | 기관→플랫폼 전환 유도 | HS256 signed_request 생성(API Key 서명), lookup API(15초 데드라인), 콜백 | **중간** — 서버 검증 5단계 구현, ido측 전용 테스트 미비 |
| 4 | **프로비저닝(회원 이벤트 push)** | 플랫폼→기관 | HTTPS 수신 1개 + 멱등 처리(auth 4종: API_KEY/HMAC/MTLS/NONE) | **구현 완료·기본 OFF** — `IDO_PROVISIONING_ENABLED=false`, `DRY_RUN=true` (`FeatureFlags.java:141-171`) |
| 5 | **Webhook(핸드오프/세션/탈퇴 통지)** | 플랫폼→기관 | HMAC 서명 검증 수신부 — **또는 수신부 없이 폴링 대체 가능** | **높음** — 서명+타임스탬프, 재시도, 기관별 이벤트 필터. 대체 pull: `GET /api/v1/agency/events` (`AgencyEventController.java:18-45`) |
| 6 | **Agency Gateway 인바운드** | 기관→플랫폼(회원 변경 이벤트) | SDK `sendInbound()` 또는 REST+HMAC+멱등키 | **반쪽** — 수신·감사는 되나 **라우팅이 log-only**, 원장 미반영 (`AgencyGatewayServiceImpl.java:248-270`) |
| 7 | **SCIM 2.0 Groups(q-authz)** | 기관→플랫폼(역할 동기화) | SCIM 클라이언트(REST) | **높음(신규)** — reconcile/patch 구현·테스트 |
| 8 | **onepass-agency-sdk** | 라이브러리 | **Java 8+**, 의존성 0 | **중간** — Handoff/게이트웨이 클라이언트, `AgencyHttpAdapter` SPI |
| 9 | **onepass-agent** | 기관 WAS 위빙 | JVM (실질 **JDK 8+**) | **§4 참조 — 재정의 필요** |

> **판단**: 표준적(모던 Java, 개방망) 기관을 위한 채널은 **이미 충분**하다. 문제는 이 채널들이 전제하는 것 — *아웃바운드 HTTPS(TLS1.2+), 서버측 REST 개발 여력, Java 8+* — 을 갖추지 못한 기관들이다.

---

## 2. Part I — 통합회원 서비스 운영 성립성 진단

### 2.1 "중앙 장애 = 전 기관 로그인 장애"인가? — **아니다 (단, 신규 원패스 로그인은 맞다)**

통합회원/SSO 중앙화의 최대 공포는 "OnePass가 죽으면 68개 기관이 다 죽는다"이다. 실사 결과:

| 시나리오 | 실제 동작 | 근거 |
|---|---|---|
| **기존 기관 세션** | **유지** — AGSID 검증은 기관 로컬 DB만 조회(idle 30분/절대 8h), OnePass 무호출 | `AgencySessionService.java:159-186` |
| 기관 **자체 로그인** | 영향 없음 — 옵션 A "완전 분리"가 기본 권장("원패스는 기관 SSO를 대체하지 않음") | `sso-agency-integration-guide.md` §2.1 |
| **신규 원패스 로그인** | 실패 — verify Retry 3회→CB OPEN→기관은 503 "잠시 후 재시도" 안내 | `IdoVerifyClient.java:73-143`, stub yml CB 설정 |
| 에이전트 구간 | fail-open — 검증 실패 시 요청 통과(가용성 우선) | `onepass-agent-architecture.md:726-748` |
| **미구현 완충** | `agency_meta.fallback_login_url` 컬럼은 존재하나 **참조 코드 0건** — "장애 시 기관 임시 로그인 리다이렉트" 계획(P4) 미착수 | `V4__add_qim_sp_receiver.sql:114-118` |

**설계 처방(전환기 필수 원칙)**: ① 기관 자체 로그인을 **최소 1년 이상 병행 유지**(이중 로그인) — 중앙 장애 시 자연 우회로. ② `fallback_login_url` 구현을 P1로 승격. ③ 기관 세션 독립성(로컬 세션 수명 동안 OnePass 무의존)을 온보딩 표준 요건으로 명문화. 이 3가지가 지켜지면 **중앙 장애의 폭발 반경은 "신규 SSO 로그인"으로 한정**된다.

### 2.2 상태 계층 공백 (전면 운영 차단 요인)

| # | 항목 | 판정 | 핵심 근거 |
|---|------|------|-----------|
| 1 | **Redis SPOF** | ❌ Missing | FE 세션·CAST jti·NICE 토큰·rate limit 전부 단일 Redis, sentinel/cluster 설정 無. Redis가 readiness에 포함 → **Redis 다운 = 전 IdO pod NotReady = 전면 중단 + 전 세션 소실**. 동시에 rate limit fail-open (`AgencyRateLimiter.java:154`) |
| 2 | **DB 백업/DR** | ❌ Missing | pg_dump/PITR/velero/재해복구 문서·스크립트 **저장소 전체 0건**. RPO/RTO 미정의 |
| 3 | **Vault 토큰 갱신(F5.3)** | ❌ Missing | 기동 시 1회 획득, renew-self 없음 → TTL 만료 시 Handoff 발급 동시 차단(S-D3 Critical) |
| 4 | **스케줄러 정합** | ❌ Missing | q-authz 만료 스케줄러 단일리더 가정 vs prod replica 3 → 3중 스캔. q-im 인프로세스 릴레이(disable 플래그 없음) + relay-batch 동시 활성 → 4중 폴링 |
| 5 | 인프라 관측 | 🟡 Partial | 앱 알람 8종은 있으나 **redis/kafka/pg exporter 미스크레이프로 인프라 알람 전부 폐기** (`alert_rules.yml:17-21`) |
| 6 | 캐파 실측 | 🟡 Partial | TPS 100/120 PASS는 **Mock 서버 대상** — 실 인프라 실측 0. 68기관×수백만 회원 초기 프로비저닝 산정 없음 |
| 7 | 기관 장애 격리 | 🟡 Partial | 기관별 rate limit는 있으나 프로비저닝/웹훅 발신은 per-agency CB 없이 수동 비활성만 |

### 2.3 회원 데이터 루프의 실제 완결성 — **현재 반쪽**

통합회원이 "운영"된다는 것은 *회원 상태 변화가 기관↔플랫폼 간에 신뢰성 있게 순환*한다는 뜻이다. 실사 결과 루프의 절반이 끊겨 있다:

```text
[플랫폼→기관]  프로비저닝: 구현됐으나 기본 OFF(2중 플래그+dry-run) ─── 🟡 스위치만 켜면 됨
               페이로드 identity_hash: 자기참조 해시 — 기관 원장 매칭 신호 가치 0 ─ ❌ 재설계
               MEMBER_WITHDRAWN: 프로비저닝 트리거 제외, "별도 처리" 경로 완결성 미확인 ─ ❌
[기관→플랫폼]  gateway inbound: 수신·감사·멱등까지 되고 라우팅이 log-only ──── ❌ 원장 미반영
[식별 연속성]  DI = HMAC(agency:qimUserId) → 계정 병합 시 DI 변경 — 통지/재설계 미결(§12) ─ ❌
[법적 근거]    기관 간 연계 동의(전환동의 vs 일괄통합동의) 미결 ────────────── ❌ 의사결정
```

> **함의**: "통합회원 운영 환경이 되는가"의 대답은 인프라(§2.2)만이 아니라 **이 루프의 완결**에 달려 있다. 특히 `identity_hash` 재설계(기관이 자기 원장과 대조할 수 있는 신호 제공 — 예: CI 해시 기반, 동의 전제)와 인바운드 라우팅 구현은 회원통합 마스터 플랜(README.md) Phase 3의 선행 조건이다.

### 2.4 안정 운영 성립을 위한 최소 조건 (10)

1. **Redis HA**(Sentinel/Cluster) + 세션 소실·rate-limit fail-open 정책 재정의
2. **DB 백업·PITR·복구 리허설 + RPO/RTO 문서** + PostgreSQL/MariaDB HA 확정
3. **Vault 토큰 자동 갱신(F5.3)** + Vault HA
4. 스케줄러 단일화(q-authz ShedLock, q-im 인프로세스 릴레이 disable 플래그 → relay-batch 일원화)
5. redis/kafka/pg **exporter 스크레이프 + 인프라 알람 부활**
6. **실 인프라 부하테스트**(60k 동시 인증, 68기관 프로비저닝, 재시도 폭주)
7. SLA 문서 제정 + 인시던트 프로세스(onepass-support 연계) 명문화
8. 기관 실데이터 온보딩(agency_meta·callback_whitelist·Secret 68건) + Key 회전 자동화
9. 프로비저닝/웹훅 per-agency circuit breaker(수동 비활성 대체)
10. `fallback_login_url` 구현 + DR 훈련 절차

---

## 3. Part II — 유관기관 세그멘테이션

### 3.1 프로젝트가 이미 인지·대응한 유형

| 유형 | 기존 전략 (구현 상태) | 근거 |
|------|----------------------|------|
| 표준 기관(SSO 없음, API 직접) | `DIRECT` + SDK(Java 8+) + Handoff — **구현·테스트 완료** | `agency_meta.integration_type`, agency-stub |
| 폐쇄망/망분리(금융·국방) | `BRIDGE` — Bridge 서버 경유 payload 사전 전달 — **전략 구현** | `BridgeHandoffStrategy.java`, V13 시드 |
| 레거시 Apache 앞단(eGovFrame) | `APACHE_GATE` — mod_auth_openidc 호환 헤더 사전 Push — **전략 구현** | `ApacheGateHandoffStrategy.java:107-116` |
| 자체 SSO 보유(OIDC/SAML/상용) | `INTERNAL_SSO` + Keycloak 브로커링(ADR-2026-004), 3패턴(CAST 브릿지/Token Exchange/Account Linking) + 의사결정 트리 — **부분 구현**(옵션 A·B만, 브로커링·SAML은 설계) | `wiki/guide/06`, ADR-2026-004 |
| 고보안 L3(금융위·국방부급) | `min_auth_level=L3` + 최소 속성(allowed_attributes) — **구현** | V13 `AGENCY_STRICT_L3` |
| 장애·악의 기관 | 카오스 시나리오 + CB/Retry — **테스트 존재** | V13 `AGENCY_CHAOS_001` |

기관 유형 분포 추정(wiki/guide/06, 68개 기관): 독립 계정 ~40% / 자체 OIDC ~25% / SAML 레거시 ~20% / 상용 SSO(드림시큐리티 등) ~15%. **단 이는 추정치이며 실측 데이터가 아니다.**

### 3.2 공백 세그먼트 (이번 실사로 확정)

| 공백 | 실사 결과 | 영향 |
|------|-----------|------|
| **G-A. JDK≤7 레거시 WAS**(JEUS 4/5/6, Tomcat 5/6, WebLogic 10.x, WebSphere 7/8 등) | 에이전트 **3중 차단**: ① 모듈이 `--release 8` 컴파일 — JDK 1.5에서 로드 즉시 `UnsupportedClassVersionError` (`build.gradle.kts:146`) ② 번들 Javassist 3.30.2도 Java 8 바이트코드 ③ **TLS 1.0 한계** — JDK≤6은 TLS1.2 불가, OnePass HTTPS 접속 자체 실패(저장소에 TLS 대응 0건). 내부 부채 D-AGENT-01로 자인됐으나 **기관용 가이드는 "✅ 지원" 허위 표기** (`onepass-agent-integration-guide.md:82-91` vs `troubleshooting.md:47-71`) | 레거시 기관 연동 불가 + **문서 신뢰 훼손 리스크** |
| **G-B. MiPlatform/Nexacro/X-Internet RIA** | 저장소 전체 언급 **0건**. 더 근본적으로 에이전트에는 **로그인 획득 경로 자체가 없음** — 리다이렉트 없이 Bearer 토큰 사후검증만 하며, 토큰 없는 요청은 **조용히 통과** (`GenericFilterWeavingStrategy.java:164-165`). RIA의 XML dataset 통신은 브라우저 리다이렉트 불가 | 국내 공공 레거시 UI 스택 다수 기관 연동 불가 |
| **G-C. 비-Java 스택**(.NET/PHP/Node) | SDK Java 전용, 에이전트 JVM 전용. Node/Python "URL 생성 샘플" 문서가 전부 | 해당 기관은 REST 명세 보고 직접 구현해야 함 |
| **G-D. 에이전트 실체 괴리** | 에이전트는 "SSO 에이전트"가 아니라 **Bearer 토큰 검증 게이트**: 세션·리다이렉트·Handoff verify·SLO 전무. 호출하는 verify 엔드포인트(경로 4종으로 분열)는 **실서버 미구현**(테스트베드 목서버만 2종 서빙). 레거시 위빙 전략은 인터페이스 정확일치 버그로 silent no-op. 테스트베드는 실행 흔적 없음(빌드 불가 Dockerfile·포트/JAR명 불일치) | "27 WAS 지원" 마케팅과 실제 간극 — 온보딩 계획을 이 위에 세우면 붕괴 |
| **G-E. SAML 2.0 SP** | 전략 문서화("향후 지원 예정", DEBT-02), 구현 없음 | SAML 전용 기관(추정 ~20%) 실연동 불가 |
| **G-F. 기관 인벤토리** | 운영 시드 `AGENCY_STUB_001` 1건. 68기관 실명·스택·배정표 부재 | 웨이브 편성·투자 산정 불가 |

### 3.3 세그먼트 정의 (실행용)

기술축(스택) × 자산축(기존 SSO) × 망축(개방/폐쇄)을 접어 **6개 실행 세그먼트**로 정리한다:

| Seg | 프로필 | 예상 규모* | 현재 커버리지 | 처방(§5) |
|-----|--------|-----------|----------------|-----------|
| **S1** | 모던 Java 웹 + 개발 여력 | 다수 | ✅ DIRECT+SDK 완비 | 즉시 온보딩 (파일럿 웨이브) |
| **S2** | 자체 SSO 보유 (OIDC형/SAML형/상용) | ~60% 합산 | 🟡 옵션 A·B만, 브로커링·SAML 미구현 | §5.2 — 공존 우선, 브로커링·SAML SP 구현 |
| **S3** | 폐쇄망/고보안 (L3) | 소수 | ✅ BRIDGE/L3 전략 존재 | 기존 전략 + mTLS, 개별 설계 |
| **S4** | 레거시 Apache 앞단 (eGovFrame) | 중간 | ✅ APACHE_GATE 전략 존재 | §5.3 Edge Gateway와 통합 운용 |
| **S5** | **레거시 스택 (JDK≤7 WAS, MiPlatform/RIA)** | 미상 (실태조사 필요) | ❌ **커버 불가 (G-A·G-B·G-D)** | §5.3 — **Edge Gateway 신설 (핵심 투자)** |
| **S6** | 비-Java (.NET/PHP 등) | 미상 | ❌ REST 명세뿐 (G-C) | §5.4 — OpenAPI 명세 + Edge Gateway 재사용 |

\* 규모는 Track 0 실태조사로 확정. **S5·S6 규모가 Edge Gateway 투자 판단의 결정 변수.**

---

## 4. onepass-agent 재정의 (정직성 회복)

실사 결과에 따라 에이전트의 위상을 다음과 같이 재정의한다:

1. **즉시(정직성)**: 기관용 가이드의 JEUS 4/5/6·JDK≤7 "✅ 지원" 표기를 "미지원(로드 불가 — D-AGENT-01)"으로 정정. 지원 매트릭스를 "실검증 완료(테스트베드 7종)" / "전략 존재·미검증" / "미지원" 3단계로 재분류.
2. **역할 축소 재정의**: 에이전트 = *"JDK 8+ Servlet WAS에서, 이미 발급된 토큰의 사후 검증 게이트"* (API 보호용). SSO 로그인 UX는 에이전트 담당이 아님을 명시.
3. **수리 대상(에이전트를 유지한다면)**: ⓐ verify 엔드포인트 서버측 구현 + 위빙 4종 경로 통일 ⓑ 레거시 전략 인터페이스 정확일치 no-op 버그 ⓒ fail-closed 옵션(D-AGENT-02) ⓓ 테스트베드 실구동.
4. **JDK≤7 direct 지원(--release 5 재빌드 + 구식 Javassist + TLS 우회)은 추진하지 않는다** — 3중 차단의 비용 대비, §5.3 Edge Gateway가 동일 문제를 기관 JVM 밖에서 해소하기 때문이다.

---

## 5. Part III — 세그먼트별 니즈 해소 설계

### 5.0 기관 공통 니즈 (분석 프레임)

| 니즈 | 내용 | 본 플랜의 응답 |
|------|------|----------------|
| N1 회원 데이터 주권 | 자기 회원원장 유지, 플랫폼은 연계만 | DI/agencySubjectId 모델 유지, 프로비저닝은 해시·가명 ID만(이미 설계됨) |
| N2 최소 개발 | 개발 여력 없는 기관 다수 | SDK(S1)·Edge Gateway(S5/S6)·폴링 채널(웹훅 수신부 불요) |
| N3 기존 회원 유지 | 전환 강제 반발 | GUEST 정책 + 전환은 opt-in, 이중 로그인 병행 |
| N4 장애 격리 | "OnePass 때문에 우리 서비스가 죽으면 안 된다" | 로컬 세션 독립·fail-open·fallback_login_url(§2.1) |
| N5 기존 SSO 보호 | SSO 투자 매몰 방지 | 옵션 A(공존) 기본, 브로커링은 기관 선택(§5.2) |
| N6 보안/규제 | 망분리·L3·감사 | BRIDGE/L3/mTLS/감사 로그(기존 자산) |
| N7 지원 | 연동 중 막히면 물어볼 곳 | 온보딩 킷+기관 지원 런북(있음)+셀프서비스 포털(신설 필요) |

### 5.1 S1 표준 기관 — 즉시 실행 가능

기존 자산으로 충분: DIRECT + SDK + Handoff/전환/웹훅(또는 폴링). **파일럿 웨이브 대상.** 유일한 준비물은 운영 선결조건(§2.4-8)과 온보딩 킷 표준화(체크리스트·샘플코드·검증 스크립트 — 상당 부분 문서 존재).

### 5.2 S2 자체 SSO 보유 기관 — "대체가 아니라 공존"

기관 니즈의 본질: **기존 SSO 투자를 버리라면 참여하지 않는다.** 따라서:

1. **기본 제안 = 옵션 A(완전 분리·공존)**: 기관 SSO는 그대로, OnePass는 *추가 로그인 수단*. 회원연계(전환)만 먼저 — SSO 통합은 후속 선택. 현재 구현으로 즉시 가능.
2. **옵션 B(위임)**: OnePass 인증 후 기관 SSO 세션 수립(`INTERNAL_SSO` 전략 — `POST {sso_domain}/internal/sso-session`). 드림시큐리티 등 상용 SSO도 "프로토콜 변환 없이" 이 패턴으로 수용(wiki/guide/05).
3. **옵션 C(브로커링) — 구현 필요**: Keycloak Identity Brokering(ADR-2026-004)으로 OnePass를 상위 IdP화. **SAML SP(DEBT-02) 구현이 선행** — SAML형(~20%)·상용 SSO 기관의 완전 통합 경로.
4. **계정 연결(Account Linking)이 SSO 통합보다 먼저다**: 어떤 옵션이든 CI 기반 전환(conversion)으로 `기관 mbrId ↔ qimUserId` 매핑을 먼저 축적하면, SSO 방식 결정과 무관하게 통합회원 가치(단일 식별)가 성립한다.
5. **이중 로그인 전환기 정책**: 전환 완료 회원의 이중 로그인 방지 처리(가이드 §398)는 기관별 소멸 일정에 맡기고, 플랫폼은 강제하지 않는다(N4·N5).

### 5.3 S5 레거시 기관 — **OnePass Edge Gateway (신설, 본 플랜의 핵심 제안)**

**문제의 재정의**: JDK 1.5 + MiPlatform 기관의 제약은 ① 구형 JVM(에이전트 로드 불가) ② TLS 1.0(플랫폼 HTTPS 호출 불가) ③ RIA 클라이언트(브라우저 리다이렉트 불가) ④ 개발 여력 부족(코드 수정 불가)이다. **이 4가지는 기관 애플리케이션 "안"에서는 해결이 원천 불가능하다. 따라서 "밖"에서 해결한다.**

**제안: 기관 앞단에 플랫폼이 표준 제공하는 경량 리버스 프록시(Edge Gateway)를 배치한다.**

```text
[사용자 브라우저/MiPlatform 클라이언트]
        │ (HTTP/HTTPS — 기관 기존 프로토콜 그대로)
        ▼
┌──────────────────────────────────────────────┐
│  OnePass Edge Gateway (기관 DMZ, 플랫폼 표준품) │   ← 신설
│  · TLS 종단 (플랫폼과는 TLS1.2+, 기관과는 기존) │
│  · 미인증 브라우저 요청 → OnePass 로그인 리다이렉트│
│  · Handoff verify 호출 → 게이트웨이 세션 쿠키 발급│
│  · 레거시 WAS로는 X-Remote-User/X-Auth-Level 헤더 주입│
│  · RIA(XML dataset) 트래픽: 세션 쿠키로 통과 판정 │
│  · fail-open/closed 정책, 경로 화이트리스트 설정형│
└──────────────┬───────────────────────────────┘
               │ (내부 HTTP — 기관 WAS 무수정)
               ▼
   [기관 레거시 WAS — JEUS 4/5, JDK 1.5, MiPlatform 백엔드]
```

**왜 이 방식인가:**
- **기존 자산과 정합**: `APACHE_GATE` 전략이 이미 "게이트웨이가 검증하고 헤더로 신원 전달"(mod_auth_openidc 호환 `X-Remote-User/X-Auth-Level/X-Handoff-Token`)을 구현했다. Edge Gateway는 그 게이트웨이 자체를 플랫폼이 표준품으로 제공하는 것 — **새 프로토콜이 아니라 기존 패턴의 제품화**다.
- **4중 제약 동시 해소**: TLS1.2+는 게이트웨이가 담당(구형 JVM 무관), JDK 제약 무관(기관 코드 무수정), 비-Java(S6)도 동일 게이트웨이 재사용, 개발 여력 불요(설정 파일만).
- **MiPlatform UX 성립 경로**: RIA 앱의 로그인은 ⓐ 포털 선행 로그인(브라우저에서 OnePass 로그인 → 게이트웨이 세션 확립 → RIA 실행) 또는 ⓑ RIA 내장/외부 브라우저 팝업으로 OnePass 로그인 후 복귀 — 이후 XML dataset 통신은 **세션 쿠키**로 통과(Bearer 아닌 쿠키 기반이 레거시 친화적). 어느 쪽이든 게이트웨이가 세션을 소유하므로 RIA 클라이언트 수정 불요.
- **구현 형태**: 자체 개발 Go/Java17 self-contained 바이너리 또는 **nginx/Apache + 검증 모듈**(기관 친숙도 높음). 배포는 기관 DMZ에 단일 인스턴스+예비 — 플랫폼이 설치 패키지·설정 템플릿·헬스체크를 표준 제공.
- **회원연계까지 확장**: 실시간 API가 불가능한 최저기술 기관을 위해 게이트웨이에 **배치 파일 연계**(일 1회 CSV/SFTP, 전환·탈퇴 목록) 어댑터를 옵션으로 두면 S5의 회원 동기화까지 커버한다.

**투자 판단**: Edge Gateway는 신규 개발이므로, **Track 0 실태조사에서 S5+S6 기관 수가 임계치(예: 10개 이상)를 넘는지 확인 후 착수**한다. 소수면 개별 컨설팅(기관별 Apache 게이트 구축 지원)으로 갈음.

### 5.4 S6 비-Java 기관

1. **OpenAPI 3.0 명세 공개**(Handoff verify·전환·폴링·인바운드) — 언어 무관 자가 구현 기반. 현재 REST는 존재하므로 명세화·샘플(C#/PHP) 추가가 전부.
2. HMAC 서명 검증 등 까다로운 부분만 **언어별 초경량 스니펫**(단일 파일) 제공 — SDK 포팅은 하지 않는다.
3. 개발 여력 없으면 **S5와 동일하게 Edge Gateway**로 수용.

### 5.5 회원연계(통합회원) 관점의 세그먼트별 경로

| Seg | SSO 경로 | 회원연계 경로 | 최소 요건 |
|-----|----------|----------------|-----------|
| S1 | DIRECT Handoff | 전환(conversion) + 프로비저닝 + 인바운드 | 컬럼 2개(ci_hash, qim_user_id) + lookup API |
| S2 | 공존→위임→브로커링 (기관 선택) | **전환 먼저**(Account Linking) — SSO 결정과 독립 | 동일 |
| S3 | BRIDGE + mTLS | 전환 + 프로비저닝(mTLS) | 폐쇄망 승인 절차 |
| S4/S5 | Edge Gateway(헤더 주입) | 전환은 브라우저 경유 동일 / 최저기술 기관은 배치 파일 연계 | 게이트웨이 설치 |
| S6 | OpenAPI 자가 구현 or Edge Gateway | 동일 | 명세 기반 |

---

## 6. Part IV — 실행 플랜

> CD 방식(push형/GitOps)은 미정 상태를 전제로 하며, 본 플랜의 트랙은 그 결정과 독립적으로 진행 가능하다.

### Track 0 — 기관 실태조사 (모든 것의 선행, 즉시 착수 가능)
- **산출물**: 68개 기관 인벤토리 시트 — {기관명, 백엔드 스택/JDK/WAS, 프런트(웹/MiPlatform/Nexacro/기타), 자체 SSO(제품/프로토콜), 망(개방/폐쇄), 회원 수, CI 보유율, 개발 여력, 담당 채널}
- **방법**: 설문(2주) + 상위 회원수 기관 10곳 심층 인터뷰
- **판단 기준 산출**: 세그먼트 배정표, S5/S6 규모 → **Edge Gateway 투자 GO/NO-GO**, 웨이브 편성안
- 부수: `agency_meta`에 `stack_profile`(JSONB)·`sso_type` 컬럼 확장(ADR-2026-004 권고 반영)

### Track A — 운영 성립 조건 충족 (§2.4의 10개 항목)
- A-1 Redis HA + 정책 재정의 / A-2 DB 백업·DR·RPO/RTO / A-3 Vault renew-self / A-4 스케줄러 단일화 — **인프라 4종 세트, 최우선**
- A-5 인프라 알람 부활 / A-6 실부하 테스트 / A-7 SLA·인시던트 / A-8 온보딩 자동화 / A-9 per-agency CB / A-10 fallback_login_url 구현 + DR 훈련

### Track B — 회원 데이터 루프 완결 (§2.3)
- B-1 프로비저닝 실발행 활성화 경로(파일럿 기관 대상 dry-run → 실발행)
- B-2 `identity_hash` 재설계 — 기관 원장 대조 가능한 신호(동의 전제 CI 해시 등, 회원통합 플랜 §12와 연동 결정)
- B-3 gateway inbound 라우팅 구현(log-only → Q-IM/inst_mbr_id_mapping 반영)
- B-4 MEMBER_WITHDRAWN 기관 전파 경로 완결
- B-5 병합-DI 변경 통지 또는 DI 재설계 결정(회원통합 플랜 §12-3)

### Track C — 에이전트 정직성 회복 + Edge Gateway (S5·S6)
- C-1 **즉시**: 문서 정정(JEUS 4/5/6 "✅"→미지원, 매트릭스 3단계 재분류) — 신뢰 문제
- C-2 에이전트 수리(유지 결정 시): verify 서버 구현·경로 통일, no-op 버그, fail-closed, 테스트베드 실구동
- C-3 **Edge Gateway PoC**: APACHE_GATE 전략 위에 표준 게이트웨이 1종(nginx 기반 권장) + MiPlatform 모의 클라이언트 검증 — *Track 0 결과로 착수 판단*
- C-4 배치 파일 연계 어댑터(최저기술 기관 옵션)

### Track D — 자체 SSO 연동 완성 (S2)
- D-1 SAML 2.0 SP 구현(DEBT-02) — Keycloak SAML IdP Mapper 경유(ADR-2026-004 D-2)
- D-2 agent/SDK token-source 쿠키 지원(v1.1.0 예정분)
- D-3 Keycloak Identity Brokering 운영화(옵션 C) + CAST sso-entry 레지스트리 연동·public-key 엔드포인트 완성

### Track E — 온보딩 웨이브
```text
Wave 0  파일럿: S1 협조 기관 2~3곳 (전환+SSO+프로비저닝 전 루프 검증)
Wave 1  S1 잔여 + S2 옵션A(공존·전환만) — 회원연계 가치 조기 실현
Wave 2  S4 (APACHE_GATE/Edge Gateway) + S3 (개별 설계)
Wave 3  S2 옵션B/C (SAML SP·브로커링 완성 후)
Wave 4  S5·S6 (Edge Gateway 성숙 후) — 실태조사 결과에 따라 앞당김 가능
```
각 웨이브: 온보딩 킷 → DRY_RUN → 검증 게이트(매칭률·오류율) → 실가동 → 회고 반영(회원통합 플랜 §7 런북과 동일 규율).

### 트랙 간 의존성
```text
Track 0 ──┬─→ Track C-3 (Edge Gateway 투자 판단)
          ├─→ Track E (웨이브 편성)
Track A ──┴─→ Wave 0 개시 조건 (A-1~A-4 필수)
Track B ──→ Wave 1 이후 회원연계 확대 조건
Track D ──→ Wave 3 개시 조건
```

---

## 7. 의사결정 필요 항목 (경영/법무/기관협의체)

| # | 결정 | 선택지 | 영향 |
|---|------|--------|------|
| 1 | Edge Gateway 투자 | 표준품 개발 vs 기관별 개별 지원 | S5/S6 커버 방식·비용 (Track 0 데이터로 판단) |
| 2 | 연계 동의 모델 | 옵트인 전환 vs 근거 기반 일괄+옵트아웃 | 회원통합 속도·법적 리스크 (회원통합 플랜 §12-1과 동일) |
| 3 | 이중 로그인 유지 기간 | 최소 1년 vs 기관 자율 vs 조기 일원화 | 중앙 장애 폭발 반경(N4) |
| 4 | 에이전트 존폐 | 수리·유지(토큰 게이트로 축소) vs Edge Gateway로 대체 | Track C 배분 |
| 5 | SAML SP 우선순위 | Wave 3 전 필수 vs S2 수요 실측 후 | ~20% 기관 일정 |
| 6 | `identity_hash` 재설계 | CI 해시 신호(동의 전제) vs 현행 유지(매칭은 전환 경로만) | 프로비저닝의 회원 매칭 가치 |
| 7 | SLA 수준 | 가용성 목표(99.9%? 99.95%?) — Redis/DB HA 투자 규모 직결 | Track A 범위 |

## 8. 리스크

| 리스크 | 완화 |
|--------|------|
| 실태조사 응답 부실 → 세그먼트 오배정 | 상위 회원수 기관 인터뷰 병행, 웨이브별 재조사 |
| Edge Gateway가 "또 하나의 운영 부담"화 | 표준품 1종·설정형 유지, 기관 커스텀 금지 원칙 |
| 레거시 기관의 게이트웨이 설치 거부(DMZ 변경 부담) | 배치 파일 연계 폴백, 기관 인프라팀 대상 설치 지원 |
| 중앙 장애 공포로 기관 이탈 | §2.1 3원칙(병행 로그인·세션 독립·fallback) 명문화·SLA 제시 |
| 에이전트 허위 표기 노출 시 신뢰 훼손 | C-1 선제 정정 + 재분류 공지 |
| 문서-코드 괴리 재발 | "실검증/미검증/미지원" 3단계 표기 규율 상시화 |

---

### 부록 A. 실사 근거 (선별)
- 채널: `HandoffServiceImpl.java:48,225-248` · `CrossAgencySsoController.java:141-314` · `ConversionInitService.java:85-187` · `ProvisioningRelayJob.java` · `WebhookRelayJob.java:127-296` · `AgencyEventController.java:18-45` · `AgencyGatewayServiceImpl.java:248-270`
- 패턴: `ido V1 agency_meta`(DIRECT/APACHE_GATE/BRIDGE/INTERNAL_SSO) · `handoff/strategy/*` · `V13__seed_agency_pattern_scenarios.sql` · `wiki/guide/06-agency-sso-integration-strategy.md` · `ADR-2026-004`
- 에이전트: `onepass-agent/build.gradle.kts:146`(--release 8) · `GenericFilterWeavingStrategy.java:147-183`(토큰 게이트·no-token 통과) · `OnePassHttpClient.java:133,144`(호출 엔드포인트 2종) · `docs/onepass-agent-troubleshooting.md:47-71`(TS-01-A) · `developer-reference.md:974-1007`(D-AGENT-01~06)
- 장애 의미론: `AgencySessionService.java:159-186`(로컬 세션) · `IdoVerifyClient.java:73-143`(CB) · `V4__add_qim_sp_receiver.sql:114-118`(fallback_login_url 미참조)
- 운영: `values-prod.yaml`(HA) · `alert_rules.yml:17-21`(인프라 알람 폐기) · `AgencyRateLimiter.java:154`(fail-open) · `VaultKmsClient.java:198-202`(F5.3)

> **주의**: 본 문서의 "미검증/공백" 판정은 2026-07-06 `shipster` 기준 코드 실사 결과다. 후속 스프린트에서 해소 시 본 문서를 갱신한다.
