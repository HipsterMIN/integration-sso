package io.github.hipstermin.idem.plugin.niceoacx;

/**
 * NICE 휴대폰 본인인증 API 포트 — 플러그인 내부 계약 (S5a 부터 {@link NicePhoneService} 가 구현).
 *
 * <p>실패는 {@link io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException} 으로 던진다.
 * reasonCode 는 종전 FE 계약의 결과 코드("4000" 세션 없음 · "5001" URL 발급 실패 · "5002" 결과 조회 실패 ·
 * "5003" 무결성 검증 실패 · "5000" 내부 오류)를 그대로 쓴다 — 레거시 프록시가 그대로 돌려줄 수 있게.
 */
public interface NicePhoneGateway {

    /** 인증 URL 발급. */
    Started start(String returnUrl);

    /** 결과 조회 + 복호화. web_transaction_id 와 requestNo 로 복호화된 결과를 돌려준다. */
    Result result(String webTransactionId, String requestNo);

    record Started(String requestNo, String authUrl) {}

    /** {@code ci} 는 registry 등록에만 쓰이고 밖(FE)으로는 나가지 않는다 (Q3=B). */
    record Result(String ci, String di, String name, String birthdate, String gender, String nationalInfo,
                  String mobileNo, String mobileCo) {}
}
