package io.github.hipstermin.idem.hub.identity.spi;

import io.github.hipstermin.idem.common.spi.identity.AuthWidgetDescriptor;
import io.github.hipstermin.idem.common.spi.identity.IdentityProviderRegistry;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인인증 SPI 표준 엔드포인트 (제공자 비종속).
 *
 * <pre>
 *   GET  /api/v1/auth/providers                    등록된 제공자 목록 (코드·등급·위젯 기술자)
 *   POST /api/v1/auth/providers/{code}/initiate    인증 시작 → VerificationStart
 *   POST /api/v1/auth/providers/{code}/complete    인증 완료 → { identity: VerifiedIdentity, registration: { qimUserId, newUser } }
 * </pre>
 * {@code /api/v1/auth/**} 하위라 기존 CORS·IP Rate Limit 정책이 그대로 적용된다.
 * 벤더별 엔드포인트({@code /nice/phone/*} 등)는 P2 에서 플러그인으로 이동할 때까지 병존한다.
 */
@RestController
@RequestMapping("/api/v1/auth/providers")
@RequiredArgsConstructor
@Validated
public class IdentityVerificationController {

    private final IdentityProviderRegistry registry;
    private final IdentityLoginService     loginService;

    @GetMapping
    public List<ProviderDescriptor> list() {
        return registry.all().stream().map(ProviderDescriptor::of).toList();
    }

    @PostMapping("/{code}/initiate")
    public VerificationStart initiate(@PathVariable String code,
                                      @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                      @Valid @RequestBody(required = false) InitiateRequest body) {
        InitiateRequest req = body == null ? new InitiateRequest(null, null) : body;
        return loginService.initiate(code, req.returnUrl(), req.params(), correlationId);
    }

    /**
     * 인증 완료 → 표준 결과 + registry 사용자 확정 (S4).
     *
     * <p>응답의 {@code identity} 는 종전 {@link VerifiedIdentity} 그대로이고, {@code registration} 에 registry 가 준
     * {@code qimUserId} 와 신규 여부가 붙는다. registry 장애면 503(IDEM_HUB_REGISTRY_UNREACHABLE) — 인증 성공을 등록 없이
     * 돌려주지 않는다(종전 NICE 흐름과 같은 원칙). 절차는 {@link IdentityLoginService} 에 있다(1.1: 코어 로그인 프런트와 공유).
     */
    @PostMapping("/{code}/complete")
    public CompleteResponse complete(@PathVariable String code,
                                     @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                     @Valid @RequestBody CompleteRequest body) {
        IdentityLoginService.Completed done = loginService.complete(code, body.txId(), body.params(), correlationId);
        return new CompleteResponse(done.identity(), new Registration(done.qimUserId(), done.newUser()));
    }

    public record InitiateRequest(@Size(max = 2048) String returnUrl, Map<String, String> params) {}

    public record CompleteRequest(@jakarta.validation.constraints.NotBlank @Size(max = 200) String txId,
                                  Map<String, String> params) {}

    public record Registration(String qimUserId, boolean newUser) {}

    public record CompleteResponse(VerifiedIdentity identity, Registration registration) {}

    public record ProviderDescriptor(String code, String level, AuthWidgetDescriptor widget) {
        static ProviderDescriptor of(IdentityVerificationProvider p) {
            return new ProviderDescriptor(p.code(), p.level().name(), p.widget().orElse(null));
        }
    }
}
