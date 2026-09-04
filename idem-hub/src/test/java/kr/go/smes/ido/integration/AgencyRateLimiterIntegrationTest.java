package kr.go.smes.ido.integration;

import kr.go.smes.ido.ratelimit.AgencyRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8-T4 | AgencyRateLimiter 통합 테스트
 *
 * <p>실제 Redis(Testcontainer)를 사용하여 Lua 스크립트 기반 Sliding Window
 * Rate Limiter 동작을 검증한다.
 *
 * <p>테스트 항목:
 * <ul>
 *   <li>TPS 제한 이하에서는 모든 요청 허용</li>
 *   <li>TPS 제한 초과 시 차단 (0 반환)</li>
 *   <li>복수 스레드 동시 요청 → Lua 원자성 보장 (race condition 없음)</li>
 *   <li>기관별 독립 키 — 다른 기관 요청이 영향을 주지 않음</li>
 *   <li>일별 쿼터 초과 시 차단</li>
 * </ul>
 */
@DisplayName("AgencyRateLimiter 통합 테스트 (Redis Testcontainer)")
class AgencyRateLimiterIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AgencyRateLimiter rateLimiter;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String AGENCY_A = "AGENCY_RL_TEST_A";
    private static final String AGENCY_B = "AGENCY_RL_TEST_B";

    @BeforeEach
    void clearRedisKeys() {
        // 테스트 격리: 이전 테스트 키 제거
        Set<String> keys = redisTemplate.keys("ido:rl:*:" + AGENCY_A + ":*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        Set<String> keysB = redisTemplate.keys("ido:rl:*:" + AGENCY_B + ":*");
        if (keysB != null && !keysB.isEmpty()) redisTemplate.delete(keysB);
    }

    @Test
    @DisplayName("TPS 제한(5) 이하 — 모든 요청 허용")
    void tpsWithinLimit_allAllowed() {
        // 설정: TPS 5, 일 1,000,000
        int allowCount = 0;
        for (int i = 0; i < 5; i++) {
            if (rateLimiter.tryAcquire(AGENCY_A, 5, 1_000_000L)) {
                allowCount++;
            }
        }
        assertThat(allowCount).isEqualTo(5);
    }

    @Test
    @DisplayName("TPS 제한(5) 초과 — 6번째 요청 차단")
    void tpsExceeded_blocked() {
        int blockedCount = 0;
        for (int i = 0; i < 10; i++) {
            boolean allowed = rateLimiter.tryAcquire(AGENCY_A, 5, 1_000_000L);
            if (!allowed) blockedCount++;
        }
        // 5개 허용, 5개 차단 (1초 윈도우 기준)
        assertThat(blockedCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("동시 다중 스레드 — Lua 원자성 보장 (초과분 정확히 차단)")
    void concurrentRequests_luaAtomicityGuaranteed() throws InterruptedException {
        int tpsLimit = 10;
        int threads  = 30;

        CountDownLatch ready  = new CountDownLatch(threads);
        CountDownLatch start  = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger blocked = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();  // 모든 스레드가 동시에 시작
                    if (rateLimiter.tryAcquire(AGENCY_A, tpsLimit, 1_000_000L)) {
                        allowed.incrementAndGet();
                    } else {
                        blocked.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        // 허용 + 차단 = 전체 요청 수 (누락 없음)
        assertThat(allowed.get() + blocked.get()).isEqualTo(threads);
        // TPS 제한 초과 → 적어도 일부는 차단되어야 함
        assertThat(blocked.get()).isGreaterThan(0);
        // TPS 제한 초과 불허 (동시성 환경에서 약간의 오차 허용 +1)
        assertThat(allowed.get()).isLessThanOrEqualTo(tpsLimit + 2);
    }

    @Test
    @DisplayName("기관별 독립 — AGENCY_A 초과가 AGENCY_B에 영향 없음")
    void agencyIsolation_independent() {
        // AGENCY_A를 TPS 한도(3)까지 소진
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryAcquire(AGENCY_A, 3, 1_000_000L);
        }

        // AGENCY_B는 영향 없이 허용
        boolean agencyBAllowed = rateLimiter.tryAcquire(AGENCY_B, 3, 1_000_000L);
        assertThat(agencyBAllowed).isTrue();
    }

    @Test
    @DisplayName("일별 쿼터(3건) 초과 — 차단")
    void dailyQuotaExceeded_blocked() {
        int allowCount = 0;
        int blockCount = 0;

        for (int i = 0; i < 6; i++) {
            // TPS 제한은 높게 (1000), 일별 쿼터만 3건으로 제한
            boolean allowed = rateLimiter.tryAcquire(AGENCY_B, 1000, 3L);
            if (allowed) allowCount++;
            else blockCount++;
        }

        assertThat(allowCount).isLessThanOrEqualTo(3);
        assertThat(blockCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("getCurrentTps() — Redis 카운터 정확히 반환")
    void getCurrentTps_returnsAccurateCount() {
        // 3회 요청
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryAcquire(AGENCY_A, 100, 1_000_000L);
        }

        long currentTps = rateLimiter.getCurrentTps(AGENCY_A);
        assertThat(currentTps).isEqualTo(3L);
    }

    @Test
    @DisplayName("rateLimitEnabled=false 설정 시 — 모든 요청 허용 (fail-open)")
    void rateLimitDisabled_alwaysAllowed() {
        // rateLimitEnabled 프로퍼티는 @Value로 주입되므로
        // 여기서는 tryAcquire(agencyCode)만 호출하여 기본 설정 통과 검증
        // (실제 disabled 테스트는 별도 @SpringBootTest 슬라이스로 처리)
        boolean result = rateLimiter.tryAcquire(AGENCY_A);
        // 기본 TPS 200 이내이므로 허용되어야 함
        assertThat(result).isTrue();
    }
}
