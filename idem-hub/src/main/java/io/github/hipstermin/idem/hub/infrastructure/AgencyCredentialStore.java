package io.github.hipstermin.idem.hub.infrastructure;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 기관 자격증명 저장소 — authCredentialRef → K8s Secret 환경변수 조회 + 캐싱 (Sprint 17)
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li>{@code authCredentialRef} = K8s Secret 경로 키 (예: {@code "secrets/agency/AGENCY_001/api-key"})</li>
 *   <li>환경변수 이름 변환 규칙: 경로의 비알파벳/숫자 문자를 {@code _}로 치환 후 대문자화</li>
 *   <li>예: {@code "secrets/agency/AGENCY_001/api-key"} → {@code SECRETS_AGENCY_AGENCY_001_API_KEY}</li>
 *   <li>조회 실패(환경변수 없음) 시 {@code null} 반환 — 호출자가 로그 후 PENDING 처리</li>
 *   <li>조회 성공 값은 {@link ConcurrentHashMap} 캐시에 보관 (스레드 안전)</li>
 * </ul>
 *
 * <h3>K8s Secret 등록 예시</h3>
 * <pre>
 * # API_KEY 기관 (authCredentialRef = "secrets/agency/AGENCY_001/api-key")
 * kubectl create secret generic ido-agency-credentials \
 *   --from-literal=SECRETS_AGENCY_AGENCY_001_API_KEY=&lt;실제 API 키&gt; \
 *   --from-literal=SECRETS_AGENCY_AGENCY_002_API_KEY=&lt;실제 API 키&gt; \
 *   -n production
 *
 * # HMAC 기관 (authCredentialRef = "secrets/agency/AGENCY_003/hmac-secret")
 * kubectl create secret generic ido-agency-credentials \
 *   --from-literal=SECRETS_AGENCY_AGENCY_003_HMAC_SECRET=&lt;32자 이상 HMAC 비밀키&gt; \
 *   -n production
 *
 * # mTLS 기관 (authCredentialRef = "secrets/agency/AGENCY_004/mtls-keystore-base64")
 * kubectl create secret generic ido-agency-credentials \
 *   --from-literal=SECRETS_AGENCY_AGENCY_004_MTLS_KEYSTORE_BASE64=&lt;Base64 인코딩된 PKCS12 KeyStore&gt; \
 *   --from-literal=SECRETS_AGENCY_AGENCY_004_MTLS_KEYSTORE_PASS=&lt;KeyStore 비밀번호&gt; \
 *   -n production
 *
 * # envFrom에 Secret 참조 추가 (ido-deployment.yml)
 * envFrom:
 *   - secretRef:
 *       name: ido-agency-credentials
 *       optional: true   # 개발 환경: 키 없어도 기동 (F-20=false 이므로 실행 미수행)
 * </pre>
 *
 * <h3>개발 환경 설정</h3>
 * <pre>
 * # application-dev.yml
 * ido:
 *   provisioning:
 *     credential:
 *       dev-overrides: >
 *         SECRETS_AGENCY_AGENCY_TEST_001_API_KEY=dev-api-key-placeholder,
 *         SECRETS_AGENCY_AGENCY_TEST_001_HMAC_SECRET=dev-hmac-secret-placeholder-32chars
 * </pre>
 *
 * <h3>보안 주의사항</h3>
 * <ul>
 *   <li>자격증명 값은 로그에 절대 출력하지 않음 (exists 여부만 로그)</li>
 *   <li>mTLS KeyStore Base64는 메모리에 캐시되므로 JVM 메모리 덤프 보안 고려</li>
 *   <li>런타임 갱신: {@link #invalidate(String)} 호출 후 재조회 시 최신 환경변수 반영</li>
 * </ul>
 *
 * @see AgencyHmacKeyStore HMAC 전용 키 저장소 (인바운드 서명 검증용)
 * @see ProvisioningServiceImpl#addAuthHeader 이 컴포넌트를 사용하는 아웃바운드 인증 헤더 설정
 */
@Slf4j
@Component
public class AgencyCredentialStore {

    /**
     * 자격증명 캐시.
     * key   = envVarName (대문자, 예: "SECRETS_AGENCY_AGENCY_001_API_KEY")
     * value = 자격증명 평문
     */
    private final ConcurrentHashMap<String, String> credentialCache = new ConcurrentHashMap<>();

    /**
     * 개발 환경 전용 — application-dev.yml에서 오버라이드 설정.
     * 형식: "ENV_NAME_1=value1,ENV_NAME_2=value2"
     * 운영 환경: K8s Secret envFrom으로 주입 (이 값은 비워 둠)
     */
    @Value("${ido.provisioning.credential.dev-overrides:}")
    private String devOverridesRaw;

    @PostConstruct
    void init() {
        // 개발 환경 오버라이드 사전 로드 (env 우선이므로 캐시 에는 넣지 않고 별도 Map 유지)
        // 실제 조회는 findSecret()에서 LazyLoading — K8s 환경에서는 환경변수 직접 조회
        if (devOverridesRaw != null && !devOverridesRaw.isBlank()) {
            for (String pair : devOverridesRaw.split(",")) {
                pair = pair.trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String envName = pair.substring(0, eq).trim();
                String value   = pair.substring(eq + 1).trim();
                if (!envName.isBlank() && !value.isBlank()) {
                    credentialCache.put(envName, value);
                    log.debug("[AgencyCredentialStore] 개발 오버라이드 사전 로드: envName={}", envName);
                }
            }
            log.info("[AgencyCredentialStore] 개발 오버라이드 {}건 로드됨.", credentialCache.size());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 공개 API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * {@code authCredentialRef} 기반 자격증명 조회.
     *
     * <p>조회 우선순위:
     * <ol>
     *   <li>메모리 캐시 (ConcurrentHashMap)</li>
     *   <li>환경변수 (K8s Secret envFrom 주입)</li>
     *   <li>시스템 프로퍼티 (테스트 편의)</li>
     *   <li>개발 오버라이드 (application-dev.yml)</li>
     * </ol>
     *
     * @param authCredentialRef K8s Secret 경로
     *                          (예: {@code "secrets/agency/AGENCY_001/api-key"})
     * @return 자격증명 평문, 미등록이면 {@code null}
     */
    public String findSecret(String authCredentialRef) {
        if (authCredentialRef == null || authCredentialRef.isBlank()) return null;

        String envName = toEnvVarName(authCredentialRef);

        // ① 캐시 히트
        String cached = credentialCache.get(envName);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        // ② 환경변수 조회 (K8s Secret envFrom)
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            credentialCache.put(envName, envValue);
            log.debug("[AgencyCredentialStore] 환경변수 로드: envName={} (캐시 등록)", envName);
            return envValue;
        }

        // ③ 시스템 프로퍼티 조회 (테스트 환경)
        String sysPropValue = System.getProperty(envName);
        if (sysPropValue != null && !sysPropValue.isBlank()) {
            credentialCache.put(envName, sysPropValue);
            log.debug("[AgencyCredentialStore] 시스템 프로퍼티 로드: envName={}", envName);
            return sysPropValue;
        }

        // ④ 미등록 — 운영 환경이라면 K8s Secret에 등록 필요
        log.warn("[AgencyCredentialStore] 자격증명 미등록: authCredentialRef={} envVarName={} " +
                 "→ K8s Secret 'ido-agency-credentials'에 {} 를 등록하세요.",
                 authCredentialRef, envName, envName);
        return null;
    }

    /**
     * mTLS KeyStore Base64 조회.
     *
     * <p>authCredentialRef 경로에 {@code -base64} 접미사를 붙여 조회.
     * 예: {@code "secrets/agency/AGENCY_004/mtls-keystore"} →
     *     {@code "secrets/agency/AGENCY_004/mtls-keystore-base64"} → envVar 조회.
     *
     * @param authCredentialRef mTLS 기관의 authCredentialRef
     * @return Base64 인코딩된 PKCS12 KeyStore 문자열, 미등록이면 {@code null}
     */
    public String findMtlsKeystoreBase64(String authCredentialRef) {
        if (authCredentialRef == null || authCredentialRef.isBlank()) return null;
        // authCredentialRef에 이미 "keystore-base64"가 포함되어 있으면 그대로, 아니면 접미사 추가
        String keystoreRef = authCredentialRef.endsWith("-base64")
                ? authCredentialRef
                : authCredentialRef + "-base64";
        return findSecret(keystoreRef);
    }

    /**
     * mTLS KeyStore 비밀번호 조회.
     *
     * @param authCredentialRef mTLS 기관의 authCredentialRef
     * @return KeyStore 비밀번호, 미등록이면 빈 문자열 (비밀번호 없는 KeyStore 대비)
     */
    public String findMtlsKeystorePassword(String authCredentialRef) {
        if (authCredentialRef == null || authCredentialRef.isBlank()) return "";
        // authCredentialRef에서 마지막 경로 구성요소를 "mtls-keystore-pass"로 교체
        String baseRef = authCredentialRef.replaceAll("[^/]+$", "mtls-keystore-pass");
        String pass = findSecret(baseRef);
        return pass != null ? pass : "";
    }

    /**
     * 캐시 무효화 — 무중단 자격증명 갱신 시 사용.
     *
     * @param authCredentialRef 무효화할 자격증명 참조 경로
     */
    public void invalidate(String authCredentialRef) {
        if (authCredentialRef == null || authCredentialRef.isBlank()) return;
        String envName = toEnvVarName(authCredentialRef);
        credentialCache.remove(envName);
        log.info("[AgencyCredentialStore] 캐시 무효화: authCredentialRef={} envName={}", authCredentialRef, envName);
    }

    /**
     * 특정 자격증명 등록 여부 확인 (환경변수 조회 없이 캐시만 확인).
     *
     * @param authCredentialRef 확인할 참조 경로
     * @return 캐시에 있으면 true
     */
    public boolean isCached(String authCredentialRef) {
        if (authCredentialRef == null || authCredentialRef.isBlank()) return false;
        return credentialCache.containsKey(toEnvVarName(authCredentialRef));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 내부 유틸
    // ════════════════════════════════════════════════════════════════════════

    /**
     * authCredentialRef → 환경변수 이름 변환.
     *
     * <p>변환 규칙: 알파벳/숫자 이외의 문자를 {@code _}로 치환 후 대문자화.
     * <ul>
     *   <li>{@code "secrets/agency/AGENCY_001/api-key"}
     *       → {@code "SECRETS_AGENCY_AGENCY_001_API_KEY"}</li>
     *   <li>{@code "secrets/agency/AGENCY_003/hmac-secret"}
     *       → {@code "SECRETS_AGENCY_AGENCY_003_HMAC_SECRET"}</li>
     *   <li>{@code "secrets/agency/AGENCY_004/mtls-keystore-base64"}
     *       → {@code "SECRETS_AGENCY_AGENCY_004_MTLS_KEYSTORE_BASE64"}</li>
     * </ul>
     *
     * @param authCredentialRef K8s Secret 경로
     * @return 환경변수 이름 (대문자, 비알파벳/숫자 → "_")
     */
    static String toEnvVarName(String authCredentialRef) {
        return authCredentialRef
                .replaceAll("[^A-Za-z0-9]", "_")
                .toUpperCase(Locale.ROOT);
    }
}
