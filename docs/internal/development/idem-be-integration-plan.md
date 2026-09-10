# onepass-be (identity-orchestrator) → integration-sso 통합 플랜

**작성일**: 2026-05-10  
**작성자**: AI 개발자 (Genspark)  
**버전**: v2.0.0 (Option A 기준 전면 재작성)  
**이전 버전**: v1.0.0 (Option B — onepass-bff 신규 모듈) → **폐기**  
**대상 브랜치**: `genspark_ai_developer`

---

## 0. 요약 (Executive Summary)

**통합 결정 변경**: ~~Option B (onepass-bff 신규 모듈)~~ → **Option A: `ido` 모듈 직접 통합**

`idem-hub`(identity-orchestrator)의 **5개 핵심 인증 엔드포인트(NICE/OACX/기업인증)** 및
**ImApiOutPort(CI → Q-IM 등록)** 기능을 `integration-sso`의 `ido` 모듈(포트 8083)에 직접 구현한다.

### Option A 선택 이유

| 기준 | Option A (ido 직접 통합) | Option B (onepass-bff 신규) |
|------|--------------------------|------------------------------|
| 모듈 수 증가 | 없음 (기존 3개 유지) | +1 모듈 (4개) |
| Spring Boot 버전 충돌 | 없음 (3.5.x 단일화) | 4.0.6 vs 3.5.x 공존 문제 |
| FE 엔드포인트 변경 | 포트 변경만 (8083) | 신규 포트 9292 추가 |
| CI 처리 위치 | ido에서 Q-IM 직접 호출 | bff → ido → Q-IM (홉 증가) |
| 운영 복잡도 | 낮음 | 높음 (서비스 1개 추가) |
| **Sprint 7 완료 가능성** | **높음 (기존 코드 활용)** | **낮음 (신규 모듈 스캐폴딩 필요)** |

### Option A 최종 아키텍처 (v2.0.0)

```
idem-console (9090)
     │
     ▼
  ido (8083)  ◄── 모든 인증 엔드포인트 통합
     │  ├─ POST /api/v1/auth/callback          (기업인증 콜백)
     │  ├─ POST /api/v1/auth/oacx/access-info  (OACX accKey/accToken)
     │  ├─ POST /api/v1/auth/oacx/easysign     (OACX 서명 결과)
     │  ├─ POST /api/v1/auth/nice/phone/url    (NICE 팝업 URL)
     │  ├─ POST /api/v1/auth/nice/phone/result (NICE 결과 조회)
     │  └─ POST /api/v1/auth/nice/ci-check     (CI 확인/등록)
     │
     ▼  ImApiOutPort (S7-T6 구현 완료)
  q-im (8082)  ◄── CI Source of Record
     │  ├─ POST /api/v1/internal/users/register    (CI 등록/갱신)
     │  └─ POST /api/v1/internal/users/find-by-ci  (CI 조회)
     │
     ▼  Kafka EDA (qim.user.events)
  ido Kafka Consumer ◄── Q-IM 이벤트 수신
```

---

## 1. idem-hub 분석 결과 요약

### 1.1 프로젝트 원본 정보

| 항목 | 값 | 통합 결정 |
|------|----|-----------| 
| 프로젝트명 | `identity-orchestrator` | ido 모듈에 흡수 |
| Spring Boot | **4.0.6** | 3.5.x로 다운그레이드 (breaking 없음) |
| Java | 21 | 동일 |
| 포트 | 9292 | **8083 (ido 포트)** 사용 |
| 패키지 | `kr.ucube.integratedauth.identityorchestrator` | `io.github.hipstermin.idem.hub.auth` 패키지 이식 |
| 아키텍처 | 헥사고날 (Ports & Adapters) | **ido에 이식** (포트/어댑터 패턴 보존) |
| DB | H2/PostgreSQL (Board 샘플만) | 불필요 (stateless 설계) |
| HTTP 클라이언트 | WebFlux WebClient (비동기) | RestTemplate (ido 표준) |

