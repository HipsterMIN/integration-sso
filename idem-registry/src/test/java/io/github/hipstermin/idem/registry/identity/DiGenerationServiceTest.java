package io.github.hipstermin.idem.registry.identity;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DiGenerationService 단위 테스트 — DI 생성/조회/직렬화 검증
 *
 * <p>커버 케이스:
 * <ul>
 *   <li>결정론적 DI — 동일 (qimUserId, agencyCode, secret) → 항상 동일 DI</li>
 *   <li>기관별 독립성 — 동일 사용자, 다른 기관 → 다른 DI</li>
 *   <li>사용자별 독립성 — 동일 기관, 다른 사용자 → 다른 DI</li>
 *   <li>비밀키 독립성 — 다른 비밀키 → 다른 DI</li>
 *   <li>getOrCreateDi() — di_map에 이미 있으면 기존 값 반환, 없으면 신규 생성</li>
 *   <li>addDiToMap() — di_map JSON 직렬화/역직렬화</li>
 *   <li>parseDiMap() — null/빈 JSON/정상 JSON</li>
 * </ul>
 */
@DisplayName("DiGenerationService — DI 생성·관리")
class DiGenerationServiceTest {

    private static final String TEST_SECRET   = "test-di-secret-for-unit-test-only";
    private static final String QIM_USER_ID_A = "user-uuid-aaa-111";
    private static final String QIM_USER_ID_B = "user-uuid-bbb-222";
    private static final String AGENCY_X      = "AGENCY_X";
    private static final String AGENCY_Y      = "AGENCY_Y";

    private DiGenerationService diService;

    @BeforeEach
    void setUp() {
        diService = new DiGenerationService(new ObjectMapper());
        ReflectionTestUtils.setField(diService, "diSecret", TEST_SECRET);
    }

    // ── generateDi() — 기본 특성 ─────────────────────────────────────────────

    @Nested
    @DisplayName("generateDi() — 기본 생성 특성")
    class GenerateDi {

        @Test
        @DisplayName("결정론적: 동일 입력 → 항상 동일 DI")
        void generateDi_sameInput_alwaysSameResult() {
            String di1 = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            String di2 = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            assertThat(di1).isEqualTo(di2);
        }

        @Test
        @DisplayName("기관별 독립성: 동일 사용자, 다른 기관 → 다른 DI")
        void generateDi_sameUser_differentAgency_differentDi() {
            String diX = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            String diY = diService.generateDi(QIM_USER_ID_A, AGENCY_Y);
            assertThat(diX).isNotEqualTo(diY);
        }

        @Test
        @DisplayName("사용자별 독립성: 다른 사용자, 동일 기관 → 다른 DI")
        void generateDi_differentUser_sameAgency_differentDi() {
            String diA = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            String diB = diService.generateDi(QIM_USER_ID_B, AGENCY_X);
            assertThat(diA).isNotEqualTo(diB);
        }

        @Test
        @DisplayName("비밀키 독립성: 다른 비밀키 → 다른 DI")
        void generateDi_differentSecret_differentDi() {
            String di1 = diService.generateDi(QIM_USER_ID_A, AGENCY_X);

            // 비밀키 변경
            ReflectionTestUtils.setField(diService, "diSecret", "different-secret-key");
            String di2 = diService.generateDi(QIM_USER_ID_A, AGENCY_X);

            assertThat(di1).isNotEqualTo(di2);
        }

        @Test
        @DisplayName("DI는 Base64URL 형식 ('+', '/', '=' 없음)")
        void generateDi_outputIsBase64Url() {
            String di = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            assertThat(di)
                    .doesNotContain("+")
                    .doesNotContain("/")
                    .doesNotContain("=");
        }

        @Test
        @DisplayName("DI 길이 — HMAC-SHA256 → 32바이트 → Base64URL 43자")
        void generateDi_length_is43chars() {
            String di = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            assertThat(di).hasSize(43);
        }

        @Test
        @DisplayName("DI는 역산 불가 — qimUserId가 DI에 포함되지 않음")
        void generateDi_doesNotContainUserId() {
            String di = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            assertThat(di).doesNotContain(QIM_USER_ID_A);
        }
    }

