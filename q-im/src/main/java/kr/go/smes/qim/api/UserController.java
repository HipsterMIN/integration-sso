package kr.go.smes.qim.api;

import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.qim.domain.QimUser;
import kr.go.smes.qim.application.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Q-IM 사용자 조회 API
 * 설계서 17.1절 참조
 * - GET /api/v1/users/{qimUserId}
 * - GET /api/v1/users/by-hash?identifierHash=...
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{qimUserId}")
    public ResponseEntity<QimUser> getUser(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable String qimUserId) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        QimUser user = userService.findById(qimUserId, cid);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/by-hash")
    public ResponseEntity<QimUser> getUserByIdentifierHash(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam String identifierHash) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        return userService.findByIdentifierHash(identifierHash, cid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
