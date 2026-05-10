package kr.go.smes.ido.auth.client;

import kr.go.smes.ido.auth.config.AuthProperties;
import kr.go.smes.ido.auth.config.AuthWebClientConfig;
import kr.go.smes.ido.auth.dto.nice.NiceResultApiResponse;
import kr.go.smes.ido.auth.dto.nice.NiceTokenApiResponse;
import kr.go.smes.ido.auth.dto.nice.NiceUrlApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * NICE IDO 통합인증 서버 API 클라이언트
 *
 * <p>NICE 휴대폰 본인인증 3단계 API를 WebClient로 호출:
 * <ol>
 *   <li>{@link #fetchAccessToken(String)} — Access Token 발급</li>
 *   <li>{@link #requestAuthUrl(String, String, String)} — 표준창 URL 발급</li>
 *   <li>{@link #requestAuthResult(String, String, String, String)} — 인증 결과 조회</li>
 * </ol>
 *
 * <p><b>NICE IDO API Base URL:</b> {@code https://auth.niceid.co.kr}
 *
 * <p><b>API 경로:</b>
 * <ul>
 *   <li>Token: {@code POST /ido/intc/v1.0/auth/token}</li>
 *   <li>URL:   {@code POST /ido/intc/v1.0/auth/url}</li>
 *   <li>Result:{@code POST /ido/intc/v1.0/auth/result}</li>
 * </ul>
 *
 * <p><b>인증 방식:</b>
 * <ul>
 *   <li>Token 발급: Basic Auth ({@code clientId:clientSecret} Base64 인코딩)</li>
 *   <li>URL/Result: Bearer Token ({@code accessToken})</li>
 * </ul>
 *
 * <p><b>에러 처리 전략:</b>
 * NICE 서버가 HTTP 4xx/5xx를 반환하면 {@link IllegalStateException}으로 래핑하여 던짐.
 * 호출 측({@code NiceAuthService})에서 try-catch로 처리하여 FE 응답 코드 변환.
 *
 * @see kr.go.smes.ido.auth.service.NiceAuthService
 * @see kr.go.smes.ido.auth.config.AuthWebClientConfig
 */
@Slf4j
@Component
public class NiceApiClient {

    private static final String TOKEN_PATH = "/ido/intc/v1.0/auth/token";
    private static final String URL_PATH = "/ido/intc/v1.0/auth/url";
    private static final String RESULT_PATH = "/ido/intc/v1.0/auth/result";

    private final WebClient client;
    private final String clientId;
    private final String clientSecret;

    public NiceApiClient(
            @Qualifier(AuthWebClientConfig.NICE_WEB_CLIENT) WebClient client,
            AuthProperties props) {
        this.client = client;
        this.clientId = props.nice().clientId();
        this.clientSecret = props.nice().clientSecret();
    }

    /**
     * NICE Access Token 발급
     *
     * <p>NICE API를 처음 호출하거나 토큰이 만료됐을 때 사용.
     * clientId:clientSecret을 Basic Auth 형식으로 인코딩하여 전달.
     *
     * <p><b>NICE API 스펙:</b>
     * <ul>
     *   <li>Method: POST</li>
     *   <li>Path: /ido/intc/v1.0/auth/token</li>
     *   <li>Authorization: Basic {Base64(clientId:clientSecret)}</li>
     *   <li>Body: {grant_type: "client_credentials", request_no: "{requestNo}"}</li>
     * </ul>
     *
     * <p><b>응답 결과 코드:</b>
     * <ul>
     *   <li>"0000" — 성공, accessToken/ticket/iterators/expiresIn 포함</li>
     *   <li>그 외 — 실패, resultMessage에 상세 내용</li>
     * </ul>
     *
     * @param requestNo 요청 번호 (ido 내부 생성, REQ_yyyyMMddHHmmss + 12자리 UUID)
     * @return NICE Token 발급 응답 DTO
     * @throws IllegalStateException NICE 서버 HTTP 오류
     */
    public NiceTokenApiResponse fetchAccessToken(String requestNo) {
        // clientId와 clientSecret은 민감 정보 — 로그에 노출하지 않음
        String basicAuth = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "request_no", requestNo
        );

        log.info("[NICE] Access Token 발급 요청: requestNo={}", requestNo);

        return client.post()
                .uri(TOKEN_PATH)
                .header("Authorization", "Basic " + basicAuth)
                .bodyValue(body)
                .exchangeToMono(resp -> parseResponse(resp, NiceTokenApiResponse.class, "auth/token"))
                .block();
    }

    /**
     * NICE 표준창 인증 URL 발급
     *
     * <p>사용자가 본인인증을 진행할 NICE 표준창 URL을 발급한다.
     * FE는 이 URL로 팝업을 열어 사용자 인증을 진행.
     *
     * <p><b>NICE API 스펙:</b>
     * <ul>
     *   <li>Method: POST</li>
     *   <li>Path: /ido/intc/v1.0/auth/url</li>
     *   <li>Authorization: Bearer {accessToken}</li>
     *   <li>Body:
     *     <ul>
     *       <li>request_no: 요청 번호</li>
     *       <li>return_url: 인증 완료 후 리다이렉트 URL</li>
     *       <li>svc_types: ["M"] (M: 휴대폰 인증)</li>
     *       <li>method_type: "GET"</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param accessToken NICE Access Token
     * @param requestNo   요청 번호
     * @param returnUrl   인증 완료 후 리다이렉트 URL
     * @return NICE URL 발급 응답 DTO (authUrl, transactionId, requestNo 포함)
     * @throws IllegalStateException NICE 서버 HTTP 오류
     */
    public NiceUrlApiResponse requestAuthUrl(String accessToken, String requestNo, String returnUrl) {
        Map<String, Object> body = Map.of(
                "request_no", requestNo,
                "return_url", returnUrl,
                "svc_types", new String[]{"M"},
                "method_type", "GET"
        );

        log.info("[NICE] 인증 URL 발급 요청: requestNo={}, returnUrl={}", requestNo, returnUrl);

        return client.post()
                .uri(URL_PATH)
                .header("Authorization", "Bearer " + accessToken)
                .bodyValue(body)
                .exchangeToMono(resp -> parseResponse(resp, NiceUrlApiResponse.class, "auth/url"))
                .block();
    }

    /**
     * NICE 인증 결과 조회
     *
     * <p>NICE 팝업에서 사용자 인증 완료 후, web_transaction_id를 이용해 암호화된 결과를 조회.
     * 결과는 AES-GCM으로 암호화되어 있으며, 복호화는 {@code NiceAuthService}에서 수행.
     *
     * <p><b>NICE API 스펙:</b>
     * <ul>
     *   <li>Method: POST</li>
     *   <li>Path: /ido/intc/v1.0/auth/result</li>
     *   <li>Authorization: Bearer {accessToken}</li>
     *   <li>Body:
     *     <ul>
     *       <li>web_transaction_id: 팝업 완료 후 postMessage로 수신한 ID</li>
     *       <li>transaction_id: URL 발급 시 NICE 서버가 발급한 ID (세션 저장소에서 조회)</li>
     *       <li>request_no: ido가 생성한 요청 번호</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param accessToken      NICE Access Token
     * @param webTransactionId 팝업 완료 후 FE에서 전달한 web_transaction_id
     * @param transactionId    URL 발급 시 세션에 저장한 NICE transaction_id
     * @param requestNo        ido 생성 요청 번호
     * @return NICE 결과 응답 DTO (encData, integrityValue 포함 — 복호화 전)
     * @throws IllegalStateException NICE 서버 HTTP 오류
     */
    public NiceResultApiResponse requestAuthResult(String accessToken,
                                                    String webTransactionId,
                                                    String transactionId,
                                                    String requestNo) {
        Map<String, String> body = Map.of(
                "web_transaction_id", webTransactionId,
                "transaction_id", transactionId,
                "request_no", requestNo
        );

        log.info("[NICE] 인증 결과 조회: webTransactionId={}, requestNo={}", webTransactionId, requestNo);

        return client.post()
                .uri(RESULT_PATH)
                .header("Authorization", "Bearer " + accessToken)
                .bodyValue(body)
                .exchangeToMono(resp -> parseResponse(resp, NiceResultApiResponse.class, "auth/result"))
                .block();
    }

    /**
     * WebClient 응답 처리 공통 메서드
     *
     * <p>HTTP 에러 상태(4xx/5xx) 시 응답 바디를 읽어 로그로 남기고
     * {@link IllegalStateException}으로 래핑하여 던짐.
     * 정상 응답 시 지정된 타입으로 역직렬화.
     *
     * @param resp  WebClient 응답
     * @param type  역직렬화 타입
     * @param label 로그용 API 이름 (예: "auth/token")
     * @return 역직렬화된 응답 Mono
     */
    private <T> Mono<T> parseResponse(ClientResponse resp, Class<T> type, String label) {
        if (resp.statusCode().isError()) {
            return resp.bodyToMono(String.class)
                    .defaultIfEmpty("(빈 응답)")
                    .flatMap(errorBody -> {
                        log.error("[NICE][{}] 에러 응답: status={}, body={}", label, resp.statusCode(), errorBody);
                        return Mono.error(new IllegalStateException(
                                "[NICE][" + label + "] HTTP " + resp.statusCode() + ": " + errorBody));
                    });
        }
        return resp.bodyToMono(type);
    }
}
