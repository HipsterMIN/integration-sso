package io.github.hipstermin.idem.common.util;

import static org.assertj.core.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SecurePasswordGenerator 단위 테스트
 *
 * <p>검증 범위:
 * <ul>
 *   <li>기본 정책: 12자, 각 유형(대/소문자·숫자·특수문자) 2개 이상</li>
 *   <li>지정 길이: 8~32자 범위 생성</li>
 *   <li>비밀번호 다양성: 연속 생성 시 중복 없음 (CSPRNG 확인)</li>
 *   <li>경계값: 길이 8(최소), 32(최대), 범위 초과 예외</li>
 *   <li>유틸리티 클래스 특성: 생성자 호출 불가</li>
 * </ul>
 */
@DisplayName("SecurePasswordGenerator — CSPRNG 기반 임시 비밀번호 생성")
class SecurePasswordGeneratorTest {

    /** 허용 특수문자 집합 (Keycloak 정책 범위) */
    private static final String ALLOWED_SPECIALS = "!@#$%^&*";

    /** 허용 대문자 (I, O 제외) */
    private static final String ALLOWED_UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    /** 허용 소문자 (i, l, o 제외) */
    private static final String ALLOWED_LOWERCASE = "abcdefghjkmnpqrstuvwxyz";

    /** 허용 숫자 (0, 1 제외) */
    private static final String ALLOWED_DIGITS = "23456789";

    // ════════════════════════════════════════════════════════════════════════
    // 기본 정책 검증
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("기본 정책 — generate() 12자, 각 유형 2개 이상")
    class DefaultPolicyTests {

        @Test
        @DisplayName("generate()는 기본 12자 반환")
        void generate_returns12Chars() {
            assertThat(SecurePasswordGenerator.generate()).hasSize(12);
        }

        @RepeatedTest(50)
        @DisplayName("[반복 50회] generate()는 항상 12자 반환")
        void generate_alwaysReturns12Chars() {
            assertThat(SecurePasswordGenerator.generate()).hasSize(12);
        }

        @RepeatedTest(50)
        @DisplayName("[반복 50회] 대문자 2개 이상 포함")
        void generate_containsAtLeast2Uppercase() {
            String pw = SecurePasswordGenerator.generate();
            long count = pw.chars()
                    .filter(c -> ALLOWED_UPPERCASE.indexOf(c) >= 0)
                    .count();
            assertThat(count).as("대문자 2개 이상 포함: pw=%s", pw).isGreaterThanOrEqualTo(2);
        }

        @RepeatedTest(50)
        @DisplayName("[반복 50회] 소문자 2개 이상 포함")
        void generate_containsAtLeast2Lowercase() {
            String pw = SecurePasswordGenerator.generate();
            long count = pw.chars()
                    .filter(c -> ALLOWED_LOWERCASE.indexOf(c) >= 0)
                    .count();
            assertThat(count).as("소문자 2개 이상 포함: pw=%s", pw).isGreaterThanOrEqualTo(2);
        }

        @RepeatedTest(50)
        @DisplayName("[반복 50회] 숫자 2개 이상 포함")
        void generate_containsAtLeast2Digits() {
            String pw = SecurePasswordGenerator.generate();
            long count = pw.chars()
                    .filter(c -> ALLOWED_DIGITS.indexOf(c) >= 0)
                    .count();
            assertThat(count).as("숫자 2개 이상 포함: pw=%s", pw).isGreaterThanOrEqualTo(2);
        }

        @RepeatedTest(50)
        @DisplayName("[반복 50회] 특수문자 2개 이상 포함")
        void generate_containsAtLeast2Specials() {
            String pw = SecurePasswordGenerator.generate();
            long count = pw.chars()
                    .filter(c -> ALLOWED_SPECIALS.indexOf(c) >= 0)
                    .count();
            assertThat(count).as("특수문자 2개 이상 포함: pw=%s", pw).isGreaterThanOrEqualTo(2);
        }