### 1.2 이식된 InPort (이미 완료)

| InPort 메서드 | ido 서비스 | 컨트롤러 엔드포인트 | 상태 |
|---------------|-----------|---------------------|------|
| `callback(AuthCallbackRequest)` | `AuthService.callback()` | `POST /api/v1/auth/callback` | ✅ 구현 완료 |
| `getOacxAccessInfo(String fn)` | `AuthService.getOacxAccessInfo()` | `POST /api/v1/auth/oacx/access-info` | ✅ 구현 완료 |
| `handleOacxEasysign(request)` | `AuthService.handleOacxEasysign()` | `POST /api/v1/auth/oacx/easysign` | ✅ 구현 완료 |
| `checkNiceCi(CiCheckRequest)` | `AuthService.checkNiceCi()` | `POST /api/v1/auth/nice/ci-check` | ✅ 구현 완료 |
| `getNicePhoneAuthUrl(String)` | `NiceAuthService.getNicePhoneAuthUrl()` | `GET /api/v1/auth/nice/phone/url` | ✅ 구현 완료 |
| `getNicePhoneAuthResult(request)` | `NiceAuthService.getNicePhoneAuthResult()` | `POST /api/v1/auth/nice/phone/result` | ✅ 구현 완료 |

### 1.3 이식된 OutPort 현황

| OutPort | 구현체 위치 | Q-IM 엔드포인트 | 상태 |
|---------|------------|-----------------|------|
| `NiceApiOutPort` | `NiceApiClient` | NICE IDO API 3개 | ✅ 구현 완료 |
| `OacxOutPort` | `OacxClient` | OACX SDK 2개 | ✅ 구현 완료 |
| `ImApiOutPort` | `ImApiOutAdapter` | Q-IM POST /register, POST /find-by-ci | ✅ **S7-T6 구현 완료** |
| `IntegrationAuthApiOutPort` | `IntegrationAuthClient` | 통합인증 auth-check | ✅ 구현 완료 |

---

## 2. GAP 분석 — Option A 기준

### 2.1 이행 완료 GAP

| GAP ID | 내용 | 해결 방법 | 버전 |
|--------|------|-----------|------|
| GAP-BE-01 | NICE 인증 엔드포인트 부재 | `NiceAuthService` + `NiceApiClient` 이식 | v1.0.0 |
| GAP-BE-02 | OACX 간편서명 엔드포인트 부재 | `AuthService.handleOacxEasysign()` 이식 | v1.0.0 |
| GAP-BE-04 | 기업인증 콜백 부재 | `AuthService.callback()` 이식 | v1.0.0 |
| GAP-QIM-01 | needsSync=true Pull 미지원 | `QimClient.getUserById()` 추가 | v1.9.4 |

### 2.2 이번 Sprint(S7-T6) 해결된 GAP

| GAP ID | 내용 | 해결 방법 | 버전 |
|--------|------|-----------|------|
| **GAP-BE-03** | **ImApiOutPort 미구현 — CI가 Q-IM에 저장되지 않음** | **ImApiOutPort + ImApiOutAdapter + QimClient 확장** | **v1.2.0 (S7-T6)** |

### 2.3 잔존 GAP (향후 Sprint)

| GAP ID | 내용 | 우선순위 | 예상 Sprint |
|--------|------|----------|------------|
| GAP-BE-05 | FE `beInstance` URL 설정 — ido 포트(8083)로 변경 필요 | 중 | S8 |
| GAP-BE-06 | NICE 인증 토큰 Redis 분산 저장 (다중 Pod 대비) | 저 | S9 |
| GAP-BE-07 | OACX SDK 버전 호환성 검증 (Spring Boot 3.5.x) | 중 | S8 |

---

## 3. S7-T6 구현 상세 — ImApiOutPort (GAP-BE-03 해결)

### 3.1 구현된 파일 목록