    // ── getOrCreateDi() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrCreateDi() — di_map 조회/생성")
    class GetOrCreateDi {

        @Test
        @DisplayName("di_map에 이미 있는 기관 → 기존 DI 반환 (저장값 우선)")
        void getOrCreateDi_existingAgency_returnsExistingDi() {
            String existingDi = "existing-di-value-for-agency-x";
            String diMapJson = "{\"" + AGENCY_X + "\": \"" + existingDi + "\"}";

            String result = diService.getOrCreateDi(QIM_USER_ID_A, AGENCY_X, diMapJson);
            assertThat(result).isEqualTo(existingDi);
        }

        @Test
        @DisplayName("di_map에 없는 기관 → 신규 DI 생성")
        void getOrCreateDi_newAgency_generatesNewDi() {
            String diMapJson = "{\"" + AGENCY_X + "\": \"some-di\"}";

            String result = diService.getOrCreateDi(QIM_USER_ID_A, AGENCY_Y, diMapJson);
            // 신규 생성된 DI는 결정론적
            String expected = diService.generateDi(QIM_USER_ID_A, AGENCY_Y);
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("di_map이 null → 신규 DI 생성")
        void getOrCreateDi_nullDiMap_generatesNewDi() {
            String result = diService.getOrCreateDi(QIM_USER_ID_A, AGENCY_X, null);
            String expected = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("di_map이 빈 JSON → 신규 DI 생성")
        void getOrCreateDi_emptyDiMap_generatesNewDi() {
            String result = diService.getOrCreateDi(QIM_USER_ID_A, AGENCY_X, "{}");
            String expected = diService.generateDi(QIM_USER_ID_A, AGENCY_X);
            assertThat(result).isEqualTo(expected);
        }
    }

    // ── addDiToMap() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addDiToMap() — di_map JSON 갱신")
    class AddDiToMap {

        @Test
        @DisplayName("빈 di_map에 기관 DI 추가")
        void addDiToMap_emptyMap_addsEntry() throws Exception {
            String updatedJson = diService.addDiToMap("{}", AGENCY_X, "di-value-x");
            ObjectMapper om = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(updatedJson, Map.class);
            assertThat(map).containsKey(AGENCY_X);
            assertThat(map.get(AGENCY_X)).isEqualTo("di-value-x");
        }

        @Test
        @DisplayName("기존 di_map에 새 기관 DI 추가 — 기존 항목 보존")
        void addDiToMap_existingEntries_preservedAfterAdd() throws Exception {
            String initial = "{\"" + AGENCY_X + "\": \"di-x\"}";
            String updatedJson = diService.addDiToMap(initial, AGENCY_Y, "di-y");
            ObjectMapper om = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(updatedJson, Map.class);
            assertThat(map).containsKey(AGENCY_X);
            assertThat(map).containsKey(AGENCY_Y);
            assertThat(map.get(AGENCY_X)).isEqualTo("di-x");
            assertThat(map.get(AGENCY_Y)).isEqualTo("di-y");
        }

        @Test
        @DisplayName("null di_map → 빈 맵으로 시작 후 추가")
        void addDiToMap_nullDiMap_startsFromEmpty() throws Exception {
            String updatedJson = diService.addDiToMap(null, AGENCY_X, "di-x");
            ObjectMapper om = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(updatedJson, Map.class);
            assertThat(map).hasSize(1);
            assertThat(map).containsKey(AGENCY_X);
        }

        @Test
        @DisplayName("동일 기관 코드 덮어쓰기")
        void addDiToMap_sameAgency_overwrites() throws Exception {
            String initial = "{\"" + AGENCY_X + "\": \"old-di\"}";
            String updatedJson = diService.addDiToMap(initial, AGENCY_X, "new-di");
            ObjectMapper om = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(updatedJson, Map.class);
            assertThat(map.get(AGENCY_X)).isEqualTo("new-di");
        }
    }

    // ── parseDiMap() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseDiMap() — JSON 역직렬화")
    class ParseDiMap {

        @Test
        @DisplayName("정상 JSON → Map 반환")
        void parseDiMap_validJson_returnsMap() {
            String json = "{\"AGENCY_A\": \"di-a\", \"AGENCY_B\": \"di-b\"}";
            Map<String, String> result = diService.parseDiMap(json);
            assertThat(result).hasSize(2)
                    .containsEntry("AGENCY_A", "di-a")
                    .containsEntry("AGENCY_B", "di-b");
        }

        @Test
        @DisplayName("null → 빈 Map 반환 (NPE 없음)")
        void parseDiMap_null_returnsEmptyMap() {
            Map<String, String> result = diService.parseDiMap(null);
            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("빈 문자열 → 빈 Map 반환")
        void parseDiMap_emptyString_returnsEmptyMap() {
            Map<String, String> result = diService.parseDiMap("");
            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("(D2) 손상된 JSON → IllegalStateException (빈 Map 으로 읽으면 기존 DI 유실·재발급)")
        void parseDiMap_malformedJson_throws() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> diService.parseDiMap("{not-valid-json"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("di_map");
        }

        @Test
        @DisplayName("빈 JSON 객체 {} → 빈 Map 반환")
        void parseDiMap_emptyJsonObject_returnsEmptyMap() {
            Map<String, String> result = diService.parseDiMap("{}");
            assertThat(result).isNotNull().isEmpty();
        }
    }

    // ── 부팅 가드 (Sprint γ-1 / F3.4) ─────────────────────────────────────────

    /**
     * {@code @PostConstruct validateDiSecret()} 가 다음을 거부하는지 검증:
     * <ul>
     *   <li>secret null/blank + escape hatch 미설정</li>
     *   <li>secret 이 알려진 placeholder ({@code default-di-secret-change-in-production} 등)</li>
     *   <li>secret 길이 16자 미만</li>
     * </ul>
     * 그리고 통과시키는지 검증:
     * <ul>
     *   <li>allow-empty-secret=true + 빈 secret (테스트/로컬)</li>
     *   <li>32자 운영 수준 secret</li>
     * </ul>
     */
    @Nested
    @DisplayName("StartupGuard — validateDiSecret() (Sprint γ-1 / F3.4)")
    class StartupGuard {

        private DiGenerationService newService(String secret, boolean allowEmpty) {
            DiGenerationService svc = new DiGenerationService(new ObjectMapper());
            ReflectionTestUtils.setField(svc, "diSecret", secret);
            ReflectionTestUtils.setField(svc, "allowEmptyDiSecret", allowEmpty);
            return svc;
        }

        @Test
        @DisplayName("secret null + allow-empty=false → IllegalStateException")
        void nullSecret_throws() {
            DiGenerationService svc = newService(null, false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QIM_DI_SECRET");
        }

        @Test
        @DisplayName("secret 빈 문자열 + allow-empty=false → IllegalStateException")
        void blankSecret_throws() {
            DiGenerationService svc = newService("   ", false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QIM_DI_SECRET");
        }

        @Test
        @DisplayName("secret = 과거 default 'default-di-secret-change-in-production' → placeholder 차단")
        void placeholderLegacyDefault_throws() {
            DiGenerationService svc = newService("default-di-secret-change-in-production", false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("secret = 'change-me' → placeholder 차단")
        void placeholderChangeMe_throws() {
            DiGenerationService svc = newService("change-me", false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("secret 15자 (16자 미만) → 길이 미달 차단")
        void tooShort_throws() {
            DiGenerationService svc = newService("abcdefghij12345", false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("짧습니다");
        }

        @Test
        @DisplayName("escape hatch + 빈 secret → 통과 (테스트/로컬 한정)")
        void escapeHatch_passes() {
            DiGenerationService svc = newService("", true);
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("escape hatch + null secret → 통과")
        void escapeHatchNull_passes() {
            DiGenerationService svc = newService(null, true);
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("운영 수준 secret (32자 hex) → 통과")
        void productionGradeSecret_passes() {
            // openssl rand -hex 16 = 32자 hex
            DiGenerationService svc = newService("a1b2c3d4e5f60718a9b0c1d2e3f40516", false);
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("정확히 16자 boundary + placeholder 아님 → 통과")
        void exactlySixteenChars_passes() {
            DiGenerationService svc = newService("xY9!aB3zQrSt7uVw", false);
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(svc, "validateDiSecret"))
                    .doesNotThrowAnyException();
        }
    }
}
