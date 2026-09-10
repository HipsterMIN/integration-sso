package io.github.hipstermin.idem.hub.identity;

import io.github.hipstermin.idem.common.identity.SubjectScheme;
import java.util.Optional;

/**
 * 기관향 주체 식별자 스킴 SPI (S4, {@code docs/generalization-plan.md} §2.3).
 *
 * <p>Service Profile {@code identity.subjectScheme} 마다 구현이 하나 있다. {@link #resolve} 가 empty 를 돌려주면
 * "이 사용자는 이 스킴의 식별자를 갖지 않는다" 는 뜻이고 Handoff 는 GUEST 가 된다 — 종전 "DI 없음 → GUEST" 와 같은 의미.
 * registry 장애 등 일시 오류는 {@link io.github.hipstermin.idem.common.error.PlatformException}
 * ({@code IDO_QIM_UNREACHABLE}) 으로 던져 GUEST 와 구분한다(F4.6 원칙).
 *
 * <p>에디션 플러그인은 이 인터페이스의 빈을 추가해 스킴을 확장한다. 스킴 코드가 겹치면 기동을 거부한다.
 */
public interface SubjectIdentifierScheme {

    SubjectScheme scheme();

    Optional<String> resolve(SubjectResolutionContext ctx);
}
