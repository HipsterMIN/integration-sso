# Phase 3 — Q-IM 식별·매핑 검증 (CI/DI/존재적 식별)

> **검증 범위**: identifierHash → qimUserId 매핑, CI 암호화/복호화, 기관별 DI, 동의·탈퇴·전환 세션 상태기계
> **관점**: "이 기능은 구현되어 있다"가 아니라 "실 사용자가 이 코드로 막힐 시나리오가 무엇인가"
> **연관 SoR**: Q-IM (MariaDB `qim` DB) — 식별·매핑·동의·탈퇴·전환 정본
> **선행 분석**: Phase 1 (아키텍처), Phase 2 (인증 흐름)

---

## 3.1 식별 체인 토폴로지

### 3.1.1 흐름 A — Keycloak 소셜 콜백 (KAKAO / NAVER)

```
사용자 ─► Keycloak ID Token (sub="kakao-12345") ─► IdO KeycloakOidcService
        │
        │  ① SHA-256(sub) → identifierHash      (IdO HexFormat hex)
        │
        ├─► QimClient.findBySocialSub(sub, providerCode)
        │     └─► POST /api/v1/internal/users/find-by-social-sub
        │           │  body: { sub, providerCode }
        │           ▼
        │       Q-IM UserController.findBySocialSub
        │         ② SHA-256(sub) → identifierHash  (Q-IM HexFormat hex)  ← 동일 알고리즘
        │         ③ findByIdentifierHashAndProviderCode(hash, code)
        │              → ACTIVE 매핑만 조회
        │         200 → 기존 qimUserId / 404 → 미등록
        │
        └─► (404 시) QimClient.registerSocialUser(sub, providerCode, identifierHash)
              └─► POST /api/v1/internal/users/register-social
                    │  body: { sub, providerCode, identifierHash }
                    ▼
                Q-IM UserController.registerSocialUser
                  ④ 경합 방어: 재조회 → 있으면 200 OK
                  ⑤ qimUserId 발급(UUIDv7), qim_user + auth_mean_mapping INSERT
                  ⑥ Outbox: UserEvent(SOCIAL_USER_REGISTERED) publishInTx
                  201 Created
```

### 3.1.2 흐름 B — PASS/CI 본인확인 (NonOIDC)

```
사용자 ─► PASS/KICA/NICE/KCB ─► IdO NonOidcAuthService
        │
        │  ① rawIdentifier(CI 평문) 수신
        │  ② SHA-256(rawCi) → identifierHash       (IdO HexFormat hex)
        │  ③ Q-Sign /api/v1/auth/broker-input (HMAC X-Internal-Sig)
        │
        └─► (이후 통합계정 전환 시) Q-IM 내부 흐름
              ├─► UserRegistrationService.registerOrGet(req)
              │     req.identifierHash, req.rawCi
              │     • 기존: findByIdentifierHash(req.identifierHash) → 200
              │     • 신규: CiCryptoService.encrypt(rawCi) → user_profile.ci 저장
              │
              └─► MemberLookupController.lookupByCi(encryptedCi)
                    ⑦ CiCryptoService.decrypt(encryptedCi) → rawCi
                    ⑧ ★ SHA-256(rawCi) → Base64URL withoutPadding ★  ← 인코딩 불일치!
                    ⑨ findByIdentifierHash(hash) → ALWAYS 404
```

### 3.1.3 식별자 분류

| 식별자 | 정의 | SoR | 인코딩 | 가역성 |
|--------|------|-----|--------|--------|
| **identifierHash** | SHA-256(sub) 또는 SHA-256(CI) | auth_mean_mapping.identifier_hash | hex 또는 Base64URL (불일치) | 단방향 |
| **qimUserId** | 플랫폼 전역 사용자 ID | qim_user.qim_user_id | UUIDv7 | 비밀번호적 비밀 아님 |
| **CI 암호문** | AES-256-GCM(rawCi) | user_profile.ci | `v1.{ivB64}.{ctB64}` | 가역 (Q-IM 내부만) |
| **DI** | HMAC-SHA256(agencyCode:qimUserId, diSecret) | user_profile.di_map[agency] | Base64URL | 단방향 |

---

## 3.2 발견 사항 (F3.x)

### F3.1 [**Critical**] SHA-256 인코딩 불일치 — CI 기반 조회 영구 실패

