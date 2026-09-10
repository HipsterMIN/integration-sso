package io.github.hipstermin.idem.hub.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sprint β-3 (F4.9) — SignaturePayloadBuilder 회귀 테스트
 *
 * <h3>검증 시나리오</h3>
 * <ol>
 *   <li>표준 페이로드 포맷 — agencyCode:idempotencyKey:epoch</li>
 *   <li>idempotencyKey null → 빈 문자열로 치환 (인바운드 필터와 동일)</li>
 *   <li>agencyCode null/blank → IllegalArgumentException</li>
 *   <li>secret null/blank → IllegalArgumentException</li>
 *   <li>외부 참조 구현(JCA Mac 직접 계산)과 byte-by-byte 일치</li>
 *   <li>인바운드/아웃바운드 동일 입력 → 동일 서명 (대칭성)</li>
 * </ol>
 */
@Tag("unit")
@DisplayName("Sprint β-3: SignaturePayloadBuilder 회귀 테스트 (F4.9)")
class SignaturePayloadBuilderTest {

    private static final String AGENCY_CODE     = "AGENCY_TEST_001";
    private static final String IDEMPOTENCY_KEY = "idem-key-001";
    private static final long   EPOCH           = 1_716_336_000L; // 2024-05-22 00:00:00 UTC
    private static final String SECRET          = "test-secret-32-bytes-padding-aaaaaa";

    @Nested
    @DisplayName("buildPayload — 페이로드 포맷")
    class BuildPayload {

        @Test
        @DisplayName("정상 입력 → 'agencyCode:idempotencyKey:epoch' 형식")
        void valid_input_returns_standard_format() {
            String payload = SignaturePayloadBuilder.buildPayload(AGENCY_CODE, IDEMPOTENCY_KEY, EPOCH);
            assertThat(payload).isEqualTo("AGENCY_TEST_001:idem-key-001:1716336000");
        }

        @Test
        @DisplayName("idempotencyKey null → 빈 문자열로 치환 (인바운드와 동일)")
        void null_idempotency_key_treated_as_empty() {
            String payload = SignaturePayloadBuilder.buildPayload(AGENCY_CODE, null, EPOCH);
            assertThat(payload).isEqualTo("AGENCY_TEST_001::1716336000");
        }

        @Test
        @DisplayName("agencyCode null → IllegalArgumentException (cross-agency 위험 차단)")
        void null_agency_code_rejected() {
            assertThatThrownBy(() ->
                    SignaturePayloadBuilder.buildPayload(null, IDEMPOTENCY_KEY, EPOCH))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agencyCode");
        }

        @Test
        @DisplayName("agencyCode blank → IllegalArgumentException")
        void blank_agency_code_rejected() {
            assertThatThrownBy(() ->
                    SignaturePayloadBuilder.buildPayload("   ", IDEMPOTENCY_KEY, EPOCH))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agencyCode");
        }
    }

    @Nested
    @DisplayName("computeSignature — HMAC-SHA256 계산")
    class ComputeSignature {

        @Test
        @DisplayName("외부 JCA Mac 직접 계산과 byte-by-byte 일치 — 참조 구현")
        void matches_reference_implementation() throws Exception {
            String actual = SignaturePayloadBuilder.computeSignature(
                    AGENCY_CODE, IDEMPOTENCY_KEY, EPOCH, SECRET);

            // 참조 구현: JCA Mac 직접 사용
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = AGENCY_CODE + ":" + IDEMPOTENCY_KEY + ":" + EPOCH;
            String expected = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

            assertThat(actual).isEqualTo(expected);
            assertThat(actual).hasSize(64); // HMAC-SHA256 hex = 64자
        }

        @Test
        @DisplayName("secret null → IllegalArgumentException")
        void null_secret_rejected() {
            assertThatThrownBy(() ->
                    SignaturePayloadBuilder.computeSignature(AGENCY_CODE, IDEMPOTENCY_KEY, EPOCH, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("secret");
        }

        @Test
        @DisplayName("secret blank → IllegalArgumentException")
        void blank_secret_rejected() {
            assertThatThrownBy(() ->
                    SignaturePayloadBuilder.computeSignature(AGENCY_CODE, IDEMPOTENCY_KEY, EPOCH, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("secret");
        }

        @Test
        @DisplayName("agencyCode 다르면 동일 idempotencyKey/epoch 라도 서명이 다름 — cross-agency 격리")
        void different_agency_yields_different_signature() {
            String sigA = SignaturePayloadBuilder.computeSignature(
                    "AGENCY_A", IDEMPOTENCY_KEY, EPOCH, SECRET);
            String sigB = SignaturePayloadBuilder.computeSignature(
                    "AGENCY_B", IDEMPOTENCY_KEY, EPOCH, SECRET);

            assertThat(sigA).isNotEqualTo(sigB);
        }

        @Test
        @DisplayName("idempotencyKey null vs 빈 문자열 → 동일 서명 (인바운드와 동일 동작)")
        void null_and_empty_idempotency_key_produce_same_signature() {
            String sigNull  = SignaturePayloadBuilder.computeSignature(
                    AGENCY_CODE, null, EPOCH, SECRET);
            String sigEmpty = SignaturePayloadBuilder.computeSignature(
                    AGENCY_CODE, "",   EPOCH, SECRET);

            assertThat(sigNull).isEqualTo(sigEmpty);
        }
    }

    @Nested
    @DisplayName("대칭성 — 인바운드/아웃바운드/프로비저닝 모두 동일 입력 → 동일 서명")
    class Symmetry {

        @Test
        @DisplayName("동일 입력 두 번 호출 → 결정적(deterministic) 동일 결과")
        void deterministic_output() {
            String first  = SignaturePayloadBuilder.computeSignature(
                    AGENCY_CODE, IDEMPOTENCY_KEY, EPOCH, SECRET);
            String second = SignaturePayloadBuilder.computeSignature(
                    AGENCY_CODE, IDEMPOTENCY_KEY, EPOCH, SECRET);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("F4.9 회귀 가드 — 이전 비대칭 페이로드(idempotencyKey:epoch)는 새 페이로드와 달라야 함")
        void legacy_provisioning_payload_differs() throws Exception {
            // 이전(잘못된) 페이로드: idempotencyKey:epoch
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String legacyPayload = IDEMPOTENCY_KEY + ":" + EPOCH;
            String legacySig = HexFormat.of().formatHex(
                    mac.doFinal(legacyPayload.getBytes(StandardCharsets.UTF_8)));

            // 새 페이로드: agencyCode:idempotencyKey:epoch
            String newSig = SignaturePayloadBuilder.computeSignature(
                    AGENCY_CODE, IDEMPOTENCY_KEY, EPOCH, SECRET);

            assertThat(legacySig).isNotEqualTo(newSig)
                    .as("β-3 (F4.9): 이전 페이로드와 새 페이로드의 서명은 반드시 달라야 한다 — " +
                        "기관 SDK 가 새 규칙으로 마이그레이션해야 함");
        }
    }
}
