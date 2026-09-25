package io.github.hipstermin.idem.hub.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TotpService — RFC 6238 벡터·시간 창·base32")
class TotpServiceTest {

    final TotpService sut = new TotpService(new AdminProperties());
    /** RFC 6238 부록 B 의 SHA1 비밀 "12345678901234567890" */
    final byte[] rfcKey = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    final String rfcSecret = Base32.encode(rfcKey);

    @Test
    @DisplayName("RFC 6238 벡터: T=59s → 287082, T=1111111109 → 081804 (8자리 94287082·07081804 의 하위 6자리)")
    void rfcVectors() {
        assertThat(TotpService.generate(rfcKey, 59 / 30)).isEqualTo("287082");
        assertThat(TotpService.generate(rfcKey, 1111111109L / 30)).isEqualTo("081804");
        assertThat(sut.currentCode(rfcSecret, Instant.ofEpochSecond(59))).isEqualTo("287082");
    }

    @Test
    @DisplayName("±1 스텝은 통과, 2스텝 밖·형식 오류는 거부")
    void windowAndFormat() {
        Instant t = Instant.ofEpochSecond(1111111109L);
        assertThat(sut.verify(rfcSecret, "081804", t)).isTrue();
        assertThat(sut.verify(rfcSecret, "081804", t.plusSeconds(30))).isTrue();
        assertThat(sut.verify(rfcSecret, "081804", t.minusSeconds(30))).isTrue();
        assertThat(sut.verify(rfcSecret, "081804", t.plusSeconds(90))).isFalse();
        assertThat(sut.verify(rfcSecret, "81804", t)).isFalse();
        assertThat(sut.verify(rfcSecret, "abcdef", t)).isFalse();
        assertThat(sut.verify(rfcSecret, null, t)).isFalse();
    }

    @Test
    void secretAndUri() {
        String s = sut.generateSecret();
        assertThat(s).matches("[A-Z2-7]{32}");
        assertThat(Base32.decode(s)).hasSize(20);
        assertThat(sut.otpauthUri("alice", s)).startsWith("otpauth://totp/Idem%3Aalice?secret=" + s + "&issuer=Idem");
        assertThat(Base32.decode(Base32.encode("hello world!".getBytes()))).isEqualTo("hello world!".getBytes());
    }
}
