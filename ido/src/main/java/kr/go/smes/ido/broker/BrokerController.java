package kr.go.smes.ido.broker;

import kr.go.smes.common.util.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * ido — OIDC 브로커 Authorization URL 발급 컨트롤러
 *
 * <p>React SPA(onepass-fe) 가 "카카오 간편인증" 버튼 클릭 시 이 엔드포인트를 호출.
 * ido 는 q-sign 에 Authorization URL 발급을 요청하고 브라우저를 카카오로 리다이렉트.
 *
 * <p>엔드포인트:
 * <ul>
 *   <li>GET /api/v1/broker/kakao/authorize  — 카카오 로그인 URL 로 302 리다이렉트</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/broker")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerService brokerService;

    /**
     * 카카오 OIDC 로그인 시작
     *
     * <p>React 에서 window.location.href = '/api/v1/broker/kakao/authorize?returnUrl=...'
     * 로 호출하면 카카오 로그인 페이지로 302 리다이렉트.
     *
     * @param returnUrl     인증 완료 후 돌아올 기관 URL (화이트리스트 검증됨)
     * @param requestedLevel 요청 인증 수준 (기본 L1)
     */
    @GetMapping("/kakao/authorize")
    public ResponseEntity<Void> kakaoAuthorize(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(defaultValue = "L1") String requestedLevel) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.get();
        CorrelationIdHolder.set(cid);

        log.info("[BrokerController] 카카오 인증 시작: correlationId={} returnUrl={}",
                cid, returnUrl);

        String kakaoAuthUrl = brokerService.buildKakaoAuthorizationUrl(cid, returnUrl, requestedLevel);

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(kakaoAuthUrl));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }
}
