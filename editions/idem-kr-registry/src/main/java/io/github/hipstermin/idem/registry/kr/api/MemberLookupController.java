package io.github.hipstermin.idem.registry.kr.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.registry.crypto.CiCryptoService;
import io.github.hipstermin.idem.registry.crypto.PiiMaskingService;
import io.github.hipstermin.idem.registry.identity.DiGenerationService;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.UserProfileJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Q-IM 내부 Member Lookup API (IdO → Q-IM)
 *
 * <p><b>엔드포인트</b>:
 * <ul>
 *   <li>POST /api/v1/internal/member/lookup-by-ci — 암호화된 CI 기반 조회</li>
 *   <li>GET  /api/v1/internal/member/lookup-by-hash — identifierHash 기반 조회</li>
 * </ul>
 *
 * <p><b>보안</b>: X-Internal-Api-Key 헤더 검증 (내부망 전용)
 * CI 복호화는 Q-IM 내부에서만 수행 — CI 평문은 외부로 절대 노출 금지
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/member")
@RequiredArgsConstructor
public class MemberLookupController {

    private final QimUserJpaRepository userRepository;
    private final CiCryptoService      ciCryptoService;
    private final PiiMaskingService    piiMaskingService;
    private final DiGenerationService  diGenerationService;
    private final ObjectMapper         objectMapper;

    /**
     * 암호화된 CI 기반 회원 조회
     * POST /api/v1/internal/member/lookup-by-ci
     * body: { "encryptedCi": "v1.xxx.yyy", "agencyCode": "AGENCY_001" }
     */
    @PostMapping("/lookup-by-ci")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> lookupByCi(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId,
            @RequestHeader(value = "X-Agency-Code",       required = false) String agencyCode,
            @RequestBody Map<String, String> body) {

        String encryptedCi = body.get("encryptedCi");
        String reqAgencyCode = body.getOrDefault("agencyCode", agencyCode);

        if (encryptedCi == null || encryptedCi.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MISSING_ENCRYPTED_CI",
                    "message", "encryptedCi는 필수입니다"));
        }

        log.info("[MemberLookup] CI 기반 조회 요청: agencyCode={} cid={}", reqAgencyCode, correlationId);

        // 1. CI 복호화 (Q-IM 내부에서만)
        String rawCi;
        try {
            rawCi = ciCryptoService.decrypt(encryptedCi);
        } catch (Exception e) {
            log.warn("[MemberLookup] CI 복호화 실패: cid={} err={}", correlationId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CI_DECRYPT_FAILED",
                    "message", "CI 복호화에 실패했습니다"));
        }

        // 2. CI → identifierHash 계산 (SHA-256)
        String identifierHash = sha256(rawCi);

        // 3. identifierHash로 사용자 조회
        return userRepository.findByIdentifierHash(identifierHash)
                .map(user -> {
                    Map<String, Object> result = buildLookupResult(user, reqAgencyCode);
                    log.info("[MemberLookup] 조회 성공: qimUserId={} agencyCode={}",
                            user.getQimUserId(), reqAgencyCode);
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> {
                    log.info("[MemberLookup] 사용자 없음: cid={}", correlationId);
                    return ResponseEntity.notFound().<Map<String, Object>>build();
                });
    }

    /**
     * identifierHash 기반 회원 조회 (직접 해시 제공)
     * GET /api/v1/internal/member/lookup-by-hash?identifierHash=...
     */
    @GetMapping("/lookup-by-hash")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> lookupByHash(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Agency-Code",    required = false) String agencyCode,
            @RequestParam String identifierHash) {

        return userRepository.findByIdentifierHash(identifierHash)
                .map(user -> ResponseEntity.ok(buildLookupResult(user, agencyCode)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildLookupResult(QimUserJpaEntity user, String agencyCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("qimUserId", user.getQimUserId());
        result.put("status",    user.getStatus());
        result.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);

        UserProfileJpaEntity profile = user.getProfile();
        if (profile != null) {
            result.put("nameMasked",     profile.getNameMasked());
            result.put("mobileMasked",   profile.getMobileMasked());
            result.put("nationalityType",profile.getNationalityType());
            result.put("birthYear",      profile.getBirthYear());
            result.put("gender",         profile.getGender());

            // 기관별 DI 조회 (di_map에서)
            if (agencyCode != null && !agencyCode.isBlank()) {
                Map<String, String> diMap = diGenerationService.parseDiMap(profile.getDiMap());
                String di = diMap.get(agencyCode);
                if (di != null) {
                    result.put("di", di);
                }
            }
        }

        return result;
    }

    /**
     * SHA-256(input) → lowercase hex 문자열 (Sprint γ-1 / F3.1 통일).
     *
     * <p><b>F3.1 결함 수정</b>: 과거 이 메서드는 Base64URL 인코딩(43자)을 반환했으나,
     * 플랫폼 내 다른 모든 identifierHash 생성 지점은 hex(64자) 를 사용하므로
     * {@code lookup-by-ci} 경로에서 회원 조회가 영구 실패했다.
     * (참조: ido KeycloakOidcService / NonOidcAuthService / AesSharedKeyDecryptor,
     *  q-sign KeycloakCallbackService / AuthServiceImpl,
     *  q-im UserController.computeSha256Hex — 모두 hex)
     *
     * <p>본 수정으로 lookup-by-ci 와 lookup-by-hash 가 동일한 hex 64자 표현을 사용하며,
     * DB 의 {@code identifier_hash} 컬럼과 정확히 매칭된다.
     */
    private String sha256(String input) {
        return CryptoProviders.current().sha256Hex(input);
    }
}
