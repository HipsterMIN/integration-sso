# Phase 1 — SSO/IM 전체 아키텍처 정찰

> **목적**: 운영 적합성 검증의 전제로, 현재 시스템의 진짜 모양을 코드 레벨에서 다시 본다.
> 문서/다이어그램에 적힌 모습과 실제 코드가 일치하는지부터 시작한다.

---

## 1. 모듈 사이즈 (Java 소스 기준)

| 모듈 | 책임 (의도) | 파일 수 | LOC | DB | 외부 의존 |
|------|------------|---------|-----|-----|----------|
| **q-sign** | 인증 SoR (Authentication) | 40 | 4,427 | PostgreSQL `qsign` 스키마 | Keycloak |
| **q-im** | 식별·매핑 SoR (Identity Mapping) | 87 | 7,470 | MariaDB `qim` | Kafka |
| **ido** | 통합 디지털 오퍼레이터 (Handoff/Broker/Gateway) | **203** | **32,893** | PostgreSQL `ido` 스키마 + MariaDB qim cross-ref | KMS(Vault), NICE CI, OACX, Keycloak, AnyId, Kafka |
| outbox-relay-batch | Outbox 릴레이 | (별도) | 약 1,500 | PostgreSQL `ido` + MariaDB `qim` | Kafka, ShedLock |
| agency-stub | 기관 시뮤레이터 | 별도 | 약 800 | — | — |
| platform-common | 공용 라이브러리 | 별도 | 약 1,000 | — | — |

### 핵심 관찰

**🔴 IdO 비대화**: 32,893 LOC, 203 파일은 q-sign(4,427)/q-im(7,470)의 **총합보다 2배 이상 크다**. SSO/IM 본질 4축 중 3축(세션/핸드오프/개인정보)을 IdO가 다 맡고 있어 구조적 단일 실패점이다.

**🔴 IdO 패키지 23개** (관찰 시점):
```
admin, api, audit, auth, broker, burst, config, conversion, crypto, domain,
ext, fe, gateway, handoff, infrastructure, kafka, memberlookup, metrics,
policy, provision, qim, ratelimit, retention, slo, sso, webhook
```
→ "통합 디지털 오퍼레이터"가 사실상 모놀리식 게이트웨이가 된 상태. **운영 시 한 모듈 변경이 다른 모듈에 미치는 영향 추적이 어려움**.

---

## 2. 데이터베이스 분포 + Flyway 마이그레이션

| 모듈 | DB | 스키마 | Flyway 버전 |
|------|-----|--------|-------------|
| q-sign | PostgreSQL `onepass` | `qsign` | V1 ~ V5 (5개) |
| ido | PostgreSQL `onepass` | `ido` | V1 ~ V19 (**19개**) |
| q-im | MariaDB `qim` | (default) | V1 ~ V7 (7개) |
| outbox-relay-batch | (둘 다 조회 — 쓰기 없음) | `ido` + `qim` | — |

### 핵심 관찰

**🟡 마이그레이션 수 격차**: q-sign 5개 vs ido 19개 — IdO 도메인이 4배 빠르게 변화. 운영 초기 스키마 변경 빈도가 매우 높을 가능성.

**🟡 Cross-DB 접근**: `outbox-relay-batch`가 PostgreSQL과 MariaDB 둘 다 접근. 한 트랜잭션 안에 두 DB가 묶이지는 않는지 후속 Phase에서 확인 필요.

**🔴 prod DB 연결**: `application.yml`의 기본값이 `localhost`/`onepass`/`onepass`로 **개발 친화적**. PR-B4에서 application-prod.yml을 분리했지만 DB 연결 정보는 여전히 환경변수에만 의존. **prod 배포 시 ENV 누락 시 dev DB로 fallback 위험** — 후속 Phase에서 검증.

---

## 3. REST 엔드포인트 인벤토리 (33개 컨트롤러)

