package io.github.hipstermin.idem.common.crypto;

import io.github.hipstermin.idem.common.crypto.jca.JcaCryptoProvider;
import java.util.Objects;

/**
 * 현재 {@link CryptoProvider} 접근점.
 *
 * <p>Spring 빈으로 주입받을 수 없는 곳(정적 유틸 {@code ApiKeyHashUtil}·{@code SubjectScheme}·플러그인 등)을 위한 정적 접근이다.
 * {@link CryptoAutoConfiguration} 이 컨텍스트의 빈을 여기에 등록하므로, KCMVP 어댑터로 교체하면 정적 호출부도 같이 바뀐다.
 * 기본값은 JCA.
 */
public final class CryptoProviders {

    private static volatile CryptoProvider current = new JcaCryptoProvider();

    private CryptoProviders() {}

    public static CryptoProvider current() {
        return current;
    }

    /** 컨텍스트 기동 시 {@link CryptoAutoConfiguration} 이 호출한다. 테스트에서 교체할 때도 쓴다. */
    public static void install(CryptoProvider provider) {
        current = Objects.requireNonNull(provider, "provider");
    }
}
