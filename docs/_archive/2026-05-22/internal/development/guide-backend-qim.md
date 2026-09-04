# Q-IM 백엔드 팀 개발 가이드

> **버전**: v2.3.0 (Sprint 10 기준)  
> **최종 수정**: 2026-05-11  
> **대상**: Q-IM 모듈 백엔드 개발팀  
> **포트**: 8082  
> **기술 스택**: Spring Boot 3.5 / Java 21 / Gradle / MariaDB / Redis / Kafka

---

## 목차

1. [모듈 개요 및 책임 경계](#1-모듈-개요-및-책임-경계)
2. [개발 환경 설정](#2-개발-환경-설정)
3. [패키지 구조 및 설계 원칙](#3-패키지-구조-및-설계-원칙)
4. [CI 암호화 구조 (AES-256-GCM)](#4-ci-암호화-구조)
5. [회원 원장 API 상세](#5-회원-원장-api-상세)
6. [회원정보 수정 연동 (Sprint 10)](#6-회원정보-수정-연동)
7. [DI(Duplicate Identity) 생성 서비스](#7-di-생성-서비스)
8. [PII 마스킹 및 개인정보 보호](#8-pii-마스킹-및-개인정보-보호)
9. [개인정보 파기 스케줄러](#9-개인정보-파기-스케줄러)
10. [Transactional Outbox 패턴](#10-transactional-outbox-패턴)
11. [멱등 처리 (Idempotent Consumer)](#11-멱등-처리)
12. [Flyway 마이그레이션 가이드](#12-flyway-마이그레이션-가이드)
13. [테스트 작성 가이드](#13-테스트-작성-가이드)
14. [자주 발생하는 오류 및 해결](#14-자주-발생하는-오류-및-해결)
15. [코딩 컨벤션 및 체크리스트](#15-코딩-컨벤션-및-체크리스트)

---

## 1. 모듈 개요 및 책임 경계

### Q-IM의 역할

Q-IM(Q-Identity Management)은 **식별 SoR(Source of Record)**로, 통합 회원 식별자 및 회원 생명주기를 단독으로 관리합니다.

```
┌─────────────────────────────────────────────────────────────┐
│  Q-IM :8082 — 식별 SoR (내부 전용)                          │
│                                                             │
│  ① CI 저장/복호화    — AES-256-GCM, v{n}.{iv}.{ct} 포맷    │
│  ② 회원 원장 관리   — QimUser, UserProfile, AuthMeanMapping  │
│  ③ DI 생성/조회     — HMAC-SHA256(siteCode:uuid:secret)      │
│  ④ PII 마스킹       — 이름/전화번호/이메일 마스킹 출력       │
│  ⑤ 회원 상태 전이   — ACTIVE→SUSPENDED→WITHDRAWN             │
│  ⑥ Outbox 릴레이    — Kafka(qim.user.events) 발행           │
│  ⑦ 멱등 처리        — processed_event 기반 중복 방지         │
└─────────────────────────────────────────────────────────────┘
```

### Q-IM이 직접 하지 않는 것 (경계 명확화)

| 행위 | 담당 모듈 | 이유 |
|------|---------|------|
| Keycloak 세션 관리 | Q-Sign | 인증 SoR |
| FE BFF / feSession 관리 | IdO | 정책 오케스트레이터 |
| SLO API 수신 | IdO | 공개 엔드포인트 |
| 기관(Agency) 서비스 연동 | IdO | Webhook/Handoff 오케스트레이션 |
| 본인인증 BFF (NICE/OACX) | IdO | 보안 정책 중앙화 |

> **핵심 원칙**: Q-IM은 절대 외부에서 직접 접근할 수 없습니다.  
> 모든 호출은 `X-Internal-Api-Key` 헤더를 가진 IdO→Q-IM 내부 경로로만 이루어집니다.

---

## 2. 개발 환경 설정

### 2.1 로컬 실행 준비

```bash
# 1. 인프라 기동 (MariaDB + Redis + Kafka 필요)
cd infra/docker
docker compose up -d mariadb redis zookeeper kafka kafka-init
# kafka-init 토픽 생성까지 약 30초 대기

# 2. Q-IM 빌드 및 실행
cd ../../
./gradlew :idem-registry:bootRun --args='--spring.profiles.active=local'

# 3. 헬스체크
curl http://localhost:8082/actuator/health
# → {"status":"UP"}
```

### 2.2 application-local.yml 예시

```yaml
# idem-registry/src/main/resources/application-local.yml
spring:
  datasource:
    url: jdbc:mariadb://localhost:3306/qim?serverTimezone=Asia/Seoul
    username: qim
    password: qim_local_pw
    driver-class-name: org.mariadb.jdbc.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: localhost:9092

qim:
  ci:
    aes-key-v1: ${QIM_CI_AES_KEY_V1:localdev_32byte_aes_key_here!!!!}
    # 로컬 테스트용 — 프로덕션에서는 반드시 Secrets 관리 필요
  di:
    secret: ${QIM_DI_SECRET:localdev-di-secret-string}

logging:
  level:
    kr.go.smes.qim: DEBUG
```

### 2.3 필수 환경 변수 목록

| 변수명 | 필수 | 설명 | 예시 |
|--------|------|------|------|
| `SPRING_DATASOURCE_URL` | ✅ | MariaDB JDBC URL | `jdbc:mariadb://mariadb:3306/qim` |
| `SPRING_DATASOURCE_USERNAME` | ✅ | DB 사용자명 | `qim` |
| `SPRING_DATASOURCE_PASSWORD` | ✅ | DB 비밀번호 | (Secrets) |
| `QIM_CI_AES_KEY_V1` | ✅ | CI AES-256 키 (v1) | (32byte, Secrets) |
| `QIM_CI_AES_KEY_V2` | 선택 | CI AES-256 키 (v2) — 로테이션 시 추가 | (Secrets) |
| `QIM_DI_SECRET` | ✅ | DI HMAC 서명 시크릿 | (Secrets) |
| `KAFKA_SERVERS` | ✅ | Kafka 브로커 주소 | `kafka:9092` |
| `IDO_RETENTION_ENABLED` | 선택 | 개인정보 파기 스케줄러 활성화 | `true` |
| `IDO_RETENTION_DRY_RUN` | 선택 | 파기 DRY_RUN 모드 (로그만) | `false` |
| `SERVER_PORT` | 선택 | 서버 포트 (기본 8082) | `8082` |

> ⚠️ **주의**: `QIM_CI_AES_KEY_V1`은 절대 Git에 커밋하지 마세요.  
> 로컬 개발 시 `.env.local` 또는 IDE 환경변수 설정을 사용하세요.

---

## 3. 패키지 구조 및 설계 원칙

### 3.1 전체 패키지 구조

```
idem-registry/src/main/java/kr/go/smes/qim/
├── QImApplication.java
├── api/                           # 내부 API (X-Internal-Api-Key 인증)
│   ├── GlobalExceptionHandler.java
│   ├── MemberLookupController.java   # CI/Hash 기반 회원 조회
│   ├── QimStatusController.java      # 상태 관리 API
│   ├── UserController.java           # CRUD API
│   └── dto/
│       ├── RegisterMemberRequest.java
│       ├── UpdateMemberRequest.java   # Sprint 10 신규
│       ├── UpdateEnterpriseRequest.java # Sprint 10 신규
│       └── MemberLookupResponse.java
├── application/                   # 비즈니스 서비스 계층
│   ├── UserService.java
│   └── UserServiceImpl.java
├── config/                        # 스프링 설정
│   ├── KafkaConsumerConfig.java
│   ├── KafkaProducerConfig.java
│   ├── KafkaTopicConfig.java
│   └── RedisConfig.java
├── crypto/                        # CI 암호화 (핵심 보안 모듈)
│   ├── CiCryptoService.java
│   ├── CiCryptoServiceImpl.java
│   └── PiiMaskingService.java
├── domain/                        # 도메인 모델 (순수 Java)
│   ├── AuthMeanMapping.java
│   ├── QimUser.java
│   └── UserProfile.java
├── identity/                      # DI 생성
│   └── DiGenerationService.java
├── infrastructure/                # JPA 구현체
│   ├── UserRepository.java
│   ├── UserRepositoryImpl.java
│   └── jpa/
│       ├── entity/
│       │   ├── AuthMeanMappingJpaEntity.java
│       │   ├── OutboxJpaEntity.java
│       │   ├── QimUserJpaEntity.java
│       │   └── UserProfileJpaEntity.java
│       └── repository/
│           ├── OutboxJpaRepository.java
│           └── QimUserJpaRepository.java
├── outbox/                        # Transactional Outbox 패턴
│   ├── OutboxRecord.java
│   ├── OutboxRepository.java
│   ├── OutboxRepositoryImpl.java
│   ├── OutboxService.java
│   └── OutboxServiceImpl.java
└── user/                          # 회원 등록 서비스
    ├── UserRegistrationService.java
    └── UserRegistrationServiceImpl.java
```

### 3.2 설계 원칙

#### 계층 간 의존 방향 (Clean Architecture)

```
api (Controller/DTO)
    └→ application (Service Interface)
           └→ domain (순수 모델)
           └→ infrastructure (JPA Repository)
```

- **도메인 모델은 JPA 어노테이션 금지** — `QimUser`, `UserProfile`은 순수 Java 객체
- **JPA 엔티티는 `jpa/entity/` 하위에만 존재** — 도메인 <→ JPA 변환은 Repository에서 담당
- **서비스 계층은 인터페이스로 정의** — `UserService` / `UserServiceImpl` 패턴

#### 비치명적 실패 처리 (Non-Fatal Failure)

```java
// ✅ 올바른 예: Outbox 릴레이 실패 시 예외 전파하지 않음
try {
    outboxService.relayPendingEvents();
} catch (Exception e) {
    log.error("[Outbox] 릴레이 실패 — 다음 주기에 재시도: {}", e.getMessage());
    // ★ 예외 rethrow 금지: 스케줄러가 종료되면 안 됨
}

// ❌ 금지 예: 치명적 예외로 취급
outboxService.relayPendingEvents(); // 예외 시 스케줄러 중단됨
```

---

## 4. CI 암호화 구조

### 4.1 저장 포맷

CI(Connecting Information)는 주민번호와 동등한 개인식별정보입니다.  
Q-IM은 CI를 다음 포맷으로 암호화하여 저장합니다.

```
v{버전}.{Base64URL(IV)}.{Base64URL(암호문)}

예: v1.dGVzdGl2MTIz.Y2lwaGVydGV4dGhlcmU=
    ─────  ─────────  ─────────────────────
    키버전   12byte IV   AES-256-GCM 암호문
```

| 구성요소 | 값 | 설명 |
|---------|---|------|
| 버전 | `v1`, `v2`, ... | 키 로테이션 시 증가 |
| IV | 12 바이트, 랜덤 생성 | 매번 새 IV 사용 (재사용 금지) |
| 암호문 | AES-256-GCM | 16바이트 GCM 태그 포함 |

### 4.2 CiCryptoService 구현

```java
// CiCryptoService.java
public interface CiCryptoService {
    /** rawCi → 암호화된 문자열 (v{n}.{iv}.{ct} 포맷) */
    String encrypt(String rawCi);

    /** v{n}.{iv}.{ct} → 원본 CI 복호화 */
    String decrypt(String encryptedCi);

    /** 현재 활성 키 버전 (로테이션 시 갱신) */
    String currentKeyVersion();
}

// CiCryptoServiceImpl.java (핵심 로직)
@Service
public class CiCryptoServiceImpl implements CiCryptoService {

    private final Map<String, SecretKey> keyMap; // v1→key1, v2→key2 ...

    @Override
    public String encrypt(String rawCi) {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        GCMParameterSpec spec = new GCMParameterSpec(128, iv); // 128bit tag
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, currentKey(), spec);
        byte[] ct = cipher.doFinal(rawCi.getBytes(StandardCharsets.UTF_8));

        return currentKeyVersion()
            + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
            + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(ct);
    }

    @Override
    public String decrypt(String encryptedCi) {
        String[] parts = encryptedCi.split("\\.");
        // parts[0] = 버전, parts[1] = iv, parts[2] = ct
        String version = parts[0];
        SecretKey key = keyMap.get(version);
        if (key == null) {
            throw new IllegalArgumentException("알 수 없는 CI 키 버전: " + version);
        }
        byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
        byte[] ct = Base64.getUrlDecoder().decode(parts[2]);

        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }
}
```

### 4.3 새 CI 암호화 키 버전 추가 방법

키 로테이션 주기는 90일이며, 다음 순서로 진행합니다.

```
1단계: 새 키 생성
   openssl rand -base64 32   → 새 32바이트 키

2단계: Secrets에 추가
   kubectl create secret generic qim-secrets \
     --from-literal=QIM_CI_AES_KEY_V2=<new_key> \
     --dry-run=client -o yaml | kubectl apply -f -

3단계: application.yml 수정
   qim.ci.aes-key-v2: ${QIM_CI_AES_KEY_V2}

4단계: CiCryptoServiceImpl.keyMap에 v2 추가
   keyMap.put("v2", buildKey(env.getProperty("qim.ci.aes-key-v2")));

5단계: currentKeyVersion()이 "v2" 반환하도록 수정
   → 신규 등록 CI는 v2로 암호화

6단계: 배포 후 기존 v1 CI는 그대로 유지 (읽기 시 v1 키로 복호화)
   → 점진적 마이그레이션 (접근 시 재암호화 또는 배치 처리)
```

> ⚠️ **절대 금지**: 기존 암호화된 CI를 삭제하거나 v1 키를 제거하기 전에  
> 모든 레코드의 `ci_key_version`이 새 버전으로 이전 완료되었는지 반드시 확인하세요.

### 4.4 Q3 정책 — CI FE 미반환

```
본인인증 완료 후 CI는 Q-IM DB에만 저장됩니다.
FE(onepass-fe)에는 절대 반환하지 않습니다. (Q3=B 정책)

허용: 내부 서비스 간 CI 전달 (X-Internal-Api-Key)
금지: FE 응답 body에 CI 포함
금지: JWT payload에 CI 포함
금지: 로그에 원본 CI 기록
```

---

## 5. 회원 원장 API 상세

### 5.1 API 목록 (X-Internal-Api-Key 인증 필수)

| Method | 경로 | 설명 | 상태 |
|--------|------|------|------|
| `POST` | `/api/ext/v1/member/register` | 회원 등록 (IdO→Q-IM) | ✅ |
| `POST` | `/api/ext/v1/member/query` | CI 기반 회원 조회 | ✅ |
| `POST` | `/api/ext/v1/member/withdraw` | 회원 탈퇴 처리 | ✅ (부분) |
| `GET` | `/api/v1/users/{qimUserId}` | 사용자 상세 조회 | ✅ |
| `GET` | `/api/v1/users/by-hash` | identifierHash 기반 조회 | ✅ |
| `GET` | `/api/v1/users/{qimUserId}/di` | DI 조회/생성 | ✅ |
| `PATCH` | `/api/ext/v1/members/{mbrNo}` | 개인회원 정보 수정 | ✅ Sprint 10 |
| `PATCH` | `/api/ext/v1/enterprises/{entMbrNo}` | 기업회원 정보 수정 | ✅ Sprint 10 |
| `GET` | `/actuator/health` | 헬스체크 | ✅ |

### 5.2 CI 기반 회원 조회 (MemberLookupController)

```java
// POST /api/ext/v1/member/query
// 요청: { "encryptedCi": "v1.iv.ct", "agencyCode": "AGENCY_001" }
// 응답: { "qimUserId": "uuid", "di": "...", "status": "ACTIVE", ... }

@PostMapping("/api/ext/v1/member/query")
public ResponseEntity<MemberLookupResponse> queryMember(
        @RequestHeader("X-Internal-Api-Key") String apiKey,
        @Valid @RequestBody MemberQueryRequest request) {

    // 1. 내부 API 키 검증 (GlobalExceptionHandler가 401 처리)
    internalApiKeyValidator.validate(apiKey);

    // 2. CI 복호화 → identifierHash 계산
    String rawCi = ciCryptoService.decrypt(request.getEncryptedCi());
    String hash = identifierHashService.hash(rawCi);

    // 3. Hash 기반 사용자 조회
    QimUser user = userRepository.findByIdentifierHash(hash)
        .orElseThrow(() -> new MemberNotFoundException("회원 없음: hash=" + hash.substring(0, 8) + "..."));

    // 4. DI 조회 (기관별 고유)
    String di = diGenerationService.getOrCreate(user.getQimUserId(), request.getAgencyCode());

    // 5. PII 마스킹 후 응답 (원본 CI 미포함)
    return ResponseEntity.ok(MemberLookupResponse.of(user, di, piiMaskingService));
}
```

### 5.3 identifierHash 설계

```
identifierHash = SHA-256(rawCi + ":" + saltSecret)
             → Base64URL 인코딩 (43자)

목적: 
  - DB 인덱스로 O(1) 회원 조회 (CI 복호화 없이)
  - CI와 Hash는 서로 다른 컬럼에 저장하여 역추적 방지
  - 동일 CI → 항상 동일 Hash (결정적)

저장 위치: user_profile.identifier_hash (UNIQUE INDEX)
```

---

## 6. 회원정보 수정 연동

### 6.1 Sprint 10에서 추가된 API

Sprint 10에서 FE `InformationStep3.tsx`가 실제 API를 호출하도록 변경되었습니다.  
Q-IM은 IdO가 전달하는 수정 요청을 처리합니다.

**개인회원 수정**: `PATCH /api/ext/v1/members/{mbrNo}`

```java
// UpdateMemberRequest.java (DTO)
public record UpdateMemberRequest(
    @NotBlank String memberName,        // 필수: 이름
    @Pattern(regexp = "\\d{3}-\\d{3,4}-\\d{4}") String phone,  // 선택: 전화번호
    @Email String email                  // 선택: 이메일
) {}

// 처리 흐름
PATCH /api/ext/v1/members/{mbrNo}
  → IdO (MemberUpdateController)
    → Q-IM (내부 API 호출, X-Internal-Api-Key)
      → UserServiceImpl.updateMember(mbrNo, request)
        → UserProfile 조회 → PII 업데이트 → save()
        → Outbox 적재 (USER_PROFILE_UPDATED 이벤트)
        → 200 OK
```

**기업회원 수정**: `PATCH /api/ext/v1/enterprises/{entMbrNo}`

```java
// UpdateEnterpriseRequest.java (DTO)
public record UpdateEnterpriseRequest(
    @NotBlank String bzmnNm,            // 필수: 사업자명
    @NotBlank String rprsvNm,           // 필수: 대표자명
    @Pattern(regexp = "\\d{2,3}-\\d{3,4}-\\d{4}") String rprsTelno, // 선택
    @Email String email                  // 선택
) {}
```

### 6.2 필드 포맷 규칙

```
전화번호 (phone / rprsTelno):
  - 형식: "010-1234-5678" (하이픈 포함)
  - 저장: user_profile.phone_number (VARCHAR(20))
  - FE에서 buildPhoneNumber() 헬퍼로 조립 후 전송

이메일 (email):
  - 형식: RFC 5321 준수 ("user@example.com")
  - 저장: user_profile.email (VARCHAR(100))
  - FE에서 buildEmail() 헬퍼로 조립 후 전송

이름 (memberName / bzmnNm):
  - 최대 50자
  - 앞뒤 공백 trim 처리 (서비스 계층에서 강제)
```

### 6.3 새 수정 가능 필드 추가 방법

1. `UpdateMemberRequest` / `UpdateEnterpriseRequest` DTO에 필드 추가
2. `UserServiceImpl.updateMember()` 로직에 필드 처리 추가
3. `user_profile` 테이블에 컬럼이 없으면 Flyway 마이그레이션 작성
4. 해당 Outbox 이벤트 payload에 변경 필드 포함
5. FE `types/api/ext/members.ts`의 `UpdateMemberRequest` / `UpdateEnterpriseRequest` 인터페이스에도 동일하게 추가

```java
// 예: 주소 필드 추가 시
// UserServiceImpl.java
public void updateMember(String mbrNo, UpdateMemberRequest req) {
    UserProfile profile = userProfileRepository.findByMbrNo(mbrNo)
        .orElseThrow(() -> new MemberNotFoundException(mbrNo));

    profile.setMemberName(req.memberName().trim());
    if (req.phone() != null) profile.setPhoneNumber(req.phone());
    if (req.email() != null) profile.setEmail(req.email());
    if (req.address() != null) profile.setAddress(req.address()); // ← 신규
    
    userProfileRepository.save(profile);
    outboxService.enqueue(UserEvent.USER_PROFILE_UPDATED, profile);
}
```

---

## 7. DI 생성 서비스

### 7.1 DI(Duplicate Identity) 개요

DI는 기관별로 동일 회원에게 발급하는 독립적인 식별자입니다.

```
DI = HMAC-SHA256(siteCode + ":" + qimUserId + ":" + serviceSecret)
   → Base64 인코딩 (44자)

특성:
  - 동일 회원, 다른 기관 → 완전히 다른 DI (기관 간 연계 불가)
  - 동일 회원, 동일 기관 → 항상 동일 DI (결정적)
  - 서비스 시크릿(QIM_DI_SECRET) 없이 역산 불가
```

### 7.2 DiGenerationService 구현

```java
@Service
public class DiGenerationService {

    private final String secret; // QIM_DI_SECRET

    public String getOrCreate(String qimUserId, String agencyCode) {
        // di_map JSON: {"AGENCY_001": "di_value_1", "AGENCY_002": "di_value_2"}
        QimUser user = userRepository.findById(qimUserId).orElseThrow();

        Map<String, String> diMap = parseDiMap(user.getDiMap());

        if (diMap.containsKey(agencyCode)) {
            return diMap.get(agencyCode); // 캐시 hit
        }

        // 신규 생성
        String di = computeHmac(agencyCode, qimUserId, secret);
        diMap.put(agencyCode, di);
        user.setDiMap(serializeDiMap(diMap));
        user.setDiMapUpdatedAt(Instant.now());
        userRepository.save(user);

        return di;
    }

    private String computeHmac(String siteCode, String userId, String secret) {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal((siteCode + ":" + userId + ":" + secret).getBytes(UTF_8));
        return Base64.getEncoder().encodeToString(raw);
    }
}
```

### 7.3 기관 추가 시 고려사항

- 새 기관(agencyCode)이 추가되면 해당 기관으로의 첫 접근 시 자동으로 DI 생성
- DI는 `qim_user.di_map` JSON 컬럼에 누적 저장
- 기관 코드 변경 시 DI가 바뀌므로 주의 (기존 DI 이관 스크립트 필요)

---

## 8. PII 마스킹 및 개인정보 보호

### 8.1 PiiMaskingService 마스킹 규칙

```java
// PiiMaskingService.java
@Service
public class PiiMaskingService {

    // 이름: 홍길동 → 홍*동 (2자: 홍* / 4자 이상: 첫글자 + ** + 마지막글자)
    public String maskName(String name) { ... }

    // 전화번호: 010-1234-5678 → 010-****-5678
    public String maskPhone(String phone) { ... }

    // 이메일: user@example.com → us**@example.com
    public String maskEmail(String email) { ... }

    // CI: 절대 출력하지 않음 (마스킹 없이 미반환 원칙)
    // → 로그에도 절대 원본 CI 출력 금지
}
```

### 8.2 로그 안전 정책

```java
// ✅ 안전한 로그 (식별 가능하되 민감정보 미포함)
log.info("[Member] 수정 완료: mbrNo={}, 필드=name,email", mbrNo);

// ❌ 금지: 원본 CI 로그
log.debug("CI: {}", rawCi); // 절대 금지!

// ❌ 금지: 마스킹 없이 전화번호 로그
log.info("phone: {}", phone); // 금지

// ✅ 마스킹 후 로그
log.info("phone: {}", piiMaskingService.maskPhone(phone));
```

### 8.3 API 응답 규칙

```
응답에 포함 가능한 정보:
  ✅ qimUserId (UUID)
  ✅ DI (기관별 마스킹 불필요 — 이미 HMAC 해시)
  ✅ 마스킹된 이름/전화번호/이메일 (조회 API)
  ✅ 회원 상태 (ACTIVE/SUSPENDED/WITHDRAWN)
  ✅ ci_key_version (암호화 버전)

응답에 절대 포함 불가:
  ❌ 원본 CI
  ❌ identifierHash (역산 방지)
  ❌ 복호화된 PII 원문 (조회 API 기준)
  ❌ AES 키 정보
```

---

## 9. 개인정보 파기 스케줄러

### 9.1 개요

개인정보 보호법에 따라 보유 기간이 만료된 회원의 PII(개인식별정보)를 파기합니다.

```java
@Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시
@ConditionalOnProperty(name = "IDO_RETENTION_ENABLED", havingValue = "true")
public void purgeExpiredPersonalData() {
    boolean dryRun = Boolean.parseBoolean(env.getProperty("IDO_RETENTION_DRY_RUN", "false"));
    
    List<UserProfile> expired = userProfileRepository.findExpiredRetention(
        LocalDateTime.now().minusDays(retentionDays) // 기본 3년
    );
    
    for (UserProfile profile : expired) {
        if (dryRun) {
            log.info("[RetentionDryRun] 파기 대상: mbrNo={}", profile.getMbrNo());
            continue;
        }
        // PII 파기 (NULL 처리)
        profile.setPhoneNumber(null);
        profile.setEmail(null);
        profile.setEncryptedCi(null);
        profile.setIdentifierHash(null);
        profile.setRetentionDeletedAt(Instant.now());
        userProfileRepository.save(profile);
        
        log.info("[Retention] PII 파기 완료: mbrNo={}", profile.getMbrNo());
    }
}
```

### 9.2 Feature Flag 제어

```yaml
# K8s ConfigMap (infra/k8s/configmaps/qim-configmap.yml)
data:
  IDO_RETENTION_ENABLED: "true"    # 파기 스케줄러 활성화
  IDO_RETENTION_DRY_RUN: "false"  # false=실제 파기, true=로그만
  IDO_RETENTION_DAYS: "1095"      # 보유 기간 (일) — 3년 = 1095일
```

### 9.3 파기 전 체크리스트

- [ ] 법적 보유 기간 검토 (서비스 유형에 따라 상이)
- [ ] `IDO_RETENTION_DRY_RUN=true`로 먼저 대상 목록 확인
- [ ] 파기 대상 백업 완료 (법적 보관 의무 대상 제외)
- [ ] 파기 후 Outbox 이벤트 발행 확인 (`USER_DATA_PURGED`)
- [ ] 감사 로그 기록 확인

---

## 10. Transactional Outbox 패턴

### 10.1 개요

Q-IM은 Kafka를 직접 발행하지 않고 **Transactional Outbox**를 통해 이벤트를 발행합니다.

```
서비스 로직 + Outbox INSERT (동일 트랜잭션)
    ↓
OutboxScheduler (15초 주기)
    ↓ PENDING → KAFKA 발행 → PUBLISHED
Kafka(qim.user.events) 토픽
```

### 10.2 Outbox 이벤트 목록

| 이벤트 타입 | 트리거 | 컨슈머 |
|------------|--------|--------|
| `USER_REGISTERED` | 회원 등록 | IdO (FeAdvisory 처리) |
| `USER_STATUS_CHANGED` | 상태 전이 | IdO, Q-Sign |
| `USER_PROFILE_UPDATED` | 정보 수정 | IdO (기관 Webhook 적재) |
| `USER_DATA_PURGED` | PII 파기 | 감사 로그 |
| `USER_WITHDRAWN` | 탈퇴 | IdO, Q-Sign |

### 10.3 OutboxServiceImpl 핵심 코드

```java
@Service
@Transactional
public class OutboxServiceImpl implements OutboxService {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void enqueue(UserEvent eventType, Object payload) {
        OutboxRecord record = OutboxRecord.builder()
            .id(UUID.randomUUID().toString())
            .eventType(eventType.name())
            .payload(objectMapper.writeValueAsString(payload))
            .status(OutboxStatus.PENDING)
            .createdAt(Instant.now())
            .build();
        outboxRepository.save(record);
        // ★ 커밋 성공 시에만 Kafka 발행 — 트랜잭션 원자성 보장
    }

    @Scheduled(fixedDelay = 15_000)
    @Transactional
    public void relayPendingEvents() {
        List<OutboxRecord> pending = outboxRepository.findPending(100);
        for (OutboxRecord record : pending) {
            try {
                kafkaTemplate.send("qim.user.events", record.getId(), record.getPayload())
                    .get(5, TimeUnit.SECONDS);
                record.setStatus(OutboxStatus.PUBLISHED);
                outboxRepository.save(record);
            } catch (Exception e) {
                log.error("[Outbox] 발행 실패: id={}, 사유={}", record.getId(), e.getMessage());
                // ⚠️ GAP-QIM-04: markFailed() 미구현 — retry_count 증가 필요
                // TODO: record.incrementRetryCount(); record.setStatus(FAILED);
            }
        }
    }
}
```

### 10.4 GAP-QIM-04 해결 가이드 (미구현 항목)

현재 Outbox 릴레이 실패 시 `retry_count`가 증가하지 않아 영구적으로 재시도됩니다.

```java
// 권장 수정 방향
// 1. OutboxRecord에 retry_count 필드 추가 (V4 마이그레이션)
// 2. relayPendingEvents()에서 실패 시:
record.incrementRetryCount();
if (record.getRetryCount() >= 5) {
    record.setStatus(OutboxStatus.DEAD_LETTER);
    // 알림 발송 (Slack/PagerDuty)
}
outboxRepository.save(record);
```

---

## 11. 멱등 처리

### 11.1 processed_event 테이블

Q-IM은 Kafka 컨슈머가 동일 이벤트를 중복 처리하지 않도록 `processed_event` 테이블을 사용합니다.

```sql
-- V2__add_idempotent_consumer.sql
CREATE TABLE qim.processed_event (
    event_id    VARCHAR(36) PRIMARY KEY,  -- Kafka 메시지 key
    topic       VARCHAR(100) NOT NULL,
    processed_at DATETIME(6) NOT NULL
);
```

### 11.2 멱등 처리 패턴

```java
@KafkaListener(topics = "platform.ido.slo-completed", groupId = "qim-consumer")
@Transactional
public void onSloCompleted(ConsumerRecord<String, String> record) {
    String eventId = record.key();

    // 중복 확인
    if (processedEventRepository.existsById(eventId)) {
        log.warn("[QimConsumer] 중복 이벤트 무시: eventId={}", eventId);
        return;
    }

    // 비즈니스 로직 실행
    SloCompletedEvent event = objectMapper.readValue(record.value(), SloCompletedEvent.class);
    userService.handleSloCompleted(event.getMbrNo());

    // 처리 완료 마킹 (동일 트랜잭션)
    processedEventRepository.save(ProcessedEvent.of(eventId, record.topic()));
}
```

---

## 12. Flyway 마이그레이션 가이드

### 12.1 현재 마이그레이션 현황

| 버전 | 파일 | 내용 | 상태 |
|------|------|------|------|
| V1 | `V1__create_schema.sql` | 기본 테이블 (qim_user, user_profile, auth_mean_mapping, outbox_record) | ✅ |
| V2 | `V2__add_idempotent_consumer.sql` | 멱등 처리 테이블 (processed_event, snapshot_meta, last_event_version) | ✅ |
| V3 | `V3__add_ci_encryption_and_status_history.sql` | CI 키 버전 컬럼, user_status_history 테이블 | ✅ |

### 12.2 새 마이그레이션 작성 규칙

```sql
-- 파일 위치: idem-registry/src/main/resources/db/migration/V4__<설명>.sql
-- 명명 규칙: V{숫자}__{스네이크케이스_설명}.sql

-- ✅ 좋은 예
-- V4__add_retention_deleted_at.sql
ALTER TABLE qim.user_profile
    ADD COLUMN retention_deleted_at DATETIME(6) NULL COMMENT '개인정보 파기 일시';
CREATE INDEX idx_user_profile_retention
    ON qim.user_profile (retention_deleted_at);

-- ❌ 나쁜 예
-- 기존 마이그레이션 파일 수정 (Flyway checksum 오류 발생!)
-- DROP TABLE 또는 데이터 삭제 (롤백 불가)
```

### 12.3 마이그레이션 체크섬 오류 해결

```bash
# 로컬 개발 중 마이그레이션 파일을 수정한 경우
# (운영 환경에서는 절대 사용 금지)
./gradlew :idem-registry:flywayRepair

# 또는 checksum 강제 업데이트
./gradlew :idem-registry:flywayValidate
```

---

## 13. 테스트 작성 가이드

### 13.1 테스트 구조

```
idem-registry/src/test/java/kr/go/smes/qim/
├── crypto/
│   └── CiCryptoServiceImplTest.java    # AES-256-GCM 암복호화 검증
├── identity/
│   └── DiGenerationServiceTest.java    # DI HMAC 결정성 검증
├── application/
│   └── UserServiceImplTest.java        # 회원 등록/수정/탈퇴 로직
├── outbox/
│   └── OutboxServiceImplTest.java      # Outbox 릴레이, 멱등 처리
└── api/
    └── MemberLookupControllerTest.java # MockMvc 통합 테스트
```

### 13.2 CiCryptoServiceImplTest 예시

```java
@ExtendWith(MockitoExtension.class)
class CiCryptoServiceImplTest {

    private CiCryptoService ciCryptoService;

    @BeforeEach
    void setUp() {
        // 32바이트 테스트 키 (실제 운영 키 미사용)
        String testKey = "12345678901234567890123456789012";
        ciCryptoService = new CiCryptoServiceImpl(testKey);
    }

    @Test
    @DisplayName("암호화→복호화 라운드트립이 원본을 복원해야 한다")
    void encryptDecryptRoundTrip() {
        String rawCi = "8101011234567890"; // 테스트용 CI
        String encrypted = ciCryptoService.encrypt(rawCi);

        // 포맷 검증: v{n}.{iv}.{ct}
        assertThat(encrypted).matches("v\\d+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

        // 복호화 검증
        assertThat(ciCryptoService.decrypt(encrypted)).isEqualTo(rawCi);
    }

    @Test
    @DisplayName("동일 CI를 두 번 암호화하면 서로 다른 결과가 나와야 한다 (IV 랜덤성)")
    void sameInputProducesDifferentCiphertext() {
        String rawCi = "8101011234567890";
        String enc1 = ciCryptoService.encrypt(rawCi);
        String enc2 = ciCryptoService.encrypt(rawCi);

        // IV가 다르므로 암호문이 달라야 함
        assertThat(enc1).isNotEqualTo(enc2);

        // 그러나 둘 다 복호화하면 원본과 같아야 함
        assertThat(ciCryptoService.decrypt(enc1)).isEqualTo(rawCi);
        assertThat(ciCryptoService.decrypt(enc2)).isEqualTo(rawCi);
    }

    @Test
    @DisplayName("알 수 없는 키 버전이면 예외가 발생해야 한다")
    void unknownKeyVersionThrows() {
        String invalidVersioned = "v99.dGVzdA.dGVzdA";
        assertThatThrownBy(() -> ciCryptoService.decrypt(invalidVersioned))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("v99");
    }
}
```

### 13.3 UserServiceImplTest 예시

```java
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserProfileRepository userProfileRepository;
    @Mock private OutboxService outboxService;
    @Mock private PiiMaskingService piiMaskingService;

    @InjectMocks private UserServiceImpl userService;

    @Test
    @DisplayName("개인회원 이름 수정 시 PII가 올바르게 업데이트되어야 한다")
    void updateMember_updatesNameCorrectly() {
        // given
        String mbrNo = "MBR-001";
        UserProfile existing = UserProfile.builder()
            .mbrNo(mbrNo).memberName("홍길동").build();
        when(userProfileRepository.findByMbrNo(mbrNo)).thenReturn(Optional.of(existing));

        UpdateMemberRequest request = new UpdateMemberRequest("홍길순", "010-1111-2222", null);

        // when
        userService.updateMember(mbrNo, request);

        // then
        assertThat(existing.getMemberName()).isEqualTo("홍길순");
        assertThat(existing.getPhoneNumber()).isEqualTo("010-1111-2222");
        verify(userProfileRepository).save(existing);
        verify(outboxService).enqueue(eq(UserEvent.USER_PROFILE_UPDATED), any());
    }

    @Test
    @DisplayName("존재하지 않는 회원 수정 시 MemberNotFoundException 발생")
    void updateMember_notFound_throwsException() {
        when(userProfileRepository.findByMbrNo("UNKNOWN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.updateMember("UNKNOWN",
                new UpdateMemberRequest("홍길동", null, null)))
            .isInstanceOf(MemberNotFoundException.class);
    }
}
```

### 13.4 테스트 커버리지 목표

| 모듈 | 현재 커버리지 | 목표 |
|------|-------------|------|
| `crypto/` | 0% (미작성) | **90%** (보안 핵심 모듈) |
| `application/` | 0% (미작성) | **80%** |
| `outbox/` | 0% (미작성) | **70%** |
| `api/` | 0% (미작성) | **60%** (통합 테스트) |

> 현재 Q-IM은 단위 테스트가 **전무**합니다. 신규 기능 개발 시 반드시 테스트를 먼저 작성하세요.

---

## 14. 자주 발생하는 오류 및 해결

### Case 1: `AEADBadTagException` — CI 복호화 실패

```
javax.crypto.AEADBadTagException: Tag mismatch!
```

**원인**: 암호화에 사용한 키와 복호화 키가 다름 (키 버전 불일치 또는 키 교체 후 미반영)

**해결**:
```bash
# 1. 문제 레코드의 ci_key_version 확인
SELECT ci_key_version, mbrno FROM user_profile WHERE mbrno = 'MBR-001';

# 2. application.yml에 해당 버전 키가 등록되어 있는지 확인
# qim.ci.aes-key-v1: ...  ← 키 존재 여부 확인
```

### Case 2: Flyway Checksum 오류

```
FlywayException: Detected failed migration to version 3
```

**원인**: 이미 적용된 마이그레이션 파일이 수정됨

**해결 (로컬만 가능)**:
```bash
./gradlew :idem-registry:flywayRepair --args='--spring.profiles.active=local'
```

**해결 (운영)**: 절대 기존 마이그레이션 수정 금지 — 새 V{n+1} 파일로 수정사항 적용

### Case 3: Outbox 릴레이 무한 재시도

```
[Outbox] 발행 실패: id=abc123, 사유=Connection refused
```

**원인**: Kafka 브로커 연결 불가 또는 `retry_count` 제한 미구현 (GAP-QIM-04)

**임시 해결**:
```sql
-- 문제 Outbox 레코드 수동 확인
SELECT * FROM qim.outbox_record WHERE status = 'PENDING' ORDER BY created_at;

-- 영구 실패로 표시 (임시 조치)
UPDATE qim.outbox_record SET status = 'DEAD_LETTER'
WHERE id = 'abc123...' AND status = 'PENDING';
```

### Case 4: DI 생성 시 `di_map` JSON 파싱 오류

```
JsonParseException: Unexpected character at position 0
```

**원인**: `qim_user.di_map` 컬럼이 NULL 또는 빈 문자열

**해결**:
```java
// DiGenerationService.java에서 NULL 방어 처리
private Map<String, String> parseDiMap(String diMapJson) {
    if (diMapJson == null || diMapJson.isBlank()) {
        return new HashMap<>(); // ← NULL 방어
    }
    return objectMapper.readValue(diMapJson, MAP_TYPE);
}
```

### Case 5: X-Internal-Api-Key 인증 실패

```
HTTP 401 Unauthorized
```

**원인**: IdO와 Q-IM의 내부 API 키가 불일치

**확인**:
```bash
# IdO 설정
kubectl get secret ido-secrets -n smes -o jsonpath='{.data.INTERNAL_API_KEY}' | base64 -d

# Q-IM 설정
kubectl get secret qim-secrets -n smes -o jsonpath='{.data.INTERNAL_API_KEY}' | base64 -d
# 두 값이 동일해야 함
```

---

## 15. 코딩 컨벤션 및 체크리스트

### 15.1 Q-IM 전용 코딩 규칙

```java
// ✅ CI/PII 관련 변수명은 명시적으로
String encryptedCi = ciCryptoService.encrypt(rawCi);   // 암호화됨
String rawCi = ciCryptoService.decrypt(encryptedCi);   // 복호화됨 (함수 내부에서만 사용)

// ❌ 혼용 금지
String ci = ciCryptoService.encrypt(rawCi);  // 암호화 여부 불명확

// ✅ 메서드 경계에서 rawCi는 즉시 소멸
public void registerMember(String encryptedCi) {
    String rawCi = ciCryptoService.decrypt(encryptedCi);
    String hash = identifierHashService.hash(rawCi);
    rawCi = null; // ← rawCi 참조 즉시 해제 (GC 대상)
    // 이후 로직에서 rawCi 사용 불가
}
```

### 15.2 새 기능 개발 체크리스트

**PR 제출 전 필수 확인 사항:**

```
□ CI/PII 원본 데이터가 로그에 포함되지 않았는가?
□ identifierHash가 API 응답에 포함되지 않았는가?
□ 새 DTO에 @Valid + @NotBlank/@Email/@Pattern 검증 어노테이션 추가했는가?
□ 새 이벤트 타입을 OutboxService.enqueue() 호출로 발행하는가?
□ Outbox enqueue와 비즈니스 로직이 동일 트랜잭션 내에 있는가?
□ 새 API가 X-Internal-Api-Key 검증을 통과하는가?
□ 단위 테스트 작성했는가? (특히 crypto/ 패키지)
□ Flyway 마이그레이션이 필요한 경우 새 파일 작성했는가? (기존 파일 수정 금지)
□ 새 환경변수가 있다면 README.md와 qim-configmap.yml에 반영했는가?
```

### 15.3 절대 금지 사항

```
❌ 원본 CI를 응답 body에 포함
❌ 원본 CI를 로그에 출력 (DEBUG 레벨 포함)
❌ identifierHash를 외부 API 응답에 포함
❌ di_map JSON을 그대로 응답에 포함
❌ AES 키를 소스코드에 하드코딩
❌ 내부 API(/api/ext/, /api/v1/)를 외부(Nginx)에서 직접 노출
❌ @Transactional 없이 Outbox.enqueue() 호출
❌ 기존 Flyway 마이그레이션 파일 수정
❌ rawCi 변수를 메서드 경계 밖으로 전달
```

### 15.4 로깅 컨벤션

```java
// Q-IM 로그 prefix 형식
log.info("[QIM-Registration] 회원 등록 완료: qimUserId={}", qimUserId);
log.info("[QIM-Update] 프로필 수정: mbrNo={}, 수정필드={}", mbrNo, changedFields);
log.warn("[QIM-Outbox] 릴레이 실패 ({}회 시도): id={}", retryCount, outboxId);
log.error("[QIM-Crypto] 복호화 실패: version={}", keyVersion);

// 절대 금지
log.debug("[QIM] CI: {}", rawCi);              // ❌
log.info("[QIM] hash={}", identifierHash);     // ❌
log.info("[QIM] 전화번호: {}", phoneNumber);   // ❌ 마스킹 없이 금지
```

---

*이전 문서: [guide-backend-ido.md](guide-backend-ido.md)*  
*다음 문서: [guide-frontend.md](guide-frontend.md)*  
*관련 문서: [05-module-qim.md](05-module-qim.md) · [10-security.md](10-security.md)*