**위치**:
- `idem-registry/.../api/MemberLookupController.java:141-149` (Base64URL)
- `idem-registry/.../api/UserController.java:378-386` (hex)
- `idem-gate/.../keycloak/KeycloakCallbackService.java:240` (hex)
- `idem-gate/.../application/AuthServiceImpl.java:199` (hex)
- `idem-hub/.../broker/keycloak/KeycloakOidcService.java:325` (hex)
- `idem-hub/.../broker/nonoidc/NonOidcAuthService.java:262` (hex)
- `idem-hub/.../broker/nonoidc/NonOidcBrokerAdapter.java:263` (hex)
- `idem-hub/.../broker/nonoidc/NonOidcBrokerController.java:287` (hex)

**문제 상세**: 동일한 입력에 대해 **`MemberLookupController.sha256()`만 Base64URL withoutPadding을 사용**하고, 나머지 모든 8곳은 hex (소문자) 인코딩을 사용한다. CI 평문 `"S1234567890"`을 예로 들면:

```
HexFormat hex   = "fb84a4b5c6d7e8f9..." (64자)
Base64URL wOPad = "-4Skt-..." (43자)
→ 같은 hash가 절대로 일치하지 않음
```

**실 사용 시나리오 — 100% 실패**:
1. 사용자가 PASS로 본인확인 → IdO NonOidcAuthService: `identifierHash_DB = hex(sha256(CI))` 저장
2. 기관이 IdO Verify API 호출하기 위해 암호화된 CI 전달 → Q-IM `MemberLookupController.lookupByCi`
3. Q-IM이 CI 복호화 → `identifierHash_QUERY = base64url(sha256(CI))`
4. `findByIdentifierHash(identifierHash_QUERY)` → **항상 404 NOT FOUND**
5. → 기관이 사용자를 찾지 못함 → IM 조회 기능 완전히 동작 불가

**즉시 조치 필요**: `MemberLookupController.sha256()`을 `HexFormat.of().formatHex(hash)` 로 변경하여 일관성 확보.

---

### F3.2 [**Critical**] `findByIdentifierHash` 단일 hash 조회 — V4 마이그레이션 후 의미 모호

**위치**:
- `idem-registry/.../infrastructure/jpa/repository/QimUserJpaRepository.java:31`
- `idem-registry/.../api/UserController.java:117` (getUserByHash)
- `idem-registry/.../api/MemberLookupController.java:83, 107`
- `idem-registry/.../user/UserRegistrationServiceImpl.java:62` (registerOrGet)
- `idem-registry/.../conversion/ConversionSessionServiceImpl.java:194` 간접 호출

**문제 상세**: V4 마이그레이션(`uq_identifier_hash` 제거, `uq_identifier_hash_provider` 추가) 이후 **identifier_hash 단독으로는 더이상 unique가 아니다**. 그러나 `findByIdentifierHash(hash)`는 여전히 `Optional` (단일 결과)를 반환한다.

```java
@Query("SELECT u FROM QimUserJpaEntity u JOIN u.authMeanMappings m "
     + "WHERE m.identifierHash = :identifierHash AND m.status = 'ACTIVE'")
Optional<QimUserJpaEntity> findByIdentifierHash(@Param("identifierHash") String identifierHash);
```

**실 사용 시나리오** (자주는 아니지만 발생 시 데이터 무결성 손상):
- PASS로 가입한 사용자 A (provider=PASS, hash=`abc123`)
- Kakao sub가 우연히 동일 hash 생성 (provider=KAKAO_OIDC, hash=`abc123`)
- V4 이후 `auth_mean_mapping`은 두 행 모두 허용
- 외부에서 `findByIdentifierHash("abc123")` 호출 시 **JPA가 NonUniqueResultException을 throw**하거나 첫 번째 행을 임의로 반환

**현재 상태**:
- `UserRegistrationServiceImpl.registerOrGet`은 PASS/CI 흐름에서만 호출되므로 PASS 경로에서는 충돌 가능성이 낮음
- 그러나 **`MemberLookupController.lookupByHash` (외부 노출 API)는 provider 정보 없이 hash만 받는다** — F3.1과 결합되면 hash 충돌 시 잘못된 사용자 반환 위험

**권장**:
- `findByIdentifierHash` → 호출처마다 명시적으로 `findByIdentifierHashAndProviderCode` 또는 `List<QimUserJpaEntity>` 반환으로 마이그레이션
- 또는 PASS/CI 계열은 V4 UNIQUE 정책 예외로 한정 (PASS의 identifier_hash는 전역 unique라는 별도 제약 추가)

---

### F3.3 [**Critical**] CI 암호화 키 운영 환경 기본값 — Production 노출 위험

**위치**: `idem-registry/.../crypto/CiCryptoServiceImpl.java:40`

