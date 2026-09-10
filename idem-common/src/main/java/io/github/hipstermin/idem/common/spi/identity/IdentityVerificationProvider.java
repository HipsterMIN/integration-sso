package io.github.hipstermin.idem.common.spi.identity;

import io.github.hipstermin.idem.common.domain.AuthResult;
import java.util.Optional;

/**
 * 본인인증(Identity Verification) 제공자 SPI.
 *
 * <p>코어는 "누가 사람을 인증하는가"를 이 인터페이스로만 안다. NICE 휴대폰 인증, OACX 간편서명,
 * AnyID, 향후 토스·해외 IdV 등 벤더 구현은 플러그인 모듈이 이 인터페이스의 빈을 등록해서 붙는다.
 * (docs/vendor-plugin-plan.md §2.1)
 *
 * <p>계약:
 * <ul>
 *   <li>{@link #code()} 는 대문자·밑줄 식별자(예: {@code NICE_PHONE}, {@code MOCK})이며 기존
 *       provider_code 체계와 같은 네임스페이스를 쓴다.</li>
 *   <li>{@link #initiate(VerificationRequest)} 는 인증 시작에 필요한 리다이렉트 URL 또는 위젯 파라미터를 돌려준다.</li>
 *   <li>{@link #complete(VerificationCallback)} 는 벤더 콜백을 검증하고 {@link VerifiedIdentity} 표준 결과로 정규화한다.
 *       실패 시 {@link IdentityVerificationException} 을 던진다.</li>
 *   <li>Spring 에 의존하지 않는다. 구현체가 Spring 빈이어도 코어는 {@link IdentityProviderRegistry} 를 통해서만 접근한다.</li>
 * </ul>
 */
public interface IdentityVerificationProvider {

    /** 제공자 코드 (대문자, 예: NICE_PHONE / MOCK). */
    String code();

    /** 이 제공자가 보증하는 인증 등급. */
    AuthResult.AuthLevel level();

    /** 인증 시작 — 리다이렉트 URL 또는 위젯 파라미터를 돌려준다. */
    VerificationStart initiate(VerificationRequest request);

    /** 인증 완료 — 벤더 콜백을 검증하고 표준 결과로 정규화한다. */
    VerifiedIdentity complete(VerificationCallback callback);

    /** FE 위젯이 필요한 제공자만 구현한다 (예: 간편인증 JS 위젯). */
    default Optional<AuthWidgetDescriptor> widget() {
        return Optional.empty();
    }
}
