package io.github.hipstermin.idem.hub.crypto.kms;

/**
 * KMS(Key Management Service) 클라이언트 인터페이스 (S9-T8)
 *
 * <p><b>설계 원칙</b>:
 * <ul>
 *   <li>실제 KMS(NHN Cloud Secure Key Manager)와 로컬(개발) 구현을 인터페이스로 분리</li>
 *   <li>Spring Profile 기반으로 구현체를 교체:
 *       {@code dev/local/test} → {@link NoOpKmsClient},
 *       {@code prod} → {@link NhnKmsClient}</li>
 *   <li>Envelope Encryption 패턴: DEK(Data Encryption Key)를 KMS가 보호</li>
 * </ul>
 *
 * <p><b>NHN Cloud Secure Key Manager 연동 방식</b>:
 * <pre>
 * [SECRET 모드 — 권장] (idem.hub.kms.nhn.mode=secret)
 *   1. NHN SKM 콘솔에서 기밀 데이터(32byte AES 키, Base64)를 keyId로 저장
 *   2. DB key_material_encrypted = SKM keyId (문자열)
 *   3. 조회 시: GET /secrets/{keyId} → base64 디코딩 → 평문 키 바이트
 *
 * [ENVELOPE 모드] (idem.hub.kms.nhn.mode=envelope)
 *   1. 32-byte AES 키(DEK) 생성
 *   2. POST /symmetric-keys/{keyid}/encrypt → EncryptedDEK
 *   3. DB key_material_encrypted = EncryptedDEK (Base64)
 *   4. 조회 시: POST /symmetric-keys/{keyid}/decrypt → 평문 DEK
 * </pre>
 *
 * <p><b>구현체 목록</b>:
 * <ul>
 *   <li>{@link NoOpKmsClient} — 개발/테스트 환경 (Base64 패스스루, {@code @Profile("!prod")})</li>
 *   <li>{@link NhnKmsClient} — NHN Cloud SKM 실제 연동 ({@code @Profile("prod")})</li>
 * </ul>
 *
 * @see NoOpKmsClient
 * @see NhnKmsClient
 * @see io.github.hipstermin.idem.hub.crypto.KeyVersionRegistry
 */
public interface KmsClient {

    /**
     * KMS를 사용하여 암호문(DEK) 또는 기밀 keyId를 복호화/조회한다.
     *
     * <p>NHN SKM SECRET 모드: {@code encryptedKeyBase64}를 keyId로 해석하여
     * 기밀 데이터 조회 API를 호출한다.
     *
     * <p>NHN SKM ENVELOPE 모드: {@code encryptedKeyBase64}를 대칭 키로 복호화한다.
     *
     * <p>NoOp: Base64 디코딩만 수행 (개발 환경).
     *
     * @param encryptedKeyBase64 DB {@code key_material_encrypted} 저장값
     *                           (SECRET 모드: SKM keyId, ENVELOPE 모드: 암호화된 DEK Base64)
     * @return 복호화된 키 바이트 (AES-256: 32 bytes, HMAC-SHA256: 32 bytes)
     * @throws KmsDecryptException KMS 복호화/조회 실패 시
     */
    byte[] decrypt(String encryptedKeyBase64);

    /**
     * KMS를 사용하여 평문 키(DEK)를 암호화한다.
     *
     * <p>새 키 버전 생성(로테이션) 시 DB 저장 전 KMS로 보호.
     *
     * <p>NHN SKM SECRET 모드: 직접 지원하지 않음 — 콘솔/API로 사전 등록 필요.
     * NHN SKM ENVELOPE 모드: {@code POST /symmetric-keys/{keyid}/encrypt}.
     * NoOp: Base64 인코딩만 수행.
     *
     * @param plainKeyBytes 암호화할 키 바이트 (32 bytes)
     * @return 암호화된 값 (Base64 인코딩) — DB {@code key_material_encrypted} 저장값
     * @throws KmsEncryptException KMS 암호화 실패 시
     */
    String encrypt(byte[] plainKeyBytes);

    /**
     * KMS 연결 상태 확인 (헬스 체크용)
     *
     * <p>NHN SKM: {@code GET /keymanager/v1.0/appkey/{appkey}/confirm}
     *
     * @return true=정상, false=연결 불가
     */
    boolean isHealthy();

    /**
     * 현재 KMS 제공자 식별자 반환 (로그/모니터링용)
     *
     * @return "nhn-skm" | "noop"
     */
    String providerName();

    // ── 예외 클래스 ────────────────────────────────────────────────────────

    /** KMS 복호화/조회 실패 예외 */
    class KmsDecryptException extends RuntimeException {
        public KmsDecryptException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** KMS 암호화 실패 예외 */
    class KmsEncryptException extends RuntimeException {
        public KmsEncryptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
