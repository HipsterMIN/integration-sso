package kr.go.smes.plugin.mockauth;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.spi.identity.IdentityVerificationException;
import kr.go.smes.common.spi.identity.VerificationCallback;
import kr.go.smes.common.spi.identity.VerificationRequest;
import kr.go.smes.common.spi.identity.VerificationStart;
import kr.go.smes.common.spi.identity.VerifiedIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockIdentityVerificationProviderTest {

    private final MockIdentityVerificationProvider provider = new MockIdentityVerificationProvider();

    @Test
    @DisplayName("initiate → complete 라운드트립: 요청 파라미터가 결과에 반영되고 subjectKey 는 결정적")
    void roundTrip() {
        VerificationStart start = provider.initiate(new VerificationRequest("c-1", "https://fe/return?x=1",
                Map.of("name", "김테스트", "birthDate", "19850505", "phone", "01012345678")));
        assertThat(start.providerCode()).isEqualTo("MOCK");
        assertThat(start.txId()).startsWith("mock-");
        assertThat(start.redirectUrl()).isEqualTo("https://fe/return?x=1&mockTxId=" + start.txId());
        assertThat(provider.pendingCount()).isEqualTo(1);

        VerifiedIdentity id = provider.complete(new VerificationCallback("MOCK", start.txId(), "c-1", Map.of()));
        assertThat(id.name()).isEqualTo("김테스트");
        assertThat(id.birthDate()).isEqualTo("19850505");
        assertThat(id.phone()).isEqualTo("01012345678");
        assertThat(id.level()).isEqualTo(AuthResult.AuthLevel.L1);
        assertThat(id.subjectKey()).startsWith("mock:").hasSize(37);
        assertThat(provider.pendingCount()).isZero();

        VerificationStart again = provider.initiate(new VerificationRequest(null, null,
                Map.of("name", "김테스트", "birthDate", "19850505", "phone", "01012345678")));
        VerifiedIdentity id2 = provider.complete(new VerificationCallback("MOCK", again.txId(), null, Map.of()));
        assertThat(id2.subjectKey()).isEqualTo(id.subjectKey());
        assertThat(again.redirectUrl()).isNull();
    }

    @Test
    @DisplayName("콜백 파라미터가 요청 파라미터보다 우선하고 subjectKey 를 직접 줄 수 있다")
    void callbackOverrides() {
        VerificationStart start = provider.initiate(new VerificationRequest(null, null, Map.of("name", "A")));
        VerifiedIdentity id = provider.complete(new VerificationCallback("MOCK", start.txId(), null,
                Map.of("name", "B", "subjectKey", "user-42")));
        assertThat(id.name()).isEqualTo("B");
        assertThat(id.subjectKey()).isEqualTo("user-42");
    }

    @Test
    @DisplayName("알 수 없는 txId · 재사용 · fail=true · 만료는 IdentityVerificationException")
    void failures() {
        assertThatThrownBy(() -> provider.complete(new VerificationCallback("MOCK", "mock-nope", null, Map.of())))
                .isInstanceOf(IdentityVerificationException.class)
                .extracting("reasonCode").isEqualTo("TX_NOT_FOUND");

        VerificationStart s = provider.initiate(new VerificationRequest(null, null, Map.of()));
        provider.complete(new VerificationCallback("MOCK", s.txId(), null, Map.of()));
        assertThatThrownBy(() -> provider.complete(new VerificationCallback("MOCK", s.txId(), null, Map.of())))
                .extracting("reasonCode").isEqualTo("TX_NOT_FOUND");

        VerificationStart f = provider.initiate(new VerificationRequest(null, null, Map.of()));
        assertThatThrownBy(() -> provider.complete(new VerificationCallback("MOCK", f.txId(), null, Map.of("fail", "true"))))
                .extracting("reasonCode").isEqualTo("MOCK_FAIL");

        MutableClock clock = new MutableClock(Instant.parse("2026-09-05T00:00:00Z"));
        MockIdentityVerificationProvider timed = new MockIdentityVerificationProvider(clock);
        VerificationStart e = timed.initiate(new VerificationRequest(null, null, Map.of()));
        clock.now = clock.now.plus(MockIdentityVerificationProvider.TX_TTL).plusSeconds(1);
        assertThatThrownBy(() -> timed.complete(new VerificationCallback("MOCK", e.txId(), null, Map.of())))
                .extracting("reasonCode").isEqualTo("TX_EXPIRED");
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
