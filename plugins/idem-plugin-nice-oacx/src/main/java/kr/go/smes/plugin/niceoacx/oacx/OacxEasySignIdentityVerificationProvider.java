package kr.go.smes.plugin.niceoacx.oacx;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.spi.identity.IdentityVerificationException;
import kr.go.smes.common.spi.identity.IdentityVerificationProvider;
import kr.go.smes.common.spi.identity.VerificationCallback;
import kr.go.smes.common.spi.identity.VerificationRequest;
import kr.go.smes.common.spi.identity.VerificationStart;
import kr.go.smes.common.spi.identity.VerifiedIdentity;

/**
 * OACX 간편서명 제공자 — 골격. 이 패키지({@code …niceoacx.oacx})는 vendor-libs 에 OACX SDK 가 있을 때만 컴파일된다
 * (build.gradle.kts 의 sourceSets exclude).
 *
 * <p>P2 본작업: idem-hub 의 {@code OacxClient}(OacxUtil.loadJSONInfo / getAccessInfo / decryptEasysignResult)와
 * {@code AuthService.getOacxAccessInfo / handleOacxEasysign} 을 여기로 옮기고, NICE 로부터 최신 SDK 를 받은 뒤
 * {@code initiate}(access-info 발급) / {@code complete}(easysign 결과 복호화)를 구현한다.
 * 그 전까지는 등록되어도 호출 시 명시적으로 실패한다.
 */
public class OacxEasySignIdentityVerificationProvider implements IdentityVerificationProvider {

    public static final String CODE = "OACX_EASYSIGN";

    @Override public String code() { return CODE; }

    @Override public AuthResult.AuthLevel level() { return AuthResult.AuthLevel.L2; }

    @Override
    public VerificationStart initiate(VerificationRequest request) {
        throw new IdentityVerificationException(CODE, "NOT_IMPLEMENTED", "OACX 간편서명은 P2 본작업(SDK 재수령) 후 구현");
    }

    @Override
    public VerifiedIdentity complete(VerificationCallback callback) {
        throw new IdentityVerificationException(CODE, "NOT_IMPLEMENTED", "OACX 간편서명은 P2 본작업(SDK 재수령) 후 구현");
    }
}