```java
@Value("${qim.crypto.ci.key-v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}")
private String aesKeyV1Base64;
```

**문제 상세**:
- 환경변수 `QIM_CI_AES_KEY_V1` 미설정 시 **`AAA...` 28개 + `=` (Base64 → 32바이트 전부 `0x00`)** 키를 사용
- 운영에서 환경변수 누락 시 모든 CI가 **알려진 키로 암호화**됨 → 사실상 평문 노출
- `@PostConstruct` 검증 없음 — 키 미설정 알람 메커니즘 부재
- `idem-gate/KeycloakCallbackService:80`의 `ido-internal-secret` 기본값(Phase 2 F2.1)과 동일 패턴

**실 사용 시나리오**:
- DevOps가 K8s Secret 누락 → Pod 정상 기동 → 모든 신규 사용자 CI가 약한 키로 암호화됨
- 키 노출 사건 발생 시 모든 사용자 CI 원문 일괄 복호화 가능
- 키 로테이션 시 `currentVersion` 만 변경 → 구 데이터는 약한 키로 남음

**권장**:
- 기본값 제거 (`@Value("${qim.crypto.ci.key-v1:}")`)
- `@PostConstruct` 검증: key가 빈값/약한 키면 애플리케이션 기동 실패
- KMS 통합 (Phase 5에서 별도 검증)

---

### F3.4 [**Critical**] DI Secret 운영 환경 기본값 — 모든 기관 DI 위조 가능

**위치**: `idem-registry/.../identity/DiGenerationService.java:39`

```java
@Value("${qim.crypto.di.secret:default-di-secret-change-in-production}")
private String diSecret;
```

**문제 상세**:
- 환경변수 `QIM_DI_SECRET` 미설정 시 **`default-di-secret-change-in-production`** 사용
- DI는 HMAC-SHA256(agencyCode:qimUserId, diSecret) → secret이 알려지면 **누구나 임의 사용자의 모든 기관 DI 재현 가능**
- 기관이 DI를 신원 검증에 쓰면 → **공격자가 임의 사용자로 가장 가능**

**실 사용 시나리오**:
- 운영 환경변수 누락 → 기본 secret 사용
- 공격자가 q-im 코드 (오픈소스 / 내부 유출) 확인 → secret 추출
- 임의 qimUserId/agencyCode 조합으로 DI 생성 → 기관 API에 위조 DI로 사용자 사칭

**권장**:
- 기본값 제거
- 기동 시 secret 미설정이면 즉시 종료
- KMS 통합 + 키 로테이션 정책 (Phase 5)

---

### F3.5 [High] CI 복호화 평문 fallback — 부분 마이그레이션 데이터의 무방비 노출

**위치**: `idem-registry/.../crypto/CiCryptoServiceImpl.java:81-88`

```java
public String decrypt(String encryptedCi) {
    ...
    if (!isEncrypted(encryptedCi)) {
        log.warn("[CiCrypto] 암호화되지 않은 CI 값 복호화 시도 — 평문 그대로 반환");
        return encryptedCi;
    }
    ...
}
```

**문제 상세**:
- `isEncrypted` 체크는 정규식 `^v\d+\..+\..+$`만 검사
- V3 마이그레이션 이전 데이터(평문 CI)가 남아있다면 **그대로 반환**되어 호출자에게 노출
- 운영 시점에 V3 마이그레이션이 완료되었는지 코드 차원에서 강제 불가
- WARN 로그만 남기고 정상 동작 — 사일런트 실패의 변형 (Phase 2 F2.11과 유사)

**권장**:
- 평문 fallback 제거 → 예외 throw (`CiCryptoException`)
- V3 마이그레이션 검증 쿼리를 기동 시 실행 (count(*) where ci NOT LIKE 'v%')

---

### F3.6 [High] 전환 세션 동시 시작 — 다중 ACTIVE 세션 가능

**위치**:
- `idem-registry/.../conversion/ConversionSessionServiceImpl.java:60` (initiate)
- `idem-registry/.../infrastructure/jpa/repository/ConversionSessionJpaRepository.java:20` (findActiveByUser)
- `idem-registry/src/main/resources/db/migration/V5__withdrawal_consent_conversion.sql:96` (conversion_session UNIQUE 미존재)

