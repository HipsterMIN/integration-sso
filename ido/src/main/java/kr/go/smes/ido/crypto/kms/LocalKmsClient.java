package kr.go.smes.ido.crypto.kms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 로컬/개발 환경용 KMS 클라이언트 — KMS 비활성화(enabled=false) 시 자동 선택
 *
 * <p><b>동작 원칙</b>:
 * <ul>
 *   <li>{@code decrypt(base64)} → Base64 디코딩만 수행 (외부 KMS 호출 없음)</li>
 *   <li>{@code encrypt(bytes)} → Base64 인코딩만 수행</li>
 * </ul>
 *
 * <p><b>활성화 조건</b>: {@code ido.kms.enabled=false} (또는 미설정)
 * <br>KMS 서버 없이도 애플리케이션이 정상 기동·테스트되어야 하는 모든 환경에 적합하다:
 * <ul>
 *   <li>로컬 개발자 PC (Docker Vault 미기동 상태)</li>
 *   <li>CI 단위 테스트 (외부 의존성 없이 빠른 피드백)</li>
 *   <li>개발 서버 (Vault 연동 준비 전 단계)</li>
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
 * {@code ido.kms.enabled=false} 설정은 개발·테스트 환경으로 제한한다.
 *
 * @see VaultKmsClient (운영 표준 — provider=vault, enabled=true)
 * @see KmsClient
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ido.kms", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalKmsClient implements KmsClient {

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