### Q-Sign — 5개 컨트롤러 (인증 본질만)
| 컨트롤러 | base path | 엔드포인트 수 | 책임 |
|----------|-----------|---------------|------|
| `AuthController` | `/api/v1/auth` | 3 | OIDC 시작, Broker 입력, 인증 결과 조회 |
| `InternalSessionController` | `/api/v1/internal/session` | 1 | 로그아웃 |
| `OidcDiscoveryController` | `/.well-known/`, `/protocol/openid-connect/*` | 6 | OIDC 표준 엔드포인트 (auth, token, userinfo, certs, logout) |
| `KeycloakAuthUrlController` | `/api/v1/oidc/{provider}/auth-url` | 1 | Keycloak Provider URL 생성 |
| `KeycloakCallbackController` | `/api/v1/oidc/keycloak/callback` | 1 | Keycloak 콜백 |

→ **인증 본질이 깔끔하게 분리됨**. OIDC 표준 엔드포인트도 갖춤. 좋음.

### Q-IM — 9개 컨트롤러 (식별·매핑)
| 컨트롤러 | base path | 엔드포인트 수 | 책임 |
|----------|-----------|---------------|------|
| `UserController` | `/api/v1/internal/users` | **7** | CRUD + DI 조회 + 상태 |
| `ConsentController` | `.../consents` | 4 | 동의 관리 |
| `ConversionController` | `.../conversion/*` | 6 | 본인확인 → 회원 매핑 전환 |
| `MemberLookupController` | `.../member/lookup-by-ci` | 2 | CI 기반 조회 |
| `BizMemberConversionController` | `.../biz-members/convert` | 2 | 사업자 회원 변환 |
| `GuardianConsentController` | `.../guardian` | 2 | 보호자 동의 (미성년) |
| `WithdrawalController` | `.../withdrawal` | 2 | 탈퇴 |
| `QimStatusController` | `/api/v1/users/{qimUserId}` | 1 | 외부 상태 조회 |
| (GlobalExceptionHandler) | — | — | 예외 처리 |

→ Q-IM도 식별·매핑 책임 안에 머무름. 좋음. 다만 `Conversion` 6개 엔드포인트와 `Withdrawal` 2개가 **장기 트랜잭션**(사용자 인터랙션이 포함된 다단계 플로우)이라 후속 Phase에서 세션·idempotency 검증 필요.

### IdO — **19개 컨트롤러** (핸드오프 + 브로커 + 게이트웨이 + 기타)
| 컨트롤러 | base path | 책임 분류 |
|----------|-----------|----------|
| `HandoffController` | `/api/v1/handoff` | **본질 — 핸드오프 발급/검증** |
| `auth/AuthController` | `/api/v1/auth/*` | NICE CI / OACX / Provision (본인확인) |
| `BrokerController` | `/api/v1/broker/*` | OIDC Broker entry |
| `OidcCompleteController` | `/api/internal/v1/oidc/complete` | OIDC 후처리 (internal) |
| `KeycloakCallbackController` (broker) | `/api/v1/broker/callback` | Keycloak Broker 콜백 |
| `AnyIdController` | `/api/v1/anyid/*` | AnyId 통합 인증 (7 endpoints) |
| `NonOidcBrokerController` | `/api/v1/broker/{provider}/nonoidc/*` | Non-OIDC Provider |
| `AgencyAdminController` | `/api/v1/admin/agencies` | 기관 관리 (CRUD + Key 교체) |
| `AgencyEventController` | `/api/v1/agency/events` | 기관용 이벤트 수신 큐 |
| `AgencyGatewayController` | `/api/v1/agency/gateway/*` | 기관 Gateway Inbound/Outbound |
| `ConversionInitController` + `ConversionSessionController` | `/api/v1/conversion/*` | Conversion 세션 |
| `ExtProxyController` | `/api/ext/**` | **외부 Proxy (모든 메서드 와일드카드)** |
| `FeSessionController` | `/api/v1/fe-session/*` | FE 세션 |
| `MemberLookupController` (ido) | `/api/v1/member/lookup` | (q-im과 별도 — IdO 자체 lookup) |
| `QimSpReceiverController` | `/api/qim/sp/v1/*` | Q-IM SP 이벤트 수신 |
| `SloController` | `/api/v1/slo/initiate` | Single Logout |
| `CrossAgencySsoController` | `/api/v1/agency/cast/*` | Cross-Agency SSO Cast |

#### 🔴 우려 사항 — IdO

