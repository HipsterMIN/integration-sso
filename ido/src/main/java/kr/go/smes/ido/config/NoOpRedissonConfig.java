package kr.go.smes.ido.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

/**
 * F-08 Redisson 분산 락 비활성화 시 NoOp 대체 구현 (IDO_REDISSON_ENABLED=false)
 *
 * <p><b>목적</b>:
 * Redis 없는 로컬/개발 환경에서 {@code RedissonClient} 빈이 없으면
 * {@code NiceAuthService} 등 의존 빈 초기화 시 {@code NoSuchBeanDefinitionException} 발생.
 * 이 클래스가 {@code RedissonClient} 인터페이스의 NoOp 프록시를 등록하여
 * 앱 기동 오류 없이 "락 없이 동작"하는 모드를 제공한다.
 *
 * <p><b>NoOp 동작 정의</b>:
 * <ul>
 *   <li>{@code getLock(name)} → NoOp RLock: tryLock 항상 {@code true} (즉시 획득)</li>
 *   <li>{@code unlock()} → 아무 동작 없음</li>
 *   <li>다른 메서드 → {@code UnsupportedOperationException} (사용하지 않는 메서드 명시)</li>
 * </ul>
 *
 * <p><b>⚠️ 단일 Pod 전용</b>:
 * NoOp 락은 JVM 간 중복 실행을 막지 않는다.
 * K8s 다중 Pod 환경에서는 반드시 {@code IDO_REDISSON_ENABLED=true}로 실제 락 사용.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "ido.redisson.enabled", havingValue = "false")
public class NoOpRedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        log.warn("[NoOpRedissonConfig] Redisson 분산 락 비활성 (IDO_REDISSON_ENABLED=false). " +
                "단일 JVM synchronized 만으로 동작. 다중 Pod 환경에서는 사용 금지.");

        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class[]{RedissonClient.class},
                new NoOpRedissonHandler()
        );
    }

    /**
     * RedissonClient 프록시 핸들러
     * getLock() 호출 시 NoOp RLock 반환, 나머지는 허용되지 않음
     */
    private static class NoOpRedissonHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getLock" -> createNoOpLock(args != null && args.length > 0 ? String.valueOf(args[0]) : "unknown");
                case "isShutdown", "isShuttingDown" -> false;
                case "shutdown", "shutdownAsync" -> null;
                default -> throw new UnsupportedOperationException(
                        "[NoOpRedissonClient] 지원하지 않는 메서드: " + method.getName() +
                        " — IDO_REDISSON_ENABLED=true 로 실제 Redisson 활성화 필요");
            };
        }

        private RLock createNoOpLock(String lockName) {
            return (RLock) Proxy.newProxyInstance(
                    RLock.class.getClassLoader(),
                    new Class[]{RLock.class},
                    new NoOpLockHandler(lockName)
            );
        }
    }

    /**
     * NoOp RLock 프록시 핸들러
     * tryLock: 항상 true (즉시 획득), unlock: 무동작
     */
    private static class NoOpLockHandler implements InvocationHandler {

        private final String lockName;

        NoOpLockHandler(String lockName) {
            this.lockName = lockName;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "tryLock" -> {
                    log.debug("[NoOpLock] tryLock 즉시 허용 (NoOp): lockName={}", lockName);
                    yield true;
                }
                case "lock" -> {
                    log.debug("[NoOpLock] lock 즉시 완료 (NoOp): lockName={}", lockName);
                    yield null;
                }
                case "unlock", "forceUnlock" -> {
                    log.debug("[NoOpLock] unlock 무시 (NoOp): lockName={}", lockName);
                    yield method.getName().equals("forceUnlock") ? Boolean.TRUE : null;
                }
                case "isHeldByCurrentThread", "isLocked" -> false;
                case "getName" -> lockName;
                case "remainTimeToLive" -> -1L;
                case "getHoldCount" -> 0;
                default -> {
                    log.trace("[NoOpLock] 미지원 메서드 무시 (NoOp): method={} lockName={}", method.getName(), lockName);
                    // 기본 반환값: boolean→false, int→0, Object→null
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class || rt == Boolean.class) yield false;
                    if (rt == int.class || rt == Integer.class) yield 0;
                    if (rt == long.class || rt == Long.class) yield 0L;
                    yield null;
                }
            };
        }
    }
}
