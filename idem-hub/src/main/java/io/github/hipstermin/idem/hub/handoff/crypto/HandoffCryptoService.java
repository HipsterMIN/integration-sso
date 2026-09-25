package io.github.hipstermin.idem.hub.handoff.crypto;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.hub.crypto.KeyVersionRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Handoff Ticket 암호화·서명 서비스
 * 설계서 §16.4 / §24.4.1 참조
 *
 * <p><b>암호화 출력 포맷 (v2 이후)</b>:
 * <pre>
 *   {version}.{base64url(12-byte IV)}.{base64url(ciphertext+16-byte GCM tag)}
 *   예: v1.SGVsbG9Xb3JsZA.dGVzdGNpcGhlcnRleHQ
 * </pre>
 *
 * <p><b>키 버전 관리</b>: {@link KeyVersionRegistry} 위임
 * <ul>
 *   <li>신규 암호화: 항상 {@link KeyVersionRegistry#currentAesVersion()} 사용</li>
 *   <li>복호화: 버전 접두사 파싱 → 해당 버전 키 선택</li>
 *   <li>레거시 지원: 버전 접두사 없는 기존 암호문({@code isVersioned=false}) → 폴백 복호화</li>
 * </ul>
 *
 * <p>운영 시 {@code IDEM_HUB_HANDOFF_AES_KEY} / {@code IDEM_HUB_HANDOFF_HMAC_KEY} 환경변수 교체 필수.
 * 두 키 모두 32바이트(256-bit) Base64URL 인코딩 문자열이어야 합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffCryptoService {

    private static final int    GCM_IV_LENGTH  = 12;           // 96-bit IV
    private static final int    GCM_TAG_LENGTH = 128;          // 128-bit auth tag
    private static final String ALGORITHM      = "AES/GCM/NoPadding";
    /** 버전 접두사 정규식: v{숫자}.{base64url}.{base64url} */
    private static final String VERSION_PREFIX_PATTERN = "^v\\d+\\..+\\..+$";

    private final KeyVersionRegistry keyVersionRegistry;

    // ── 암호화 ───────────────────────────────────────────────────────────

    /**
     * AES-256-GCM 암호화
     *
     * <p>출력 형식: {@code {version}.{base64url(IV)}.{base64url(ciphertext+tag)}}
     *
     * @param plaintext 암호화할 평문 (JSON 직렬화된 payload)
     * @param aad       추가 인증 데이터 (ticketId — GCM AAD 바인딩)
     * @return 버전 접두사 포함 암호문
     */
    public String encrypt(String plaintext, String aad) {
        String version = keyVersionRegistry.currentAesVersion();
        try {
            byte[] keyBytes    = keyVersionRegistry.resolveAesKey(version);
            byte[] iv          = CryptoProviders.current().randomBytes(GCM_IV_LENGTH);
            byte[] cipherBytes = CryptoProviders.current().aesGcmEncrypt(keyBytes, iv, plaintext.getBytes(StandardCharsets.UTF_8), aadBytes(aad));

            String ivB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(iv);
            String ctB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(cipherBytes);

            // 출력: v{n}.{base64url(IV)}.{base64url(ciphertext+tag)}
            return version + "." + ivB64 + "." + ctB64;

        } catch (Exception e) {
            log.error("[HandoffCrypto] AES-256-GCM 암호화 실패 (version={})", version, e);
            throw new RuntimeException("Handoff payload 암호화 실패", e);
        }
    }

    /**
     * AES-256-GCM 복호화
     *
     * <p>버전 접두사({@code v{n}.{iv}.{ct}}) 가 있으면 해당 버전 키로 복호화.
     * 접두사 없는 레거시 암호문은 {@link #decryptLegacy(String, String)} 으로 처리.
     *
     * @param encryptedPayload 암호화된 페이로드
     * @param aad              추가 인증 데이터 (ticketId — 암호화 시와 동일해야 함)
     * @return 복호화된 평문
     */
    public String decrypt(String encryptedPayload, String aad) {
        if (encryptedPayload == null || encryptedPayload.isBlank()) {
            throw new IllegalArgumentException("암호화된 payload가 비어있습니다.");
        }

        if (isVersioned(encryptedPayload)) {
            return decryptVersioned(encryptedPayload, aad);
        } else {
            log.warn("[HandoffCrypto] 레거시 포맷 암호문 복호화 시도 (버전 접두사 없음)");
            return decryptLegacy(encryptedPayload, aad);
        }
    }

    /**
     * 현재 암호문이 버전 접두사 포맷인지 확인
     *
     * @param value 검사할 문자열
     * @return {@code v{n}.xxx.yyy} 패턴이면 {@code true}
     */
    public boolean isVersioned(String value) {
        return value != null && value.matches(VERSION_PREFIX_PATTERN);
    }

    // ── 서명 ─────────────────────────────────────────────────────────────

    /**
     * HMAC-SHA256 서명 생성
     *
     * <p>서명 대상: {@code ticketId | agencyCode | encryptedPayload}
     *
     * @param ticketId         티켓 ID
     * @param agencyCode       기관 코드
     * @param encryptedPayload 암호화된 페이로드
     * @return Base64URL 인코딩된 HMAC-SHA256 서명값
     */
    public String sign(String ticketId, String agencyCode, String encryptedPayload) {
        String version = keyVersionRegistry.currentHmacVersion();
        try {
            byte[]       keyBytes = keyVersionRegistry.resolveHmacKey(version);
            String signingInput = ticketId + "|" + agencyCode + "|" + encryptedPayload;
            return CryptoProviders.current().hmacSha256Base64Url(keyBytes, signingInput);
        } catch (Exception e) {
            log.error("[HandoffCrypto] HMAC-SHA256 서명 실패 (version={})", version, e);
            throw new RuntimeException("Handoff signature 생성 실패", e);
        }
    }

    /**
     * HMAC-SHA256 서명 검증
     *
     * @return {@code true} if valid
     */
    public boolean verify(String ticketId, String agencyCode,
                          String encryptedPayload, String expectedSignature) {
        try {
            String actual = sign(ticketId, agencyCode, encryptedPayload);
            return CryptoProviders.current().constantTimeEquals(actual, expectedSignature);
        } catch (Exception e) {
            log.error("[HandoffCrypto] 서명 검증 실패", e);
            return false;
        }
    }

    // ── 내부 구현 ─────────────────────────────────────────────────────────

    /**
     * 버전 접두사 포맷 복호화
     * 포맷: {@code v{n}.{base64url(IV)}.{base64url(ciphertext+tag)}}
     */
    private String decryptVersioned(String encryptedPayload, String aad) {
        String[] parts = encryptedPayload.split("\\.", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("잘못된 버전 접두사 포맷: " + encryptedPayload);
        }
        String version = parts[0];
        try {
            byte[]       iv         = Base64.getUrlDecoder().decode(parts[1]);
            byte[]       cipherBytes = Base64.getUrlDecoder().decode(parts[2]);
            byte[]       keyBytes   = keyVersionRegistry.resolveAesKey(version);
            byte[]       plain      = CryptoProviders.current().aesGcmDecrypt(keyBytes, iv, cipherBytes, aadBytes(aad));
            return new String(plain, StandardCharsets.UTF_8);

        } catch (KeyVersionRegistry.KeyNotFoundException e) {
            log.error("[HandoffCrypto] 키 버전 '{}' 없음 — 복호화 불가", version, e);
            throw new RuntimeException("Handoff payload 복호화 실패: 알 수 없는 키 버전 " + version, e);
        } catch (Exception e) {
            log.error("[HandoffCrypto] AES-256-GCM 복호화 실패 (version={})", version, e);
            throw new RuntimeException("Handoff payload 복호화 실패", e);
        }
    }

    /**
     * 레거시 포맷 복호화 (하위 호환)
     * 구 포맷: {@code Base64URL(IV || ciphertext+tag)} — 단일 연결 문자열
     * v1 키를 사용하여 복호화 시도.
     */
    private String decryptLegacy(String encryptedPayload, String aad) {
        try {
            byte[] combined   = Base64.getUrlDecoder().decode(encryptedPayload);
            byte[] iv         = new byte[GCM_IV_LENGTH];
            byte[] cipherBytes = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.length);

            // 레거시 → v1 키 사용
            byte[] keyBytes = keyVersionRegistry.resolveAesKey("v1");
            byte[] plain    = CryptoProviders.current().aesGcmDecrypt(keyBytes, iv, cipherBytes, aadBytes(aad));
            return new String(plain, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("[HandoffCrypto] 레거시 포맷 복호화 실패", e);
            throw new RuntimeException("Handoff payload 레거시 복호화 실패", e);
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────

    /** GCM AAD — ticketId 바인딩 (비어 있으면 없음) */
    private static byte[] aadBytes(String aad) {
        return aad != null && !aad.isEmpty() ? aad.getBytes(StandardCharsets.UTF_8) : null;
    }
}
