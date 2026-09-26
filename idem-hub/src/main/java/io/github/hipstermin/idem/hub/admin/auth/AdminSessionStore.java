package io.github.hipstermin.idem.hub.admin.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자 세션 (Redis) — 유휴(sliding)·절대 만료, 관리자당 동시 세션 수 제한.
 * <pre>
 *   idem:admin:session:{sid}          → JSON {@link AdminSession} (TTL = 유휴)
 *   idem:admin:user:{adminId}         → sid  (concurrent=1: 새 로그인이 이전 세션을 끝낸다)
 *   idem:admin:mfa:{token}            → JSON {@link PendingMfa} (2단계 대기, 짧은 TTL)
 *   idem:admin:totp:{adminId}:{step}  → "1"  (검증에 쓴 TOTP 스텝 — 같은 코드 재사용 거부, TTL = 창 길이)
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSessionStore {

    static final String SESSION_PREFIX = "idem:admin:session:";
    static final String USER_PREFIX = "idem:admin:user:";
    static final String MFA_PREFIX = "idem:admin:mfa:";
    static final String TOTP_PREFIX = "idem:admin:totp:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AdminProperties props;

    public record AdminSession(String sessionId, String adminId, String username, AdminRole role, String tenantCode,
                               boolean mustChangePassword, Instant createdAt, Instant lastActivityAt, String ip) {
        public AdminPrincipal principal() {
            return new AdminPrincipal(adminId, username, role, tenantCode, sessionId, mustChangePassword);
        }
        AdminSession touched(Instant now) {
            return new AdminSession(sessionId, adminId, username, role, tenantCode, mustChangePassword, createdAt, now, ip);
        }
        AdminSession withMustChangePassword(boolean v) {
            return new AdminSession(sessionId, adminId, username, role, tenantCode, v, createdAt, lastActivityAt, ip);
        }
    }

    /** 2단계 인증 대기 — purpose ENROLL 이면 {@code secret} 은 아직 저장 전인 새 비밀 */
    public record PendingMfa(String adminId, String purpose, String secretSealed, String ip) {}

    public AdminSession create(AdminUserEntity user, String ip) {
        Instant now = Instant.now();
        String sid = CryptoProviders.current().randomToken(32);
        AdminSession s = new AdminSession(sid, user.getAdminId(), user.getUsername(), user.getRole(), user.getTenantCode(),
                user.isMustChangePassword(), now, now, ip);
        // 동시 세션 제한 — 종전 세션을 끝낸다 (concurrent=1). 값을 키우면 그만큼만 남긴다: 단순화를 위해 1 만 지원
        String previous = redis.opsForValue().get(USER_PREFIX + user.getAdminId());
        if (previous != null && props.getSession().getConcurrent() <= 1) {
            redis.delete(SESSION_PREFIX + previous);
            log.info("[AdminSession] 동시 세션 제한 — 이전 세션 종료 username={}", user.getUsername());
        }
        save(s);
        redis.opsForValue().set(USER_PREFIX + user.getAdminId(), sid, Duration.ofMinutes(props.getSession().getAbsoluteMinutes()));
        return s;
    }

    /** 유효한 세션이면 활동 시각을 갱신해 돌려준다 (유휴·절대 만료 검사) */
    public Optional<AdminSession> touch(String sid) {
        if (sid == null || sid.isBlank()) return Optional.empty();
        String raw = redis.opsForValue().get(SESSION_PREFIX + sid);
        if (raw == null) return Optional.empty();
        AdminSession s = parse(raw);
        Instant now = Instant.now();
        if (s.createdAt().plus(Duration.ofMinutes(props.getSession().getAbsoluteMinutes())).isBefore(now)) {
            delete(sid);
            return Optional.empty();
        }
        AdminSession touched = s.touched(now);
        save(touched);
        return Optional.of(touched);
    }

    public void update(AdminSession s) { save(s); }

    public void delete(String sid) {
        String raw = redis.opsForValue().get(SESSION_PREFIX + sid);
        if (raw != null) {
            AdminSession s = parse(raw);
            String current = redis.opsForValue().get(USER_PREFIX + s.adminId());
            if (sid.equals(current)) redis.delete(USER_PREFIX + s.adminId());
        }
        redis.delete(SESSION_PREFIX + sid);
    }

    /** 관리자의 현재 세션 전부 종료 (잠금·비활성·비밀번호 재설정 시) */
    public void deleteAllOf(String adminId) {
        String sid = redis.opsForValue().get(USER_PREFIX + adminId);
        if (sid != null) redis.delete(SESSION_PREFIX + sid);
        redis.delete(USER_PREFIX + adminId);
    }

    public String createMfaToken(PendingMfa pending) {
        String token = CryptoProviders.current().randomToken(32);
        redis.opsForValue().set(MFA_PREFIX + token, write(pending), Duration.ofSeconds(props.getSession().getMfaTokenSeconds()));
        return token;
    }

    /** 1회 소비 */
    public Optional<PendingMfa> consumeMfaToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String raw = redis.opsForValue().getAndDelete(MFA_PREFIX + token);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(raw, PendingMfa.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * TOTP 스텝 1회 소비 (RFC 6238 §5.2) — 이 관리자가 이 스텝의 코드로 이미 검증했으면 false. Redis SET NX 라 여러 인스턴스·동시 요청에서도 한 번만 true.
     * Redis 오류는 예외로 올라간다(fail-closed: 로그인 실패).
     */
    public boolean consumeTotpStep(String adminId, long step, Duration ttl) {
        Boolean first = redis.opsForValue().setIfAbsent(TOTP_PREFIX + adminId + ":" + step, "1", ttl);
        return Boolean.TRUE.equals(first);
    }

    private void save(AdminSession s) {
        redis.opsForValue().set(SESSION_PREFIX + s.sessionId(), write(s), Duration.ofMinutes(props.getSession().getIdleMinutes()));
    }

    private String write(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException("세션 직렬화 실패", e); }
    }

    private AdminSession parse(String raw) {
        try { return objectMapper.readValue(raw, AdminSession.class); } catch (Exception e) { throw new IllegalStateException("세션 역직렬화 실패", e); }
    }
}
