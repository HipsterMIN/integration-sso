package io.github.hipstermin.idem.common.messaging;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;

/**
 * Kafka 가 꺼졌을 때 모든 {@code @KafkaListener} 컨테이너 팩토리의 {@code autoStartup} 을 끈다.
 *
 * <p>{@code spring.kafka.listener.auto-startup} 은 Boot 기본 팩토리에만 적용되므로, 각 모듈이 직접 만든
 * 팩토리({@code handoffListenerContainerFactory} 등)까지 잡으려면 빈 후처리가 필요하다. 컨테이너는 만들어지되
 * 시작하지 않으므로 브로커 접속·토픽 확인({@code missingTopicsFatal})이 일어나지 않는다.
 */
public class KafkaListenerAutoStartupDisabler implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
            factory.setAutoStartup(false);
        }
        return bean;
    }
}
