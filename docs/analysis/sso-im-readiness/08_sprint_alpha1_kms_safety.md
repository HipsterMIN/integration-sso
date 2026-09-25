# Sprint α-1 진행 보고 — KMS 안전망 (F5.1 + F5.2)

**진행 일자**: 2026-05-22
**브랜치**: `sprint-alpha-1-kms-safety`
**해결 결함**: F5.1 (Critical), F5.2 (Critical)
**관련 분석**: `05_privacy_kms_audit.md §F5.1, §F5.2`

---

## 1. 작업 목적

옵션 1 (점진 수정 / 안전) 로드맵의 첫 단계.
KMS 관련 두 개의 Critical 결함을 **운영 진입 전 절대 차단** 사항으로 분류하여 우선 격리한다.

운영에서 다음 두 가지 무방비 상태를 차단하는 것이 목표:
1. **F5.1**: 환경변수 누락 시 `LocalKmsClient`가 자동 활성화되어 평문 키를 사용하는 경로
2. **F5.2**: Vault 토큰 획득 실패에도 startup이 진행되어 컨테이너가 LIVE이지만 첫 호출에서 실패하는 silent failure

---

## 2. F5.1 — LocalKmsClient 자동 활성화 차단

### 2.1 변경 전 상태

```java
// idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/crypto/kms/LocalKmsClient.java
@ConditionalOnProperty(prefix = "idem.hub.kms", name = "enabled",
                       havingValue = "false", matchIfMissing = true)  // ← 위험
public class LocalKmsClient implements KmsClient {
```

**문제 경로**:
- ConfigMap 마운트 실패 / 환경변수 누락 / Helm values 미적용 시
- `idem.hub.kms.enabled` 속성이 "없음" 상태로 인식됨
- `matchIfMissing=true` 가 작동하여 자동으로 LocalKmsClient 활성화
- 평문 키 모드로 운영 가동 → 실 사용자 PII 위협

### 2.2 변경 후 상태

```java
@Component
@Profile("!prod & !stage")                                            // ← 1차 가드
@ConditionalOnProperty(
    prefix      = "idem.hub.kms",
    name        = "enabled",
    havingValue = "false",
    matchIfMissing = false   // ← 2차 가드: 환경변수 명시 필수
)
public class LocalKmsClient implements KmsClient {

    private final Environment environment;

    @Value("${idem.hub.kms.local.allow-in-prod:false}")
    private boolean allowInProd;

    public LocalKmsClient(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void failFastIfProdLike() {                                       // ← 3차 가드
        String[] active = environment.getActiveProfiles();
        for (String profile : active) {
            if (FORBIDDEN_PROFILES.contains(profile.toLowerCase())) {
                if (allowInProd) {
                    log.error("[KMS-Local] ⚠️ 운영 의심 프로파일 ...");
                    return;
                }
                throw new IllegalStateException(
                    "[KMS-Local][F5.1 Guard] 운영 의심 프로파일 '" + profile +
                    "' 에서 LocalKmsClient가 활성화될 수 없습니다. ...");
            }
        }
    }
}
```

### 2.3 3중 안전망 설계

| 계층 | 방어 메커니즘 | 차단 시점 | 우회 가능성 |
|------|-------------|---------|-----------|
| 1차 | `@Profile("!prod & !stage")` | 빈 등록 단계 | `@Profile` 어노테이션 삭제 시만 |
| 2차 | `matchIfMissing=false` | 빈 등록 단계 | 환경변수에 `false` 명시 시만 활성 |
| 3차 | `@PostConstruct failFastIfProdLike()` | 빈 생성 직후 | `idem.hub.kms.local.allow-in-prod=true` 명시 시만 |

3중 안전망은 **silent 미스컨피그가 자동으로 평문 모드로 fallback되는 모든 경로를 차단**한다.

### 2.4 호환성

- **로컬 개발**: `IDEM_HUB_KMS_ENABLED=false` (또는 application.yml 기본값) → `havingValue="false"` 매칭 → 활성. **변화 없음**.
- **dev 서버**: `values-dev.yaml` 에 `IDEM_HUB_KMS_ENABLED: "false"` → 명시적 `false` → 활성. **변화 없음**.
- **stage 서버**: `values-stage.yaml` 에 `IDEM_HUB_KMS_ENABLED: "true"` + `IDEM_HUB_KMS_PROVIDER: "vault"` → VaultKmsClient 활성. LocalKmsClient는 `@Profile("!stage")` 로 1차 차단. **변화 없음**.
- **prod 서버**: `values-prod.yaml` 도 동일하게 vault 활성. **변화 없음**.
- **prod에서 ConfigMap 누락 시 (회귀 사고 가정)**:
  - 변경 전: `matchIfMissing=true` → LocalKmsClient 자동 활성 → 평문 키 모드 → 🔴 실 사용자 PII 위협
  - 변경 후: 3중 가드 모두 실패 → `BeanCreationException` → ApplicationContext 기동 실패 → 컨테이너 재시작 루프 → ✅ 트래픽 차단

