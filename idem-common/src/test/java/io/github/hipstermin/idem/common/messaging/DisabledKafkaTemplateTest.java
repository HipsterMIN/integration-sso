package io.github.hipstermin.idem.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.SendResult;

class DisabledKafkaTemplateTest {

    private final DisabledKafkaTemplate<String, Object> sut = new DisabledKafkaTemplate<>();

    @Test
    void send는_예외를_던지지_않고_KafkaDisabledException_으로_실패한_Future를_돌려준다() {
        CompletableFuture<SendResult<String, Object>> f = sut.send("idem.gate.auth.events", "key", "payload");

        assertThat(f).isCompletedExceptionally();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        f.whenComplete((r, ex) -> seen.set(ex)).exceptionally(ex -> null).join();
        assertThat(seen.get()).isInstanceOf(KafkaDisabledException.class);
    }

    @Test
    void 모든_send_오버로드가_같은_결과다() {
        assertThat(sut.send("t", "v")).isCompletedExceptionally();
        assertThat(sut.send("t", 0, "k", "v")).isCompletedExceptionally();
        assertThat(sut.send(new ProducerRecord<>("t", "k", "v"))).isCompletedExceptionally();
        assertThat(sut.sendDefault("v")).isCompletedExceptionally();
    }

    @Test
    void 트랜잭션_수신_API는_즉시_예외이고_flush는_무동작이다() {
        assertThatThrownBy(() -> sut.executeInTransaction(ops -> null)).isInstanceOf(KafkaDisabledException.class);
        assertThatThrownBy(() -> sut.execute(p -> null)).isInstanceOf(KafkaDisabledException.class);
        sut.flush();
        assertThat(sut.isTransactional()).isFalse();
        assertThat(sut.inTransaction()).isFalse();
        assertThat(sut.partitionsFor("t")).isEmpty();
    }
}