1. **`ExtProxyController`가 `/api/ext/**` 와일드카드로 모든 메서드 프록시** — 보안적으로 큰 잠재 위험. 후속 Phase에서 권한 검증/대상 화이트리스트 확인 필요.
2. **OIDC Broker 콜백이 두 군데(q-sign / ido)에 모두 존재** — `KeycloakCallbackController`가 q-sign(`/api/v1/oidc/keycloak/callback`)과 ido(`/api/v1/broker/callback`) 양쪽에 있다. **어느 콜백이 어떤 시나리오에 사용되는가, 둘 다 정말 필요한가**가 핵심 질문.
3. **AnyId 컨트롤러에 endpoint 7개** — `/initiate`, `/callback`, `/ssob`, `/oidc/ssoLogin`, `/config`, `/txId`, 그리고 base path 없는 `/ssob` — base path 충돌 / 이중 매핑 가능성 확인 필요.
4. **MemberLookup이 ido와 q-im 양쪽에 있음** — `/api/v1/member/lookup` (ido) vs `/api/v1/internal/member/lookup-by-ci` (q-im). **누가 진짜 진실의 원천인가**가 후속 Phase의 핵심 질문.

---

## 4. Kafka 의존성 (Producer / Consumer)

### Producer (이벤트 발행)
| 모듈 | 클래스 | 이벤트 |
|------|--------|--------|
| q-sign | `AuthServiceImpl` | 인증 결과 이벤트 |
| q-im | `OutboxServiceImpl`, `SnapshotServiceImpl` | 회원 상태 변경, 스냅샷 |
| ido | `AuditLogPublisher`, `SessionAdvisoryPublisher`, `IdoOutboxRelay`, `QimOutboxRelay`, `HandoffServiceImpl` 등 | 감사 로그, 세션 자문, Outbox 릴레이, Handoff 이벤트 |

### Consumer (이벤트 구독)
| 모듈 | 클래스 | 토픽 (추정) |
|------|--------|------|
| q-sign | `QimUserEventConsumer` | q-im → q-sign 사용자 변경 |
| q-im | `KafkaConsumerConfig` (별도 Listener) | (TBD) |
| ido | `HandoffEventConsumer`, `QimEventConsumer`, `QsignAuthEventConsumer`, `QimSpMemberEventConsumer`, `FeAdvisoryConsumer` | 핸드오프/q-im/q-sign/qim-sp/FE Advisory |

### 핵심 관찰

**🟡 이벤트 흐름이 양방향**:
- q-im → q-sign (사용자 변경)
- q-im → ido (회원 매핑 변경)
- q-sign → ido (인증 결과)
- ido → ido (자기 자신, Outbox)

순환은 없으나 **q-im이 두 곳에 동시에 영향**을 주는 hub 역할. q-im에서 발행한 이벤트가 q-sign 와 ido 양쪽 다 동시에 처리되어야 일관성 유지. **Eventually consistent 시나리오**에서 잠시 불일치 발생 가능 — 후속 Phase에서 idempotency / ordering 검증 필요.

---

## 5. 외부 시스템 의존성 (코드 레벨 식별 + 회고와 대조)

`docs/OPERATION_INVENTORY.md §2`는 8종을 나열함. 코드와 대조:

| # | 외부 시스템 | 코드 위치 | 사용 모듈 | 장애 시 |
|---|------------|----------|----------|---------|
| 1 | PostgreSQL | `application.yml`, JPA Repository | q-sign, ido | **즉시 인증 중단** |
| 2 | MariaDB | `application.yml`, JPA Repository | q-im | **회원 조회 불가 → 인증 중단** |
| 3 | Redis | `RedisConfig` | 모든 모듈 | 세션/Rate Limit/ShedLock 영향 |
| 4 | Kafka | `KafkaProducerConfig`, `KafkaConsumerConfig` | 모든 모듈 | 이벤트 지연 (Outbox로 데이터 손실은 막힘) |
| 5 | Vault (KMS) | `ido/crypto/kms`, `VaultKmsHealthIndicator` | ido | **신규 암호화 불가** (검증은 가능) |
| 6 | Keycloak | `q-sign/keycloak/*`, `ido/broker/keycloak/*` | q-sign, ido | Keycloak Broker만 영향 |
| 7 | NICE CI | `ido/auth/adapter` (NICE) | ido | 본인확인 흐름 중단 |
| 8 | OACX | `ido/auth/controller/AuthController` | ido | 간편인증 일부 |
| 9 | AnyId | `ido/broker/anyid` (7 endpoints) | ido | AnyId 인증만 |
| 10 | OTLP Collector | tracing config | 모든 모듈 | 트레이스만 손실 (비핵심) |

