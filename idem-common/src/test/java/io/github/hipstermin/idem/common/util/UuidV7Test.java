package io.github.hipstermin.idem.common.util;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * UuidV7 단위 테스트 (RFC 9562 §5.7)
 *
 * <p>검증 범위:
 * <ul>
 *   <li>포맷: {@code xxxxxxxx-xxxx-7xxx-yxxx-xxxxxxxxxxxx} 패턴 (version=7, variant=10xx)</li>
 *   <li>길이: 36자 (하이픈 포함), 32자 (compact)</li>
 *   <li>단조증가: 연속 생성 시 사전순 비교 ≥ 유지</li>
 *   <li>고유성: 대량 생성 시 중복 없음</li>
 *   <li>스레드 안전성: 멀티스레드 동시 생성 시 중복 없음</li>
 *   <li>시간 정렬 가능: 앞 12자(타임스탬프 hex)가 시간 경과에 따라 증가</li>
 * </ul>
 */
@DisplayName("UuidV7 — RFC 9562 v7 포맷·단조증가·고유성 검증")
class UuidV7Test {

    /** UUID v7 포맷 정규식: xxxxxxxx-xxxx-7xxx-[89ab]xxx-xxxxxxxxxxxx */
    private static final Pattern UUID_V7_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    /** UUID compact 포맷 정규식: 32자 소문자 hex (하이픈 없음) */
    private static final Pattern UUID_COMPACT_PATTERN = Pattern.compile(
            "^[0-9a-f]{32}$"
    );

    // ════════════════════════════════════════════════════════════════════════
    // 포맷 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("포맷 검증 — RFC 9562 §5.7 준수")
    class FormatTests {

        @Test
        @DisplayName("generate()는 UUID v7 포맷과 일치: xxxxxxxx-xxxx-7xxx-[89ab]xxx-xxxxxxxxxxxx")
        void generate_matchesUuidV7Pattern() {
            String uuid = UuidV7.generate();
            assertThat(uuid).matches(UUID_V7_PATTERN);
        }

        @Test
        @DisplayName("generate()는 36자 반환 (하이픈 4개 포함)")
        void generate_returns36Chars() {
            assertThat(UuidV7.generate()).hasSize(36);
        }

        @Test
        @DisplayName("generate()의 version 필드는 항상 '7'")
        void generate_versionFieldIs7() {
            // UUID v7: position 14 (0-indexed) = version nibble
            // 형식: 00000000-0000-[v]000-...  index 14 = 'v'
            String uuid = UuidV7.generate();
            assertThat(uuid.charAt(14)).isEqualTo('7');
        }

        @Test
        @DisplayName("generate()의 variant 필드는 '8','9','a','b' 중 하나 (RFC 4122 변형 10xx)")
        void generate_variantFieldIsRfc4122() {
            String uuid = UuidV7.generate();
            // 형식: 00000000-0000-0000-[v]000-...  index 19 = variant nibble
            char variant = uuid.charAt(19);
            assertThat(variant).isIn('8', '9', 'a', 'b');
        }