### 2.5 추가된 회귀 테스트

`LocalKmsClientTest$ProdGuard` (Nested class, 8건):

| 테스트 | 시나리오 | 기대 |
|--------|---------|------|
| `prodProfile_rejected` | active=prod | IllegalStateException |
| `stageProfile_rejected` | active=stage | IllegalStateException |
| `productionAlias_rejected` | active=PRODUCTION (대소문자) | IllegalStateException |
| `prodProfile_allowedExplicitly` | active=prod + allowInProd=true | 통과 (escape hatch) |
| `localProfile_passes` | active=local | 통과 |
| `devProfile_passes` | active=dev | 통과 |
| `testProfile_passes` | active=test | 통과 |
| `noActiveProfile_passes` | active=[] | 통과 |
| `multiProfileWithProd_rejected` | active=[custom, prod, extra] | IllegalStateException |

---

## 3. F5.2 — VaultKmsClient 토큰 획득 실패 시 startup 차단

### 3.1 변경 전 상태

```java
// idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/crypto/kms/VaultKmsClient.java
@PostConstruct
void init() {
    // ...
    this.clientToken = acquireToken();

    if (clientToken == null || clientToken.isBlank()) {
        log.error("[KMS-Vault] 토큰 획득 실패. ...");   // ← 로그만, startup 계속
    } else {
        log.info("[KMS-Vault] 초기화 완료: ...");
    }
}
```

**문제 경로** (Half-up Silent Failure):
1. `VAULT_TOKEN` 환경변수 누락 또는 K8s SA 토큰 파일 마운트 실패
2. `acquireToken()` 이 `null` 반환
3. `init()` 가 `log.error()` 만 출력하고 정상 종료
4. Spring ApplicationContext가 정상 기동 → Liveness Probe = LIVE
5. K8s Service가 Pod에 트래픽 라우팅 시작
6. 첫 SSO 요청에서 encrypt/decrypt 호출 → `NullPointerException` 또는 401 → 모든 사용자 차단
7. Liveness Probe는 여전히 LIVE이므로 재시작 안 됨 → 운영자가 수동 개입할 때까지 모든 사용자 차단

### 3.2 변경 후 상태

```java
@PostConstruct
void init() {
    // RestTemplate 초기화 (변화 없음)
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectionTimeoutMs);
    factory.setReadTimeout(requestTimeoutMs);
    this.restTemplate = new RestTemplate(factory);

    // F5.2 강화: 토큰 획득 중 예외도 차단
    try {
        this.clientToken = acquireToken();
    } catch (Exception e) {
        handleTokenAcquisitionFailure(
            "[KMS-Vault] 토큰 획득 중 예외 발생: " + e.getMessage(), e);
        return;
    }

    // F5.2 강화: 빈 토큰 시 startup 차단
    if (clientToken == null || clientToken.isBlank()) {
        handleTokenAcquisitionFailure(
            "[KMS-Vault] 토큰 획득 실패. ...", null);
        return;
    }

    log.info("[KMS-Vault] 초기화 완료: ...");
}

private void handleTokenAcquisitionFailure(String reason, Throwable cause) {
    if (allowEmptyToken) {
        log.error("[KMS-Vault][F5.2 Escape] {} — escape hatch 우회됨. ...", reason, cause);
        this.clientToken = null;
        return;
    }
    if (cause != null) {
        throw new IllegalStateException(reason + " [Startup blocked by F5.2 Guard]", cause);
    }
    throw new IllegalStateException(reason + " [Startup blocked by F5.2 Guard]");
}
```

### 3.3 Escape Hatch

```yaml
# application.yml
ido:
  kms:
    vault:
      allow-empty-token: ${IDEM_HUB_KMS_VAULT_ALLOW_EMPTY_TOKEN:false}   # 기본 false (안전)
```

- 기본값 `false` 로 운영 안전 보장
- 테스트·일시 격리 등 특수 상황에서만 `true`로 설정 (운영 환경 금지)

### 3.4 변경 후 동작 (Pod 라이프사이클)

| 단계 | 변경 전 | 변경 후 |
|------|---------|---------|
| ApplicationContext 기동 | 정상 종료 | `IllegalStateException` → 기동 실패 |
| Pod 상태 | Running, Liveness LIVE | CrashLoopBackOff |
| Service 라우팅 | ✅ 받음 | ❌ Not Ready → 차단 |
| 사용자 영향 | 모든 요청 실패 (silent) | Pod 진입 자체 차단 (loud) |
| 운영자 알림 | 첫 사용자 실패 후 메트릭 | Pod 재시작 카운트 + CrashLoopBackOff 알람 |

### 3.5 추가된 회귀 테스트

`VaultKmsClientTest$StartupGuard` (Nested class, 4건):

