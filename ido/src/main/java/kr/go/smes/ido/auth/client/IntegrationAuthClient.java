package kr.go.smes.ido.auth.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import kr.go.smes.ido.auth.config.AuthWebClientConfig;
import kr.go.smes.ido.auth.dto.AuthCallbackRequest;
import kr.go.smes.ido.auth.dto.AuthCheckResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 통합인증 서버 WebClient 기반 HTTP 클라이언트
 *
 * <p>기업 간편인증 콜백 처리 시 통합인증 서버에 auth-check를 요청.
 * 기업인증 결과 데이터를 Base64 인코딩된 JSON으로 수신하여 반환.
 *
 * <p><b>사용 시나리오 (Q2=B — 기업인증 향후 FE 연동 대비):</b>
 * <pre>
 * 1. 간편인증창 → FE에 {txId, tokenId, ...} postMessage
 * 2. FE → POST /api/v1/auth/callback (AuthCallbackRequest 전달)
 * 3. ido (AuthService) → IntegrationAuthClient.sendAuthCheck()
 * 4. 통합인증 서버 → auth-check 결과 반환 (Base64 인코딩된 resultData)
 * 5. ido → Base64 디코딩 + AuthCallbackResponse 변환 → FE 반환
 * </pre>
 *
 * <p><b>설정:</b> {@code ido.auth.integration.base-url}, {@code ido.auth.integration.timeout-seconds}
 *
 * <p><b>Resilience4j (S9-T2):</b>
 * {@code integration-auth-client} CB+Retry — 통합인증 서버 장애 격리.
 * CB OPEN 시 fallback null 반환 → AuthService에서 5001 처리.
 *
 * @see kr.go.smes.ido.auth.service.AuthService
 */
@Slf4j
@Component
public class IntegrationAuthClient {

    /** 통합인증 서버 auth-check API 경로 */
    private static final String AUTH_CHECK_PATH = "/auth-check/v1";

    private final WebClient client;
    private final ObjectMapper objectMapper;

    public IntegrationAuthClient(
            @Qualifier(AuthWebClientConfig.INTEGRATION_AUTH_WEB_CLIENT) WebClient client,
            ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * Resilience4j CB+Retry Fallback — 통합인증 서버 장애 시
     *
     * <p>Circuit Breaker OPEN 상태 또는 재시도 소진 시 호출.
     * null 반환으로 호출 측(AuthService)에서 5001 처리.
     */
    public AuthCheckResponse sendAuthCheckFallback(AuthCallbackRequest request, Throwable t) {
        log.error("[IntegrationAuth][CB-FALLBACK] sendAuthCheck 실패 — txId={}, cause={}",
                request.getTxId(), t.getMessage());
        return null;
    }

    /**
     * 통합인증 서버에 기업인증 auth-check 요청
     *
     * <p>FE에서 전달받은 인증 콜백 데이터를 통합인증 서버로 그대로 전달하여
     * 기업인증 결과를 확인한다.
     *
     * <p><b>주의:</b> 통합인증 서버는 {@code resultData}를
     * JSON 직렬화 후 Base64 인코딩하여 반환함. 디코딩은 {@code AuthService}에서 처리.
     *
     * <p><b>에러 처리:</b>
     * <ul>
     *   <li>HTTP 4xx/5xx: 응답 바디 로그 후 {@link IllegalStateException} 던짐</li>
     *   <li>JSON 파싱 실패: 원시 응답 바디 로그 후 {@link IllegalStateException} 던짐</li>
     *   <li>null 반환: {@code AuthService}에서 5001(응답 없음) 처리</li>
     * </ul>
     *
     * @param request FE에서 수신한 기업인증 콜백 요청 (siteInfo, txId, tokenId 등)
     * @return 통합인증 서버 응답 (resultCode, resultData 포함)
     * @throws IllegalStateException 통합인증 서버 HTTP 오류 또는 JSON 파싱 실패
     */
    @CircuitBreaker(name = "integration-auth-client", fallbackMethod = "sendAuthCheckFallback")
    @Retry(name = "integration-auth-client")
    public AuthCheckResponse sendAuthCheck(AuthCallbackRequest request) {
        log.info("[IntegrationAuth] auth-check 요청: txId={}, tokenId={}",
                request.getTxId(), request.getTokenId());

        return client.post()
                .uri(AUTH_CHECK_PATH)
                .bodyValue(request)
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            log.info("[IntegrationAuth] auth-check 응답: status={}, contentType={}, bodyLen={}",
                                    resp.statusCode(),
                                    resp.headers().contentType().orElse(null),
                                    body.length());

                            if (resp.statusCode().isError()) {
                                log.error("[IntegrationAuth] auth-check 에러: status={}, body={}",
                                        resp.statusCode(), body);
                                return Mono.error(new IllegalStateException(
                                        "[IntegrationAuth] HTTP " + resp.statusCode() + ": " + body));
                            }

                            try {
                                return Mono.just(objectMapper.readValue(body, AuthCheckResponse.class));
                            } catch (JsonProcessingException e) {
                                log.error("[IntegrationAuth] JSON 파싱 실패. body={}", body, e);
                                return Mono.error(e);
                            }
                        }))
                .block();
    }
}