**문제 상세**:
- `findActiveByUser(qimUserId, now)`는 `LIMIT 1` + ORDER BY createdAt DESC → 가장 최근 1건만 보고 판단
- conversion_session 테이블에 `(qim_user_id, status NOT IN (...))` UNIQUE 제약 없음
- 트랜잭션 격리도 READ COMMITTED + 동시 요청 2건이 모두 `findActiveByUser=empty` → **둘 다 새 세션 INSERT 성공**
- 결과: 동일 사용자에게 2개의 ACTIVE 세션 → 후보 조회/연결 시 어느 세션이 정본인지 모호

**실 사용 시나리오**:
- 사용자가 통합전환 화면에서 더블 클릭
- 서로 다른 디바이스에서 동시 전환 시도
- 결과: 첫 세션의 selectedAgencyCodes와 두 번째 세션의 link가 엇갈려 부분 연결 발생

**권장**:
- `conversion_session`에 부분 UNIQUE 인덱스 (`qim_user_id, status` where status NOT IN (...)) — MariaDB는 함수 기반 UNIQUE 미지원 → 별도 컬럼 `is_active` 추가하고 partial unique 시뮬레이션
- 또는 `initiate()` 트랜잭션 격리도를 SERIALIZABLE 또는 분산 락 (Redis SETNX) 사용

---

### F3.7 [High] 동의 동시 INSERT — 같은 type 다중 AGREED 레코드

**위치**:
- `idem-registry/.../consent/ConsentServiceImpl.java:41-66` (agree)
- `idem-registry/src/main/resources/db/migration/V5__withdrawal_consent_conversion.sql:63` (consent_record UNIQUE 미존재)

**문제 상세**:
- `agree()`는 항상 새 record INSERT (이력 보존 원칙)
- 사용자가 같은 consent_type을 동시에 두 번 클릭 → 2개의 AGREED 레코드 생성
- `findLatestByUserAndType`이 가장 최근 1건만 반환하므로 표면적으로는 정상으로 보이나, 감사 추적 시 중복 이벤트 노이즈
- 동의 철회(`withdraw()`) 시 가장 최근 1건만 WITHDRAWN으로 표시 — 직전 AGREED 레코드는 여전히 AGREED로 남음 → 동의 상태 모호

**권장**:
- 동의는 idempotent 이어야 함 — `(qim_user_id, version_id)` UNIQUE 제약 추가 또는 application-level dedupe
- 또는 동의는 멱등으로 redirect (이미 있으면 200 + 기존 record 반환)

---

### F3.8 [High] PII 삭제가 중복 정의 — 단일 책임 위반 + 코드 동기화 위험

**위치**:
- `idem-registry/.../user/UserRegistrationServiceImpl.java:202-217` (deletePii)
- `idem-registry/.../withdrawal/WithdrawalServiceImpl.java:255-270` (deletePii)

**문제 상세**:
- 동일한 `UPDATE user_profile SET name_masked=NULL, ..., ci=NULL, ...` 쿼리가 **두 곳에 똑같이 복사**되어 있음
- V6 마이그레이션에서 `guardian_qim_user_id`, `guardian_consent_at` 컬럼 추가 시 둘 다 업데이트했지만, 향후 PII 컬럼 추가 시 한 곳만 업데이트하면 PII가 남음
- 양쪽 모두 `auth_mean_mapping` 의 identifier_hash는 **삭제하지 않음** — GDPR §17 위반 가능성

**실 사용 시나리오**:
- 사용자가 탈퇴 → user_profile PII는 NULL이지만 auth_mean_mapping.identifier_hash는 그대로 (`SHA-256(CI)` 단방향이지만 동일 사용자가 같은 CI로 재가입 시 hash 재계산하면 매핑됨 → 사실상 영속 식별자)
- 개인정보보호위원회 감사에서 "탈퇴 후에도 식별자가 보관되어 있다"고 지적될 위험

**권장**:
- `PiiDeletionService` 단일 책임 분리 (현재 UserRegistration / Withdrawal 양쪽 중복)
- 탈퇴 시 auth_mean_mapping을 REVOKED 상태로 전환 + identifier_hash 재해시(salt 추가) 또는 NULL 처리
- 컬럼 추가 시 자동 반영을 위해 `user_profile` PII 컬럼을 어노테이션/메타데이터로 관리

---

### F3.9 [High] DI 저장 누락 시 매번 재계산 — 멱등성은 OK이지만 di_map 갱신 누락 가능

**위치**: `idem-registry/.../api/UserController.java:159-186` (getDi)

```java
boolean alreadyExists = diGenerationService.parseDiMap(currentDiMap).containsKey(agencyCode);
String di = diGenerationService.getOrCreateDi(qimUserId, agencyCode, currentDiMap);

if (!alreadyExists && profile != null) {
    String updatedDiMap = diGenerationService.addDiToMap(currentDiMap, agencyCode, di);
    profile.setDiMap(updatedDiMap);  // ← JPA dirty checking 의존
}
```

