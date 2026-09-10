package io.github.hipstermin.idem.plugin.mockauth;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 본인인증 제공자 — 벤더 없이 인증 흐름(initiate → 콜백 → complete)을 재현한다.
 *
 * <p>동작:
 * <ul>
 *   <li>{@code initiate}: 트랜잭션을 메모리에 기록하고 {@code returnUrl?mockTxId=…} 로 되돌아가라는 결과를 돌려준다.
 *       요청 params 의 {@code name / birthDate / gender / phone / subjectKey / subjectScheme / email} 를 그대로 결과에 반영한다.
 *       {@code email} 이 있으면 EMAIL 스킴(subjectKey = email), 아니면 EXTERNAL_SUB 로 판정한다 (S4).</li>
 *   <li>{@code complete}: txId 가 살아 있고 만료 전이면 표준 결과를 만든다. params 에 {@code fail=true} 가 오면
 *       {@link IdentityVerificationException} 을 던져 실패 경로를 검증할 수 있게 한다.</li>
 *   <li>{@code subjectKey} 가 없으면 {@code mock:} + SHA-256(name|birthDate|phone) 앞 32자로 결정적으로 만든다.
 *       같은 입력이면 같은 사람으로 판정된다.</li>
 * </ul>
 * 운영 데이터가 아니므로 개인정보 마스킹·암호화는 하지 않는다. 절대 운영 프로파일에서 활성화하지 않는다.
 */
public class MockIdentityVerificationProvider implements IdentityVerificationProvider {

    public static final String CODE = "MOCK";
    static final Duration TX_TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, PendingTx> pending = new ConcurrentHashMap<>();

    public MockIdentityVerificationProvider() {
        this(Clock.systemUTC());
    }

    MockIdentityVerificationProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public AuthResult.AuthLevel level() {
        return AuthResult.AuthLevel.L1;
    }

    @Override
    public VerificationStart initiate(VerificationRequest request) {
        String txId = "mock-" + UUID.randomUUID();
        pending.put(txId, new PendingTx(request.params(), clock.instant().plus(TX_TTL)));

        String returnUrl = request.returnUrl();
        String redirect = returnUrl == null || returnUrl.isBlank() ? null
                : returnUrl + (returnUrl.contains("?") ? "&" : "?") + "mockTxId=" + txId;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("mockTxId", txId);
        params.put("hint", "POST /api/v1/auth/providers/MOCK/complete with txId");
        return new VerificationStart(CODE, txId, redirect, params);
    }

    @Override
    public VerifiedIdentity complete(VerificationCallback callback) {
        PendingTx tx = callback.txId() == null ? null : pending.remove(callback.txId());
        if (tx == null) {
            throw new IdentityVerificationException(CODE, "TX_NOT_FOUND", "알 수 없는 mock 트랜잭션: " + callback.txId());
        }
        if (clock.instant().isAfter(tx.expiresAt())) {
            throw new IdentityVerificationException(CODE, "TX_EXPIRED", "mock 트랜잭션 만료: " + callback.txId());
        }
        if ("true".equalsIgnoreCase(callback.param("fail")) || "true".equalsIgnoreCase(tx.params().get("fail"))) {
            throw new IdentityVerificationException(CODE, "MOCK_FAIL", "mock 인증 실패 시나리오");
        }

        Map<String, String> merged = new LinkedHashMap<>(tx.params());
        merged.putAll(callback.params());      // 콜백 값이 우선

        String name      = merged.getOrDefault("name", "홍길동");
        String birthDate = merged.getOrDefault("birthDate", "19900101");
        String gender    = merged.getOrDefault("gender", "1");
        String phone     = merged.getOrDefault("phone", "01000000000");
        String subject   = merged.get("subjectKey");
        SubjectScheme scheme = SubjectScheme.parse(merged.get("subjectScheme")).orElse(null);
        String email = merged.get("email");
        if (scheme == null) {
            // email 이 오면 EMAIL 스킴(CI 없는 코어 경로), 아니면 제공자 안정 식별자(EXTERNAL_SUB)
            scheme = email != null && !email.isBlank() ? SubjectScheme.EMAIL : SubjectScheme.EXTERNAL_SUB;
        }
        if ((subject == null || subject.isBlank()) && scheme == SubjectScheme.EMAIL) {
            subject = email;
        }
        if (subject == null || subject.isBlank()) {
            subject = "mock:" + sha256(name + "|" + birthDate + "|" + phone).substring(0, 32);
        }

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("mock", "true");
        if (email != null && !email.isBlank()) attrs.put("email", email);
        return new VerifiedIdentity(CODE, callback.txId(), subject, name, birthDate, gender, phone,
                merged.getOrDefault("phoneCarrier", "MOCK"), level(), clock.instant(), attrs, scheme);
    }

    /** 테스트·운영 관찰용: 대기 중 트랜잭션 수. */
    public int pendingCount() {
        return pending.size();
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record PendingTx(Map<String, String> params, Instant expiresAt) {}
}
