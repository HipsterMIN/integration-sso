package io.github.hipstermin.idem.common.messaging;

import org.springframework.core.env.Environment;

/**
 * Kafka 선택 의존 마스터 스위치 (범용화 D1-b, {@code docs/generalization-plan.md} D1).
 *
 * <p>{@value #PROPERTY} (환경변수 {@value #ENV}) — 기본 {@code false}.
 * <ul>
 *   <li>{@code false}(기본): 브로커 없이 기동한다. 토픽 생성·컨슈머 컨테이너·Kafka 릴레이가 꺼지고
 *       {@link org.springframework.kafka.core.KafkaTemplate} 은 {@link DisabledKafkaTemplate} 로 대체된다.
 *       아웃박스·감사는 DB 만으로 완결된다(hub 는 아웃박스를 폴링해 프로세스 내 핸들러로 배달).</li>
 *   <li>{@code true}: 종전과 같은 Kafka 경로. 다중 인스턴스·타 시스템 연동 시 사용.</li>
 * </ul>
 *
 * <p>모듈별 Kafka 전용 빈은 {@code @ConditionalOnProperty(name = KafkaOptional.PROPERTY, havingValue = "true")} 로 건다.
 * 꺼졌을 때의 파생 기본값(릴레이 플래그 등)은 {@link KafkaOptionalEnvironmentPostProcessor} 가 넣는다.
 */
public final class KafkaOptional {

    /** 설정 키 */
    public static final String PROPERTY = "idem.messaging.kafka.enabled";
    /** 환경변수 (application.yml 이 {@code ${IDEM_KAFKA_ENABLED:false}} 로 매핑) */
    public static final String ENV = "IDEM_KAFKA_ENABLED";

    private KafkaOptional() {}

    public static boolean isEnabled(Environment environment) {
        return environment.getProperty(PROPERTY, Boolean.class, false);
    }
}