**문제 상세**:
- `profile.setDiMap()` 만 호출 — `userRepository.save(profile)` 명시적 호출 없음
- `@Transactional` 안에서 JPA dirty checking으로 flush되지만, **profile이 detached 상태이면 갱신 안 됨**
- `getDi`는 `@Transactional` 가 적용되어 있으나 `userRepository.findById(qimUserId)`로 가져온 user는 lazy 로딩 → profile fetch 시점에 따라 detached 위험
- DI는 결정론적이므로 매번 재계산해도 같은 값이지만, **저장이 안 되면 매 호출마다 신규 INSERT가 되어 INFO 로그가 누적 + audit log 노이즈**

**실 사용 시나리오**:
- 기관이 동일 사용자의 DI를 100번 조회 → log "신규 DI 생성"이 100번 찍힘
- 운영팀이 "DI 신규 생성"이 비정상으로 많다고 판단 → 디버깅 시간 소모

**권장**:
- `userRepository.save(user)` 명시 호출
- 또는 별도 `diRepository.upsertDi(qimUserId, agencyCode, di)` 메서드 사용

---

### F3.10 [Medium] Conversion fallback hash가 qimUserId 자체 — 다른 기관 조회 결과와 매칭 불가

**위치**: `idem-registry/.../conversion/ConversionSessionServiceImpl.java:192-211` (resolveIdentifierHash)

```java
private String resolveIdentifierHash(String qimUserId) {
    // ① 운영 DB에서 PASS/CI 계열 identifierHash 조회
    var dbHash = authMeanMappingRepository.findActivePassCiHash(qimUserId);
    if (dbHash.isPresent()) return dbHash.get();

    // ② Fallback — qimUserId 자체를 SHA-256
    log.warn("[Conversion] PASS/CI identifierHash 미등록 — qimUserId SHA-256 Fallback 사용...");
    return HexFormat.of().formatHex(md.digest(qimUserId.getBytes(...)));
}
```

**문제 상세**:
- PASS/CI 매핑이 없으면 `qimUserId`를 hash → 기관 stub `/api/v1/members/lookup` 으로 전달
- 기관 측은 **`SHA-256(CI)` 기준**으로 회원을 저장하므로 `SHA-256(qimUserId)`로는 절대 매칭 안 됨
- **결과: 소셜 로그인만 한 사용자는 통합전환 후보 조회가 항상 0건**
- WARN 로그는 남기지만 사용자에게는 "후보 없음"으로 표시 → 사용자는 자기 계정이 어디 있는지 알 수 없음

**실 사용 시나리오**:
- KAKAO 로그인한 신규 사용자 → 통합전환 시도
- IdO/Q-IM 입장: identifierHash 매핑 없음 → fallback qimUserId hash 사용
- 68개 기관 모두 lookup → 모두 found=false → 후보 0건 반환
- 사용자: "내 기존 정부24 계정이 안 보여요" → 운영팀 문의 폭주

**권장**:
- Fallback을 발생시키지 말고 명시적 에러 반환 ("본인확인이 필요합니다")
- 또는 PASS/CI 본인확인 단계를 통합전환 진입점에 강제

---

### F3.11 [Medium] CI 복호화 실패 시 동일 응답 코드 — Oracle attack 우려

**위치**: `idem-registry/.../api/MemberLookupController.java:71-77`

```java
try {
    rawCi = ciCryptoService.decrypt(encryptedCi);
} catch (Exception e) {
    return ResponseEntity.badRequest().body(Map.of(
            "error", "CI_DECRYPT_FAILED",
            "message", "CI 복호화에 실패했습니다"));
}
```

**문제 상세**:
- 복호화 실패의 모든 원인을 동일 메시지/코드로 반환 (인증 키 오류, 패딩 오류, 만료 등 구분 없음)
- 좋은 점: 정보 노출 최소화 (의도된 설계)
- 나쁜 점: 호출자(기관)가 원인을 알 수 없어 디버깅 불가 → 운영팀 의존성↑
- **GCM authentication tag 검증 실패**와 **버전 미지원**을 동일하게 처리 → 키 로테이션 디버깅 곤란

**권장**:
- 내부 로그에는 cause를 명확히 기록 (현재 `log.warn`은 좋음)
- 외부 응답은 동일 유지 (보안 OK), 내부 cid로 추적 가능하도록 correlationId 강조

---

