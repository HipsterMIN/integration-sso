package io.github.hipstermin.idem.common.messaging;

/** Kafka 가 꺼진 상태({@code idem.messaging.kafka.enabled=false})에서 발행이 시도됐을 때 실패 Future 에 실리는 예외. */
public class KafkaDisabledException extends IllegalStateException {

    public KafkaDisabledException() {
        super("Kafka is disabled (" + KafkaOptional.PROPERTY + "=false) — event not sent");
    }
}
