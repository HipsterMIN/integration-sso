package kr.go.smes.ido.gateway;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 기관별 HMAC-SHA256 서명 키 저장소 (Sprint 17)
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li>기관별 독립 비밀키 — 한 기관 키 유출이 타 기관에 영향 없음</li>
 *   <li>K8s Secret 마운트 방식 — 환경변수 {@code IDO_GATEWAY_HMAC_KEY_{AGENCY_CODE}} 읽기</li>
 *   <li>기동 시 등록 키 목록 로그 출력 (키 값은 절대 출력 안 함)</li>
 *   <li>런타임 키 갱신 지원 ({@link #refresh(String, String)}) — 무중단 키 로테이션</li>
 * </ul>
 *
 * <h3>K8s Secret 등록 방법</h3>
 * <pre>
 * # 1) Secret 생성 (기관별 키 포함)
 * kubectl create secret generic ido-gateway-hmac-keys \
 *   --from-literal=AGENCY_001=&lt;32자 이상 랜덤 시크릿&gt; \
 *   --from-literal=AGENCY_002=&lt;32자 이상 랜덤 시크릿&gt; \
 *   -n production
 *
 * # 2) ido-deployment.yml envFrom에 Secret 참조 추가 (Sprint 17 Helm values 포함)
 * envFrom:
 *   - secretRef:
 *       name: ido-gateway-hmac-keys
 *       optional: true   # 개발 환경: 키 없어도 기동 (F-26=false이므로 검증 미수행)
 *
 * # 3) 환경변수 명명 규칙: IDO_GATEWAY_HMAC_KEY_{기관코드 대문자}
 * #    예: AGENCY_001 → IDO_GATEWAY_HMAC_KEY_AGENCY_001
 * </pre>
 *
 * <h3>키 로테이션 (무중단)</h3>
 * <pre>
 * # 새 키로 Secret 업데이트
 * kubectl patch secret ido-gateway-hmac-keys \
 *   --patch='{"stringData":{"AGENCY_001":"new-secret-value"}}' \
 *   -n production
 *
 * # Reloader(Stakater) 설치 시 자동 재로드
 * # 미설치 시 kubectl rollout restart deployment/ido -n production
 * </pre>
 *
 * <h3>보안 주의사항</h3>
 * <ul>
 *   <li>키 길이: 최소 32자(256비트) 권장 — 미달 시 기동 경고</li>
 *   <li>키 값은 로그에 절대 출력하지 않음 (첫 4자만 마스킹 로그)</li>
 *   <li>메모리 캐시: {@link ConcurrentHashMap} — 스레드 안전</li>
 * </ul>
 *
 * @see HmacSignatureFilter
 */
@Slf4j
@Component
public class AgencyHmacKeyStore {

    /** 환경변수 접두사: IDO_GATEWAY_HMAC_KEY_{AGENCY_CODE} */
    private static final String ENV_PREFIX = "IDO_GATEWAY_HMAC_KEY_";

    /** 최소 권장 키 길이 (256비트 = 32바이트) */
    private static final int MIN_KEY_LENGTH = 32;

    /**
     * 기관별 HMAC 비밀키 캐시.
     * key   = 기관코드 (대문자, 예: "AGENCY_001")
     * value = HMAC 비밀키 평문
     */
    private final ConcurrentHashMap<String, String> keyCache = new ConcurrentHashMap<>();

    // ── 개발/테스트용 fallback 설정 ──────────────────────────────────────
    // 운영 환경: K8s Secret 환경변수로 주입
    // 개발 환경: ido.gateway.hmac.dev-keys.{agencyCode}=secret 으로 설정 가능

    /**
     * 개발 환경 전용 — application.yml에서 기관별 테스트 키 설정.
     *
     * <p>운영 환경에서는 K8s Secret 환경변수({@code IDO_GATEWAY_HMAC_KEY_*})로 주입됩니다.
     * 이 속성은 {@code application-dev.yml} 또는 {@code application-test.yml}에서만 설정하세요.
     *
     * <p>예시 ({@code application-dev.yml}):
     * <pre>
     * ido:
     *   gateway:
     *     hmac:
     *       dev-keys: "AGENCY_TEST_001=test-secret-key-for-dev-env-only-not-prod"
     * </pre>
     */
    @Value("${ido.gateway.hmac.dev-keys:}")
    private String devKeysRaw;

    // ════════════════════════════════════════════════════════════════════════
    // 초기화
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 기동 시 환경변수 스캔 → keyCache 초기화.
     *
     * <p>우선순위:
     * <ol>
     *   <li>환경변수 {@code IDO_GATEWAY_HMAC_KEY_{AGENCY_CODE}} (K8s Secret)</li>
     *   <li>시스템 속성 {@code IDO_GATEWAY_HMAC_KEY_{AGENCY_CODE}}</li>
     *   <li>개발용 {@code ido.gateway.hmac.dev-keys} (application-dev.yml)</li>
     * </ol>
     */
    @PostConstruct
    void init() {
        // ① 환경변수/시스템 속성에서 IDO_GATEWAY_HMAC_KEY_* 패턴 로드
        Map<String, String> env = System.getenv();
        int loadedCount = 0;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().startsWith(ENV_PREFIX)) {
                String agencyCode = entry.getKey().substring(ENV_PREFIX.length());
                String secret     = entry.getValue();
                if (agencyCode.isBlank() || secret == null || secret.isBlank()) continue;
                validateAndRegister(agencyCode, secret, "env");
                loadedCount++;
            }
        }

        // ② 시스템 프로퍼티에서도 로드 (테스트 환경 편의)
        for (String propName : System.getProperties().stringPropertyNames()) {
            if (propName.startsWith(ENV_PREFIX)) {
                String agencyCode = propName.substring(ENV_PREFIX.length());
                String secret     = System.getProperty(propName);
                if (agencyCode.isBlank() || secret == null || secret.isBlank()) continue;
                if (!keyCache.containsKey(agencyCode)) { // env 우선
                    validateAndRegister(agencyCode, secret, "sysprop");
                    loadedCount++;
                }
            }
        }

        // ③ 개발용 dev-keys 로드 (형식: "AGENCY_001=secret1,AGENCY_002=secret2")
        if (devKeysRaw != null && !devKeysRaw.isBlank()) {
            for (String pair : devKeysRaw.split(",")) {
                pair = pair.trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String agencyCode = pair.substring(0, eq).trim();
                String secret     = pair.substring(eq + 1).trim();
                if (!keyCache.containsKey(agencyCode)) { // env/sysprop 우선
                    validateAndRegister(agencyCode, secret, "dev-yml");
                    loadedCount++;
                }
            }
        }

        if (loadedCount == 0) {
            log.warn("[AgencyHmacKeyStore] 등록된 기관 HMAC 키가 없습니다. " +
                     "F-26(IDO_HMAC_SIG_REQUIRED=true) 활성화 전에 " +
                     "K8s Secret 'ido-gateway-hmac-keys'에 키를 등록하세요. " +
                     "환경변수 명명 규칙: IDO_GATEWAY_HMAC_KEY_{기관코드대문자}");
        } else {
            log.info("[AgencyHmacKeyStore] 기관 HMAC 키 로드 완료: {}개 기관 — {}",
                     loadedCount, String.join(", ", keyCache.keySet()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 공개 API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 기관코드로 HMAC 비밀키 조회.
     *
     * @param agencyCode 기관코드 (대소문자 무관 — 내부적으로 대문자 정규화)
     * @return HMAC 비밀키 평문, 미등록이면 {@code null}
     */
    public String findSecret(String agencyCode) {
        if (agencyCode == null || agencyCode.isBlank()) return null;
        return keyCache.get(agencyCode.toUpperCase().trim());
    }

    /**
     * 런타임 키 등록/갱신 (무중단 키 로테이션).
     *
     * <p>Stakater Reloader 또는 Kubernetes Controller에서 Secret 변경 시
     * 이 메서드를 호출하여 재시작 없이 키를 갱신합니다.
     *
     * @param agencyCode 기관코드
     * @param newSecret  새 비밀키
     */
    public void refresh(String agencyCode, String newSecret) {
        if (agencyCode == null || agencyCode.isBlank()) return;
        String normalizedCode = agencyCode.toUpperCase().trim();
        validateAndRegister(normalizedCode, newSecret, "runtime-refresh");
        log.info("[AgencyHmacKeyStore] 기관 HMAC 키 갱신 완료: agencyCode={}", normalizedCode);
    }

    /**
     * 등록된 기관코드 목록 반환 (키 값 미포함).
     *
     * @return 대문자 기관코드 집합 (읽기 전용)
     */
    public java.util.Set<String> registeredAgencies() {
        return Collections.unmodifiableSet(keyCache.keySet());
    }

    /**
     * 특정 기관 키 등록 여부 확인.
     *
     * @param agencyCode 기관코드
     * @return 등록되어 있으면 true
     */
    public boolean isRegistered(String agencyCode) {
        if (agencyCode == null || agencyCode.isBlank()) return false;
        return keyCache.containsKey(agencyCode.toUpperCase().trim());
    }

    // ════════════════════════════════════════════════════════════════════════
    // private
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 키 유효성 검사 후 캐시 등록.
     *
     * <p>키 길이 부족 시 경고 로그 출력 후 등록 (기능 차단은 운영자 판단에 위임).
     * 키 값은 처음 4자만 마스킹 형태로 로그 출력.
     */
    private void validateAndRegister(String agencyCode, String secret, String source) {
        String normalized = agencyCode.toUpperCase().trim();
        if (secret.length() < MIN_KEY_LENGTH) {
            log.warn("[AgencyHmacKeyStore][보안경고] 기관 HMAC 키 길이 부족: agencyCode={} 길이={}자 " +
                     "(권장={}자 이상) source={} — 등록은 하되 강화를 권장합니다.",
                     normalized, secret.length(), MIN_KEY_LENGTH, source);
        }
        keyCache.put(normalized, secret);
        String masked = secret.substring(0, Math.min(4, secret.length())) + "****";
        log.debug("[AgencyHmacKeyStore] 키 등록: agencyCode={} masked={} source={}",
                  normalized, masked, source);
    }
}