### F3.12 [Medium] InternalApiKeyInterceptor — actuator/Swagger excludePath 미설정

**위치**:
- `idem-registry/.../config/InternalApiKeyInterceptor.java`
- `idem-registry/.../config/QimWebMvcConfig.java:42-45`

**문제 상세**:
- `QimWebMvcConfig`은 `/api/v1/internal/**` 에만 인터셉터를 적용
- 문서에는 "Actuator / Swagger 경로 제외"라고 했으나 코드에는 `excludePathPatterns` 호출 자체가 없음
- 결과적으로 Actuator는 path matching에서 제외됨 (다른 prefix) — 안전
- 그러나 향후 `/api/v1/internal/actuator/**` 같은 경로가 추가되면 의도와 어긋날 위험

**권장**:
- 명시적 `excludePathPatterns("/actuator/**", "/api/v1/users/**", "/swagger-ui/**", "/v3/api-docs/**")` 추가
- 또는 인터셉터 적용 경로를 좁게 명시 (예: `/api/v1/internal/users/**`, `/api/v1/internal/conversion/**` 개별 등록)

---

### F3.13 [Medium] register-social 정상 200 OK + isNew=false 응답 — IdO 측 처리 모호

**위치**: `idem-registry/.../api/UserController.java:313-324` (registerSocialUser 경합 방어)

```java
if (existing.isPresent()) {
    log.info("[UserCtrl][registerSocial] 경합 감지 — 기존 사용자 반환: ...");
    return ResponseEntity.ok(UserResponse.builder()
            .qimUserId(existing.get().getQimUserId())
            .isNew(false)
            ...);
}
```

**문제 상세**:
- 정상 신규 등록은 `201 Created + isNew=true`
- 경합으로 기존 사용자 반환 시 `200 OK + isNew=false`
- IdO 측 `QimClientImpl.registerSocialUser` 는 200/201 둘 다 받아야 함 — 만약 201만 처리하면 경합 시 사용자 정보 누락
- 실제 IdO 구현 검증 필요 (Phase 4에서 다룸)

**권장**:
- HTTP 응답 코드를 모두 200으로 통일 (REST 의미상 register는 201이 표준이지만, 멱등 보장 시 200도 허용)
- 또는 IdO 클라이언트에서 두 상태 모두 동일하게 처리하는지 명시

---

### F3.14 [Low] Q-IM Outbox는 자체 Kafka producer 직접 사용 — outbox-relay-batch와 책임 중복

**위치**: `idem-registry/.../outbox/OutboxServiceImpl.java:93-103, 167-197`

**문제 상세**:
- Q-IM 자체 스케줄러(`@Scheduled(fixedDelayString = "${qim.outbox.relay-interval-ms:500}")`)가 outbox 테이블을 폴링하여 Kafka 직접 발행
- 반면 q-sign은 별도의 `outbox-relay-batch` 모듈이 발행 담당
- 동일한 패턴이 모듈마다 다르게 구현됨 → Phase 1 R1 (IdO bloat)와 유사한 일관성 문제
- Q-IM Pod이 다운되면 outbox 발행도 멈춤 (q-sign은 batch가 독립 Pod이므로 회복 가능)

**권장**:
- 전체 outbox-relay 책임을 outbox-relay-batch로 통일
- 또는 모든 모듈이 자체 스케줄러로 통일 (현재 혼재가 가장 큰 문제)
- 운영 부담을 고려하면 PR-A5 회고 정책에 따라 **현재는 변경 보류, Phase 7 로드맵에 기록**

---

## 3.3 치명적 시나리오 — Q-IM 식별 실패 매트릭스

### 시나리오 D — CI 기반 회원 조회 영구 실패 (F3.1 단독)

```
1. 사용자가 정부24에서 PASS 본인확인 가입
   → IdO: identifierHash_PASS = hex(sha256(CI))
   → Q-IM: auth_mean_mapping.identifier_hash = hex(...) 저장 OK
2. 사용자가 다른 기관 사이트에서 회원 조회 요청
   → 기관이 IdO Verify API 호출 → IdO가 Q-IM lookup-by-ci POST
3. Q-IM MemberLookupController.lookupByCi
   → CI 복호화 OK
   → identifierHash_QUERY = base64url(sha256(CI))  ← 인코딩 다름
   → findByIdentifierHash → 404 NOT FOUND
4. 결과: 기관 사이트는 항상 "회원 정보를 찾을 수 없습니다"
5. 사용자는 정부24와 다른 기관 모두에 가입했지만 SSO 연동 동작 안 함
```