| 테스트 | 시나리오 | 기대 |
|--------|---------|------|
| `emptyTokenWithoutEscape_throwsStartupBlock` | token+빈 staticToken | IllegalStateException("F5.2 Guard") |
| `emptyTokenWithEscape_passes` | + allowEmptyToken=true | 통과, clientToken=null |
| `appRoleEmptyCreds_throwsStartupBlock` | approle+빈 ROLE_ID/SECRET_ID | IllegalStateException |
| `k8sMissingSaToken_throwsStartupBlock` | kubernetes+SA 토큰 파일 없음 | IllegalStateException |

---

## 4. 변경 파일 요약

| 파일 | 변경 종류 | 추가 LOC | 삭제 LOC |
|------|----------|---------|---------|
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/crypto/kms/LocalKmsClient.java` | 수정 | +60 | -3 |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/crypto/kms/VaultKmsClient.java` | 수정 | +55 | -12 |
| `idem-hub/src/main/resources/application.yml` | 설정 추가 | +15 | 0 |
| `idem-hub/src/test/java/io/github/hipstermin/idem/idem-hub/crypto/kms/LocalKmsClientTest.java` | 테스트 추가 | +95 | -3 |
| `idem-hub/src/test/java/io/github/hipstermin/idem/idem-hub/crypto/kms/VaultKmsClientTest.java` | 테스트 추가 | +95 | 0 |
| `docs/analysis/sso-im-readiness/07_risk_matrix_roadmap.md` | 진행 상태 반영 | +6 | -10 |
| `docs/analysis/sso-im-readiness/08_sprint_alpha1_kms_safety.md` | 신규 | +250 | 0 |

---

## 5. DoD (Definition of Done) 자기 검증

- [x] F5.1: `LocalKmsClient` `matchIfMissing=false` 적용
- [x] F5.1: `@Profile("!prod & !stage")` 1차 가드 추가
- [x] F5.1: `@PostConstruct failFastIfProdLike()` 3차 가드 추가
- [x] F5.1: escape hatch (`idem.hub.kms.local.allow-in-prod`) 추가 + 기본 false
- [x] F5.1: 회귀 테스트 9건 추가 (`LocalKmsClientTest$ProdGuard`)
- [x] F5.2: `VaultKmsClient.init()` 토큰 획득 실패 시 IllegalStateException
- [x] F5.2: try-catch로 acquireToken() 내부 예외도 차단
- [x] F5.2: escape hatch (`idem.hub.kms.vault.allow-empty-token`) 추가 + 기본 false
- [x] F5.2: 회귀 테스트 4건 추가 (`VaultKmsClientTest$StartupGuard`)
- [x] 기존 dev/local/stage/prod 환경 호환성 보존 (코드 변경으로 인한 동작 변화 없음)
- [x] application.yml 새 설정 키 문서화
- [x] 분석 문서 진행 상태 반영 (`07_risk_matrix_roadmap.md`)
- [ ] (CI에서 검증) `./gradlew :idem-hub:test --tests "*KmsClientTest*"` 통과
- [ ] (CI에서 검증) `./gradlew :idem-hub:build` 통과

> sandbox에 Java/Gradle 미설치로 로컬 컴파일·테스트는 수행 불가.
> 정적 검증 결과: 중괄호 균형 OK, import 누락 없음, 기존 호출 사이트(테스트 2건) 모두 업데이트 완료.

---

## 6. 후속 작업 (Sprint α-2 이후 예정)

- **α-2 (다음)**: F4.1 (verify dead code) + F4.5 (verify 비원자) + F4.2 (consume race) — Handoff 무결성
- **α-3**: F4.3 (default webhook secret) + F4.4 (CAST JWT URL) + F4.6 (PolicyEngine 마스킹)
- **α-4**: F5.4 (audit qimUserId 평문) + F5.3 (Vault 토큰 자동 갱신, 별도 PR — 복잡도)

---

## 7. 운영 적용 시 주의사항

본 변경은 **회귀 사고 방지용 안전망 강화**이며, 정상 운영 환경에서는 동작 변화가 없다.
다만 다음 두 가지 운영 시나리오에서 주의 필요:

### 7.1 prod ConfigMap 누락 회귀 사고 시뮬레이션

이전: 평문 키로 silent 가동 → 사용자 PII 위협
**현재: Pod 기동 실패 → CrashLoopBackOff → 운영자에게 즉시 알림**

### 7.2 Vault 일시 장애 (token endpoint 응답 없음)

이전: token 획득 실패 → log.error 후 LIVE → 첫 사용자 요청 실패
**현재: Pod 기동 실패 → CrashLoopBackOff → Vault 복구 후 자동 회복**

> ⚠️ **운영 알람 추가 권장**: PR #173 에서 정리된 알람 외에, `KubePodCrashLooping{pod=~"ido-.*"}` 알람이 KMS 미스컨피그를 명시적으로 표면화하도록 알람 설명에 "F5.1/F5.2 가드 가능성 점검" 추가.

(이 항목은 별도 PR에서 처리)