→ **회고 문서(§2)에서 누락된 의존성**: AnyId(9번)와 OACX(8번)가 별도 외부 의존인데 §2에는 명시 없음. **OPERATION_INVENTORY.md §2 보강 필요** (별도 작업 후보).

---

## 6. 인증 Provider 다양성 (인증 진입점 매트릭스)

코드에서 식별한 인증 Provider:

| Provider | 진입점 | 백엔드 |
|----------|--------|--------|
| **PASS** | NICE CI 본인확인 | NICE CI |
| **GPKI** | (별도 — 회고 §6) | (TBD — 코드 확인 필요) |
| **Keycloak Broker** | `/api/v1/broker/{provider}/authorize` | Keycloak |
| **Kakao** | `/api/v1/broker/kakao/authorize` | Kakao OIDC |
| **AnyId** | `/api/v1/anyid/{provider}/*` | AnyId |
| **Non-OIDC** | `/api/v1/broker/{provider}/nonoidc/*` | 자체 |
| **OACX (간편인증)** | `/api/v1/auth/oacx/*` | OACX SDK |

→ **인증 Provider가 최소 6~7종**. 본질 메트릭(인증 성공률)을 provider별로 측정해야 하는 이유가 코드에서도 확인됨.

→ **운영 우려**: Provider 한 곳 장애 시 전체 인증 성공률이 자동으로 떨어지는데, 알람 `AuthSuccessRateLow`는 단일 임계값(90%)으로 동작. 특정 Provider만 장애일 때 다른 Provider 사용자가 정상이어도 알람이 발화 → 후속 Phase에서 **Provider별 알람 분리 필요성** 평가.

---

## 7. Phase 1 결론

### 검증된 사실
- ✅ 4축 SoR 분리는 의도대로 구현됨 (q-sign 인증, q-im 식별, ido 핸드오프)
- ✅ OIDC 표준 엔드포인트는 q-sign에 모여 있음
- ✅ Flyway 마이그레이션은 모든 모듈에 적용
- ✅ Outbox 패턴은 q-im + ido + outbox-relay-batch로 정합하게 구성

### 운영 적합성 우려 (후속 Phase 정밀 검증)

| # | 우려 사항 | 후속 Phase | 우선순위 |
|---|----------|-----------|----------|
| **R1** | IdO 32,893 LOC / 203 파일 비대화 — 변경 영향 추적 어려움 | Phase 4 | High |
| **R2** | `ExtProxyController`가 `/api/ext/**` 와일드카드로 모든 메서드 프록시 — **보안 위험** | Phase 5 | **Critical** |
| **R3** | Keycloak Callback이 q-sign / ido 양쪽에 — 사용 시나리오 명확화 필요 | Phase 2 | High |
| **R4** | MemberLookup이 ido / q-im 양쪽에 — 진실의 원천 명확화 필요 | Phase 3 | High |
| **R5** | DB 기본값이 localhost/onepass — prod ENV 누락 시 dev DB fallback 가능 | Phase 5 | High |
| **R6** | 인증 Provider 6~7종 — 본질 메트릭이 Provider별로 분리되지 않음 | Phase 2 | Medium |
| **R7** | q-im hub 역할 — 이벤트가 q-sign + ido 양쪽으로 가야 일관성 유지 | Phase 3, 4 | Medium |
| **R8** | OPERATION_INVENTORY §2 의존성 누락 (AnyId, OACX) | (문서) | Low |

### 다음 Phase
**Phase 2** — 인증 플로우를 q-sign 코드를 따라 한 줄씩 추적하면서 "한 명의 사용자가 로그인할 때 어디서 막힐 수 있는가"를 본다.