**현재 영향**: lookup-by-ci API가 사실상 dead code — 이 API를 호출하는 모든 기관 통합이 미동작.
**일정**: 외부 기관 본격 연동 전에 반드시 수정 필요.

---

### 시나리오 E — 운영 배포 시 환경변수 누락 (F3.3 + F3.4 결합)

```
DevOps 시점:
1. K8s manifest에 QIM_CI_AES_KEY_V1, QIM_DI_SECRET 누락
2. Q-IM Pod 정상 기동 (기본값 사용으로 인해 검증 미발동)
3. 첫 사용자 가입 → CI가 "AAA...=" 키로 암호화되어 DB 저장
4. 첫 DI 발급 → secret "default-di-secret-change-in-production"으로 HMAC 계산
5. 1주일 후 운영팀이 환경변수 추가 → 키 로테이션 시도
6. 기존 데이터는 약한 키로 남음 → 재암호화 필요
7. 동시에 모든 DI 무효 → 기관 측 DI 매핑 일괄 갱신 필요
8. 키 로테이션 부재 → 사일런트 보안 사고
```

**현재 영향**: 운영 배포 시 발생 가능한 가장 위험한 시나리오. PR-A5 회고 정책에서 "운영은 늦춤" 결정했지만, **첫 배포 시점에 반드시 해결되어야 할 항목**.

---

### 시나리오 F — 동시 통합전환 + 다중 세션 (F3.6 단독)

```
1. 사용자 김씨가 PC에서 통합전환 시작 → session A (INITIATED)
2. 김씨가 동시에 모바일에서 통합전환 시작 → session B 신규 생성
   (findActiveByUser는 LIMIT 1로 A를 봤지만, A는 아직 후보 조회 전이라 두 번째 트랜잭션은 A를 못 봄)
3. PC에서 fetch-candidates → session A로 68개 기관 조회 → 5개 후보
4. 모바일에서 fetch-candidates → session B로 다시 68개 기관 조회 → 5개 후보
5. PC에서 정부24만 선택 → session A.selected = [GOV24]
6. 모바일에서 국세청만 선택 → session B.selected = [NTS]
7. PC에서 link → 정부24 연결 완료 (session A.COMPLETED)
8. 모바일에서 link → 국세청 연결 완료 (session B.COMPLETED)
9. 사용자 입장: "두 사이트 모두 잘 됐네" — 표면적으로는 OK
10. 그러나 audit log에 통합전환 세션이 2번 기록 — 동의 이력에서 부분 연결로 보임
```

**현재 영향**: 사용자 경험 직접 손상은 아니나, 감사 로그/통계 무결성 손상. 부분 연결로 인한 사용자 혼란 가능.

---

### 시나리오 G — 탈퇴 후 동일 CI 재가입 (F3.8 단독)

```
1. 김씨가 PASS로 가입 → identifierHash_김 = hex(sha256(CI_김))
   auth_mean_mapping: { id_김, provider=PASS, hash=H_김, status=ACTIVE }
2. 김씨가 탈퇴
   user_profile.ci = NULL (PII 삭제 OK)
   auth_mean_mapping은 그대로 (revoke 안 됨)
3. 일주일 후 김씨가 다시 PASS로 가입
   → IdO: identifierHash_김2 = hex(sha256(CI_김)) = H_김 (동일)
   → Q-IM registerOrGet: findByIdentifierHash(H_김) → 기존 사용자 발견 (status=WITHDRAWN)
4. registerOrGet은 status 체크 없이 기존 user 반환
   → 탈퇴된 사용자의 qimUserId를 그대로 재사용
   → status=WITHDRAWN인 채로 동작 시도 → 다른 화면에서 에러
```

**현재 영향**: `registerOrGet`이 status를 무시하고 기존 사용자 반환 → 탈퇴 사용자 부활 또는 동작 불가 상태. PII 삭제는 했지만 매핑 정리가 안 되어 발생.

---

## 3.4 잠재적 정상 케이스 검증 — 무엇이 잘 되고 있는가

긍정적인 발견도 기록 (false-assurance 방지를 위해 실제 코드로 확인된 것만):

