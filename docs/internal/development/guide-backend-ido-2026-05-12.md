# ido 백엔드 팀 개발 가이드

> **버전**: v3.0.0 (PR #77 반영 — 2026-05-12 GAP 패치 완료 기준)  
> **최종 수정**: 2026-05-12  
> **대상**: ido 모듈 백엔드 개발팀  
> **포트**: 8083 (내부), 9292 (기존 플랫폼 통합)  
> **기술 스택**: Spring Boot 3.5 / Java 21 / Gradle / PostgreSQL / Redis / Kafka

---

## 목차

1. [모듈 개요 및 v3.0 변경 사항](#1-모듈-개요-및-v30-변경-사항)
2. [개발 환경 설정](#2-개발-환경-설정)
3. [환경변수 전체 목록](#3-환경변수-전체-목록)
4. [AuthController 엔드포인트 전체 목록](#4-authcontroller-엔드포인트-전체-목록)
5. [신규 엔드포인트: GET /api/v1/auth/provision/aes-gcm-key (B-1)](#5-신규-엔드포인트-aes-gcm-key)
6. [Q-IM Forward Proxy (B-5)](#6-q-im-forward-proxy-b-5)
7. [CI 토큰 교환 흐름 (Q3=B)](#7-ci-토큰-교환-흐름)
8. [NICE / OACX 인증 BFF](#8-nice--oacx-인증-bff)
9. [temp-password 반환 키 수정](#9-temp-password-반환-키-수정)
10. [패키지 구조 및 설계 원칙](#10-패키지-구조-및-설계-원칙)
11. [빌드 및 테스트](#11-빌드-및-테스트)
12. [운영 배포 체크리스트](#12-운영-배포-체크리스트)
13. [자주 발생하는 오류 및 해결](#13-자주-발생하는-오류-및-해결)

---

## 1. 모듈 개요 및 v3.0 변경 사항

### 1.1 ido의 역할

IdO(Identity Orchestrator)는 **정책 오케스트레이터 + FE BFF** 역할을 담당합니다.

```
┌──────────────────────────────────────────────────────────────┐
│  ido :8083 — 유일한 외부 공개 서비스 (FE BFF)                 │
│                                                              │
│  ① FE BFF         — beApiInstance 단일 진입점               │
│  ② /api/ext Proxy — FE 대신 Q-IM /api/ext/** forward        │
│  ③ 본인인증 BFF    — NICE / OACX 중계                        │
│  ④ CI Token 교환  — encryptedCI → Q-IM → ciToken(JWT) 반환  │
│  ⑤ AES-GCM 키 제공 — FE 런타임 키 주입 (B-1)               │
│  ⑥ 임시비밀번호   — CSPRNG 기반 생성 (Step5)                │
│  ⑦ Handoff        — 발급/검증 (기관 연동)                   │
│  ⑧ SLO            — FE 로그아웃 → Keycloak 전파             │
│  ⑨ Kafka          — 감사 로그 발행                          │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 v3.0 핵심 변경 요약 (PR #77)

| 항목 | 변경 내용 |
|------|----------|
| **B-1: AES-GCM 키 서버 제공** | `GET /api/v1/auth/provision/aes-gcm-key` 신규 엔드포인트 추가 |
| **B-1: AuthService.getFeAesGcmKey()** | `feAesGcmKey` 필드 검증 후 반환, 미설정 시 보안경고 + 500 |
| **temp-password 키 수정** | `Map.of("password", ...)` → `Map.of("tempPassword", ...)` (FE 필드명 일치) |
| **B-5: /api/ext 프록시** | FE 대신 Q-IM forward proxy — `IDO_QIM_EXT_API_KEY` 서버사이드 주입 |
| **q-sign: identifierHash** | `issueFromOidc()` → `extractSubFromIdToken()` + SHA-256(sub) |
| **docker-compose KEYCLOAK** | `change-me` → `${KEYCLOAK_IDO_CLIENT_SECRET:-}` 환경변수화 |

### 1.3 ido가 직접 하지 않는 것

| 행위 | 담당 모듈 |
|------|---------|
| Keycloak 세션 종료 | Q-Sign (`/api/v1/internal/slo`) |
| CI 저장/복호화 | Q-IM (`/api/v1/internal/member/*`) |
| JWT 토큰 발급 | Q-Sign |
| 회원 원장 관리 | Q-IM |
| `/api/ext/**` 직접 구현 | Q-IM (ido는 forward proxy만) |

---

## 2. 개발 환경 설정

### 2.1 로컬 실행 (Docker 없이)

```bash
# Feature Flag 비활성화 (외부 의존성 없이 기동)
export DOCKER_UNAVAILABLE=true

# 컴파일 검증
./gradlew :ido:compileJava --no-daemon -q

# 빌드 + 테스트 검증
./gradlew :ido:compileJava :q-sign:compileJava --no-daemon
./gradlew :ido:test :q-sign:test --no-daemon

# 로컬 프로파일 실행
./gradlew :ido:bootRun --args='--spring.profiles.active=local'
```

### 2.2 application-local.yml 최소 설정

```yaml
# ido/src/main/resources/application-local.yml
ido:
  nice:
    client-id: test-client-id
    client-secret: test-secret
  qim:
    base-url: http://localhost:8082
    internal-api-key: ido-internal
    ext-api-key: test-ext-key
  fe-aes-gcm-key: dGVzdC1hZXMtZ2NtLWtleS0zMmJ5dGVzLS0tLS0= # 로컬 테스트용
  qsign:
    base-url: http://localhost:8081
    internal-sig-secret: local-dev-secret-change-in-prod

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ido_dev
    username: ido_user
    password: local_password
  data:
    redis:
      host: localhost
      port: 6379
      password: ""
```

### 2.3 Docker Compose 로컬 실행

```bash
# 인프라만 기동 (PostgreSQL, Redis, Kafka, Keycloak)
cd infra/docker
docker compose up -d postgres redis kafka keycloak

# 애플리케이션은 IDE 또는 Gradle로 실행
./gradlew :ido:bootRun --args='--spring.profiles.active=local'
```

---

## 3. 환경변수 전체 목록

### 3.1 필수 환경변수 (운영)

| 변수명 | 설명 | 기본값 |
|--------|------|--------|
| `DB_HOST` | PostgreSQL 호스트 | — |
| `DB_PORT` | PostgreSQL 포트 | `5432` |
| `DB_NAME` | 데이터베이스명 | — |
| `DB_USERNAME` | DB 사용자명 | — |
| `DB_PASSWORD` | DB 비밀번호 | — |
| `REDIS_HOST` | Redis 호스트 | — |
| `REDIS_PORT` | Redis 포트 | `6379` |
| `REDIS_PASSWORD` | Redis 비밀번호 | — |
| `FE_AES_GCM_KEY` | **FE AES-GCM 키 (B-1 신규)** — Base64(AES-256 32B) | — |
| `IDO_QIM_INTERNAL_API_KEY` | Q-IM 내부 API 인증 키 | — |
| `IDO_QIM_EXT_API_KEY` | Q-IM 외부 API 키 (B-5: FE 대신 서버 주입) | — |
| `IDO_INTERNAL_SIG_SECRET` | Q-Sign 내부 HMAC-SHA256 시크릿 | — |
| `IDO_HANDOFF_AES_KEY` | Handoff 데이터 AES 암호화 키 | — |
| `IDO_HANDOFF_HMAC_KEY` | Handoff 서명 HMAC 키 | — |
| `NICE_CLIENT_ID` | NICE 인증 클라이언트 ID | — |
| `NICE_CLIENT_SECRET` | NICE 인증 시크릿 | — |
| `OACX_PROVIDER_KEY_PATH` | OACX provider key JSON 파일 절대 경로 | — |
| `QIM_BASE_URL` | Q-IM 서버 URL | `http://localhost:8082` |
| `QIM_INBOUND_API_KEY_HASH` | Q-IM → ido 수신 API Key PBKDF2 해시 | — |
| `QIM_AES_SHARED_KEY` | Q-IM CI 재암호화 AES 공유키 | — |
| `QSIGN_BASE_URL` | Q-Sign 서버 URL | `http://localhost:8081` |
| `KEYCLOAK_BASE_URL` | Keycloak 서버 URL | — |
| `KEYCLOAK_REALM` | Keycloak realm | — |
| `KEYCLOAK_CLIENT_ID` | Keycloak 클라이언트 ID | — |
| `KEYCLOAK_CLIENT_SECRET` | Keycloak 클라이언트 시크릿 | — |
| `CORS_ORIGIN_PROD` | 허용 CORS Origin (운영) | — |

### 3.2 선택 환경변수

| 변수명 | 설명 | 기본값 |
|--------|------|--------|
| `KAFKA_SERVERS` | Kafka Bootstrap 서버 | `localhost:9092` |
| `IDO_BROKER_MODE` | 브로커 모드 (`qsign` / `keycloak`) | `qsign` |
| `IDO_RATE_LIMIT_ENABLED` | Rate Limiting 활성화 | `false` |
| `IDO_AUDIT_KAFKA_ENABLED` | Kafka 감사로그 활성화 | `false` |
| `IDO_AUDIT_DB_ENABLED` | DB 감사로그 활성화 | `true` |
| `IDO_OUTBOX_RELAY_ENABLED` | Outbox 릴레이 활성화 | `false` |
| `IDO_SECURITY_HEADERS_ENABLED` | 보안 헤더 강제 설정 | `false` |
| `OTLP_ENDPOINT` | OpenTelemetry 수집 엔드포인트 | — |
| `NICE_TIMEOUT_SECONDS` | NICE API 타임아웃(초) | `10` |

### 3.3 신규 환경변수 (v3.0)

| 변수명 | 용도 | 담당 |
|--------|------|------|
| `FE_AES_GCM_KEY` | `GET /api/v1/auth/provision/aes-gcm-key` 응답값 | **ido 팀 필수 설정** |

> **운영 전 필수**: Q-IM 팀과 협의하여 `FE_AES_GCM_KEY`와 Q-IM의 복호화 키가 동일한지 확인.

---

## 4. AuthController 엔드포인트 전체 목록

> **Base URL**: `/api/v1/auth`  
> **인증**: `X-BE-API-Key` 헤더 (Nginx same-origin 프록시로 보안 처리)

| 메서드 | 경로 | 설명 | 버전 |
|--------|------|------|------|
| `GET` | `/nice/phone/url` | NICE 휴대폰 인증 URL 발급 | v1 |
| `POST` | `/nice/phone/result` | NICE 휴대폰 인증 결과 조회 + CI 처리 | v1 |
| `POST` | `/nice/ci-check` | CI 기반 회원 존재 확인 (조회 전용) | v1 |
| `POST` | `/oacx/access-info` | OACX 접근키/토큰 발급 | v1 |
| `POST` | `/oacx/easysign` | OACX 간편서명 결과 처리 + CI 처리 | v1 |
| `GET` | `/provision/temp-password` | 임시 비밀번호 생성 (CSPRNG) | v1 |
| `GET` | `/provision/aes-gcm-key` | **FE AES-GCM 키 제공 (B-1 신규)** | **v3.0** |
| `POST` | `/ci-token` | 암호화된 CI → ciToken(JWT) 교환 | v1 |
| `POST` | `/callback` | 기업 간편인증 콜백 수신 (Q2=B) | v1 |

---

## 5. 신규 엔드포인트: aes-gcm-key

### 5.1 엔드포인트 정의

```
GET /api/v1/auth/provision/aes-gcm-key
```

**목적**: FE 번들에 AES-GCM 키를 포함하지 않고, 런타임에 ido 서버에서 키를 주입받음 (B-1 보안 패치).

**응답**:

```json
// 200 OK
{ "aesGcmKey": "base64EncodedAES256Key==" }

// 500 Internal Server Error (FE_AES_GCM_KEY 미설정)
{
  "timestamp": "2026-05-12T00:00:00Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "서버 설정 오류: FE_AES_GCM_KEY가 설정되지 않았습니다."
}
```

### 5.2 AuthService 구현

```java
// AuthService.java
@Value("${ido.fe-aes-gcm-key:}")
private String feAesGcmKey;

public Map<String, String> getFeAesGcmKey() {
    if (feAesGcmKey == null || feAesGcmKey.isBlank()) {
        log.error("[AES-GCM-KEY][보안경고] ido.fe-aes-gcm-key 미설정 — FE_AES_GCM_KEY 환경변수를 설정하세요.");
        throw new IllegalStateException("서버 설정 오류: FE_AES_GCM_KEY가 설정되지 않았습니다.");
    }
    log.debug("[AES-GCM-KEY] FE AES-GCM 키 제공 완료");
    return Map.of("aesGcmKey", feAesGcmKey);
}
```

### 5.3 키 형식 요건

| 항목 | 요건 |
|------|------|
| 알고리즘 | AES-256-GCM |
| 키 길이 | 32바이트 (256비트) |
| 인코딩 | Base64 (표준 또는 URL-safe) |
| 생성 방법 | `openssl rand -base64 32` |

```bash
# 운영 키 생성 예시
openssl rand -base64 32
# → 결과를 FE_AES_GCM_KEY 환경변수로 설정

# Q-IM 팀과 동일한 키 사용 여부 확인 필수
# (FE: encryptCi → ido: decryptCi 복호화 시 같은 키 필요)
```

### 5.4 운영 주의사항

- `FE_AES_GCM_KEY` 미설정 시 **Step3 본인인증 전체 불능** (FE에서 CI 암호화 불가)
- 키 교체 시 **Q-IM 팀과 동시 교체** 필요 (Q-IM이 복호화 담당)
- K8s Secret 또는 Vault로 관리 권장

---

## 6. Q-IM Forward Proxy (B-5)

### 6.1 개요

FE가 `/api/ext/**`를 호출하면 ido가 서버사이드에서 Q-IM으로 forward.  
`IDO_QIM_EXT_API_KEY`를 `X-Ext-Api-Key` 헤더로 주입 → FE 번들에 Q-IM API Key 미포함.

```
FE → [GET /api/ext/clients] → ido:8083
                                  │
                          X-Ext-Api-Key 주입
                                  │
                                  ▼
                          Q-IM :8082 [GET /api/ext/clients]
                                  │
                                  ▼
                          ido → FE 응답 반환
```

### 6.2 프록시 설정 (`application.yml`)

```yaml
ido:
  qim:
    base-url: ${QIM_BASE_URL:http://localhost:8082}
    ext-api-key: ${IDO_QIM_EXT_API_KEY:}   # FE 대신 서버에서 주입
```

### 6.3 로컬 개발 시 주의

로컬에서 `/api/ext/**`를 테스트하려면 `IDO_QIM_EXT_API_KEY`와 `QIM_BASE_URL`이 모두 설정되어야 합니다.  
Q-IM 서버가 없는 경우 `DOCKER_UNAVAILABLE=true` 설정 후 mock 응답으로 대체하세요.

---

## 7. CI 토큰 교환 흐름

### 7.1 POST /api/v1/auth/ci-token

```
FE (Step3 완료)
    │
    ├─ encryptCi(ci) 호출 → GET /api/v1/auth/provision/aes-gcm-key
    │   → AES-256-GCM 암호화 → encryptedCi (base64)
    │
    ▼
POST /api/v1/auth/ci-token
{
  "encryptedCi": "base64(IV||ciphertext||tag)",
  "realm": "ucube-qsign",
  "clientId": "onepassCli",
  "flowContext": "CONVERSION" | "REGISTER"
}
    │
    ▼ ido AuthService
    ├─ AES-GCM 복호화 (feAesGcmKey)
    ├─ Q-IM AES 공유키로 재암호화
    └─ POST Q-IM /api/ext/ci/token
           → ciToken(JWT) 반환
    │
    ▼
FE: ciToken → ConversionContext / RegisterContext 저장
    (CI 평문은 FE에 없음 ✅ Q3=B 정책)
```

### 7.2 Q3=B 정책 준수 체크리스트

```
[ ] FE에 CI 평문 미반환 (ido가 복호화 후 재암호화)
[ ] FE는 ciToken(JWT)만 수신
[ ] CI 평문은 ido 메모리에서만 처리 후 즉시 해제
[ ] encryptedCi 로그 출력 금지
```

---

## 8. NICE / OACX 인증 BFF

### 8.1 NICE 인증 흐름

```
GET /api/v1/auth/nice/phone/url?returnUrl=...
    │
    ├─ Redis에서 NICE Access Token 조회 (캐시 TTL 50분)
    ├─ 없으면 NICE API → Access Token 발급 → Redis 저장
    └─ NICE URL 발급 API 호출
    │
    ▼
응답: { resultCode: "2000", authUrl: "...", requestNo: "..." }

※ FE는 authUrl로 팝업 오픈 → 인증 완료 후 requestNo로 결과 조회

POST /api/v1/auth/nice/phone/result
{ "requestNo": "...", "returnUrl": "..." }
    │
    └─ NICE 결과 조회 → CI 추출 → Q-IM 처리 (Q3=B)
```

### 8.2 OACX 인증 흐름

```
POST /api/v1/auth/oacx/access-info
{ "userId": "...", "returnUrl": "..." }
    │
    └─ OACX SDK → Access Key + Token 발급
    
POST /api/v1/auth/oacx/easysign
{ "accessKey": "...", "token": "...", "signedData": "..." }
    │
    └─ OACX SDK 검증 → CI 추출 → Q-IM 처리 (Q3=B)
```

### 8.3 운영 필수 설정

```
NICE_CLIENT_ID     — NICE 계약 후 발급
NICE_CLIENT_SECRET — NICE 계약 후 발급
NICE_RETURN_URL    — NICE 인증 콜백 URL (운영 도메인)
OACX_PROVIDER_KEY_PATH — OACX provider key 파일 절대경로
```

---

## 9. temp-password 반환 키 수정

> PR #77에서 수정된 버그: `"password"` → `"tempPassword"` (FE Step5.tsx 참조 필드명 일치)

```java
// AuthController.java (v3.0)
@GetMapping("/provision/temp-password")
public Map<String, String> generateTempPassword() {
    String password = SecurePasswordGenerator.generate();
    log.info("[임시비밀번호] CSPRNG 기반 임시 비밀번호 생성 완료");
    return Map.of("tempPassword", password);  // ← "password" → "tempPassword" 수정
}
```

**FE Step5.tsx 참조 코드**:
```typescript
const pwRes = await beApiInstance.get('/api/v1/auth/provision/temp-password');
const tempPassword = pwRes.data.tempPassword;  // 필드명 일치 확인
```

---

## 10. 패키지 구조 및 설계 원칙

```
ido/src/main/java/kr/go/smes/ido/
├── auth/
│   ├── controller/
│   │   └── AuthController.java     # /api/v1/auth/** — BFF 진입점
│   ├── service/
│   │   ├── AuthService.java        # CI 처리, AES-GCM 키 제공, 임시비밀번호
│   │   └── NiceAuthService.java    # NICE 인증 전담
│   └── dto/
│       └── *.java                  # 요청/응답 DTO
├── proxy/
│   └── ExtProxyController.java     # /api/ext/** → Q-IM forward proxy (B-5)
├── handoff/
│   └── HandoffController.java      # /api/v1/handoff/** — 기관 연동
├── session/
│   └── FeSessionController.java    # /api/v1/fe-session/** — FE 세션 관리
├── config/
│   ├── IdoWebMvcConfig.java        # CORS 설정
│   └── IdoSecurityConfig.java      # Spring Security 설정
└── common/
    └── exception/
        └── PlatformException.java  # 표준 예외
```

### 설계 원칙

1. **Controller는 얇게**: 비즈니스 로직은 Service로 위임
2. **CI 평문 로그 금지**: `@JsonIgnore` + 로그 마스킹 철저
3. **환경변수 미설정 = 즉시 500**: `isBlank()` 검증 + 보안경고 로그
4. **내부 API 키 분리**: Q-IM 내부용(`X-Internal-Api-Key`) / 외부용(`X-Ext-Api-Key`) 구분

---

## 11. 빌드 및 테스트

```bash
# 컴파일 검증
./gradlew :ido:compileJava :q-sign:compileJava --no-daemon

# 테스트 실행
./gradlew :ido:test :q-sign:test --no-daemon

# 전체 빌드
./gradlew :ido:build --no-daemon

# 특정 테스트 클래스만 실행
./gradlew :ido:test --tests "kr.go.smes.ido.auth.*" --no-daemon
```

### 테스트 작성 주의사항

```java
// FE_AES_GCM_KEY 미설정 시나리오 반드시 테스트
@Test
void getFeAesGcmKey_whenKeyBlank_thenThrowsIllegalStateException() {
    // feAesGcmKey = "" (미설정)
    assertThrows(IllegalStateException.class, () -> authService.getFeAesGcmKey());
}

// tempPassword 키 이름 검증
@Test
void generateTempPassword_returnsMapWithTempPasswordKey() {
    Map<String, String> result = authController.generateTempPassword();
    assertThat(result).containsKey("tempPassword");
    assertThat(result).doesNotContainKey("password"); // 구 버전 키명 불존재 확인
}
```

---

## 12. 운영 배포 체크리스트

```
[ ] FE_AES_GCM_KEY        — Base64 AES-256 32바이트 키 설정 (Q-IM 팀과 동일한 키)
[ ] IDO_QIM_EXT_API_KEY   — Q-IM 외부 API 키 (FE 대신 서버 주입)
[ ] IDO_QIM_INTERNAL_API_KEY — Q-IM 내부 API 키
[ ] IDO_INTERNAL_SIG_SECRET  — Q-Sign HMAC 시크릿 (Q-Sign 팀과 동기화)
[ ] IDO_HANDOFF_AES_KEY   — Handoff AES 암호화 키
[ ] IDO_HANDOFF_HMAC_KEY  — Handoff HMAC 서명 키
[ ] NICE_CLIENT_ID        — NICE 운영 자격증명
[ ] NICE_CLIENT_SECRET    — NICE 운영 자격증명
[ ] OACX_PROVIDER_KEY_PATH — OACX provider key 파일 경로
[ ] QIM_AES_SHARED_KEY    — Q-IM CI 재암호화 공유키 (Q-IM 팀과 합의)
[ ] QIM_INBOUND_API_KEY_HASH — Q-IM inbound API key PBKDF2 해시
[ ] KEYCLOAK_CLIENT_SECRET — Keycloak IDO 클라이언트 시크릿
[ ] DB_* / REDIS_* / KAFKA_SERVERS 운영 값 설정
[ ] CORS_ORIGIN_PROD — 운영 FE 도메인 설정
[ ] OACX_DEBUG_MODE=false — 운영 환경 반드시 false
[ ] ./gradlew :ido:build → Docker 이미지 빌드 → 배포
```

---

## 13. 자주 발생하는 오류 및 해결

### 13.1 GET /api/v1/auth/provision/aes-gcm-key → 500

```
원인: FE_AES_GCM_KEY 환경변수 미설정
로그: [AES-GCM-KEY][보안경고] ido.fe-aes-gcm-key 미설정
해결: 환경변수 설정 후 재기동
     openssl rand -base64 32 → FE_AES_GCM_KEY 환경변수로 주입
```

### 13.2 Q-IM /api/ext/** → 401

```
원인: IDO_QIM_EXT_API_KEY 미설정 또는 잘못된 값
해결: Q-IM 팀에서 발급한 키 확인 후 환경변수 재설정
```

### 13.3 NICE Access Token → 5001

```
원인: NICE_CLIENT_ID / NICE_CLIENT_SECRET 오류, 또는 NICE API 서버 장애
해결: NICE 계약 자격증명 확인
     Redis 캐시 삭제 후 재시도 (keys "nice:access-token*" del)
```

### 13.4 CI 토큰 교환 → 복호화 실패

```
원인: FE_AES_GCM_KEY(ido)와 Q-IM 복호화 키 불일치
해결: Q-IM 팀과 키 동기화 확인
     FE_AES_GCM_KEY와 QIM_AES_SHARED_KEY가 동일한지 확인
```

### 13.5 tempPassword 필드 없음 (FE에서)

```
원인: 이전 버전 ido (v2.x) 배포 중 — "password" 키 반환
해결: ido v3.0으로 재배포 (PR #77 포함)
```

---

*최종 수정: 2026-05-12 / PR #77 반영*  
*다음 업데이트 예정: Q-IM /api/ext/** 교차검증 완료 후*
