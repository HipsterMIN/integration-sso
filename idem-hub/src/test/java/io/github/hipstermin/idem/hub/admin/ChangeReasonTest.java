package io.github.hipstermin.idem.hub.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChangeReason — X-Change-Reason 헤더의 percent-encoding 을 풀고, ASCII 원문은 그대로")
class ChangeReasonTest {

    @Test
    void decodesPercentEncodedKorean() {
        assertThat(ChangeReason.decode("%EB%8D%B0%EB%AA%A8%20%EC%98%A8%EB%B3%B4%EB%94%A9")).isEqualTo("데모 온보딩");
        assertThat(ChangeReason.decode("%EC%9E%AC%EB%B0%9C%EA%B8%89%20%26%20%ED%9A%8C%EC%A0%84")).isEqualTo("재발급 & 회전");
    }

    @Test
    void leavesPlainAsciiAndPlusAlone() {
        assertThat(ChangeReason.decode("rotate key + policy v2")).isEqualTo("rotate key + policy v2");
        assertThat(ChangeReason.decode("  spaced  ")).isEqualTo("spaced");
        assertThat(ChangeReason.decode("a%2Bb")).isEqualTo("a+b");
    }

    @Test
    void nullBlankAndBadEncoding() {
        assertThat(ChangeReason.decode(null)).isNull();
        assertThat(ChangeReason.decode("   ")).isNull();
        assertThat(ChangeReason.decode("100%")).isEqualTo("100%");
        assertThat(ChangeReason.decode("x".repeat(600))).hasSize(500);
    }
}
