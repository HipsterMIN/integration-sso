package io.github.hipstermin.idem.hub.identity;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 등록된 {@link SubjectIdentifierScheme} 을 스킴별로 찾아 실행한다 (S4).
 * 프로파일이 지정한 스킴에 구현이 없으면 fail-closed(E-IDO-115) — 조용히 다른 스킴으로 대체하지 않는다.
 */
@Slf4j
@Component
public class SubjectIdentifierResolver {

    private final Map<SubjectScheme, SubjectIdentifierScheme> schemes;

    public SubjectIdentifierResolver(List<SubjectIdentifierScheme> implementations) {
        Map<SubjectScheme, SubjectIdentifierScheme> map = new EnumMap<>(SubjectScheme.class);
        for (SubjectIdentifierScheme impl : implementations) {
            if (map.putIfAbsent(impl.scheme(), impl) != null) {
                throw new IllegalStateException("SubjectIdentifierScheme 중복: " + impl.scheme());
            }
        }
        this.schemes = Collections.unmodifiableMap(map);
        log.info("[SubjectResolver] 주체 식별자 스킴: {}", schemes.keySet());
    }

    public boolean supports(SubjectScheme scheme) {
        return schemes.containsKey(scheme);
    }

    /** empty = 이 스킴의 식별자가 없는 사용자(GUEST). */
    public Optional<String> resolve(SubjectScheme scheme, SubjectResolutionContext ctx) {
        SubjectIdentifierScheme impl = schemes.get(scheme);
        if (impl == null) {
            throw new PlatformException(PlatformErrorCode.IDO_SUBJECT_SCHEME_UNSUPPORTED, ctx.correlationId(),
                    "subjectScheme=" + scheme + " 지원: " + schemes.keySet());
        }
        return impl.resolve(ctx);
    }
}
