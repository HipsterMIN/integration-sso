package io.github.hipstermin.idem.hub.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.hipstermin.idem.common.spi.broker.DirectBrokerAdapter;
import io.github.hipstermin.idem.hub.broker.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.hub.broker.provider.ProviderConfig;
import io.github.hipstermin.idem.hub.broker.provider.ProviderConfigRepository;
import io.github.hipstermin.idem.hub.broker.provider.ProviderRouter;
import io.github.hipstermin.idem.hub.broker.state.IdoOidcStateStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/** S5b — 코어 BrokerService 가 플러그인 {@link DirectBrokerAdapter} 를 broker_mode·휴리스틱으로 고르는지. */
@DisplayName("BrokerService — 플러그인 직접 브로커 어댑터 선택")
class BrokerServiceDirectAdapterTest {

    private final ProviderConfigRepository repo = mock(ProviderConfigRepository.class);

    private static DirectBrokerAdapter adapter(String id, String... supported) {
        DirectBrokerAdapter a = mock(DirectBrokerAdapter.class);
        when(a.id()).thenReturn(id);
        when(a.supports(any())).thenAnswer(inv -> List.of(supported).contains(inv.<String>getArgument(0)));
        return a;
    }

    private BrokerService service(List<DirectBrokerAdapter> adapters) {
        return new BrokerService(mock(RestTemplate.class), mock(KeycloakProperties.class),
                mock(IdoOidcStateStore.class), adapters, mock(ProviderRouter.class), repo);
    }

    @Test
    @DisplayName("provider_config.broker_mode 가 어댑터 id 와 같으면 그 어댑터 (휴리스틱보다 우선)")
    void byBrokerMode() {
        ProviderConfig cfg = mock(ProviderConfig.class);
        when(cfg.getBrokerMode()).thenReturn("VENDOR_B");
        when(repo.findByCode("X_METHOD")).thenReturn(Optional.of(cfg));
        DirectBrokerAdapter a = adapter("vendor_a", "X_METHOD");
        DirectBrokerAdapter b = adapter("vendor_b");

        assertThat(service(List.of(a, b)).resolveDirectBrokerAdapter("X_METHOD", "cid")).contains(b);
    }

    @Test
    @DisplayName("DB 설정이 없거나 조회가 실패하면 supports() 휴리스틱")
    void byHeuristic() {
        when(repo.findByCode("MOBILE_ID")).thenReturn(Optional.empty());
        when(repo.findByCode("PASS")).thenThrow(new IllegalStateException("db down"));
        DirectBrokerAdapter a = adapter("vendor_a", "MOBILE_ID");
        BrokerService svc = service(List.of(a));

        assertThat(svc.resolveDirectBrokerAdapter("MOBILE_ID", "cid")).contains(a);
        assertThat(svc.resolveDirectBrokerAdapter("PASS", "cid")).isEmpty();
    }

    @Test
    @DisplayName("플러그인이 없으면(코어 에디션) 항상 비어 있다 — DB 조회도 하지 않는다")
    void noAdapters() {
        assertThat(service(List.of()).resolveDirectBrokerAdapter("MOBILE_ID", "cid")).isEmpty();
        assertThat(service(null).resolveDirectBrokerAdapter("MOBILE_ID", "cid")).isEmpty();
    }
}