| 파일 경로 | 역할 | 신규/수정 |
|-----------|------|-----------|
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/auth/port/ImApiOutPort.java` | 아웃바운드 포트 인터페이스 | **신규** |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/auth/adapter/ImApiOutAdapter.java` | 포트 구현체 (QimClient 위임) | **신규** |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/auth/dto/im/QimRegisterResponse.java` | Q-IM 등록 응답 DTO | **신규** |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/auth/dto/im/QimMemberInfo.java` | Q-IM CI 조회 결과 DTO | **신규** |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/infrastructure/QimClient.java` | HTTP 클라이언트 인터페이스 확장 | **수정** |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/infrastructure/QimClientImpl.java` | HTTP 클라이언트 구현체 확장 | **수정** |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/auth/service/AuthService.java` | handleOacxEasysign + checkNiceCi TODO 해제 | **수정** |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/auth/service/NiceAuthService.java` | getNicePhoneAuthResult CI 등록 로직 추가 | **수정** |

### 3.2 CI 처리 플로우 (Q3=B 결정 준수)

#### OACX 간편서명 완료 플로우

```
FE → POST /api/v1/auth/oacx/easysign
         │
         ▼ AuthService.handleOacxEasysign()
         ├─ OacxClient.decryptEasysignResult()   ← OACX JWT 복호화
         ├─ ci 추출 (decrypted.get("ci"))
         ├─ ImApiOutPort.register(authResult)    ← S7-T6: Q-IM에 CI 등록
         │     └─ QimClient.registerUser()
         │           └─ POST /api/v1/internal/users/register
         └─ FE 응답: {name, birthday, phone}     ← CI 미포함 (Q3=B)
```

#### NICE 휴대폰 인증 완료 플로우

```
FE → POST /api/v1/auth/nice/phone/result
         │
         ▼ NiceAuthService.getNicePhoneAuthResult()
         ├─ NiceApiClient.requestAuthResult()    ← NICE encData 수신
         ├─ decryptAndVerify()                   ← AES-GCM 복호화 + HMAC 검증
         ├─ ciForInternalUse = resultMap.get("ci")
         ├─ ImApiOutPort.register(authResult)    ← S7-T6: Q-IM에 CI 등록
         │     └─ QimClient.registerUser()
         │           └─ POST /api/v1/internal/users/register
         └─ FE 응답: {name, birthdate, di, ...}  ← CI 미포함 (Q3=B)
```

#### NICE CI 확인 플로우

```
FE → POST /api/v1/auth/nice/ci-check
         │
         ▼ AuthService.checkNiceCi()
         ├─ 파라미터 검증 (ci, mbrDvsnCd, bizno)
         ├─ ImApiOutPort.findByCi(ci, memberType) ← S7-T6: 기존 회원 조회
         │     └─ QimClient.findByCi()
         │           └─ POST /api/v1/internal/users/find-by-ci
         ├─ [기존 회원] → indvlMbrId/cmpMbrId 반환
         └─ [신규 회원] → ImApiOutPort.register() 후 "신규 등록 완료" 반환
```

### 3.3 의존성 방향 (헥사고날 아키텍처)

```
[AuthService]           → ImApiOutPort (인터페이스)
[NiceAuthService]       → ImApiOutPort (인터페이스)
                              ↑
                        [ImApiOutAdapter] (구현체)
                              │
                        [QimClient] (인터페이스)
                              ↑
                        [QimClientImpl] (RestTemplate 구현체)
                              │
                        Q-IM HTTP API (8082)
```

### 3.4 Q-IM 내부 API 명세 (ido → q-im)

| 메서드 | 엔드포인트 | 요청 바디 | 응답 |
|--------|-----------|-----------|------|
| POST | `/api/v1/internal/users/register` | `{ci, di, name, birthday, gender, mobile, mobileCorp}` | `{qimUserId, status, isNew, message}` |
| POST | `/api/v1/internal/users/find-by-ci` | `{ci, memberType}` | `{qimUserId, status, memberType, indvlMbrId, cmpMbrId}` |

