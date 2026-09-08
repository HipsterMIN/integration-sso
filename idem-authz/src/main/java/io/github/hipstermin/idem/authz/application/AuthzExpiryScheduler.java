package io.github.hipstermin.idem.authz.application;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 한시 권한(JIT) 만료 전이 스케줄러.
 *
 * <p>{@code expires_at}이 경과한 ACTIVE 부여를 주기적으로 스캔해 EXPIRED로 전이한다.
 * 한 주기에 {@code batch-size}건씩 처리하고, 가득 차면 다음 주기에 이어서 처리한다.
 *
 * <h3>다중 인스턴스 주의</h3>
 * <p>q-authz를 여러 인스턴스로 운영하면 각 인스턴스가 동시에 스캔하여 EXPIRE 감사가
 * 중복될 수 있다. 운영 경화 시 ShedLock(outbox-relay-batch 패턴) 도입으로
 * 리더 1개만 실행하도록 하는 것이 권장된다(후속 증분).
 *
 * <h3>설정</h3>
 * <pre>
 * authz:
 *   expiry:
 *     enabled: true             # 비활성화하려면 false
 *     scan-interval-ms: 60000   # 스캔 주기(기본 60초)
 *     batch-size: 500           # 1주기 처리 상한
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "authz.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class AuthzExpiryScheduler {

    private final AuthzService authzService;

    @Value("${authz.expiry.batch-size:500}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${authz.expiry.scan-interval-ms:60000}",
            initialDelayString = "${authz.expiry.initial-delay-ms:30000}")
    public void scanAndExpire() {
        try {
            int expired = authzService.expireOverdue(Instant.now(), batchSize);
            if (expired > 0) {
                log.info("[AuthzExpiryScheduler] 만료 전이 완료: {}건", expired);
            }
        } catch (Exception e) {
            // 스케줄러 예외가 다음 주기를 막지 않도록 흡수
            log.error("[AuthzExpiryScheduler] 만료 전이 실패(다음 주기 재시도): {}", e.getMessage(), e);
        }
    }
}
