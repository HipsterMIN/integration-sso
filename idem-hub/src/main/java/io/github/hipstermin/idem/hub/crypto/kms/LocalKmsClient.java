package io.github.hipstermin.idem.hub.crypto.kms;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 로컬/개발 환경용 KMS 클라이언트 — KMS 비활성화(enabled=false) 시 명시적으로 선택
 *
 * <p><b>동작 원칙</b>:
 * <ul>
 *   <li>{@code decrypt(base64)} → Base64 디코딩만 수행 (외부 KMS 호출 없음)</li>
 *   <li>{@code encrypt(bytes)} → Base64 인코딩만 수행</li>
 * </ul>
 *
 * <p><b>활성화 조건</b> (Sprint α-1 F5.1 강화):
 * <ol>
 *   <li>{@code idem.hub.kms.enabled=false} <b>가 명시적으로 설정되어야 함</b>
 *       — 환경변수 누락(matchIfMissing) 시 자동 활성화되지 않음.</li>
 *   <li>활성 Spring profile에 {@code prod} 또는 {@code stage} 가 <b>포함되지 않아야 함</b>
 *       — 운영/스테이지에서는 이중 안전망으로 빈 등록 자체를 거부.</li>
 *   <li>위 조건을 만족해도 {@link #failFastIfProdLike()}가 한 번 더 안전 검증을 수행.</li>
 * </ol>
 *
 * <p><b>F5.1 결함 이력</b>: 이전에는 {@code matchIfMissing=true} 로 인해 ConfigMap 마운트
 * 실패·환경변수 누락 시 운영에서도 자동으로 평문 키 모드가 활성화될 위험이 있었음.
 * Sprint α-1에서 다음과 같이 가드를 추가:
 * <ul>
 *   <li>{@code matchIfMissing=false}: 환경변수가 명시적으로 "false"여야만 활성</li>
 *   <li>{@code @Profile("!prod & !stage")}: 운영·스테이지 프로파일에서는 빈 등록 거부</li>
 *   <li>{@code @PostConstruct} 부팅 가드: 운영 의심 환경 감지 시 fail-fast</li>
 * </ul>
 *
 * <p><b>KMS Off 모드와 DB 키 저장 형식</b>:
 * <pre>
 * enabled=false  → key_material_encrypted = Base64(32-byte DEK)
 *                  (평문 Base64 — 보안 없음, 개발 전용)
 * enabled=true   → key_material_encrypted = "vault:v1:AABB..."
 *                  (Vault ciphertext — 운영 표준)
 * </pre>
 * KMS On/Off 전환 시 DB 데이터 마이그레이션이 필요하다.
 * 운영 환경에서는 반드시 {@code enabled=true}로 설정해야 한다.
 *
 * <p><b>보안 경고</b>:
 * 이 구현체는 키 재료를 암호화하지 않으므로
 * 운영·스테이징 환경에서 절대 사용 금지.
 * {@code idem.hub.kms.enabled=false} 설정은 개발·테스트 환경으로 제한한다.
 *
 * @see VaultKmsClient (운영 표준 — provider=vault, enabled=true)
 * @see KmsClient
 */
@Slf4j
@Component
@Primary   // 일반 KMS(NoOp/Local/Nhn/Vault)는 ido.kms.provider 로 상호배타 활성 — 단일 KmsClient 주입의 정본
@Profile("!prod & !stage")
@ConditionalOnProperty(
    prefix      = "idem.hub.kms",
    name        = "enabled",
    havingValue = "false",
    matchIfMissing = false   // F5.1: 환경변수 누락 시 자동 활성화 금지 (Sprint α-1)
)
public class LocalKmsClient implements KmsClient {

    /**
     * 활성화 거부 대상 프로파일 — {@link #failFastIfProdLike()}에서 사용.
     * {@code @Profile("!prod & !stage")} 어노테이션이 1차 가드, 이 리스트가 2차 부팅 가드.
     */
    private static final List<String> FORBIDDEN_PROFILES = Arrays.asList("prod", "stage", "production");

    private final Environment environment;

    @Value("${idem.hub.kms.local.allow-in-prod:false}")
    private boolean allowInProd;

    public LocalKmsClient(Environment environment) {
        this.environment = environment;
    }

    /**
     * 부팅 가드 — 활성 프로파일에 prod/stage가 포함된 경우 fail-fast.
     *
     * <p>{@code @Profile} 어노테이션과 중복되어 보이지만, 다음 케이스를 잡기 위함:
     * <ul>
     *   <li>JVM 옵션 {@code -Dspring.profiles.active=prod,custom} 같이 동적으로 prod가 추가된 경우</li>
     *   <li>실수로 {@code @Profile} 어노테이션이 제거된 미래 회귀</li>
     * </ul>
     *
     * @throws IllegalStateException 운영 의심 환경에서 활성화될 때
     */
    @PostConstruct
    void failFastIfProdLike() {
        String[] active = environment.getActiveProfiles();
        for (String profile : active) {
            if (FORBIDDEN_PROFILES.contains(profile.toLowerCase())) {
                if (allowInProd) {
                    log.error("[KMS-Local] ⚠️ 운영 의심 프로파일 '{}' 에서 LocalKmsClient 활성화 — " +
                              "idem.hub.kms.local.allow-in-prod=true 로 명시적 허용됨. " +
                              "이 모드는 평문 키를 사용하며 운영 환경에 부적합합니다.", profile);
                    return;
                }
                throw new IllegalStateException(
                    "[KMS-Local][F5.1 Guard] 운영 의심 프로파일 '" + profile + "' 에서 " +
                    "LocalKmsClient가 활성화될 수 없습니다. " +
                    "운영 환경에서는 idem.hub.kms.enabled=true 및 idem.hub.kms.provider=vault 설정이 필요합니다. " +
                    "테스트 목적이라면 idem.hub.kms.local.allow-in-prod=true 로 명시적 허용하세요. " +
                    "활성 프로파일: " + Arrays.toString(active));
            }
        }
        log.warn("[KMS-Local] KMS Off 모드 활성화 — 평문 키 사용. 운영·스테이지 환경에서는 절대 사용 금지. " +
                 "활성 프로파일: {}", Arrays.toString(active));
    }

    /**
     * Base64 디코딩만 수행 (KMS 호출 없음).
     *
     * <p>DB {@code key_material_encrypted}에 저장된 값이 Base64 인코딩된
     * 평문 32-byte DEK라고 가정한다.
     * URL-safe / standard / padding 불일치를 모두 허용한다.
     *
     * @param encryptedKeyBase64 Base64 인코딩된 DEK (실제로는 평문)
     * @return 디코딩된 키 바이트
     */
    @Override
    public byte[] decrypt(String encryptedKeyBase64) {
        log.debug("[KMS-Local] KMS Off 모드: Base64 디코딩만 수행 (외부 KMS 호출 없음)");
        try {
            return decodeBase64Flexible(encryptedKeyBase64);
        } catch (Exception e) {
            throw new KmsDecryptException("[KMS-Local] Base64 디코딩 실패: " + e.getMessage(), e);
        }
    }

    /**
     * Base64 인코딩만 수행 (KMS 호출 없음).
     *
     * <p>키 로테이션 시 생성된 평문 DEK를 DB에 저장하기 위해
     * Base64 인코딩만 수행한다.
     * 운영 환경({@link VaultKmsClient})에서는 이 값 대신 "vault:v1:..." ciphertext를 저장한다.
     *
     * @param plainKeyBytes 32-byte DEK
     * @return Base64 인코딩 문자열
     */
    @Override
    public String encrypt(byte[] plainKeyBytes) {
        log.debug("[KMS-Local] KMS Off 모드: Base64 인코딩만 수행 (외부 KMS 호출 없음)");
        return Base64.getEncoder().encodeToString(plainKeyBytes);
    }

    /** KMS Off 모드는 항상 정상으로 응답 */
    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public String providerName() {
        return "local";
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────

    private byte[] decodeBase64Flexible(String b64) {
        // URL-safe(-→+, _→/) + padding 보정
        String std = b64.replace('-', '+').replace('_', '/');
        int pad = std.length() % 4;
        if (pad == 2) std += "==";
        else if (pad == 3) std += "=";
        return Base64.getDecoder().decode(std);
    }
}
