package io.github.hipstermin.idem.hub.broker;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ido — OIDC 브로커 Authorization URL 발급 컨트롤러 (문서 §6)
 *
 * <p>React SPA(onepass-fe)가 간편인증 버튼 클릭 시 이 엔드포인트를 호출.
 * {@code ido.broker.mode} 설정에 따라 두 가지 모드로 동작:
 *
 * <ul>
 *   <li>{@code qsign}    : 기존 모드 — ido → q-sign으로 URL 발급 위임</li>
 *   <li>{@code keycloak} : 신규 모드 — ido가 직접 Keycloak Authorization URL 생성</li>
 * </ul>
 *
 * <p>엔드포인트:
 * <ul>
 *   <li>GET /api/v1/broker/{provider}/authorize  — provider = kakao | naver 등</li>
 * </ul>
 * (종전 카카오 전용 별칭 {@code /kakao/authorize} 는 S5b 에서 제거 — 같은 경로가 {@code {provider}} 매핑으로 처리된다)
 *
 * <p>프론트엔드 호출 예시:
 * <pre>
 *   window.location.href = '/api/v1/broker/kakao/authorize?returnUrl=http://localhost:8084/entry'
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/broker")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerService brokerService;

    /**
     * OIDC 브로커 로그인 시작 — provider 동적 라우팅
     *
     * <p>React에서 window.location.href 로 호출 시 302 리다이렉트.
     * 브로커 모드(qsign/keycloak)는 BrokerService 내부에서 분기.
     *
     * @param provider       인증 수단 식별자 (kakao / naver 등)
     * @param returnUrl      인증 완료 후 돌아올 기관 URL
     * @param requestedLevel 요청 인증 수준 (기본 L1)
     * @param correlationId  흐름 추적 ID (optional, 없으면 자동 생성)
     */
    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(
            @PathVariable String provider,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(defaultValue = "L1") String requestedLevel) {

        String cid = (correlationId != null && !correlationId.isBlank())
                ? correlationId : CorrelationIdHolder.get();
        CorrelationIdHolder.set(cid);

        log.info("[BrokerController] 인증 시작: provider={} correlationId={} returnUrl={}",
                provider, cid, returnUrl);

        String authUrl = brokerService.buildAuthorizationUrl(provider, cid, returnUrl, requestedLevel);

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(authUrl));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }
}