        @Test
        @DisplayName("generate()는 소문자 hex만 포함 (대문자 없음)")
        void generate_isLowerCase() {
            String uuid = UuidV7.generate();
            // 하이픈 제거 후 모두 소문자 hex
            String hexOnly = uuid.replace("-", "");
            assertThat(hexOnly).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("generate()는 하이픈이 정확히 4개: 위치 8, 13, 18, 23")
        void generate_hasHyphensAtCorrectPositions() {
            String uuid = UuidV7.generate();
            assertThat(uuid.charAt(8)).isEqualTo('-');
            assertThat(uuid.charAt(13)).isEqualTo('-');
            assertThat(uuid.charAt(18)).isEqualTo('-');
            assertThat(uuid.charAt(23)).isEqualTo('-');
        }

        @Test
        @DisplayName("generateCompact()는 32자 소문자 hex (하이픈 없음)")
        void generateCompact_returns32HexChars() {
            String compact = UuidV7.generateCompact();
            assertThat(compact).hasSize(32);
            assertThat(compact).matches(UUID_COMPACT_PATTERN);
        }

        @Test
        @DisplayName("generateCompact()의 version 필드(index 12)는 '7'")
        void generateCompact_versionNibbleIs7() {
            // compact: 하이픈 없이 32자 — 원래 UUID의 12번 index = compact의 12번 index
            String compact = UuidV7.generateCompact();
            assertThat(compact.charAt(12)).isEqualTo('7');
        }

        @RepeatedTest(20)
        @DisplayName("[반복 20회] generate()는 항상 UUID v7 포맷 준수")
        void generate_alwaysMatchesPattern() {
            assertThat(UuidV7.generate()).matches(UUID_V7_PATTERN);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 단조증가 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("단조증가 — 시간 정렬 가능성")
    class MonotonicityTests {

        @Test
        @DisplayName("연속 생성된 UUID v7은 사전순(lexicographic)으로 비단조 감소하지 않음")
        void consecutiveUuids_areMonotonicallyNonDecreasing() {
            List<String> uuids = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                uuids.add(UuidV7.generate());
            }

            // 연속 두 값의 비교: uuid[i] <= uuid[i+1] (사전순)
            for (int i = 0; i < uuids.size() - 1; i++) {
                assertThat(uuids.get(i).compareTo(uuids.get(i + 1)))
                        .as("uuid[%d]=%s should be ≤ uuid[%d]=%s",
                                i, uuids.get(i), i + 1, uuids.get(i + 1))
                        .isLessThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("1ms 간격 생성된 UUID는 타임스탬프 prefix가 증가")
        void uuidsWithTimeDifference_haveLargerTimestampPrefix() throws InterruptedException {
            String uuid1 = UuidV7.generate();
            Thread.sleep(2);  // 최소 2ms 대기 (타임스탬프 증가 보장)
            String uuid2 = UuidV7.generate();

            // UUID v7의 첫 8자(32bit) + 다음 4자 = 48bit 타임스탬프 prefix
            // "xxxxxxxx-xxxx-7xxx-..." → 첫 13자(하이픈 포함)가 타임스탬프 부분
            // 사전순 비교로 uuid2 > uuid1 확인
            assertThat(uuid2.compareTo(uuid1)).isGreaterThan(0);
        }

        @Test
        @DisplayName("compact UUID도 사전순으로 단조 비감소")
        void compactUuids_areMonotonicallyNonDecreasing() {
            List<String> compacts = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                compacts.add(UuidV7.generateCompact());
            }
            for (int i = 0; i < compacts.size() - 1; i++) {
                assertThat(compacts.get(i).compareTo(compacts.get(i + 1)))
                        .isLessThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("UUID v7 sort → 생성 순서와 동일 (DB 인덱스 정렬 효율 검증)")
        void sortedUuids_maintainGenerationOrder() throws InterruptedException {
            // 3개 UUID를 1ms 간격으로 생성
            String uuid1 = UuidV7.generate();
            Thread.sleep(1);
            String uuid2 = UuidV7.generate();
            Thread.sleep(1);
            String uuid3 = UuidV7.generate();

            List<String> original = List.of(uuid1, uuid2, uuid3);
            List<String> sorted = new ArrayList<>(original);
            sorted.sort(String::compareTo);

            // 이미 정렬된 순서와 원래 순서가 같아야 함
            assertThat(sorted).isEqualTo(original);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 고유성 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("고유성 — 대량·멀티스레드 생성 시 중복 없음")
    class UniquenessTests {

        @Test
        @DisplayName("1,000개 연속 생성 시 중복 없음")
        void bulkGenerate_noDuplicates() {
            int count = 1_000;
            Set<String> seen = new HashSet<>(count);
            for (int i = 0; i < count; i++) {
                seen.add(UuidV7.generate());
            }
            assertThat(seen).hasSize(count);
        }

        @Test
        @DisplayName("compact 500개 연속 생성 시 중복 없음")
        void bulkGenerateCompact_noDuplicates() {
            int count = 500;
            Set<String> seen = new HashSet<>(count);
            for (int i = 0; i < count; i++) {
                seen.add(UuidV7.generateCompact());
            }
            assertThat(seen).hasSize(count);
        }

        @Test
        @DisplayName("generate()와 generateCompact()의 결과는 서로 다름 (길이/형식 차이)")
        void generate_and_generateCompact_areDifferentFormats() {
            String standard = UuidV7.generate();
            String compact  = UuidV7.generateCompact();

            assertThat(standard).hasSize(36);
            assertThat(compact).hasSize(32);
            assertThat(standard).isNotEqualTo(compact);
        }

        @Test
        @DisplayName("멀티스레드 50개 스레드 × 100개 = 5,000개 동시 생성 시 중복 없음 (스레드 안전성)")
        void concurrentGenerate_noDuplicates() throws InterruptedException {
            int threads  = 50;
            int perThread = 100;
            int total    = threads * perThread;

            List<String> results = new ArrayList<>(total);
            Object lock = new Object();
            CountDownLatch latch = new CountDownLatch(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);

            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    List<String> local = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        local.add(UuidV7.generate());
                    }
                    synchronized (lock) {
                        results.addAll(local);
                    }
                    latch.countDown();
                });
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).as("모든 스레드가 10초 내 완료되어야 함").isTrue();
            assertThat(results).hasSize(total);

            Set<String> unique = new HashSet<>(results);
            assertThat(unique).as("5,000개 UUID v7 모두 고유해야 함 (스레드 안전성)").hasSize(total);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 유틸리티 클래스 특성 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("유틸리티 클래스 특성")
    class UtilityClassTests {

        @Test
        @DisplayName("UuidV7 생성자 호출 시 UnsupportedOperationException 발생")
        void constructor_throwsUnsupportedOperationException() {
            assertThatThrownBy(() -> {
                var constructor = UuidV7.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                constructor.newInstance();
            }).hasCauseInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("null 반환 없음 — generate()는 항상 non-null 반환")
        void generate_neverReturnsNull() {
            for (int i = 0; i < 10; i++) {
                assertThat(UuidV7.generate()).isNotNull();
            }
        }

        @Test
        @DisplayName("null 반환 없음 — generateCompact()는 항상 non-null 반환")
        void generateCompact_neverReturnsNull() {
            for (int i = 0; i < 10; i++) {
                assertThat(UuidV7.generateCompact()).isNotNull();
            }
        }
    }
}
