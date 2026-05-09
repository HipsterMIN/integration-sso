package kr.go.smes.ido.handoff.validate;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * CallbackUrlValidator — Callback URL 화이트리스트 검증 단위 테스트
 *
 * <p>검증 전략 (구현 우선순위):
 * <ol>
 *   <li>완전 일치 — whitelist에 URL이 그대로 있으면 통과</li>
 *   <li>와일드카드 — {@code *.domain.com} 패턴 지원</li>
 *   <li>접두사 일치 — {@code https://domain.com}으로 시작하면 통과</li>
 * </ol>
 *
 * <p>스킵 조건:
 * <ul>
 *   <li>requestedUrl이 null 또는 blank → 검증 스킵 (예외 미발생)</li>
 *   <li>whitelist가 null 또는 empty → 검증 스킵 (PoC 하위호환)</li>
 * </ul>
 *
 * <p>차단 시: PlatformException(AGENCY_CALLBACK_BLOCKED) 발생
 */
@DisplayName("CallbackUrlValidator — Callback URL 화이트리스트 검증")
class CallbackUrlValidatorTest {

    private CallbackUrlValidator validator;

    private static final String CID               = "test-cid-001";
    private static final String ALLOWED_URL        = "https://agency.example.com/callback";
    private static final String BLOCKED_URL        = "https://evil.com/steal";
    private static final List<String> WHITELIST_SINGLE = List.of(ALLOWED_URL);

    @BeforeEach
    void setUp() {
        validator = new CallbackUrlValidator();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Nested 1: 검증 스킵 조건
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("검증 스킵 조건")
    class SkipValidation {

        @Test
        @DisplayName("requestedUrl이 null이면 검증 스킵 — 예외 미발생")
        void nullRequestedUrl_skips() {
            assertThatCode(() ->
                    validator.validate(null, WHITELIST_SINGLE, CID)
            ).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "requestedUrl=\"{0}\" → 스킵")
        @ValueSource(strings = {"", "  ", "\t", "\n"})
        @DisplayName("requestedUrl이 blank이면 검증 스킵")
        void blankRequestedUrl_skips(String blank) {
            assertThatCode(() ->
                    validator.validate(blank, WHITELIST_SINGLE, CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("whitelist가 null이면 검증 스킵 — PoC 하위호환")
        void nullWhitelist_skips() {
            assertThatCode(() ->
                    validator.validate(BLOCKED_URL, null, CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("whitelist가 빈 리스트이면 검증 스킵 — PoC 하위호환")
        void emptyWhitelist_skips() {
            assertThatCode(() ->
                    validator.validate(BLOCKED_URL, Collections.emptyList(), CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("whitelist가 null이고 requestedUrl도 null이면 스킵")
        void bothNull_skips() {
            assertThatCode(() ->
                    validator.validate(null, null, CID)
            ).doesNotThrowAnyException();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Nested 2: 완전 일치 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("완전 일치 검증")
    class ExactMatch {

        @Test
        @DisplayName("URL이 whitelist에 그대로 있으면 통과")
        void exactMatch_allowed() {
            assertThatCode(() ->
                    validator.validate(ALLOWED_URL, WHITELIST_SINGLE, CID)
            ).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "허용={0}, 요청={1} → 통과")
        @CsvSource({
                "https://agency.example.com/callback,  https://agency.example.com/callback",
                "https://sso.gov.kr/done,              https://sso.gov.kr/done",
                "http://localhost:8080/cb,              http://localhost:8080/cb"
        })
        @DisplayName("완전 일치 다양한 케이스 — 모두 통과")
        void exactMatch_various(String allowed, String requested) {
            String req = requested.trim();
            assertThatCode(() ->
                    validator.validate(req, List.of(allowed.trim()), CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("URL이 whitelist에 없으면 AGENCY_CALLBACK_BLOCKED 예외 발생")
        void exactMatch_blocked() {
            assertThatThrownBy(() ->
                    validator.validate(BLOCKED_URL, WHITELIST_SINGLE, CID)
            )
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> {
                        PlatformException pe = (PlatformException) ex;
                        assertThat(pe.getErrorCode()).isEqualTo(PlatformErrorCode.AGENCY_CALLBACK_BLOCKED);
                        assertThat(pe.getCorrelationId()).isEqualTo(CID);
                    });
        }

        @Test
        @DisplayName("대소문자 불일치는 차단 (대소문자 구분)")
        void caseInsensitive_blocked() {
            // URL 비교는 대소문자 구분
            assertThatThrownBy(() ->
                    validator.validate(
                            ALLOWED_URL.toUpperCase(),
                            WHITELIST_SINGLE,
                            CID
                    )
            ).isInstanceOf(PlatformException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Nested 3: 와일드카드 패턴 검증 (*.domain.com)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("와일드카드 패턴 검증 (*.domain.com)")
    class WildcardMatch {

        @Test
        @DisplayName("*.domain.com — 서브도메인 URL 허용")
        void wildcard_subdomain_allowed() {
            List<String> whitelist = List.of("*.agency.example.com");

            assertThatCode(() ->
                    validator.validate("https://auth.agency.example.com/callback", whitelist, CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("*.domain.com — 여러 레벨 서브도메인 허용")
        void wildcard_multiLevel_subdomain_allowed() {
            List<String> whitelist = List.of("*.example.com");

            // sub.example.com → endsWith(".example.com") → 허용
            assertThatCode(() ->
                    validator.validate("https://sub.example.com/path", whitelist, CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("*.domain.com — 루트 도메인 자체도 허용 (suffix 제거 후 equals)")
        void wildcard_rootDomain_allowed() {
            List<String> whitelist = List.of("*.example.com");

            // host=example.com, suffix=.example.com → suffix.substring(1)=example.com
            assertThatCode(() ->
                    validator.validate("https://example.com/callback", whitelist, CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("*.domain.com — 다른 도메인은 차단")
        void wildcard_differentDomain_blocked() {
            List<String> whitelist = List.of("*.agency.example.com");

            assertThatThrownBy(() ->
                    validator.validate("https://evil.com/steal", whitelist, CID)
            ).isInstanceOf(PlatformException.class)
                    .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                            .isEqualTo(PlatformErrorCode.AGENCY_CALLBACK_BLOCKED));
        }

        @Test
        @DisplayName("*.domain.com — 유사 도메인(evil.agency.example.com.hack.com)은 차단")
        void wildcard_spoofedDomain_blocked() {
            List<String> whitelist = List.of("*.agency.example.com");

            // host=agency.example.com.hack.com → endsWith(".agency.example.com") == false
            assertThatThrownBy(() ->
                    validator.validate("https://agency.example.com.hack.com/cb", whitelist, CID)
            ).isInstanceOf(PlatformException.class);
        }

        @Test
        @DisplayName("*.domain.com — 잘못된 URI 형식은 차단 (파싱 예외 처리)")
        void wildcard_invalidUri_blocked() {
            List<String> whitelist = List.of("*.agency.example.com");

            // 완전 일치도 아니고, 와일드카드 파싱 실패 → false → 예외
            assertThatThrownBy(() ->
                    validator.validate("not a valid uri !!!!", whitelist, CID)
            ).isInstanceOf(PlatformException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Nested 4: 접두사 일치 검증 (prefix match)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("접두사 일치 검증 (prefix)")
    class PrefixMatch {

        @ParameterizedTest(name = "허용={0}, 요청={1} → 통과")
        @CsvSource({
                // whitelist entry (origin),       requested URL
                "https://agency.example.com,        https://agency.example.com/callback",
                "https://agency.example.com,        https://agency.example.com/callback?state=abc",
                "https://agency.example.com,        https://agency.example.com",
        })
        @DisplayName("허용 도메인 접두사 — 하위 경로/쿼리 파라미터 URL 통과")
        void prefix_pathAndQuery_allowed(String allowed, String requested) {
            assertThatCode(() ->
                    validator.validate(requested.trim(), List.of(allowed.trim()), CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("접두사 일치 — 슬래시 없이 다른 도메인으로 이어지는 URL 차단 (evil.com.hack.com 방지)")
        void prefix_spoofedDomain_blocked() {
            // allowed=https://agency.example.com
            // requested=https://agency.example.com.evil.com/cb
            // → remainder=".evil.com/cb" → startsWith("/")? NO → 차단
            List<String> whitelist = List.of("https://agency.example.com");

            assertThatThrownBy(() ->
                    validator.validate("https://agency.example.com.evil.com/cb", whitelist, CID)
            ).isInstanceOf(PlatformException.class)
                    .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                            .isEqualTo(PlatformErrorCode.AGENCY_CALLBACK_BLOCKED));
        }

        @Test
        @DisplayName("접두사 일치 — 완전히 다른 도메인은 차단")
        void prefix_differentDomain_blocked() {
            List<String> whitelist = List.of("https://agency.example.com");

            assertThatThrownBy(() ->
                    validator.validate("https://evil.com/steal", whitelist, CID)
            ).isInstanceOf(PlatformException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Nested 5: 복합 whitelist 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("복합 whitelist 검증")
    class MultipleWhitelist {

        @Test
        @DisplayName("여러 항목 중 하나라도 일치하면 통과")
        void multipleEntries_oneMatch_allowed() {
            List<String> whitelist = List.of(
                    "https://agency-a.example.com/callback",
                    "https://agency-b.example.com/callback",
                    "*.agency-c.example.com"
            );

            // 첫 번째 항목 완전 일치
            assertThatCode(() ->
                    validator.validate("https://agency-a.example.com/callback", whitelist, CID)
            ).doesNotThrowAnyException();

            // 와일드카드 일치
            assertThatCode(() ->
                    validator.validate("https://sub.agency-c.example.com/cb", whitelist, CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("어떤 항목과도 일치하지 않으면 차단")
        void multipleEntries_noMatch_blocked() {
            List<String> whitelist = List.of(
                    "https://agency-a.example.com/callback",
                    "*.agency-b.example.com"
            );

            assertThatThrownBy(() ->
                    validator.validate("https://evil.com/steal", whitelist, CID)
            ).isInstanceOf(PlatformException.class)
                    .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                            .isEqualTo(PlatformErrorCode.AGENCY_CALLBACK_BLOCKED));
        }

        @Test
        @DisplayName("whitelist에 blank 항목이 섞여 있어도 유효 항목으로 검증")
        void whitelist_withBlankEntries_stillValidates() {
            // blank 항목은 matches() 내부에서 false 반환 → 다음 항목으로 진행
            List<String> whitelist = List.of(
                    "",
                    "  ",
                    ALLOWED_URL
            );

            assertThatCode(() ->
                    validator.validate(ALLOWED_URL, whitelist, CID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("correlationId가 null이어도 PlatformException에 null correlationId 포함")
        void blockedUrl_nullCorrelationId_exceptionContainsNull() {
            assertThatThrownBy(() ->
                    validator.validate(BLOCKED_URL, WHITELIST_SINGLE, null)
            )
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> {
                        PlatformException pe = (PlatformException) ex;
                        assertThat(pe.getErrorCode()).isEqualTo(PlatformErrorCode.AGENCY_CALLBACK_BLOCKED);
                        // correlationId가 null이어도 예외 생성은 정상 동작
                        assertThat(pe.getCorrelationId()).isNull();
                    });
        }
    }
}
