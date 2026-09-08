package io.github.hipstermin.idem.plugin.niceoacx;

/**
 * NICE 휴대폰 본인인증 API 포트 — 플러그인 내부 계약.
 *
 * <p>P2 본작업에서 idem-hub 의 {@code NiceAuthService}(URL 발급·결과 조회·토큰 캐시·분산락)와
 * {@code NiceApiClient}(WebClient + Resilience4j) 를 이 인터페이스 뒤로 옮긴다. 골격 단계에서는 구현이 없고,
 * 테스트는 가짜 구현으로 SPI 매핑만 검증한다.
 */
public interface NicePhoneGateway {

    /** 인증 URL 발급. @return requestNo + authUrl */
    Started start(String returnUrl);

    /** 결과 조회. web_transaction_id 와 requestNo 로 복호화된 결과를 돌려준다. */
    Result result(String webTransactionId, String requestNo);

    record Started(String requestNo, String authUrl) {}

    record Result(String di, String name, String birthdate, String gender, String nationalInfo,
                  String mobileNo, String mobileCo) {}
}
