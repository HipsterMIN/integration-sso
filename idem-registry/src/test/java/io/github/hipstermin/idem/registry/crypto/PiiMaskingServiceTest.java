package io.github.hipstermin.idem.registry.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PiiMaskingService 단위 테스트 — 이름/전화번호/이메일 마스킹 경계값 검증
 *
 * <p>커버 케이스:
 * <ul>
 *   <li>maskName(): 1자·2자·3자 이상·외국인명·null/blank</li>
 *   <li>maskMobile(): 11자리·10자리·하이픈 포함·짧은 번호·null/blank</li>
 *   <li>maskEmail(): 1자·2자·3자 이상 로컬파트·도메인 없음·null/blank</li>
 * </ul>
 */
@DisplayName("PiiMaskingService — PII 마스킹")
class PiiMaskingServiceTest {

    private PiiMaskingService maskingService;

    @BeforeEach
    void setUp() {
        maskingService = new PiiMaskingService();
    }

    // ── maskName() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("maskName() — 이름 마스킹")
    class MaskName {

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource({
                "홍길동, 홍*동",
                "김철수, 김*수",
                "이영희, 이*희",
                "홍길, 홍*",
                "김민, 김*",
                "홍, *",
                "홍길동철, 홍**철",
                "홍길동철수, 홍***수",
        })
        @DisplayName("한국어 이름 마스킹")
        void maskName_korean(String input, String expected) {
            assertThat(maskingService.maskName(input)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource({
                "John, J**n",
                "Jo, J*",
                "J, *",
                "John Doe, J******e",
                "Alice Smith, A*********h",
        })
        @DisplayName("외국인 이름 마스킹")
        void maskName_foreign(String input, String expected) {
            assertThat(maskingService.maskName(input)).isEqualTo(expected);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("null/빈 문자열/공백 → null 반환")
        void maskName_nullOrBlank_returnsNull(String input) {
            assertThat(maskingService.maskName(input)).isNull();
        }

        @Test
        @DisplayName("앞뒤 공백은 trim 후 마스킹")
        void maskName_leadingTrailingSpaces_trimmed() {
            assertThat(maskingService.maskName("  홍길동  ")).isEqualTo("홍*동");
        }

        @Test
        @DisplayName("마스킹 결과에 원본 길이 정보 노출 없음 (앞1자+*+뒤1자)")
        void maskName_threeChars_firstAndLastVisible() {
            String result = maskingService.maskName("홍길동");
            assertThat(result).startsWith("홍");
            assertThat(result).endsWith("동");
            assertThat(result).contains("*");
        }
    }

    // ── maskMobile() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("maskMobile() — 전화번호 마스킹")
    class MaskMobile {

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource({
                "01012345678, 010-****-5678",
                "01098765432, 010-****-5432",
                "01011112222, 010-****-2222",
        })
        @DisplayName("11자리 휴대폰 번호 마스킹")
        void maskMobile_11digits(String input, String expected) {
            assertThat(maskingService.maskMobile(input)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource({
                "010-1234-5678, 010-****-5678",
                "010-9876-5432, 010-****-5432",
        })
        @DisplayName("하이픈 포함 전화번호 — 숫자 추출 후 마스킹")
        void maskMobile_withHyphen(String input, String expected) {
            assertThat(maskingService.maskMobile(input)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource({
                "0212345678, 021-****-5678",
                "0312345678, 031-****-5678",
        })
        @DisplayName("10자리 일반 전화번호 마스킹")
        void maskMobile_10digits(String input, String expected) {
            assertThat(maskingService.maskMobile(input)).isEqualTo(expected);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("null/빈 문자열/공백 → null 반환")
        void maskMobile_nullOrBlank_returnsNull(String input) {
            assertThat(maskingService.maskMobile(input)).isNull();
        }

        @Test
        @DisplayName("짧은 번호(≤4자리) → ****")
        void maskMobile_shortNumber_returnsMasked() {
            assertThat(maskingService.maskMobile("1234")).isEqualTo("****");
        }

        @Test
        @DisplayName("마스킹 결과 앞자리(010)와 뒷자리(1234)는 노출")
        void maskMobile_prefixAndSuffixVisible() {
            String result = maskingService.maskMobile("01012341234");
            assertThat(result).startsWith("010-");
            assertThat(result).endsWith("-1234");
            assertThat(result).contains("****");
        }
    }

    // ── maskEmail() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("maskEmail() — 이메일 마스킹")
    class MaskEmail {

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource({
                "user@example.com, us**@example.com",
                "john@example.com, jo**@example.com",
                "alice@domain.org, al***@domain.org",
                "hello@test.co.kr, he***@test.co.kr",
        })
        @DisplayName("일반 이메일 마스킹 (3자 이상 로컬파트)")
        void maskEmail_normalEmail(String input, String expected) {
            assertThat(maskingService.maskEmail(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("로컬파트 2자 → 첫 1자 보존 + *")
        void maskEmail_twoCharLocal() {
            assertThat(maskingService.maskEmail("ab@example.com")).isEqualTo("a*@example.com");
        }

        @Test
        @DisplayName("로컬파트 1자 → * 단독")
        void maskEmail_oneCharLocal() {
            assertThat(maskingService.maskEmail("a@example.com")).isEqualTo("*@example.com");
        }

        @Test
        @DisplayName("@ 없는 문자열 → ****")
        void maskEmail_noAtSign_returnsMasked() {
            assertThat(maskingService.maskEmail("notanemail")).isEqualTo("****");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("null/빈 문자열/공백 → null 반환")
        void maskEmail_nullOrBlank_returnsNull(String input) {
            assertThat(maskingService.maskEmail(input)).isNull();
        }

        @Test
        @DisplayName("도메인 파트는 마스킹하지 않음")
        void maskEmail_domainNotMasked() {
            String result = maskingService.maskEmail("user@example.com");
            assertThat(result).endsWith("@example.com");
        }

        @Test
        @DisplayName("복잡한 도메인(서브도메인 포함) 유지")
        void maskEmail_complexDomainPreserved() {
            String result = maskingService.maskEmail("user@mail.example.co.kr");
            assertThat(result).endsWith("@mail.example.co.kr");
        }
    }

    // ── 일관성 검증 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("마스킹 일관성 — 동일 입력은 동일 출력")
    class Consistency {

        @Test
        @DisplayName("동일 이름 반복 호출 → 동일 마스킹 결과")
        void maskName_sameInput_sameOutput() {
            String r1 = maskingService.maskName("홍길동");
            String r2 = maskingService.maskName("홍길동");
            assertThat(r1).isEqualTo(r2);
        }

        @Test
        @DisplayName("동일 전화번호 반복 호출 → 동일 마스킹 결과")
        void maskMobile_sameInput_sameOutput() {
            String r1 = maskingService.maskMobile("01012345678");
            String r2 = maskingService.maskMobile("01012345678");
            assertThat(r1).isEqualTo(r2);
        }

        @Test
        @DisplayName("동일 이메일 반복 호출 → 동일 마스킹 결과")
        void maskEmail_sameInput_sameOutput() {
            String r1 = maskingService.maskEmail("user@example.com");
            String r2 = maskingService.maskEmail("user@example.com");
            assertThat(r1).isEqualTo(r2);
        }
    }
}