        @RepeatedTest(50)
        @DisplayName("[반복 50회] 허용된 문자 집합 이외의 문자 없음")
        void generate_containsOnlyAllowedChars() {
            String allAllowed = ALLOWED_UPPERCASE + ALLOWED_LOWERCASE + ALLOWED_DIGITS + ALLOWED_SPECIALS;
            String pw = SecurePasswordGenerator.generate();
            for (char c : pw.toCharArray()) {
                assertThat(allAllowed).as("허용 문자가 아닌 문자 발견: '%c' in pw=%s", c, pw)
                        .contains(String.valueOf(c));
            }
        }

        @Test
        @DisplayName("generate()는 null 반환 없음")
        void generate_neverReturnsNull() {
            for (int i = 0; i < 10; i++) {
                assertThat(SecurePasswordGenerator.generate()).isNotNull();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 지정 길이 검증
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("지정 길이 — generate(int length)")
    class CustomLengthTests {

        @ParameterizedTest
        @ValueSource(ints = {8, 10, 12, 16, 20, 24, 32})
        @DisplayName("지정 길이로 생성 — 정확한 길이 반환")
        void generate_returnsExactLength(int length) {
            assertThat(SecurePasswordGenerator.generate(length)).hasSize(length);
        }

        @Test
        @DisplayName("최소 길이(8자) 생성 성공")
        void generate_minLength8() {
            String pw = SecurePasswordGenerator.generate(8);
            assertThat(pw).hasSize(8);
        }

        @Test
        @DisplayName("최대 길이(32자) 생성 성공")
        void generate_maxLength32() {
            String pw = SecurePasswordGenerator.generate(32);
            assertThat(pw).hasSize(32);
        }

        @Test
        @DisplayName("길이 7(최소 미만) 시 IllegalArgumentException 발생")
        void generate_throwsForLength7() {
            assertThatThrownBy(() -> SecurePasswordGenerator.generate(7))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("8자 이상");
        }

        @Test
        @DisplayName("길이 33(최대 초과) 시 IllegalArgumentException 발생")
        void generate_throwsForLength33() {
            assertThatThrownBy(() -> SecurePasswordGenerator.generate(33))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32자 이하");
        }

        @Test
        @DisplayName("길이 0 시 IllegalArgumentException 발생")
        void generate_throwsForLength0() {
            assertThatThrownBy(() -> SecurePasswordGenerator.generate(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("길이 음수 시 IllegalArgumentException 발생")
        void generate_throwsForNegativeLength() {
            assertThatThrownBy(() -> SecurePasswordGenerator.generate(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 다양성 검증 (CSPRNG 동작 간접 확인)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("다양성 — CSPRNG 기반 예측 불가 확인")
    class DiversityTests {

        @Test
        @DisplayName("200개 연속 생성 시 중복 없음 (CSPRNG 다양성)")
        void generate_noDuplicatesIn200Calls() {
            int count = 200;
            Set<String> passwords = new HashSet<>(count);
            for (int i = 0; i < count; i++) {
                passwords.add(SecurePasswordGenerator.generate());
            }
            // CSPRNG이므로 200개 중 중복은 사실상 불가능
            assertThat(passwords).as("200개 비밀번호 중 중복 없음").hasSize(count);
        }

        @Test
        @DisplayName("동일 호출 2회가 동일 값을 반환하지 않음 (일치 확률 무시 가능 수준)")
        void twoConsecutiveCalls_areDifferent() {
            // 극히 드문 확률로 같을 수 있으나, CSPRNG에서는 사실상 불가능
            String pw1 = SecurePasswordGenerator.generate();
            String pw2 = SecurePasswordGenerator.generate();
            assertThat(pw1).isNotEqualTo(pw2);
        }

        @Test
        @DisplayName("Math.random() 패턴 불사용 — 생성 결과가 고정 prefix로 시작하지 않음")
        void generate_doesNotStartWithFixedPrefix() {
            // Math.random() 기반이면 "Rnd"로 시작하는 고정 패턴 존재
            for (int i = 0; i < 50; i++) {
                assertThat(SecurePasswordGenerator.generate())
                        .doesNotStartWith("Rnd");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 유틸리티 클래스 특성
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("유틸리티 클래스 특성")
    class UtilityClassTests {

        @Test
        @DisplayName("생성자 호출 시 UnsupportedOperationException 발생")
        void constructor_throwsUnsupportedOperationException() {
            assertThatThrownBy(() -> {
                var constructor = SecurePasswordGenerator.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                constructor.newInstance();
            }).hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }
}
