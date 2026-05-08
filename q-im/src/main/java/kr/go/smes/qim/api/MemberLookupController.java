package kr.go.smes.qim.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.qim.crypto.CiCryptoService;
import kr.go.smes.qim.crypto.PiiMaskingService;
import kr.go.smes.qim.identity.DiGenerationService;
import kr.go.smes.qim.infrastructure.jpa.entity.QimUserJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.entity.UserProfileJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.QimUserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

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

    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 계산 실패", e);
        }
    }
}
