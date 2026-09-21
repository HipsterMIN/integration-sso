package io.github.hipstermin.idem.common.messaging;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@code idem.messaging.kafka.enabled=false}(기본) 일 때 적용되는 자동 설정 (D1-b).
 *
 * <ul>
 *   <li>{@link KafkaTemplate} → {@link DisabledKafkaTemplate} (모듈이 직접 정의한 템플릿이 없을 때).
 *       {@link KafkaAutoConfiguration} 보다 먼저 평가되어 Boot 기본 템플릿이 물러난다.</li>
 *   <li>모든 리스너 컨테이너 팩토리 {@code autoStartup=false} ({@link KafkaListenerAutoStartupDisabler}).</li>
 * </ul>
 * 토픽 {@code NewTopic} 빈·프로듀서 설정은 각 모듈이 {@code havingValue = "true"} 조건으로 스스로 끈다.
 */
@Slf4j
@AutoConfiguration(before = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(name = KafkaOptional.PROPERTY, havingValue = "false", matchIfMissing = true)
public class KafkaOptionalAutoConfiguration {

    @PostConstruct
    void logDisabled() {
        log.info("[Idem] Kafka 비활성 ({}=false, env {}) — 브로커 없이 기동, 아웃박스·감사는 DB 로 완결",
                KafkaOptional.PROPERTY, KafkaOptional.ENV);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate.class)
    public KafkaTemplate<?, ?> kafkaTemplate() {
        return new DisabledKafkaTemplate<>();
    }

    @Bean
    public static BeanPostProcessor kafkaListenerAutoStartupDisabler() {
        return new KafkaListenerAutoStartupDisabler();
    }
}
