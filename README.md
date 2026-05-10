# OnePass 통합인증 플랫폼 (Integration-SSO)

**중소벤처기업부 중기원패스(OnePass) 통합인증 SSO 및 아이덴티티 관리 시스템** PoC/프리프로덕션 구현체.  
**4+1 축 책임 모델** (Q-Sign · Q-IM · IdO · onepass-fe · agency-stub) 기반 EDA 아키텍처.

> **현재 버전: v2.2.0** — Sprint 9 완료 (Redisson 분산 락 · Resilience4j CB · Bean Validation · OTel 추적 · 감사 로그 · K8s Secret · Rate Limiting · NHN KMS)  
> **빌드 상태**: `./gradlew build -x test` → **BUILD SUCCESSFUL** (전 모듈)  
> **테스트**: `./gradlew test` → **397개 통과** (기존 397, Sprint 9는 인프라/보안 강화 중심)  
> **PR**: [#50 (OPEN)](https://github.com/HipsterMIN/integration-sso/pull/50) — Sprint 9 프로덕션 강화 (S9-T1~T8)

---

## 목차

1. [버전 히스토리](#버전-히스토리)
2. [전체 구현 진행률](#전체-구현-진행률)
3. [아키텍처 개요](#아키텍처-개요)
4. [S7-T2: NICE/OACX 본인인증 통합](#s7-t2-niceoacx-본인인증-통합)
5. [모듈 책임 분리](#모듈-책임-분리)
6. [기술 스택](#기술-스택)
7. [모듈 구성](#모듈-구성)
8. [데이터베이스 구성](#데이터베이스-구성)
9. [Kafka 토픽](#kafka-토픽)
10. [보안 체계](#보안-체계)
11. [Flyway 마이그레이션 현황](#flyway-마이그레이션-현황)
12. [테스트 현황](#테스트-현황)
13. [모니터링 인프라](#모니터링-인프라)
14. [빠른 시작](#빠른-시작)
15. [접속 URL](#접속-url)
16. [개발 환경 설정](#개발-환경-설정)
17. [전체 로드맵 & 개발 플랜](#전체-로드맵--개발-플랜)
18. [상용 서비스 관점 품질 분석](#상용-서비스-관점-품질-분석)
19. [코딩 컨벤션](#코딩-컨벤션)
20. [문서 디렉토리](#문서-디렉토리)

---

## 버전 히스토리

| 버전 | PR | 스프린트 | 주요 내용 |
|------|----|---------|---------| 
| **v2.2.0** | [#50](https://github.com/HipsterMIN/integration-sso/pull/50) | Sprint 9 | **프로덕션 강화** — Redisson 분산 락, Resilience4j CB+Retry, Bean Validation, OTel AOP 계측, 감사 로그(platform.audit.log), K8s Secret/ConfigMap, Auth Rate Limit, NHN Cloud SKM 연동 |
| **v2.1.0** | [#45](https://github.com/HipsterMIN/integration-sso/pull/45) | Sprint 7 S7-T2 | **NICE/OACX 본인인증 ido BFF 완전 이식** — 6개 API, Redis 세션/토큰 캐시, PBKDF2+AES-256-GCM, 32개 테스트 |
| v2.0.0 | [#39](https://github.com/HipsterMIN/integration-sso/pull/39) | Sprint 5 | **AES 키 로테이션** (KeyVersionRegistry + v{n}.{iv}.{ct} 포맷) + **모니터링 인프라** (Prometheus/Grafana/Loki Docker Compose + 대시보드) |
| v1.9.9 | [#38](https://github.com/HipsterMIN/integration-sso/pull/38) | Sprint 4-5 | **UuidV7Test 27개** (v7 포맷·단조증가·고유성·스레드안전) + **WebhookDispatcherServiceTest 33개** |
| v1.9.8 | [#37](https://github.com/HipsterMIN/integration-sso/pull/37) | Sprint 4 | **HandoffServiceImplTest 18개** — issue/verify/revoke 단위 테스트 |
| v1.9.5 | [#35](https://github.com/HipsterMIN/integration-sso/pull/35) | Sprint 3-4 | **UUID v4 → v7 전체 교체** (RFC 9562) + **단위 테스트 기반** (7파일 신규) |
| v1.9.4 | [#33](https://github.com/HipsterMIN/integration-sso/pull/33) | Sprint 3-4 | **P2 운영 고도화** + **P3 배포 준비** 완전 구현 |
| v1.9.3 | [#32](https://github.com/HipsterMIN/integration-sso/pull/32) | Sprint 2 | **SLO 완전 구현** + 개인정보 파기 스케줄러 + FE 인증 기반 |
| v1.9.2 | [#31](https://github.com/HipsterMIN/integration-sso/pull/31) | Sprint 1 | **P0 보안 결함** 완전 제거 + 테스트 기반 구축 |
| v1.9.1 | [#28](https://github.com/HipsterMIN/integration-sso/pull/28) | — | 기관 이벤트 폴링 API 완성 |
| v1.9.0 | [#24](https://github.com/HipsterMIN/integration-sso/pull/24) | — | P0/P1/P2 GAP 마감, ProviderRouter, 동적 CircuitBreaker |
| v1.8.0 | [#22](https://github.com/HipsterMIN/integration-sso/pull/22) | — | Admin API, Redis Rate Limiter, HandoffStrategy 패턴, PKCE |

---

## 전체 구현 진행률

> **기준일**: 2026-05-10 | **총 테스트**: 397개 (ido 202 + platform-common 59 + q-sign 23 + q-im 113) | v2.2.0 Sprint 9 반영

### 모듈별 구현 완성도

```
platform-common  ████████████████████ 100%  (도메인·이벤트·에러코드 완비, UUID v7 유틸)
Q-Sign           ████████████████████  97%  (InternalSig 수신 검증 완료, SLO 완료)
Q-IM             ████████████████████  96%  (CI 암호화 v{n} 포맷, 파기 스케줄러 완료)
IdO              ████████████████████  85%  (AES 키 로테이션 완료, NICE/OACX auth 완료, S7-T6 CI→IM 미완)
agency-stub      ████████████████████  90%  (E2E 시뮬레이터 완비, 단위 테스트 미작성)
onepass-fe       ████████████████░░░░  78%  (인증 기반 완비, 관리자 UI·CSP 미완)
인프라/Docker    ████████████████████ 100%  (모니터링 스택 완비, CI/CD P3 대기)
보안             ████████████████████  98%  (AES 키 로테이션 완료, NICE/OACX PII 보호 완료)
테스트 커버리지  ████████████░░░░░░░░  58%  (ido auth 32개 추가, 통합테스트 0개)
```

**전체 완성도**: 약 **91%** — 프리프로덕션 단계 (auth 이식 완료, CI→IM 연동 미완)

### Sprint별 완료 현황

| Sprint | 목표 | 상태 | 완료 항목 |
|--------|------|------|-----------|
| **Sprint 1** | P0 보안 결함 | ✅ **완료** | API Key PBKDF2, 기본 시크릿 제거, X-Internal-Sig |
| **Sprint 2** | P1 SLO + 개인정보 | ✅ **완료** | SLO Keycloak 전파, SP 로그아웃 Webhook, 파기 스케줄러 |
| **Sprint 3** | P2 운영 고도화 | ✅ **완료** | UUID v7, Micrometer 기초, 구조화 로깅, FE 상태관리 |
| **Sprint 4** | 테스트 기반 | ✅ **완료** | HandoffServiceImpl 18개, Webhook 33개, UuidV7 27개 |
| **Sprint 5** | 암호화 + 모니터링 | ✅ **완료** | AES 키 로테이션, Prometheus/Grafana/Loki, 대시보드 |
| **Sprint 6** | 잔여 테스트 | ✅ **완료** | agency-stub 테스트, 유관기관 패턴 Stub (S6-T1~T2) |
| **Sprint 7** | 본인인증 BFF | ✅ **완료** | S7-T2 NICE/OACX 이식, S7-T6 CI→Q-IM 등록 (ImApiOutPort) |
| **Sprint 8** | CI/CD + 부하테스트 | ✅ **완료** | GitHub Actions, k6 부하테스트, OWASP ZAP, Grafana 알림 |
| **Sprint 9** | 프로덕션 강화 | ✅ **완료** | Redisson 분산 락, Resilience4j CB+Retry, Bean Validation, OTel AOP 추적, 감사 로그, K8s Secret, Rate Limiting, NHN SKM |

---

## 아키텍처 개요

> **⚠️ 설계 원칙**: 모든 유관기관(기관 시스템)은 **외부망**에 위치합니다.  
> 기관은 내부 Kafka·DB에 직접 접근하지 않으며, **IdO 공개 API(HTTPS)** 만을 통해 통신합니다.

```
══════════════════════════════════════════════════════════════════════
  외부망 (External Network)
══════════════════════════════════════════════════════════════════════

  ┌──────────────────────────────────────────────────────────────────┐
  │      최종 사용자 (브라우저 / 앱)                                    │
  │                                                                  │
  │  [개발] React dev :3000                                           │
  │    webpack proxy → ido:8083  (BE_API_TARGET=http://localhost:8083)│
  │  [운영] Nginx :3001                                               │
  │    /api/** → ido:8083 (same-origin 보안, Q1=B)                  │
  └──────────┬───────────────────────────────────────────────────────┘
             │ HTTPS / /api/v1/**
             │  ├── /fe-session/**     (FE 세션 관리)
             │  ├── /handoff/**        (Handoff 발급/검증)
             │  ├── /auth/**           (본인인증 BFF ★S7-T2 신규)
             │  │     ├── /nice/phone/url
             │  │     ├── /nice/phone/result
             │  │     ├── /nice/ci-check
             │  │     ├── /oacx/access-info
             │  │     ├── /oacx/easysign
             │  │     └── /callback
             │  └── /broker/**         (OIDC 브로커)

  ┌─────────────────────────────────────────────────────────────────┐
  │  유관기관 시스템 (외부망)                  외부 인증 공급자         │
  │  agency-stub :8084 ← PoC 전용             NICE IDO 서버          │
  │                                           https://auth.niceid.co.kr │
  │  ① POST /api/v1/handoff/issue            OACX SDK v1.3.2         │
  │  ② POST /api/v1/handoff/verify           통합인증 서버            │
  │  ③ GET  /api/v1/agency/events                                    │
  │  ④ POST /api/v1/webhook/inbound                                  │
  └────────────────────────────┬────────────────────────────────────┘
                               │ HTTPS (공개 API만)
══════════════════════════════╪═══════════════════════════════════════
  내부망 (Internal Network — onepass-net 172.20.0.0/24)
══════════════════════════════╪═══════════════════════════════════════
                              ▼
  ┌────────────────────────────────────────────────────────────────────┐
  │  ido  :8083  정책 오케스트레이터 + FE BFF                            │
  │                                                                    │
  │  [FE BFF]                        [기관향 공개 API]                  │
  │  feSessionId 쿠키 발급/갱신/만료    POST /api/v1/handoff/issue        │
  │  ReturnUrl 화이트리스트 검증        POST /api/v1/handoff/verify       │
  │                                   GET  /api/v1/agency/events       │
  │  [본인인증 BFF ★S7-T2]             [Webhook Push]                  │
  │  NICE 휴대폰 인증 (URL발급/결과)    WebhookDispatcherService          │
  │  OACX 간편서명 (접근정보/결과)      WebhookDispatchOutboxRelay        │
  │  Redis 세션·토큰 캐시 (TTL관리)                                      │
  │  PBKDF2+HMAC-SHA256+AES-256-GCM                                    │
  │  CI PII 보호 (FE 미반환 Q3=B)                                       │
  │                                   [암호화 — Sprint 5]              │
  │  [IdP 브로커]                      AES-256-GCM (v{n}.{iv}.{ct})    │
  │  /api/v1/broker/**                KeyVersionRegistry               │
  │  /api/v1/oidc/**                  HandoffKeyRotationScheduler      │
  │  ProviderRouter                   Redis 분산 락 (90일 주기)         │
  └──────────────────┬─────────────────────────────────────────────────┘
                     │ HTTP (내부망 전용)
         ┌───────────┴───────────┐
         ▼                       ▼
  ┌─────────────┐       ┌─────────────┐
  │ q-sign:8081 │       │  q-im:8082  │
  │  인증 SoR    │       │  식별 SoR    │
  │  Keycloak   │       │  회원 원장   │
  │  OIDC 브로커 │       │  CI 암호화   │
  │  SLO 전파   │       │  v{n}.{iv}  │
  └──────┬──────┘       └──────┬──────┘
         │  Outbox              │  Outbox
         └──────────┬───────────┘
                    ▼
    ┌───────────────────────────────────┐
    │           Apache Kafka            │
    │  qsign.auth.events                │
    │  ido.handoff.events               │
    │  qim.user.events (Compacted)      │
    │  platform.session.advisory        │
    │  platform.audit.log               │
    │  + 각 토픽별 .dlq 토픽             │
    └───────────────────────────────────┘

    ┌───────────────────────────────────┐
    │     모니터링 스택 (Sprint 5)        │
    │  Prometheus :9090                 │
    │  Grafana    :3000                 │
    │  Loki       :3100                 │
    │  Promtail   :9080                 │
    │  + Redis/Kafka/PG Exporter        │
    └───────────────────────────────────┘
```

---

## S7-T2: NICE/OACX 본인인증 통합

> **Sprint 7 Task 2 완료** — onepass-be 헥사고날 아키텍처에서 ido 평탄화 계층 구조로 이식.  
> 32개 단위 테스트 통과. 설계 결정: Q1=B(API Key 없음), Q2=B(callback 추가), Q3=B(CI FE 미반환), Q4=A(WebClient).

### 설계 결정 요약

| Q | 결정 | 근거 |
|---|------|------|
| **Q1=B** | API Key 없음 | Nginx same-origin 프록시로 보안 처리. `/api/v1/auth/**` 는 FE BFF 전용 — 외부 노출 없음 |
| **Q2=B** | callback 엔드포인트 추가 | 기업인증 FE 구현 대비. 현재 FE 미연동이지만 백엔드 완성 |
| **Q3=B** | CI FE 미반환 | PII 보호 핵심 원칙. CI는 백엔드 내부에서만 처리, `@JsonInclude(NON_NULL)` 적용 |
| **Q4=A** | WebClient (Tomcat 유지) | `spring-webflux` + `reactor-netty-http`만 추가. `DispatcherServlet` 유지로 Tomcat 서버 전환 없음 |

### 신규 패키지 구조 (`ido/src/main/java/kr/go/smes/ido/auth/`)

```
auth/
├── config/
│   ├── AuthProperties.java          # @ConfigurationProperties(prefix="ido.auth")
│   └── AuthWebClientConfig.java     # niceWebClient, integrationAuthWebClient @Bean
│
├── client/
│   ├── NiceApiClient.java           # NICE IDO API (Access Token, URL 발급, 결과 조회)
│   ├── OacxClient.java              # OACX SDK v1.3.2 래퍼 (getAccessInfo, jwtDecryptResult)
│   └── IntegrationAuthClient.java   # 통합인증 서버 WebClient (POST /auth-check/v1)
│
├── store/
│   ├── NiceTokenStore.java          # Redis Hash — NICE Access Token 캐시 (60초 여유 재발급)
│   └── NiceAuthSessionStore.java    # Redis Hash — NICE 인증 세션 (TTL 10분, requestNo 키)
│
├── util/
│   └── NiceCryptoUtil.java          # PBKDF2WithHmacSHA256(512bit) → HMAC-SHA256 → AES-256-GCM
│
├── service/
│   ├── NiceAuthService.java         # NICE 휴대폰 인증 플로우 전체 (URL발급, 결과조회, 복호화)
│   └── AuthService.java             # 기업인증 콜백, OACX 간편서명, CI 확인
│
├── controller/
│   └── AuthController.java          # 6개 REST 엔드포인트 (Swagger 없이 상세 Javadoc)
│
└── dto/
    ├── AuthCallbackRequest/Response.java      # 기업 간편인증 콜백 요청/응답
    ├── AuthCheckResponse.java                 # 통합인증 서버 응답 (내부용)
    ├── AuthResult.java                        # IM API 전달용 인증 결과
    ├── CiCheckRequest/Response.java           # CI 기반 회원 확인
    ├── NicePhoneAuthResultRequest/Response.java # NICE 결과 조회 (CI 제외)
    ├── NicePhoneAuthUrlResponse.java          # NICE URL 발급 응답
    ├── OacxAccessInfoResponse.java            # OACX 접근정보 (fn, accKey, accToken)
    ├── OacxEasysignRequest/Response.java      # OACX 간편서명 (CI=null, @JsonInclude(NON_NULL))
    └── nice/
        ├── NiceResultApiResponse.java         # NICE 결과 원본 응답 (내부용)
        ├── NiceTokenApiResponse.java          # NICE Access Token 응답
        └── NiceUrlApiResponse.java            # NICE URL 발급 원본 응답
```

### 본인인증 API 엔드포인트 (6개)

> 상세 명세: [`docs/api-auth-spec.md`](docs/api-auth-spec.md) 참조 (FE 팀 대상 TypeScript 타입 정의 포함)

#### NICE 휴대폰 본인인증 플로우

```
[FE] → GET /api/v1/auth/nice/phone/url?returnUrl=...
         ↓
[ido] → NICE Access Token 발급 (Redis 캐시, 만료 60초 전 재발급)
         ↓ POST https://auth.niceid.co.kr/ido/intc/v1.0/auth/url
[ido] → NICE URL 발급 API 호출
         ↓ requestNo, webTransactionId Redis 세션 저장 (TTL 10분)
[FE] ← { resultCode: "2000", authUrl, requestNo }

[FE] → NICE 팝업 실행 (window.open(authUrl))
         ↓ 사용자 인증 완료 후 returnUrl로 리다이렉트
[FE] → window.addEventListener('message', ...) → web_transaction_id 수신

[FE] → POST /api/v1/auth/nice/phone/result
         { web_transaction_id, request_no }
         ↓ Redis 세션 조회 (requestNo → transactionId, webTransactionId)
[ido] → NICE 결과 조회 API 호출
         ↓ PBKDF2WithHmacSHA256(512bit) 키 파생
         ↓ HMAC-SHA256 무결성 검증
         ↓ AES-256-GCM 복호화
[FE] ← { resultCode: "2000", resultData: { name, birthdate, gender, di, mobileCo, mobileNo } }
         ※ CI는 응답에 미포함 (Q3=B 보안 정책)
```

#### OACX 간편서명 플로우

```
[FE] → POST /api/v1/auth/oacx/access-info  (body: "simpleAuth")
         ↓ OACX SDK.getAccessInfo() 호출
[FE] ← { resultCode: "2000", fn, accKey, accToken }

[FE] → OACXsdk.init({ fn, accKey, accToken })
         ↓ 간편서명 팝업 실행 → 사용자 서명 완료
         ↓ SDK 콜백: { fn: "authComplete", status: "success", res: {...} }

[FE] → POST /api/v1/auth/oacx/easysign  (body: SDK 콜백 데이터)
         ↓ fn="authComplete" 검증, resultCode="200" 검증
         ↓ OACX SDK.jwtDecryptResult() — JWT 복호화
         ↓ provider별 키 통일 처리
         │  (naver/toss/dream: name/phone → AuthService에서 표준화)
         │  (PASS 통신3사: userNm/phoneNo → name/phone으로 변환)
[FE] ← { resultCode: "2000", name, birthday, phone }
         ※ CI는 null (Q3=B), @JsonInclude(NON_NULL)로 응답에서 제거
```

### 핵심 구현 상세

#### NiceCryptoUtil — NICE IDO 암호화 스펙

```java
// 1단계: PBKDF2WithHmacSHA256으로 키 파생 (512bit 출력)
//   입력: ticket(Access Token), transactionId, iterators
//   출력: 512bit 키 문자열
String keyString = NiceCryptoUtil.deriveKey(ticket, transactionId, iterators);

// 2단계: HMAC 키 추출 (인덱스 48~79, 32바이트)
//   NICE 스펙: keyString의 48번째~79번째 문자 사용
String hmacKey = NiceCryptoUtil.extractHmacKey(keyString);

// 3단계: HMAC-SHA256 무결성 검증
//   encData의 무결성을 integrityValue와 비교 (Base64URL 인코딩)
String computedHmac = NiceCryptoUtil.hmacSha256Base64Url(encData, hmacKey);
// → integrityValue와 불일치 시 DataIntegrityException 발생

// 4단계: AES 키 추출 (인덱스 0~31, 32바이트)
//   NICE 스펙: keyString의 0번째~31번째 문자를 UTF-8 바이트로 사용
byte[] aesKey = NiceCryptoUtil.extractAesKey(keyString);

// 5단계: AES-256-GCM 복호화
//   입력: 32바이트 AES 키, Base64URL 인코딩 암호문
//   앞 12바이트: GCM IV, 나머지: 암호문+인증태그
String plainText = NiceCryptoUtil.aesGcmDecrypt(aesKey, encDataBase64Url);
```

#### Redis 기반 상태 관리 (다중 Pod 대응)

| 저장소 | Redis Key 패턴 | TTL | 용도 |
|--------|--------------|-----|------|
| `NiceTokenStore` | `nice:token:snapshot` | Access Token 만료 - 60초 | NICE Access Token 캐시 (재발급 최소화) |
| `NiceAuthSessionStore` | `nice:session:{requestNo}` | 10분 | NICE 인증 세션 (requestNo ↔ transactionId, webTransactionId) |

> **설계 의도**: onepass-be의 `AtomicReference` + `ConcurrentHashMap` 인메모리 방식 → Redis 교체로 K8s 다중 Pod 환경에서 세션 공유 가능. 단, `NiceAuthService.ensureAccessToken()`의 `synchronized` 블록은 단일 JVM에만 유효 (후속 S7-T6에서 Redisson 분산 락 적용 예정).

#### 의존성 추가 (`ido/build.gradle.kts`)

```kotlin
// WebClient 의존성 (Tomcat 유지 — spring-boot-starter-webflux 아님!)
implementation("org.springframework:spring-webflux")
implementation("io.projectreactor.netty:reactor-netty-http")

// NICE IDO 암호화 (PBKDF2 → AES-256-GCM)
implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

// OACX SDK v1.3.2 (Maven Central 미등록, 로컬 JAR)
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
```

#### 환경변수 (신규)

| 환경변수 | 설명 | 운영 필수 |
|---------|------|----------|
| `NICE_CLIENT_ID` | NICE IDO 클라이언트 ID | ✅ |
| `NICE_CLIENT_SECRET` | NICE IDO 클라이언트 시크릿 | ✅ |
| `NICE_RETURN_URL` | NICE 인증 완료 후 리다이렉트 URL | ✅ |
| `NICE_TIMEOUT_SECONDS` | NICE API 타임아웃 (기본: 10s) | — |
| `OACX_PROVIDER_KEY_PATH` | OACX provider key JSON 파일 경로 | ✅ |
| `OACX_DEBUG_MODE` | OACX SDK 디버그 로그 (기본: false) | — |
| `INTEGRATION_AUTH_BASE_URL` | 통합인증 서버 Base URL | ✅ |
| `INTEGRATION_AUTH_TIMEOUT_SECONDS` | 통합인증 서버 타임아웃 (기본: 10s) | — |

#### FE 연동 빠른 참조

```typescript
// 1. NICE 휴대폰 인증 URL 발급
const { data } = await beApiInstance.get('/api/v1/auth/nice/phone/url', {
  params: { returnUrl: 'https://www.smes.go.kr/otp/auth-result' }
});
// data: { resultCode, resultMsg, authUrl, requestNo }
const popup = window.open(data.authUrl, 'niceAuth', 'width=500,height=600');

// 2. NICE 인증 결과 조회
window.addEventListener('message', async (event) => {
  const { web_transaction_id, request_no } = event.data;
  const { data } = await beApiInstance.post('/api/v1/auth/nice/phone/result', {
    web_transaction_id, request_no
  });
  // data.resultData: { name, birthdate, gender, nationalInfo, di, mobileCo, mobileNo }
  // ※ CI 없음 (Q3=B 보안 정책)
});

// 3. OACX 접근정보 발급
const { data } = await beApiInstance.post('/api/v1/auth/oacx/access-info',
  JSON.stringify('simpleAuth'),
  { headers: { 'Content-Type': 'application/json' } }
);
// data: { resultCode, fn, accKey, accToken }
OACXsdk.init({ fn: data.fn, accKey: data.accKey, accToken: data.accToken });

// 4. OACX 간편서명 결과 처리
OACXsdk.open(async (callbackData) => {
  const { data } = await beApiInstance.post('/api/v1/auth/oacx/easysign', callbackData);
  // data: { resultCode, name, birthday, phone }
  // ※ CI 없음 (Q3=B 보안 정책)
});
```

> 전체 API 명세, 에러 코드 목록, TypeScript 타입 정의: [`docs/api-auth-spec.md`](docs/api-auth-spec.md)

---

## 모듈 책임 분리

| 모듈 | SoR 역할 | 포트 | 핵심 책임 |
|------|---------|------|----------|
| `platform-common` | — | — | 공통 도메인·이벤트·에러코드·UUID v7 유틸 |
| `q-sign` | **인증 SoR** | 8081 | OIDC 브로커링, JWT 검증, PKCE, SLO Keycloak 전파 |
| `q-im` | **식별 SoR** | 8082 | qimUserId, CI AES-256-GCM v{n}, DI HMAC, 회원 원장, 파기 |
| `ido` | **정책 오케스트레이터 + FE BFF** | 8083 | Handoff 발급/검증, Policy, Webhook, FE BFF, AES 키 로테이션, **NICE/OACX 본인인증(S7-T2)** |
| `agency-stub` | — (PoC 전용) | 8084 | 유관기관 연동 E2E 시뮬레이터 |
| `onepass-fe` | — | 3000/3001 | React 18 SPA (TypeScript, Ant Design) |

---

## 기술 스택

### 백엔드 공통

| 기술 | 버전 | 적용 범위 |
|------|------|----------|
| Java | **21 LTS** | 전 모듈 |
| Spring Boot | **3.5.9** | q-sign, q-im, ido, agency-stub |
| Gradle | **9.5.0** | 멀티모듈 빌드 |
| Spring Data JPA | BOM 관리 | q-sign, q-im, ido |
| Spring Kafka | BOM 관리 | 전 서비스 |
| Spring Data Redis | BOM 관리 | ido, q-im |
| **Spring WebFlux** | BOM 관리 | **ido (WebClient 전용, Tomcat 유지) ★S7-T2** |
| Flyway | **11.8.0** | DB 마이그레이션 |
| Resilience4j | **2.2.0** | Circuit Breaker, Retry |
| JJWT | **0.12.6** | JWT 서명 검증 |
| Micrometer | BOM 관리 | Prometheus 메트릭 |
| BouncyCastle | **1.78.1** | **NICE 암호화 (AES-256-GCM, PBKDF2) ★S7-T2** |
| OACX SDK | **v1.3.2** | **OACX 전자서명 중계모듈 (로컬 libs/ JAR) ★S7-T2** |
| JUnit 5 + Mockito | BOM 관리 | 단위 테스트 (397개) |

### 프론트엔드 (`onepass-fe/frontend/`)

| 기술 | 버전 |
|------|------|
| React | 18.3 |
| TypeScript | 5.4 |
| Ant Design | 5.18 |
| TanStack Query | v5 |
| Zustand | 4.5 |

### 인프라

| 서비스 | 이미지 | 용도 |
|--------|--------|------|
| PostgreSQL | `postgres:16-alpine` | q-sign, ido 스키마 |
| MariaDB | `mariadb:11.4` | q-im 전용 |
| Redis | `redis:7.2-alpine` | 세션, PKCE, 캐시, Rate Limit, **NICE 토큰/세션 ★S7-T2** |
| Kafka | `confluentinc/cp-kafka:7.6.1` | 이벤트 버스 |
| Keycloak | `quay.io/keycloak/keycloak:24` | OIDC IdP 브로커 |
| Prometheus | `prom/prometheus:v2.51.2` | 메트릭 수집 |
| Grafana | `grafana/grafana-oss:10.4.2` | 대시보드 |
| Loki | `grafana/loki:2.9.6` | 로그 집계 |
| Promtail | `grafana/promtail:2.9.6` | 컨테이너 로그 수집 |

---

## 모듈 구성

```
onepass-platform/
├── platform-common/
│   └── src/main/java/kr/go/smes/common/
│       ├── domain/           # AuthResult, HandoffPayload, HandoffTicket
│       ├── error/            # PlatformErrorCode
│       ├── event/            # AuthEvent, HandoffEvent, AuditLogEvent
│       └── util/             # UuidV7, ApiKeyHashValidator
│
├── q-sign/                   # 인증 SoR (포트 8081)
│   └── src/main/java/kr/go/smes/qsign/
│       ├── broker/           # Keycloak OIDC 브로커
│       ├── kafka/            # Outbox + 멱등 컨슈머
│       ├── pkce/             # RFC 7636 PKCE
│       └── slo/              # SLO Keycloak end_session 전파
│
├── q-im/                     # 식별 SoR (포트 8082, MariaDB)
│   └── src/main/java/kr/go/smes/qim/
│       ├── crypto/           # CI AES-256-GCM v{n}.{iv}.{ct}
│       ├── identity/         # DI HMAC-SHA256
│       ├── outbox/           # Outbox + Snapshot
│       ├── retention/        # 개인정보 파기 스케줄러 (Sprint 2)
│       └── user/             # 회원 등록·조회·상태
│
├── ido/                      # 정책 오케스트레이터 + FE BFF (포트 8083)
│   ├── libs/
│   │   └── OACX-SDK-v1.3.2.jar   ★S7-T2: OACX 전자서명 SDK (Maven Central 미등록)
│   └── src/main/java/kr/go/smes/ido/
│       ├── admin/            # 기관 Admin API
│       ├── api/              # Handoff + 기관 이벤트 폴링
│       ├── auth/             # ★S7-T2 신규: NICE/OACX 본인인증 BFF
│       │   ├── client/       #   NiceApiClient, OacxClient, IntegrationAuthClient
│       │   ├── config/       #   AuthProperties, AuthWebClientConfig
│       │   ├── controller/   #   AuthController (6개 엔드포인트)
│       │   ├── dto/          #   13개 DTO (nice/ 서브패키지 포함)
│       │   ├── service/      #   NiceAuthService, AuthService
│       │   ├── store/        #   NiceTokenStore, NiceAuthSessionStore (Redis)
│       │   └── util/         #   NiceCryptoUtil (PBKDF2+HMAC+AES-GCM)
│       ├── broker/           # IdP 브로커 + Provider 라우팅
│       ├── config/           # Rate Limit, TraceparentFilter
│       ├── crypto/           # KeyVersionRegistry + HandoffKeyRotationScheduler ★Sprint5
│       ├── fe/               # FE 세션 관리 (IdoWebMvcConfig CORS 포함)
│       ├── handoff/
│       │   ├── crypto/       # HandoffCryptoService (v{n}.{iv}.{ct}) ★Sprint5
│       │   └── strategy/     # HandoffStrategy 패턴
│       ├── kafka/            # 이벤트 컨슈머 4종
│       ├── policy/           # PolicyEngine
│       ├── ratelimit/        # Redis Lua 슬라이딩 윈도우
│       └── webhook/          # Webhook Push + Outbox Relay
│
├── agency-stub/              # 기관 시뮬레이터 (포트 8084)
│
├── onepass-fe/               # React SPA
│
└── infra/
    ├── docker/
    │   ├── docker-compose.yml              # 기본 인프라
    │   └── docker-compose.monitoring.yml   # 모니터링 스택 ★Sprint5
    ├── monitoring/
    │   ├── prometheus/                     # prometheus.yml + alert_rules.yml
    │   ├── grafana/
    │   │   ├── dashboards/                 # onepass-overview.json ★Sprint5
    │   │   └── provisioning/              # datasources + dashboards 프로비저닝
    │   ├── loki/                           # loki-config.yml
    │   └── promtail/                       # promtail-config.yml
    ├── k6/                                 # 부하 테스트 (P3 예정)
    └── owasp/                              # 취약점 스캔 설정
```

---

## 데이터베이스 구성

| 모듈 | DB 엔진 | 스키마 | 최신 Flyway 버전 |
|------|---------|--------|----------------|
| Q-Sign | PostgreSQL 16 | `qsign` | **V5** — auth_method 컬럼 |
| IdO | PostgreSQL 16 | `ido` | **V10** — auth_result 확장, provider_routing |
| Q-IM | MariaDB 11.4 | `qim` | **V3** — CI 암호화 키 버전, user_status_history |
| agency-stub | PostgreSQL 16 | `agency_stub` | **V2** — webhook + api_key |

> **V9 (ido)**: `crypto_key_registry` — AES/HMAC 키 버전 메타데이터 (Sprint 5에서 활용)  
> **Auth 관련 DB 없음**: NICE/OACX 인증은 Redis 상태 관리 (stateless 설계) — DB 스키마 추가 불필요

---

## Kafka 토픽

| 토픽 | 파티션 | 보존 | 생산자 | 소비자 |
|------|--------|------|--------|--------|
| `qsign.auth.events` | 12 | 1h | Q-Sign | IdO |
| `ido.handoff.events` | 12 | 1y | IdO | IdO → Webhook |
| `platform.session.advisory` | 12 | 24h | IdO | IdO |
| `platform.audit.log` | 12 | 2y | IdO | 감사 시스템 |
| `qim.user.events` | 6 | Compacted | Q-IM | IdO, Q-Sign |
| `qim.user.snapshot` | 6 | Compacted | Q-IM | (확장 예정) |
| `qim.sp.member.events` | 6 | 30d | IdO | IdO |
| *.dlq / *.dlt | 3~6 | 7d | 에러핸들러 | 운영 |

> **본인인증 이벤트 (S7-T6 예정)**: `platform.audit.log` 토픽에 인증 성공/실패 이벤트 발행 계획. CI 원문 미포함 필수.

---

## 보안 체계

| 보안 항목 | 구현 방식 | 상태 |
|----------|---------|------|
| 기관 API 키 인증 | PBKDF2-HMAC-SHA256 + 상수시간 비교 | ✅ Sprint 1 |
| **AES 키 버전 로테이션** | `v{n}.{iv}.{ct}` 포맷, 90일 주기, Redis 분산 락 | ✅ Sprint 5 |
| Handoff Ticket 암호화 | AES-256-GCM + 버전 접두사 | ✅ Sprint 5 |
| CI 암호화 (Q-IM) | AES-256-GCM v{n}.{iv}.{ct} | ✅ v1.8.0 |
| Ticket 서명 | HMAC-SHA256 | ✅ 완료 |
| 내부 서비스 서명 (X-Internal-Sig) | HMAC-SHA256 ±60s 검증 | ✅ Sprint 1 |
| Webhook 서명 | HMAC-SHA256 + ±5분 타임스탬프 | ✅ 완료 |
| PKCE (RFC 7636) | S256 code_challenge | ✅ 완료 |
| W3C traceparent 전파 | TraceparentFilter | ✅ 완료 |
| Rate Limiter | Redis Lua 슬라이딩 윈도우 | ✅ 완료 |
| Provider 단위 CB | Resilience4j 동적 생성 | ✅ 완료 |
| SLO Keycloak 전파 | end_session_endpoint 연동 | ✅ Sprint 2 |
| 개인정보 파기 스케줄러 | GDPR §17 준수, 탈퇴 후 90일 | ✅ Sprint 2 |
| **NICE CI PII 보호** | FE 미반환 (Q3=B), `@JsonInclude(NON_NULL)` | ✅ **S7-T2** |
| **OACX CI PII 보호** | FE 미반환 (Q3=B), ci 필드 null 처리 | ✅ **S7-T2** |
| **NICE 무결성 검증** | HMAC-SHA256 서명 검증 → DataIntegrityException | ✅ **S7-T2** |
| **NICE 토큰/세션 Redis 격리** | 키 네임스페이스 분리, TTL 강제 | ✅ **S7-T2** |

---

## Flyway 마이그레이션 현황

### IdO (ido 스키마) — 최신: V10

| 버전 | 내용 |
|------|------|
| V1 | agency_meta, handoff_ticket |
| V2~V6 | outbox, keycloak, oidc_session, broker_audit_log |
| V7~V8 | maintenance, webhook_dispatch_outbox |
| **V9** | `crypto_key_registry`, agency_rate_limit_config ★Sprint 5 활용 |
| **V10** | auth_result 4컬럼 추가, provider_circuit_config |

> **V11 예정 (S7-T6)**: NICE/OACX 본인인증 감사 로그 테이블 또는 별도 Redis 보존 전략. 현재 stateless 설계로 DB 추가 없음.

---

## 테스트 현황

### 모듈별 테스트 수 (2026-05-10 기준)

| 모듈 | 테스트 파일 | 테스트 케이스 | 주요 내용 |
|------|-----------|-------------|---------|
| `ido` | 10개 | **202개** | HandoffServiceImpl 18, WebhookDispatcher 15, OutboxRelay 18, HandoffCryptoService 51, InternalSigVerifier 11, MemberLookup 17, AesDecryptor 12, CallbackUrlValidator 28, **NiceCryptoUtilTest 17 ★S7-T2**, **AuthServiceTest 15 ★S7-T2** |
| `platform-common` | 2개 | **59개** | UuidV7Test 27, (기타 32) |
| `q-sign` | 1개 | **23개** | InternalSigVerifier 11, (기타 12) |
| `q-im` | 5개 | **113개** | CiCryptoService, IdentityService, AuditLog 등 |
| `agency-stub` | 3개 | **0개** | ⚠️ Sprint 6 목표 (구현만 완료) |
| **합계** | **21개** | **397개** | — |

### S7-T2 신규 테스트 상세 (32개)

#### `NiceCryptoUtilTest` (17개)

| 테스트 그룹 | 건수 | 검증 내용 |
|-----------|------|---------|
| `deriveKey` | 4 | 결정론적 출력, 다른 입력 → 다른 키, null 처리, 다양한 iterators |
| `hmacSha256Base64Url` | 2 | 정상 HMAC 생성, 다른 키 → 다른 HMAC |
| `extractAesKey` | 3 | 0~31바이트 추출, 짧은 키 처리, 정확한 길이 검증 |
| `extractHmacKey` | 2 | 48~79 인덱스 추출, 짧은 키 처리 |
| `aesGcmDecrypt` | 3 | 잘못된 Base64 예외, 짧은 암호문 예외 |
| 통합 | 3 | HMAC 무결성 검증, 복호화 통합 플로우, 다양한 iterators 비교 |

#### `AuthServiceTest` (15개)

| 테스트 그룹 | 건수 | 핵심 검증 |
|-----------|------|---------|
| `CallbackTest` | 3 | 정상 콜백 처리, 통합인증 서버 오류, Base64 디코딩 |
| `OacxEasysignTest` | 5 | **CI=null 검증 (Q3=B)**, PASS provider `userNm`/`phoneNo` 키 통일, fn 검증 실패, resultCode 검증 실패, JWT 복호화 실패 |
| `CheckNiceCiTest` | 6 | 개인회원 성공, 기업회원 성공, ci 누락, mbrDvsnCd 누락, 기업회원 bizno 누락, 잘못된 mbrDvsnCd |
| `GetOacxAccessInfoTest` | 1 | 정상 접근정보 발급 |

### 커버리지 목표 vs 현황

```
ido              ████████████████░░░░  ~68% (auth 신규 커버 포함, 통합테스트 미완)
platform-common  ████████████████████  ~85% (UUID v7, 공통 유틸)
q-sign           ████████████░░░░░░░░  ~50% (InternalSig 커버, OIDC 플로우 미완)
q-im             ████████████████░░░░  ~70% (암호화·식별 커버)
agency-stub      ░░░░░░░░░░░░░░░░░░░░   0%  (단위 테스트 미작성)
```

---

## 모니터링 인프라

### 기동 방법

```bash
# 기본 인프라 + 모니터링 스택 함께 기동
docker compose -f infra/docker/docker-compose.yml \
               -f infra/docker/docker-compose.monitoring.yml up -d

# 모니터링만 별도 기동 (기본 인프라 실행 중인 경우)
docker compose -f infra/docker/docker-compose.monitoring.yml up -d
```

### 구성 서비스

| 서비스 | URL | 계정 | 역할 |
|--------|-----|------|------|
| **Prometheus** | http://localhost:9090 | — | 메트릭 수집 (30일 보존) |
| **Grafana** | http://localhost:3000 | admin / onepass-admin | 대시보드 + 알림 |
| **Loki** | http://localhost:3100 | — | 로그 집계 (31일 보존) |
| Redis Exporter | :9121 | — | Redis 메트릭 |
| Kafka Exporter | :9308 | — | Consumer Lag |
| PG Exporter | :9187 | — | PostgreSQL 메트릭 |

### Grafana 대시보드 패널 구성

`infra/monitoring/grafana/dashboards/onepass-overview.json` — 자동 프로비저닝

| Row | 패널 |
|-----|------|
| 서비스 가용성 | q-sign / q-im / ido / agency-stub UP/DOWN Stat |
| Handoff 티켓 | 처리량 & 오류율, P50/P95/P99 응답시간 |
| AES 키 로테이션 | 암호화 오류 수, encrypt/decrypt 처리량 |
| Rate Limit | 429 응답 추이, 서비스별 처리량 |
| Webhook Relay | 성공/실패/DLQ 추이 |
| 인프라 메트릭 | Redis 메모리, Kafka Lag, JVM Heap |
| 감사 로그 (Loki) | AUDIT 레벨 / ERROR 로그 |

---

## 빠른 시작

### 필수 소프트웨어

| 소프트웨어 | 최소 버전 |
|-----------|---------|
| JDK | **21 LTS** |
| Docker Desktop | **24+** |
| Docker Compose | **v2** (플러그인) |
| Node.js | **20.14 LTS** |

### 기동 절차

```bash
# 1. 저장소 복제
git clone https://github.com/HipsterMIN/integration-sso.git
cd integration-sso && chmod +x gradlew

# 2. 인프라 기동
docker compose -f infra/docker/docker-compose.yml up -d

# 3. 모니터링 스택 기동 (선택)
docker compose -f infra/docker/docker-compose.monitoring.yml up -d

# 4. 백엔드 전체 빌드
./gradlew build -x test

# 5. 전체 테스트 실행 (397개)
./gradlew test

# 5-1. auth 모듈 테스트만 실행 (32개)
./gradlew :ido:test --tests "kr.go.smes.ido.auth.*"

# 6. 서비스 기동 (터미널 4개)
./gradlew :q-sign:bootRun      # :8081
./gradlew :q-im:bootRun        # :8082
./gradlew :ido:bootRun         # :8083
./gradlew :agency-stub:bootRun # :8084

# 7. 프론트엔드
cd onepass-fe/frontend && yarn install && yarn dev  # :3000
```

### NICE/OACX 로컬 테스트 설정

```bash
# NICE 인증 테스트 (NICE 개발계정 필요)
export NICE_CLIENT_ID=<NICE 계약 clientId>
export NICE_CLIENT_SECRET=<NICE 계약 clientSecret>
export NICE_RETURN_URL=http://localhost:3000/otp/auth-result

# OACX 간편서명 테스트 (OACX provider key JSON 필요)
export OACX_PROVIDER_KEY_PATH=/path/to/oacx-provider-key.json
export OACX_DEBUG_MODE=true

# 통합인증 서버 (mock 서버 또는 실제 개발 서버)
export INTEGRATION_AUTH_BASE_URL=http://localhost:9292

# ido 기동
./gradlew :ido:bootRun --args='--spring.profiles.active=local'
```

---

## 접속 URL

| 서비스 | URL | 비고 |
|--------|-----|------|
| Q-Sign | http://localhost:8081 | 인증 SoR |
| Q-IM | http://localhost:8082 | 식별 SoR |
| IdO | http://localhost:8083 | 오케스트레이터 + FE BFF |
| **IdO Auth API** | **http://localhost:8083/api/v1/auth/** | **본인인증 BFF ★S7-T2** |
| agency-stub | http://localhost:8084 | 기관 시뮬레이터 |
| onepass-fe | http://localhost:3000 | React SPA |
| Keycloak | http://localhost:8088 | OIDC IdP (admin/admin) |
| Kafka UI | http://localhost:8090 | 토픽·메시지 조회 |
| Prometheus | http://localhost:9090 | 메트릭 |
| **Grafana** | **http://localhost:3000** | **대시보드 (admin/onepass-admin)** |
| Loki | http://localhost:3100 | 로그 집계 |
| pgAdmin | http://localhost:5050 | PostgreSQL 관리 |
| Redis Insight | http://localhost:5540 | Redis 관리 |

> ⚠️ Grafana와 onepass-fe 개발 서버가 동일한 포트(3000)를 사용합니다.  
> 동시 기동 시 Grafana는 `docker-compose.monitoring.yml`로 별도 포트 조정 필요.

---

## 개발 환경 설정

### 필수 환경변수

```bash
# IdO 암호화 / 서명 키 (32바이트 Base64, 운영 교체 필수)
IDO_HANDOFF_AES_KEY=<base64-32bytes>
IDO_HANDOFF_HMAC_SECRET=<base64-32bytes>
IDO_INTERNAL_SIG_SECRET=<32bytes+>

# Q-IM CI 암호화
QIM_CI_AES_KEY_V1=<base64-32bytes>
QIM_DI_SECRET=<32bytes+>

# Q-Sign Keycloak
QSIGN_KEYCLOAK_CLIENT_SECRET=<Keycloak Admin에서 발급>

# 공통 인프라
DB_HOST=localhost  DB_PORT=5432  DB_NAME=onepass
REDIS_HOST=localhost  KAFKA_SERVERS=localhost:9092

# ★S7-T2 신규: NICE/OACX 본인인증
NICE_CLIENT_ID=<NICE 계약 clientId>
NICE_CLIENT_SECRET=<NICE 계약 clientSecret>
NICE_RETURN_URL=https://www.smes.go.kr/otp/auth-result
OACX_PROVIDER_KEY_PATH=/etc/oacx/provider-key.json
INTEGRATION_AUTH_BASE_URL=https://auth.integration.smes.go.kr

# 모니터링 (선택)
GF_SECRET_KEY=<32bytes+ Grafana 시크릿>
```

---

## 전체 로드맵 & 개발 플랜

### Sprint 7 (현재 진행 중) — 본인인증 BFF 완성

| ID | 항목 | 우선순위 | 상태 | 예상 공수 |
|----|------|---------|------|---------|
| **S7-T2** | **ido NICE/OACX 본인인증 이식** | 🔴 CRITICAL | ✅ **완료** | 3일 |
| S7-T5 | FE 마이페이지 정보수정 4개 파일 onepass-fe 통합 | 🟠 HIGH | 🔲 미시작 | 2일 |
| S7-T6 | ImApiOutPort 구현 — CI → Q-IM 저장 (AuthService/NiceAuthService TODO) | 🟠 HIGH | 🔲 미시작 | 1일 |

#### S7-T6 상세: CI → IM API 연동 (다음 즉시 작업)

현재 `AuthService.checkNiceCi()`, `NiceAuthService.getNicePhoneAuthResult()`에 `// TODO: S7-T6` 주석으로 표시된 부분. CI를 Q-IM API에 저장하여 실제 회원 조회/등록 플로우를 완성해야 함.

```java
// AuthService.checkNiceCi() 현재 상태
// TODO: S7-T6 — ImApiOutPort 구현 후 실제 IM API 조회/등록 호출
// 현재: 파라미터 검증만 수행, result=true 반환
return CiCheckResponse.success(request.mbrDvsnCd(), ...);

// NiceAuthService.getNicePhoneAuthResult() 현재 상태
// TODO: S7-T6 — CI를 Q-IM에 저장 또는 조회
// 현재: resultData에 ci 미포함(Q3=B), DI만 반환
```

### Sprint 8 (2주) — CI/CD + 부하테스트

| ID | 항목 | 우선순위 | 예상 공수 |
|----|------|---------|---------|
| S8-T1 | GitHub Actions CI/CD (PR 빌드 + 테스트 자동화) | 🟠 HIGH | 1일 |
| S8-T2 | k6 부하 테스트 스크립트 완성 (Rate Limiter + auth 엔드포인트 검증) | 🟡 MED | 1.5일 |
| S8-T3 | OWASP Dependency-Check Gradle 플러그인 | 🟡 MED | 0.5일 |
| S8-T4 | Testcontainers 기반 통합 테스트 (DB + Redis + NICE mock) | 🟡 MED | 2일 |
| S8-T5 | SonarQube 연동 (정적 분석) | 🟢 LOW | 1일 |
| S8-T6 | Docker 멀티스테이지 빌드 최적화 | 🟢 LOW | 1일 |
| S8-T7 | Grafana 알림 채널 설정 (Slack/이메일) | 🟢 LOW | 0.5일 |

### Sprint 9 (2주) — 프로덕션 강화

| ID | 항목 | 우선순위 | 예상 공수 |
|----|------|---------|---------|
| S9-T1 | **분산 락 (Redisson)** — NiceTokenStore ensureAccessToken() 다중 Pod 대응 | 🔴 CRITICAL | 1일 |
| S9-T2 | **Resilience4j** — NICE/OACX/통합인증 클라이언트 CB+Retry 적용 | 🔴 CRITICAL | 1.5일 |
| S9-T3 | **Bean Validation** — AuthController `@Valid` + DTO `@NotNull/@Size` | 🟠 HIGH | 0.5일 |
| S9-T4 | **OTel 추적** — auth 관련 스팬 추가 (NICE 호출, OACX SDK, CI 처리) | 🟠 HIGH | 1일 |
| S9-T5 | **감사 로그** — 본인인증 성공/실패 이벤트 `platform.audit.log` 발행 | 🟠 HIGH | 1일 |
| S9-T6 | **K8s Secret 관리** — NICE_CLIENT_SECRET 등 Vault/K8s Secret 연동 | 🟠 HIGH | 1일 |
| S9-T7 | Rate Limiting — auth 엔드포인트 남용 방지 (기존 ido rate-limit 설정 활용) | 🟡 MED | 0.5일 |
| S9-T8 | KMS 연동 — KeyVersionRegistry key_material_encrypted 실제 KMS로 대체 | 🟡 MED | 2일 |

---

## 상용 서비스 관점 품질 분석

> **비교 기준**: 토스(Toss), 카카오(Kakao), PASS(SKT/KT/LGU+), Keycloak 25, Spring Authorization Server 1.3, NIST SP 800-63B, OWASP ASVS Level 2

### 1. 본인인증(S7-T2) 상용 수준 GAP 분석

#### ✅ 잘 된 부분

| 항목 | 현황 | 상용 기준 대비 |
|------|------|-------------|
| **CI PII 보호** | FE 미반환, 백엔드 전용 처리 (Q3=B) | 카카오/네이버도 동일 패턴. `@JsonInclude(NON_NULL)` 직렬화 제어 |
| **Redis TTL 관리** | NiceTokenStore 60초 여유, NiceAuthSessionStore 10분 | 토스 10분 세션, 카카오 10분 — 동일 수준 |
| **PBKDF2+AES-GCM 스펙 준수** | NICE IDO 스펙 정확 이식 (키 인덱스 분리) | NICE 공식 스펙 준수 |
| **OACX provider 키 통일** | PASS vs naver/toss/dream provider 키 차이 처리 | 실제 운영 SDK와 동일 처리 |
| **단위 테스트 32개** | NiceCryptoUtilTest 17 + AuthServiceTest 15 | CI=null 검증, PASS 키 통일 검증 포함 |

#### ⚠️ 상용 서비스 대비 미진한 부분 (우선순위순)

---

**[P0 — 즉시] 분산 락 미적용: `synchronized` → Redisson**

```java
// 현재: NiceAuthService.java
// ❌ 단일 JVM synchronized — K8s 다중 Pod에서 경쟁 조건 발생 가능
private synchronized NiceTokenSnapshot ensureAccessToken(String requestNo) {
    NiceTokenStore.NiceTokenSnapshot snapshot = niceTokenStore.get();
    if (snapshot != null && snapshot.isValid()) return snapshot;
    // ... NICE Access Token 재발급
}
```

**문제**: K8s 환경에서 2개 이상의 ido Pod가 동시에 토큰 만료를 감지하면, NICE 서버에 동시 다발적 토큰 발급 요청이 발생. NICE 서버 rate limit 초과 위험.

**상용 수준 해결책** (Redisson):
```java
// ✅ 목표: Redisson 분산 락 적용 (S9-T1)
private NiceTokenSnapshot ensureAccessToken(String requestNo) {
    RLock lock = redissonClient.getLock("ido:lock:nice-token-refresh");
    try {
        if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {  // 3초 대기, 10초 만료
            NiceTokenStore.NiceTokenSnapshot snapshot = niceTokenStore.get();
            if (snapshot != null && snapshot.isValid()) return snapshot;
            return refreshAccessToken(requestNo);
        }
    } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    throw new RuntimeException("토큰 갱신 락 획득 실패");
}
```

---

**[P0 — 즉시] Resilience4j 미적용: NICE/OACX/통합인증 클라이언트**

```
현재 상태:
├── qim-client:       Resilience4j CB + Retry ✅ (기존 적용)
├── keycloak-client:  Resilience4j CB + Retry ✅ (기존 적용)
├── NiceApiClient:    ❌ 미적용 — NICE 서버 장애 시 무한 대기
├── OacxClient:       ❌ 미적용 — OACX SDK 블로킹 호출, 타임아웃 없음
└── IntegrationAuthClient: ❌ 미적용 — 통합인증 서버 장애 전파
```

**문제**: NICE 서버 장애 또는 지연 시 WebClient 타임아웃만으로는 불충분. 연속 실패 시 Open 상태로 빠르게 전환하여 장애 격리 필요.

**상용 수준 해결책** (S9-T2):
```yaml
# application.yml에 추가
resilience4j:
  circuitbreaker:
    instances:
      niceApiClient:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
      oacxClient:
        slidingWindowSize: 5
        failureRateThreshold: 60
        waitDurationInOpenState: 60s
  retry:
    instances:
      niceApiClient:
        maxAttempts: 2
        waitDuration: 500ms
        retryExceptions:
          - org.springframework.web.reactive.function.client.WebClientResponseException
```

---

**[P1 — 중요] Bean Validation 미적용**

```java
// 현재: AuthController.java
// ❌ @Valid 없음 — 잘못된 요청이 서비스 레이어까지 도달
@PostMapping("/nice/phone/result")
public NicePhoneAuthResultResponse getNicePhoneAuthResult(
        @RequestBody NicePhoneAuthResultRequest request) {
    return niceAuthService.getNicePhoneAuthResult(request);
}

// ❌ NicePhoneAuthResultRequest.java — 검증 어노테이션 없음
public record NicePhoneAuthResultRequest(
    String webTransactionId,  // null 허용 — 서비스 레이어에서 수동 검증
    String requestNo
) {}
```

**문제**: 현재 서비스 레이어에서 수동으로 null 체크. 토스/카카오/네이버 모두 DTO에 Bean Validation 어노테이션 적용 후 `@Valid`로 컨트롤러 레이어에서 일괄 검증.

**상용 수준 해결책** (S9-T3):
```java
// ✅ 목표 DTO
public record NicePhoneAuthResultRequest(
    @NotBlank(message = "web_transaction_id는 필수입니다")
    @Size(max = 64)
    String webTransactionId,

    @NotBlank(message = "request_no는 필수입니다")
    @Size(max = 64)
    String requestNo
) {}

// ✅ 목표 컨트롤러
@PostMapping("/nice/phone/result")
public NicePhoneAuthResultResponse getNicePhoneAuthResult(
        @Valid @RequestBody NicePhoneAuthResultRequest request) { ... }
```

---

**[P1 — 중요] Rate Limiting — 인증 엔드포인트 남용 방지**

```
현재 Rate Limit:
- 기관 API (Handoff): Redis Lua 슬라이딩 윈도우 ✅
- 본인인증 API (/api/v1/auth/**): ❌ 미적용
```

**문제**: 인증 API는 NICE/OACX 외부 서비스 호출을 유발하므로 무제한 요청 시 NICE 서버 rate limit 초과 + 비용 증가 위험. 실명인증 API는 악용 시 개인정보 열거 공격(Enumeration Attack) 가능.

**상용 수준 해결책** (S9-T7): 기존 `AgencyRateLimiter`(Redis Lua) 확장 또는 Bucket4j 적용:
```yaml
# 인증 엔드포인트별 별도 rate limit
ido:
  auth:
    rate-limit:
      nice-url-per-ip: 5/분        # NICE URL 발급 — IP당 5회/분
      nice-result-per-session: 3/회 # NICE 결과 조회 — 세션당 3회
      oacx-per-ip: 10/분           # OACX 접근정보 — IP당 10회/분
```

---

**[P1 — 중요] OTel 추적 스팬 미추가**

```
현재: TraceparentFilter로 W3C traceparent 전파만 구현 ✅
문제: NICE API 호출, OACX SDK 호출, CI 처리 등 auth 플로우 내부 스팬 없음
```

**상용 수준 해결책** (S9-T4):
```java
// ✅ 목표: Micrometer Tracing + OTel
@Observed(name = "nice.phone.auth.url", contextualName = "NICE 인증 URL 발급")
public NicePhoneAuthUrlResponse getNicePhoneAuthUrl(String returnUrl) { ... }

@Observed(name = "nice.phone.auth.result", contextualName = "NICE 인증 결과 복호화")
public NicePhoneAuthResultResponse getNicePhoneAuthResult(NicePhoneAuthResultRequest req) { ... }
```

**기대 효과**: Grafana Tempo/Jaeger에서 전체 요청 플로우 (FE → ido → NICE API → Redis → 복호화) 추적 가능.

---

**[P1 — 중요] 감사 로그(Audit Log) 미발행**

```
현재: 본인인증 성공/실패 이벤트 platform.audit.log 토픽 미발행
문제: 개인정보보호법 §29 안전조치 의무 — 접근 로그 기록 필요
```

**법적 요구사항**: 개인정보보호법 §29, 전자서명법 §18, ISMS-P A.3.1.1. 본인인증 처리 로그(성공/실패, 요청자 IP, 타임스탬프)는 최소 6개월 보관 필요.

**상용 수준 해결책** (S9-T5):
```java
// ✅ 목표: 인증 감사 이벤트 발행 (CI 원문 미포함 필수)
AuditLogEvent event = AuditLogEvent.builder()
    .eventType("NICE_PHONE_AUTH_SUCCESS")
    .requestNo(requestNo)           // NICE 요청번호 (CI 대체 식별자)
    .diHash(DigestUtils.sha256Hex(di))  // DI 해시 (원문 아님)
    .clientIp(requestIp)
    .timestamp(Instant.now())
    .build();
kafkaTemplate.send("platform.audit.log", event);
```

---

**[P2 — 보통] K8s Secret 관리**

```
현재: NICE_CLIENT_SECRET 등 민감정보 환경변수 직접 주입
문제: K8s Secret 암호화 없이 etcd에 Base64 평문 저장 위험
```

**상용 수준 목표** (S9-T6):
```yaml
# K8s Secret (Sealed Secrets 또는 External Secrets Operator 활용)
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: SecretStore
    name: vault-backend
  target:
    name: ido-auth-secrets
  data:
    - secretKey: NICE_CLIENT_SECRET
      remoteRef:
        key: secret/onepass/nice
        property: clientSecret
```

---

**[P2 — 보통] 통합 테스트 0개 (MockMvc + Testcontainers)**

```
현재:
- 단위 테스트: 397개 (Mockito 기반)
- 통합 테스트: 0개
문제: NiceApiClient가 실제 Redis와 올바르게 동작하는지, CORS가 실제로 동작하는지 미검증
```

**상용 수준 해결책** (S8-T4):
```java
// ✅ 목표: Testcontainers 기반 auth 통합 테스트
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class NiceAuthIntegrationTest {
    @Container
    static RedisContainer redis = new RedisContainer("redis:7.2-alpine");

    @MockBean  // NICE 외부 API는 WireMock으로 대체
    NiceApiClient niceApiClient;

    @Test
    void nicePhoneAuthUrl_shouldStoreSessionInRedis() {
        // NICE API mock → URL 발급 → Redis 세션 저장 → 검증
    }

    @Test
    void nicePhoneAuthResult_shouldExpireAfter10Minutes() {
        // TTL 10분 검증 (Testcontainers Redis 직접 확인)
    }
}
```

---

**[P3 — 낮음] OacxClient 블로킹 SDK 호출**

```java
// 현재: OacxClient.java
// ⚠️ OACX SDK는 동기 블로킹 호출 — 스레드 풀 고갈 위험 (고부하 시)
public OacxAccessInfoResponse getAccessInfo(String fn) {
    OacxUtil oacxUtil = new OacxUtil();
    oacxUtil.loadJSONInfo(providerKeyPath);
    return oacxUtil.getAccessInfo(fn);  // 블로킹
}
```

**문제**: OACX SDK가 Maven Central 미등록 JAR로 내부 구현 확인 불가. 만약 SDK 내부에 네트워크 I/O가 있다면 WebFlux 스레드 모델에서 위험. 현재 Tomcat 서블릿 스레드 사용이므로 허용 범위이나, 고부하 시 주의.

**상용 수준 고려**: OACX SDK 동기 호출을 `@Async` + `CompletableFuture` 또는 별도 스레드풀로 격리.

---

### 2. 전체 아키텍처 수준 평가 (S7-T2 이후 업데이트)

#### ✅ 잘 된 부분

| 항목 | 평가 | 근거 |
|------|------|------|
| **SoR 책임 분리** | ⭐⭐⭐⭐⭐ | Q-Sign/Q-IM/IdO 3축 분리는 Keycloak의 단일 서버 모델보다 확장성 우월 |
| **Outbox 패턴** | ⭐⭐⭐⭐☆ | Transactional Outbox → Kafka 이중 쓰기 문제 방지. 상용 수준 |
| **AES 키 버전 로테이션** | ⭐⭐⭐⭐☆ | `v{n}.{iv}.{ct}` 포맷 + 3계층 캐시는 HashiCorp Vault Transit Engine 설계와 유사 |
| **Rate Limiter (Redis Lua)** | ⭐⭐⭐⭐☆ | 슬라이딩 윈도우 Lua 스크립트는 Stripe/GitHub API 수준의 정밀도 |
| **EDA 토픽 설계** | ⭐⭐⭐⭐☆ | DLQ 분리, Compacted 토픽, 보존 기간 차별화 — 상용 패턴 적용 |
| **NICE HMAC 무결성 검증** | ⭐⭐⭐⭐⭐ | NICE IDO 스펙 정확 준수, DataIntegrityException 명시적 분리 |
| **CI PII 보호 설계** | ⭐⭐⭐⭐⭐ | Q3=B 결정 + `@JsonInclude(NON_NULL)` — 개인정보보호 원칙 준수 |

#### ⚠️ 개선 필요 사항

**[가장 시급] 운영 키 관리 아키텍처 — KMS 미연동**

```java
// ❌ KeyVersionRegistry — DB key_material_encrypted 컬럼에 키 재료 직접 저장
// 운영 환경에서 KMS 없이 사용 시 DB 유출 = 암호화 무력화
"[ENCRYPTED_BY_KMS:" + keyBase64.substring(0, 8) + "...]"
```

상용 수준 목표 (HashiCorp Vault Transit / AWS KMS):
```
애플리케이션 → Vault Transit API → 봉인된(envelope) 키 재료 반환
DB에는 키 재료 절대 미저장 — KMS가 유일한 키 소지자
```

**권고사항**: `key_material_encrypted` 컬럼을 실제 KMS envelope encryption으로 대체. Vault Java SDK 또는 AWS KMS SDK 연동이 프로덕션 Go-Live 조건.

---

**[중요] 테스트 전략 — 상용 프레임워크 대비**

| 구분 | 현황 | Spring Authorization Server 기준 | 목표 |
|------|------|----------------------------------|------|
| 단위 테스트 | 397개 (21파일) | 2,000개+ | 600개+ |
| 통합 테스트 | 0개 | Testcontainers 100개+ | 50개+ |
| E2E 테스트 | 수동 agency-stub | Playwright/Selenium | 자동화 필요 |
| 커버리지 | ~58% | ~80% | 70%+ |
| 뮤테이션 테스트 | 없음 | PIT Mutation 적용 | 선택적 적용 |

---

**[중요] 관측 가능성(Observability) — SRE 관점**

현재 Micrometer 메트릭은 `AgencyRateLimiter`에만 적용. 상용 수준을 위해 필요한 핵심 메트릭:

```java
// 필요한 비즈니스 메트릭 (Micrometer Counter/Timer)
handoff.issue.total{result="success"|"policy_fail"|"rate_limit"}
handoff.verify.total{result="success"|"expired"|"tampered"}
auth.nice.phone.url.total{result="success"|"token_error"}       // ★S7-T2 후속
auth.nice.phone.result.total{result="success"|"integrity_fail"|"decrypt_fail"}
auth.oacx.easysign.total{result="success"|"fn_error"|"decrypt_fail"}
webhook.dispatch.total{status="success"|"failed"|"dlq"}
qim.ci.lookup.duration_seconds{result="hit"|"miss"}
```

---

**[중요] 분산 환경 키 캐시 무효화**

```java
// KeyVersionRegistry.evictCache() — 인메모리 캐시만 초기화
// 문제: 다중 IdO 인스턴스 환경에서 다른 인스턴스의 캐시는 무효화 안 됨
```

**권고**: Redis Pub/Sub으로 캐시 무효화 이벤트 브로드캐스트:
```java
// 로테이션 완료 후
redisTemplate.convertAndSend("ido:crypto:cache-evict", newVersion);
// 각 인스턴스 @EventListener로 evictCache() 호출
```

---

**[FE] TypeScript 타입 안전성**

현재 `api/client.ts`의 제네릭 사용이 일부 `any` 타입으로 처리됨. 상용 수준 FE(카카오, 토스)는 Zod schema validation + strict TypeScript(`strict: true`)를 사용.

**권고**: API 응답 타입에 Zod 스키마 도입:
```typescript
// docs/api-auth-spec.md에 TypeScript 타입 정의 포함
// 이를 Zod 스키마로 변환하여 런타임 검증 추가
import { z } from 'zod';
const NicePhoneAuthResultDataSchema = z.object({
  name: z.string(),
  birthdate: z.string(),
  gender: z.string(),
  nationalInfo: z.string(),
  di: z.string(),
  mobileCo: z.string(),
  mobileNo: z.string(),
  // ci: 없음 — Q3=B 보안 정책
});
```

---

### 3. 종합 상용화 준비도 평가 (S7-T2 반영)

| 항목 | 현재 수준 | 상용 기준 | 갭 |
|------|----------|---------|-----|
| **보안 핵심** | ⭐⭐⭐⭐☆ | ⭐⭐⭐⭐⭐ | KMS 연동, mTLS, auth 분산 락 |
| **가용성** | ⭐⭐⭐☆☆ | ⭐⭐⭐⭐⭐ | auth 분산 락, CB+Retry, 다중 인스턴스 캐시 무효화 |
| **관측 가능성** | ⭐⭐⭐☆☆ | ⭐⭐⭐⭐⭐ | auth 메트릭 미등록, OTel 스팬 미추가 |
| **입력 검증** | ⭐⭐☆☆☆ | ⭐⭐⭐⭐⭐ | Bean Validation 미적용, auth 엔드포인트 rate limit 없음 |
| **테스트** | ⭐⭐⭐☆☆ | ⭐⭐⭐⭐⭐ | 통합 테스트 0개, 커버리지 58% |
| **감사/컴플라이언스** | ⭐⭐⭐☆☆ | ⭐⭐⭐⭐⭐ | 본인인증 감사 로그 미발행, K8s Secret 미관리 |
| **배포 자동화** | ⭐⭐☆☆☆ | ⭐⭐⭐⭐⭐ | CI/CD 없음, 수동 배포 |
| **아키텍처 품질** | ⭐⭐⭐⭐☆ | ⭐⭐⭐⭐⭐ | 전략 패턴, Outbox, EDA 상용 수준 |

**총평**: 아키텍처 설계, PII 보호 원칙, NICE/OACX 암호화 구현은 상용 수준에 근접.  
프로덕션 Go-Live를 위한 **3대 필수 과제**: ① 분산 락 + CB+Retry (S9-T1, S9-T2), ② 감사 로그 + K8s Secret (S9-T5, S9-T6), ③ CI/CD + 통합 테스트 (S8-T1, S8-T4).

---

## 코딩 컨벤션

- **패키지**: `kr.go.smes.{module}` (예: `kr.go.smes.ido.auth.service`)
- **에러코드**: `PlatformErrorCode` 열거형 사용 — 하드코딩 문자열 금지
- **PII 보호**: CI 원문, 개인명 원문, 전화번호 원문 로그 **절대 금지**
- **CI 반환 금지**: FE 응답에 CI 필드 포함 금지 (`@JsonInclude(NON_NULL)` + null 설정)
- **감사 로그**: `platform.audit.log` 토픽 — PII 원문 미포함 JSON만 허용
- **Outbox 패턴**: 모든 Kafka 게시는 Transactional Outbox 경유
- **상수시간 비교**: 서명·해시 비교는 `MessageDigestUtil.safeEquals()` 사용
- **암호화 출력**: Handoff 티켓은 항상 버전 접두사 `v{n}.{iv}.{ct}` 형식 사용
- **UUID**: 비즈니스 ID는 반드시 `UuidV7.generate()` 사용 (UUID v4 금지)
- **WebClient**: 블로킹 컨텍스트에서 `block()` 사용 금지 — Mono 반환 또는 `subscribeOn(Schedulers.boundedElastic())`
- **OACX SDK**: 매 요청마다 `OacxUtil` 신규 생성 + `loadJSONInfo()` 호출 (SDK 설계상 stateless 아님)
- **환경변수**: 민감 정보는 환경변수로 주입, 코드/YAML 하드코딩 절대 금지

---

## 문서 디렉토리

```
docs/
├── api-auth-spec.md                 # ★S7-T2 신규: FE 팀 대상 본인인증 API 명세
│                                    #   (NICE/OACX 6개 엔드포인트 + TypeScript 타입 + 플로우 다이어그램)
├── spec/                            # 정밀 분석 기반 기술 명세 (v1.9.3)
│   ├── 00-index.md                  # 전체 조감도 + 빠른 참조 카드
│   ├── 01~09-*.md                   # 시스템·아키텍처·모듈·API·DB·Kafka·보안
│   └── 09-gap-and-roadmap.md        # 미구현 현황·Sprint 계획·기술 부채
│
├── development/                     # 개발 진행 상태 문서
│   ├── 12-implementation-gaps.md    # 미구현 항목 우선순위별 상세
│   └── 13-development-history.md    # v1.0~v2.1.0 버전별 변경 이력
│
├── onepass-be-integration-plan.md   # onepass-be → ido 통합 플랜 (Option A 기준 재작성 필요)
├── 2026-05-09_v194_gap_implementation_plan.md  # Sprint 1~5 상세 플랜
└── 2026-05-08_production_development_plan.md   # 운영 전환 계획
```

---

*GitHub: [HipsterMIN/integration-sso](https://github.com/HipsterMIN/integration-sso)*  
*PR #45 (OPEN): [Sprint 7 S7-T2 — NICE/OACX 본인인증 ido BFF 통합](https://github.com/HipsterMIN/integration-sso/pull/45)*
