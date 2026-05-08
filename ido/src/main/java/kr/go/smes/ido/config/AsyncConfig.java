package kr.go.smes.ido.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 비동기 처리 설정
 *
 * <p><b>Executor 목록</b>:
 * <ul>
 *   <li>{@code auditExecutor}   — AuditLogPublisher @Async 감사 로그 비동기 발행</li>
 *   <li>{@code webhookExecutor} — WebhookDispatchOutboxRelay HTTP 발송 스레드 풀 (미래 확장용)</li>
 * </ul>
 *
 * <p><b>60,000명 급증 대응 설계</b>:
 * <pre>
 * auditExecutor:
 *   - 감사 로그는 비즈니스 흐름 블로킹 금지 → 별도 스레드 풀
 *   - corePoolSize=4: 일반 부하
 *   - maxPoolSize=16: 60k 급증 시 감사 로그 폭발 대응
 *   - queueCapacity=10000: 큐에서 대기 후 처리
 *   - keepAliveSeconds=60: 유휴 스레드 빠른 반환
 *
 * webhookExecutor (미래 확장):
 *   - 현재는 Outbox Relay 단일 스레드가 처리
 *   - 향후 WebhookDispatcherService 비동기 호출 시 사용
 * </pre>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    // ── auditExecutor 설정 ─────────────────────────────────────────────
    @Value("${ido.async.audit.core-pool-size:4}")
    private int auditCorePoolSize;

    @Value("${ido.async.audit.max-pool-size:16}")
    private int auditMaxPoolSize;

    @Value("${ido.async.audit.queue-capacity:10000}")
    private int auditQueueCapacity;

    @Value("${ido.async.audit.keep-alive-seconds:60}")
    private int auditKeepAliveSeconds;

    // ── webhookExecutor 설정 ──────────────────────────────────────────
    @Value("${ido.async.webhook.core-pool-size:4}")
    private int webhookCorePoolSize;

    @Value("${ido.async.webhook.max-pool-size:20}")
    private int webhookMaxPoolSize;

    @Value("${ido.async.webhook.queue-capacity:5000}")
    private int webhookQueueCapacity;

    // ═══════════════════════════════════════════════════════════════════

    /**
     * 감사 로그 비동기 Executor
     *
     * <p>AuditLogPublisher.publish()의 {@code @Async("auditExecutor")} 참조.
     * 호출 스레드(HTTP 요청 핸들러 or Kafka 컨슈머)를 블로킹하지 않고
     * 감사 로그를 별도 스레드로 DB 저장 + Kafka 발행.
     *
     * <p>CallerRunsPolicy: 큐 가득 찼을 때 호출 스레드에서 직접 실행
     * (서비스 중단보다 약간의 지연을 선호하는 감사 로그 특성에 적합)
     */
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(auditCorePoolSize);
        executor.setMaxPoolSize(auditMaxPoolSize);
        executor.setQueueCapacity(auditQueueCapacity);
        executor.setKeepAliveSeconds(auditKeepAliveSeconds);
        executor.setThreadNamePrefix("audit-exec-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Webhook 발송 비동기 Executor (미래 확장용)
     *
     * <p>현재 WebhookDispatchOutboxRelay는 @Scheduled 단일 스레드 폴링으로 동작.
     * 향후 WebhookDispatcherService를 @Async로 직접 호출하는 방식으로 전환 시 사용.
     *
     * <p>AbortPolicy: 큐 가득 찼을 때 예외 발생 (backpressure 신호)
     * → 호출측에서 예외 처리 후 Outbox 재시도로 우회
     */
    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(webhookCorePoolSize);
        executor.setMaxPoolSize(webhookMaxPoolSize);
        executor.setQueueCapacity(webhookQueueCapacity);
        executor.setThreadNamePrefix("webhook-exec-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