| ✓ | 항목 | 위치 |
|---|------|------|
| ✓ | InternalApiKeyInterceptor 키 미설정 시 전면 거부 | InternalApiKeyInterceptor.java:85-89 |
| ✓ | 상수시간 비교 (`MessageDigest.isEqual`) | InternalApiKeyInterceptor.java:137-141 |
| ✓ | CI AES-256-GCM 사용 (12-byte IV, 128-bit tag) | CiCryptoServiceImpl.java:35-37 |
| ✓ | CI 키 버전 관리 (v1/v2 분리) | CiCryptoServiceImpl.java:40-48 |
| ✓ | 동시 신규 가입 경합 방어 (`DataIntegrityViolationException` catch + 재조회) | UserRegistrationServiceImpl.java:182-190 |
| ✓ | register-social 경합 방어 (find → 재조회 → 200 OK) | UserController.java:313-324 |
| ✓ | UUIDv7 사용 (시간순 정렬 + B-tree 친화) | 전체 (UuidV7.generate) |
| ✓ | Outbox 발행 실패 시 errorMessage DB 저장 | OutboxServiceImpl.java:178-179 |
| ✓ | maxRetry 도달 시 영구 FAILED + 운영 알람 로그 | OutboxServiceImpl.java:182-188 |
| ✓ | 탈퇴 PII 즉시 삭제 (GDPR §17) | WithdrawalServiceImpl.java:255-270 |
| ✓ | 동의 이력 보존 (UPDATE 없이 INSERT) | ConsentServiceImpl.java:48 |
| ✓ | 필수 동의 철회 거부 (`IM_WITHDRAWAL_NOT_ALLOWED`) | ConsentServiceImpl.java:88-90 |
| ✓ | Conversion Virtual Thread + 데드라인 기반 타임아웃 | AgencyMemberLookupServiceImpl.java:79-130 |
| ✓ | Conversion 부분 실패 허용 (개별 기관 SKIP) | AgencyMemberLookupServiceImpl.java:161-165 |
| ✓ | 전환 세션 TTL 30분 + 만료 스케줄러 | ConversionSessionServiceImpl.java:43, 168-177 |

---

## 3.5 Phase 3 요약 — 식별·매핑 위험도

| ID | 심각도 | 항목 | 운영 영향 |
|----|--------|------|-----------|
| **F3.1** | **Critical** | SHA-256 인코딩 불일치 (Base64URL vs hex) | lookup-by-ci API **100% 실패** — 기관 SSO 미동작 |
| **F3.2** | **Critical** | `findByIdentifierHash` 단일 hash 조회 (V4 후 unique 아님) | hash 충돌 시 NonUniqueResult 또는 잘못된 사용자 반환 |
| **F3.3** | **Critical** | CI AES 키 운영 기본값 | 환경변수 누락 시 약한 키로 모든 CI 암호화 |
| **F3.4** | **Critical** | DI Secret 운영 기본값 | 누구나 임의 사용자 DI 위조 가능 |
| F3.5 | High | CI 복호화 평문 fallback | 부분 마이그레이션 데이터 노출 위험 |
| F3.6 | High | 전환 세션 동시 시작 다중 ACTIVE | 부분 연결로 감사 로그/통계 무결성 손상 |
| F3.7 | High | 동의 동시 INSERT 다중 AGREED | 동의 상태 모호 + 감사 노이즈 |
| F3.8 | High | PII 삭제 중복 정의 + auth_mean_mapping 미정리 | 탈퇴 후 식별자 잔존 — GDPR §17 위반 위험 + 탈퇴 사용자 부활 (시나리오 G) |
| F3.9 | High | DI 저장 명시적 save 누락 | DI 매번 재계산 + 로그 노이즈 |
| F3.10 | Medium | Conversion fallback hash가 qimUserId | 소셜 사용자 통합전환 후보 0건 |
| F3.11 | Medium | CI 복호화 실패 응답 동일 코드 | 디버깅 곤란 (보안 trade-off) |
| F3.12 | Medium | InternalApiKeyInterceptor excludePath 미설정 | 향후 path 추가 시 의도 어긋남 |
| F3.13 | Medium | register-social 200/201 혼재 | IdO 클라이언트 처리 가정 검증 필요 (Phase 4) |
| F3.14 | Low | Outbox 발행 책임 모듈 간 불일치 | PR-A5 회고 정책상 보류 항목 |

**Critical 4건, High 5건, Medium 4건, Low 1건 — 총 14건**

가장 즉각적으로 사용자가 느낄 수 있는 문제: **F3.1 (CI 조회 영구 실패)**, **F3.10 (소셜 통합전환 0건)**, **F3.8/시나리오 G (탈퇴 후 재가입 좀비)**.

---

## 3.6 다음 단계

Phase 4: **IdO Handoff 검증** — Issue/Verify/Webhook 흐름과 q-sign↔ido↔qim 3-tier 데이터 흐름 검증으로 진행. Phase 3에서 발견된 F3.13(register-social 200/201 처리) 검증 포함.
