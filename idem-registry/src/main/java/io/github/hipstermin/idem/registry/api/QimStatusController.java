package io.github.hipstermin.idem.registry.api;

import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * IdO → Q-IM 사용자 상태 조회 전용 컨트롤러
 *
 * <p>IdO {@code QimClientImpl} 이 호출하는 경로와 정확히 일치:
 * <pre>GET /api/v1/users/{qimUserId}</pre>
 *
 * <p>응답 형식: {@code { "status": "ACTIVE" | "SUSPENDED" | "WITHDRAWN" }}
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class QimStatusController {

    private final QimUserJpaRepository userRepository;

    /**
     * 사용자 상태 조회
     * GET /api/v1/users/{qimUserId}
     *
     * <p>IdO PolicyEngineImpl → QimClientImpl → 이 엔드포인트 호출
     */
    @GetMapping("/{qimUserId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getUserStatus(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        log.debug("[QimStatus] 상태 조회: qimUserId={} correlationId={}", qimUserId, correlationId);

        return userRepository.findById(qimUserId)
                .map(u -> ResponseEntity.ok(Map.<String, Object>of(
                        "qimUserId", u.getQimUserId(),
                        "status", u.getStatus(),
                        "updatedAt", u.getUpdatedAt().toString()
                )))
                .orElse(ResponseEntity.ok(Map.of(
                        "qimUserId", qimUserId,
                        "status", "UNKNOWN"
                )));
    }
}
