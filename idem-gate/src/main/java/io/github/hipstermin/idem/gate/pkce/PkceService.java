package io.github.hipstermin.idem.gate.pkce;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * PKCE (Proof Key for Code Exchange) 서비스 — RFC 7636 완전 구현
 *
 * <p><b>목적</b>: 인증 코드 탈취 공격(Authorization Code Interception Attack) 방지.
 * SPA / 모바일 앱처럼 client_secret을 안전하게 보관하기 어려운 환경에서 사용.
 *
 * <p><b>흐름</b>:
 * <pre>
 *   [클라이언트]
 *     1. code_verifier = random(43~128 chars, unreserved chars)
 *     2. code_challenge = BASE64URL(SHA-256(code_verifier))
 *     3. GET /auth-url?code_challenge=...&code_challenge_method=S256
 *
 *   [Q-Sign]
 *     4. Redis에 {state → code_challenge} 저장 (TTL 5분)
 *     5. Keycloak 인가 요청 시 code_challenge 전달 (PKCE 지원 IdP)
 *
 *   [콜백]
 *     6. POST /callback?code=...&state=...
 *     7. 클라이언트가 code_verifier 제출
 *     8. SHA-256(code_verifier) == stored code_challenge 검증
 * </pre>
 *
 * <p><b>RFC 7636 §4.2</b>: code_verifier 요구사항:
 * <ul>
 *   <li>길이 43~128자</li>
 *   <li>허용 문자: A-Z / a-z / 0-9 / '-' / '.' / '_' / '~'</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PkceService {

    private static final String KEY_PREFIX    = "qsign:pkce:challenge:";
    private static final int    VERIFIER_LEN  = 64; // 64 bytes → 86 chars base64url (within 43-128)
    private static final String CHALLENGE_METHOD = "S256";

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${qsign.pkce.challenge-ttl-seconds:300}")
    private long challengeTtlSeconds;

    @Value("${qsign.pkce.enabled:true}")
    private boolean pkceEnabled;

    // ────────────────────────────────────────────────────────────────────────
    // 클라이언트 측 헬퍼 (서버에서 code_verifier 생성 시 사용 — 테스트/시뮬레이션용)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * code_verifier 생성 (RFC 7636 §4.1)
     * <p>운영: 클라이언트가 직접 생성. 이 메서드는 서버 시뮬레이션/테스트 전용.
     */
    public String generateCodeVerifier() {
        return CryptoProviders.current().randomToken(VERIFIER_LEN);
    }

    /**
     * code_challenge 생성 (RFC 7636 §4.2 — S256 방식)
     * code_challenge = BASE64URL(SHA-256(ASCII(code_verifier)))
     */
    public String generateCodeChallenge(String codeVerifier) {
        validateVerifier(codeVerifier);
        return CryptoProviders.current().sha256Base64Url(codeVerifier.getBytes(StandardCharsets.US_ASCII));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 서버 측 — 인가 요청 시 code_challenge 저장
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 인가 요청 처리 시 code_challenge를 Redis에 저장
     *
     * <p><b>P2 보안 수정</b>: {@code plain} 방식 거부. S256만 허용.
     * plain은 code_verifier가 네트워크에 그대로 노출되어 탈취 시 PKCE 보호가 무력화됨.
     * Discovery 문서의 {@code code_challenge_methods_supported}에서도 plain 제거.
     *
     * @param state          CSRF 방어용 state 값 (기존 KeycloakStateEntry key와 연계)
     * @param codeChallenge  클라이언트가 제출한 code_challenge
     * @param method         code_challenge_method — S256만 허용 (plain 거부)
     * @throws PkceException method가 S256이 아닌 경우 (plain 포함 모든 비표준 method)
     */
    public void storeChallenge(String state, String codeChallenge, String method) {
        if (!pkceEnabled) {
            log.debug("[PKCE] PKCE 비활성화 — challenge 저장 스킵");
            return;
        }
        if (state == null || codeChallenge == null) {
            throw new PkceException("state와 code_challenge는 필수입니다");
        }
        // P2 보안 수정: plain 방식 명시적 거부 — S256만 허용 (RFC 7636 §4.2)
        if (!"S256".equalsIgnoreCase(method)) {
            log.warn("[PKCE][P2-보안] 허용되지 않는 PKCE method '{}' — S256만 허용. 요청 거부: state={}",
                    method, state);
            throw new PkceException(
                    "지원하지 않는 code_challenge_method: '" + method + "'. S256만 허용됩니다.");
        }

        String key   = KEY_PREFIX + state;
        String value = CHALLENGE_METHOD + ":" + codeChallenge;  // method는 항상 S256

        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(challengeTtlSeconds));
        log.debug("[PKCE] code_challenge 저장: state={} method=S256", state);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 서버 측 — 콜백 시 code_verifier 검증
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 콜백 처리 시 code_verifier 검증 (RFC 7636 §4.6)
     *
     * @param state        인가 요청 시 사용한 state
     * @param codeVerifier 클라이언트가 제출한 code_verifier
     * @return true=검증 성공
     * @throws PkceException 검증 실패
     */
    public boolean verifyCodeVerifier(String state, String codeVerifier) {
        if (!pkceEnabled) return true;

        String key   = KEY_PREFIX + state;
        Object stored = redisTemplate.opsForValue().get(key);

        if (stored == null) {
            log.warn("[PKCE] code_challenge 없음 또는 만료: state={}", state);
            throw new PkceException("PKCE code_challenge가 만료되었거나 존재하지 않습니다");
        }

        // 사용 후 즉시 삭제 (1회성)
        redisTemplate.delete(key);

        String storedValue = stored.toString();
        String[] parts     = storedValue.split(":", 2);
        String method      = parts[0];
        String challenge   = parts[1];

        // code_verifier 검증
        String computedChallenge;
        if ("S256".equalsIgnoreCase(method)) {
            computedChallenge = generateCodeChallenge(codeVerifier);
        } else {
            // plain: code_verifier == code_challenge
            computedChallenge = codeVerifier;
        }

        // 상수시간 비교 (타이밍 공격 방지)
        boolean valid = CryptoProviders.current().constantTimeEquals(challenge, computedChallenge);

        if (!valid) {
            log.warn("[PKCE] code_verifier 검증 실패: state={} method={}", state, method);
            throw new PkceException("PKCE code_verifier 검증 실패 — 코드 탈취 의심");
        }

        log.debug("[PKCE] code_verifier 검증 성공: state={}", state);
        return true;
    }

    /**
     * PKCE 파라미터가 요청에 포함되어 있는지 확인 (선택적 PKCE 지원)
     */
    public boolean hasPkceParams(String codeChallenge) {
        return codeChallenge != null && !codeChallenge.isBlank();
    }

    // ── private ────────────────────────────────────────────────────────────

    private void validateVerifier(String codeVerifier) {
        if (codeVerifier == null) throw new PkceException("code_verifier는 null일 수 없습니다");
        int len = codeVerifier.length();
        if (len < 43 || len > 128) {
            throw new PkceException("code_verifier 길이 오류 (RFC 7636): " + len + " (허용: 43~128)");
        }
        // RFC 7636 §4.1 허용 문자 검증: A-Z a-z 0-9 - . _ ~
        if (!codeVerifier.matches("[A-Za-z0-9\\-._~]+")) {
            throw new PkceException("code_verifier에 허용되지 않는 문자가 포함되어 있습니다 (RFC 7636 §4.1)");
        }
    }

    /** PKCE 전용 예외 */
    public static class PkceException extends RuntimeException {
        public PkceException(String message) { super(message); }
        public PkceException(String message, Throwable cause) { super(message, cause); }
    }
}
