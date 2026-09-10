package io.github.hipstermin.idem.hub.identity;

import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 코어가 제공하는 {@link SubjectIdentifierScheme} 구현 (S4).
 *
 * <ul>
 *   <li>{@link SubjectScheme#PAIRWISE_HMAC} — registry 의 기관별 DI ({@code GET /internal/users/{id}/di}). 종전 동작 그대로:
 *       사용자가 없으면(404) empty → GUEST, 장애면 예외</li>
 *   <li>{@link SubjectScheme#PLATFORM_ID} — qimUserId 그대로. 조회 없음</li>
 *   <li>{@link SubjectScheme#EMAIL}/{@link SubjectScheme#PHONE}/{@link SubjectScheme#EXTERNAL_SUB} — registry 주체 키
 *       ({@code GET /internal/users/{id}/subject?scheme=}). 사용자의 스킴이 다르면 empty → GUEST</li>
 * </ul>
 */
@Slf4j
@Configuration
public class CoreSubjectSchemes {

    @Bean
    public SubjectIdentifierScheme pairwiseHmacSubjectScheme(QimClient qimClient) {
        return new SubjectIdentifierScheme() {
            @Override public SubjectScheme scheme() { return SubjectScheme.PAIRWISE_HMAC; }
            @Override public Optional<String> resolve(SubjectResolutionContext ctx) {
                String di = qimClient.getDi(ctx.qimUserId(), ctx.tenantCode(), ctx.correlationId());
                return di == null || di.isBlank() ? Optional.empty() : Optional.of(di);
            }
        };
    }

    @Bean
    public SubjectIdentifierScheme platformIdSubjectScheme() {
        return new SubjectIdentifierScheme() {
            @Override public SubjectScheme scheme() { return SubjectScheme.PLATFORM_ID; }
            @Override public Optional<String> resolve(SubjectResolutionContext ctx) {
                return Optional.ofNullable(ctx.qimUserId()).filter(s -> !s.isBlank());
            }
        };
    }

    @Bean
    public SubjectIdentifierScheme emailSubjectScheme(QimClient qimClient) {
        return new RegistryKeyScheme(SubjectScheme.EMAIL, qimClient);
    }

    @Bean
    public SubjectIdentifierScheme phoneSubjectScheme(QimClient qimClient) {
        return new RegistryKeyScheme(SubjectScheme.PHONE, qimClient);
    }

    @Bean
    public SubjectIdentifierScheme externalSubSubjectScheme(QimClient qimClient) {
        return new RegistryKeyScheme(SubjectScheme.EXTERNAL_SUB, qimClient);
    }

    /** registry 가 보관한 주체 키를 그대로 기관향 식별자로 쓰는 스킴. */
    @RequiredArgsConstructor
    static final class RegistryKeyScheme implements SubjectIdentifierScheme {
        private final SubjectScheme scheme;
        private final QimClient qimClient;

        @Override public SubjectScheme scheme() { return scheme; }

        @Override public Optional<String> resolve(SubjectResolutionContext ctx) {
            return qimClient.getSubjectKey(ctx.qimUserId(), scheme, ctx.correlationId());
        }
    }
}
