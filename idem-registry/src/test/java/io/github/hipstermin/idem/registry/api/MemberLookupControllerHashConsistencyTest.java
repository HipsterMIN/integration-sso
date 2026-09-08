package io.github.hipstermin.idem.registry.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * MemberLookupController.sha256() 인코딩 일관성 회귀 가드 — Sprint γ-1 / F3.1
 *
 * <p><b>F3.1 결함</b>: 과거 {@code MemberLookupController.sha256()} 는 Base64URL 인코딩(43자) 을
 * 반환했으나, 플랫폼 내 다른 모든 identifierHash 생성 지점은 hex(64자) 를 사용했다.
 * 결과: lookup-by-ci 경로가 CI 평문을 받아 해시를 계산해도 DB의 identifier_hash 와
 * 매칭되지 않아 회원 조회가 **영구 실패**했다.
 *
 * <p><b>본 테스트가 보장하는 것</b>:
 * <ul>
 *   <li>sha256() 출력이 lowercase hex 64자 (Base64URL 아님)</li>
 *   <li>플랫폼 표준 SHA-256 hex 와 정확히 동일한 값을 반환</li>
 *   <li>'+', '/', '=' 등 Base64 특수문자가 포함되지 않음</li>
 * </ul>
 *
 * <p>이 테스트가 깨지면 즉시 F3.1 회귀를 의심하라.
 */
@DisplayName("MemberLookupController — sha256() identifierHash 인코딩 일관성 (F3.1)")
class MemberLookupControllerHashConsistencyTest {

    private final MemberLookupController controller =
            new MemberLookupController(null, null, null, null, new ObjectMapper());

    /**
     * 플랫폼 표준 identifierHash 계산: SHA-256 → lowercase hex
     * (q-sign KeycloakCallbackService / ido KeycloakOidcService / q-im UserController 와 동일)
     */
    private static String standardSha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String invokeSha256(String input) {
        return ReflectionTestUtils.invokeMethod(controller, "sha256", input);
    }

    @Nested
    @DisplayName("출력 형식")
    class OutputFormat {

        @Test
        @DisplayName("hex 64자 (SHA-256 32바이트 × 2)")
        void outputIsHex64Chars() {
            String hash = invokeSha256("test-ci-value");
            assertThat(hash).hasSize(64);
        }

        @Test
        @DisplayName("lowercase hex 문자만 포함 ([0-9a-f])")
        void outputIsLowercaseHex() {
            String hash = invokeSha256("test-ci-value");
            assertThat(hash).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("Base64 특수문자 ('+', '/', '=') 포함 안 됨 — F3.1 회귀 가드")
        void outputDoesNotContainBase64Chars() {
            String hash = invokeSha256("test-ci-value");
            assertThat(hash)
                    .doesNotContain("+")
                    .doesNotContain("/")
                    .doesNotContain("=")
                    .doesNotContain("_")
                    .doesNotContain("-");
        }
    }

    @Nested
    @DisplayName("플랫폼 표준과 일치 — F3.1 핵심 회귀")
    class PlatformConsistency {

        @Test
        @DisplayName("표준 SHA-256 hex 와 정확히 동일 값")
        void matchesStandardSha256Hex() {
            String input = "test-ci-value";
            String expected = standardSha256Hex(input);
            String actual = invokeSha256(input);
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("결정론적: 동일 input → 항상 동일 hash")
        void deterministic() {
            String h1 = invokeSha256("same-input");
            String h2 = invokeSha256("same-input");
            assertThat(h1).isEqualTo(h2);
        }

        @Test
        @DisplayName("다른 input → 다른 hash")
        void differentInputDifferentHash() {
            String h1 = invokeSha256("input-a");
            String h2 = invokeSha256("input-b");
            assertThat(h1).isNotEqualTo(h2);
        }

        @Test
        @DisplayName("UTF-8 한글 입력 처리 — 표준과 일치")
        void unicodeInputMatchesStandard() {
            String input = "한국인-식별자-12345";
            assertThat(invokeSha256(input)).isEqualTo(standardSha256Hex(input));
        }

        @Test
        @DisplayName("알려진 SHA-256 벡터 검증 (NIST: empty string)")
        void knownVector_emptyString() {
            // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            String hash = invokeSha256("");
            assertThat(hash).isEqualTo(
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }

        @Test
        @DisplayName("알려진 SHA-256 벡터 검증 (NIST: 'abc')")
        void knownVector_abc() {
            // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
            String hash = invokeSha256("abc");
            assertThat(hash).isEqualTo(
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        }
    }
}