**인증 헤더**: `X-Internal-Api-Key: ido-internal`  
**추적 헤더**: `X-Correlation-Id: {correlationId}`

---

## 4. 설계 결정 기록

### 4.1 Q3=B — CI FE 미반환

| 결정 | 이유 |
|------|------|
| CI는 절대 FE 응답에 포함하지 않음 | CI(연계정보)는 주민등록번호 기반 PII — FE 노출 시 보안사고 위험 |
| CI는 Q-IM(ido 포트 아웃바운드)으로만 전달 | Q-IM이 CI를 AES-256-GCM 암호화하여 Transactional Outbox로 관리 |
| `NicePhoneAuthResultResponse`에 ci 필드 없음 | DTO 레벨에서 구조적으로 CI 반환 불가 |

### 4.2 ImApiOutPort 오류 처리 전략

| 상황 | 처리 방법 | 이유 |
|------|-----------|------|
| Q-IM 서버 장애 (503/timeout) | 인증 플로우 중단 (5010 반환) | CI 미등록 상태로 진행 불가 |
| CI 미포함 (provider가 CI 미제공) | 경고 로그 + 플로우 계속 | 일부 OACX provider가 CI 제공 안 함 |
| CI 조회 404 | `Optional.empty()` 반환 | 미등록 사용자 = 정상 케이스 |
| CI 중복 등록 | Q-IM이 `isNew=false`로 처리 | Q-IM에서 멱등 처리 담당 |

### 4.3 QimClient 확장 vs 별도 HTTP 클라이언트 신설

**결정**: `QimClient` 인터페이스 확장 (registerUser, findByCi 메서드 추가)

**이유**:
- Q-IM은 단일 외부 시스템 → 클라이언트 분산 시 `qimBaseUrl`, `X-Internal-Api-Key` 설정 중복
- `ImApiOutAdapter`가 `QimClient`를 주입받아 위임하는 단순 패턴 유지
- 테스트 시 `QimClient` Mock 하나로 Q-IM 전체 동작 제어 가능

---

## 5. FE 연동 변경 사항 (GAP-BE-05)

idem-console의 `beInstance` 설정을 아래와 같이 변경해야 한다.

```javascript
// 변경 전 (Option B 기준 — 적용 안 됨)
const beInstance = axios.create({ baseURL: 'http://localhost:9292' });

// 변경 후 (Option A — ido 포트)
const beInstance = axios.create({ baseURL: 'http://localhost:8083' });
```

**영향 받는 FE 엔드포인트**:
- `POST /api/v1/auth/callback`
- `POST /api/v1/auth/oacx/access-info`
- `POST /api/v1/auth/oacx/easysign`
- `GET  /api/v1/auth/nice/phone/url`
- `POST /api/v1/auth/nice/phone/result`
- `POST /api/v1/auth/nice/ci-check`

---

## 6. 빌드 & 테스트 현황

### Sprint 7 완료 시점 (S7-T6 구현 후)

| 항목 | 결과 | 비고 |
|------|------|------|
| `./gradlew :idem-hub:compileJava` | ✅ BUILD SUCCESSFUL | S7-T6 신규 파일 포함 |
| `./gradlew :idem-hub:test --tests "io.github.hipstermin.idem.hub.auth.*"` | ✅ **33건 전체 통과** | checkNiceCi +2건 신규 |
| `AuthServiceTest` | ✅ 16건 | ImApiOutPort Mock 주입 완료 |
| `NiceCryptoUtil` 테스트 | ✅ 17건 | 기존 테스트 유지 |

---

## 7. 이전 버전 폐기 기록

| 버전 | 결정 | 폐기 이유 |
|------|------|-----------|
| **v1.0.0 (Option B)** | onepass-bff 신규 모듈 추가 | Spring Boot 4.0.6 vs 3.5.x 버전 충돌, 운영 복잡도 증가, Sprint 내 완료 불가 |
| **v2.0.0 (Option A)** | ido 모듈 직접 통합 | **현행 유지** |
