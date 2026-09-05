package kr.go.smes.common.spi.identity;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 제공자 레지스트리 — 코드로 {@link IdentityVerificationProvider} 를 찾는다. Spring 비의존, 스레드 안전(불변).
 *
 * <p>코어는 부팅 시 발견한 모든 제공자 빈을 여기 등록한다. 같은 코드가 둘 이상이면 설정 오류로 간주해 즉시 실패한다
 * (플러그인이 서로 다른 벤더인데 코드가 겹치는 상황을 조기에 드러내기 위함).
 */
public final class IdentityProviderRegistry {

    private final Map<String, IdentityVerificationProvider> byCode;

    public IdentityProviderRegistry(Collection<? extends IdentityVerificationProvider> providers) {
        Map<String, IdentityVerificationProvider> map = new LinkedHashMap<>();
        for (IdentityVerificationProvider p : providers) {
            String code = normalize(p.code());
            if (code.isEmpty()) {
                throw new IllegalArgumentException("IdentityVerificationProvider 의 code() 가 비어 있음: " + p.getClass().getName());
            }
            IdentityVerificationProvider prev = map.putIfAbsent(code, p);
            if (prev != null) {
                throw new IllegalStateException("본인인증 제공자 코드 중복: " + code + " (" + prev.getClass().getName()
                        + " vs " + p.getClass().getName() + ")");
            }
        }
        this.byCode = Collections.unmodifiableMap(map);
    }

    public static IdentityProviderRegistry empty() {
        return new IdentityProviderRegistry(List.of());
    }

    public Optional<IdentityVerificationProvider> find(String code) {
        if (code == null) return Optional.empty();
        return Optional.ofNullable(byCode.get(normalize(code)));
    }

    public IdentityVerificationProvider require(String code) {
        return find(code).orElseThrow(() ->
                new IdentityVerificationException(code, "PROVIDER_UNKNOWN", "등록되지 않은 본인인증 제공자: " + code));
    }

    public List<IdentityVerificationProvider> all() {
        return List.copyOf(byCode.values());
    }

    public List<String> codes() {
        return List.copyOf(byCode.keySet());
    }

    public boolean isEmpty() {
        return byCode.isEmpty();
    }

    private static String normalize(String code) {
        return Objects.requireNonNullElse(code, "").trim().toUpperCase(Locale.ROOT);
    }
}
