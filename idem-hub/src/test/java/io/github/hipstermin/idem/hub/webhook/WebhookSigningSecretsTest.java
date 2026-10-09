package io.github.hipstermin.idem.hub.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.hub.crypto.kms.KmsClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("WebhookSigningSecrets — 봉인·복호화(캐시)·1.0.x 원문 행 봉인")
class WebhookSigningSecretsTest {

    /** NoOp 과 같은 모양의 가짜 KMS — 호출 횟수를 센다 */
    static class FakeKms implements KmsClient {
        final AtomicInteger decrypts = new AtomicInteger();
        @Override public byte[] decrypt(String s) { decrypts.incrementAndGet(); return Base64.getDecoder().decode(s.substring("fake:".length())); }
        @Override public String encrypt(byte[] b) { return "fake:" + Base64.getEncoder().encodeToString(b); }
        @Override public boolean isHealthy() { return true; }
        @Override public String providerName() { return "fake"; }
    }

    private final FakeKms kms = new FakeKms();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WebhookSigningSecrets sut = new WebhookSigningSecrets(kms, jdbc, true);

    @Test
    @DisplayName("seal → resolve 왕복; 같은 봉인값은 한 번만 복호화(캐시); 빈 비밀은 봉인 거부")
    void sealAndResolve() {
        String sealed = sut.seal("s3cret-value");
        assertThat(sealed).startsWith("fake:").isNotEqualTo("s3cret-value");
        assertThat(sut.resolve(sealed, null)).isEqualTo("s3cret-value");
        assertThat(sut.resolve(sealed, "ignored-legacy")).isEqualTo("s3cret-value");
        assertThat(kms.decrypts.get()).isEqualTo(1);
        assertThatThrownBy(() -> sut.seal(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("봉인값이 없으면 1.0.x 컬럼의 원문을 그대로, 둘 다 없으면 null; 지문은 해시 앞 8자")
    void legacyFallbackAndFingerprint() {
        assertThat(sut.resolve(null, "legacy-raw")).isEqualTo("legacy-raw");
        assertThat(sut.resolve("", "legacy-raw")).isEqualTo("legacy-raw");
        assertThat(sut.resolve(null, null)).isNull();
        assertThat(sut.resolve(null, " ")).isNull();
        assertThat(kms.decrypts.get()).isZero();
        assertThat(WebhookSigningSecrets.fingerprint("abcdef0123456789")).isEqualTo("abcdef01");
        assertThat(WebhookSigningSecrets.fingerprint(null)).isNull();
        assertThat(WebhookSigningSecrets.fingerprint("short")).isNull();
    }

    @Test
    @DisplayName("sealLegacyRows: 봉인값 없는 행의 원문을 봉인하고 signing_secret_hash 를 SHA-256 으로 바꾼다 — 봉인된 행은 건드리지 않는다")
    void sealLegacyRows() {
        given(jdbc.queryForList(anyString())).willReturn(List.of(Map.of("agency_code", "AG1", "signing_secret_hash", "raw-1")));
        given(jdbc.update(anyString(), eq("fake:" + Base64.getEncoder().encodeToString("raw-1".getBytes(StandardCharsets.UTF_8))),
                eq(CryptoProviders.current().sha256Hex("raw-1")), eq("AG1"))).willReturn(1);

        sut.sealLegacyRows();

        verify(jdbc).update(anyString(), eq("fake:" + Base64.getEncoder().encodeToString("raw-1".getBytes(StandardCharsets.UTF_8))),
                eq(CryptoProviders.current().sha256Hex("raw-1")), eq("AG1"));
    }

    @Test
    @DisplayName("sealLegacyRows: 꺼져 있으면(seal-legacy-on-boot=false) DB 를 읽지 않고, DB 오류는 기동을 막지 않는다")
    void sealLegacyRowsOffOrFailing() {
        new WebhookSigningSecrets(kms, jdbc, false).sealLegacyRows();
        verify(jdbc, never()).queryForList(anyString());

        given(jdbc.queryForList(anyString())).willThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        sut.sealLegacyRows();   // 예외 없이 끝난다
    }
}
