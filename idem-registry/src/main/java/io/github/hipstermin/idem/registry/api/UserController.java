package io.github.hipstermin.idem.registry.api;

import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.registry.api.dto.*;
import io.github.hipstermin.idem.registry.identity.DiGenerationService;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.AuthMeanMappingJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.UserProfileJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import io.github.hipstermin.idem.registry.outbox.OutboxService;
import io.github.hipstermin.idem.registry.user.UserRegistrationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Q-IM 사용자 내부 API 컨트롤러
 * — IdO 가 직접 호출하는 내부 전용 엔드포인트
 *
 * <p>보안: X-Internal-Api-Key 헤더 검증 (InternalApiKeyInterceptor)
 *
 * <p>v2.0 추가 (SSO 소셜 계정 지원):
 * <ul>
 *   <li>{@link #findBySocialSub} — Keycloak sub + providerCode 복합 키 기반 소셜 계정 조회</li>
 *   <li>{@link #registerSocialUser} — 소셜 신규 사용자 Q-IM 등록 (identifierHash + providerCode 복합 저장)</li>
 * </ul>
 *
 * <p><b>소셜 계정 식별 전략</b>:
 * {@code auth_mean_mapping} 테이블에 {@code identifierHash}(= SHA-256(sub))와
 * {@code providerCode}가 함께 저장된다.
 * 두 필드의 복합 조건으로 조회/upsert를 수행하여 서로 다른 소셜 제공자의
 * 동일 identifierHash 충돌을 방지한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRegistrationService userRegistrationService;
    private final QimUserJpaRepository    userRepository;
    private final DiGenerationService     diGenerationService;
    private final OutboxService           outboxService;

    // ── 기존 CRUD API ─────────────────────────────────────────────────────────

    /**
     * 사용자 등록 또는 기존 사용자 반환 (Upsert)
     * POST /api/v1/internal/users
     */
    @PostMapping
    public ResponseEntity<UserResponse> registerOrGet(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody UserRegisterRequest request) {

        log.info("[UserCtrl] 사용자 등록/조회 요청: correlationId={}", correlationId);
        UserResponse response = userRegistrationService.registerOrGet(request);
        HttpStatus status = response.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 사용자 조회 by qimUserId
     * GET /api/v1/internal/users/{qimUserId}
     */
    @GetMapping("/{qimUserId}")
    @Transactional(readOnly = true)
    public ResponseEntity<UserResponse> getUser(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        return userRepository.findById(qimUserId)
                .map(u -> {
                    String nameMasked   = u.getProfile() != null ? u.getProfile().getNameMasked() : null;
                    String mobileMasked = u.getProfile() != null ? u.getProfile().getMobileMasked() : null;
                    String nationality  = u.getProfile() != null ? u.getProfile().getNationalityType() : null;
                    Short  birthYear    = u.getProfile() != null ? u.getProfile().getBirthYear() : null;
                    String gender       = u.getProfile() != null ? u.getProfile().getGender() : null;
                    return ResponseEntity.ok(UserResponse.builder()
                            .qimUserId(u.getQimUserId())
                            .status(u.getStatus())
                            .nameMasked(nameMasked)
                            .mobileMasked(mobileMasked)
                            .nationalityType(nationality)
                            .birthYear(birthYear)
                            .gender(gender)
                            .isNew(false)
                            .createdAt(u.getCreatedAt())
                            .updatedAt(u.getUpdatedAt())
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * identifierHash 기반 사용자 조회
     * GET /api/v1/internal/users/by-hash?identifierHash=
     */
    @GetMapping("/by-hash")
    @Transactional(readOnly = true)
    public ResponseEntity<UserResponse> getUserByHash(
            @RequestParam String identifierHash,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        return userRepository.findByIdentifierHash(identifierHash)
                .map(u -> ResponseEntity.ok(UserResponse.builder()
                        .qimUserId(u.getQimUserId())
                        .status(u.getStatus())
                        .isNew(false)
                        .createdAt(u.getCreatedAt())
                        .updatedAt(u.getUpdatedAt())
                        .build()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 사용자 상태 변경
     * PATCH /api/v1/internal/users/{qimUserId}/status
     */
    @PatchMapping("/{qimUserId}/status")
    public ResponseEntity<Map<String, String>> updateStatus(
            @PathVariable String qimUserId,
            @RequestBody UserStatusUpdateRequest request) {

        userRegistrationService.updateStatus(
                qimUserId, request.getNewStatus(), request.getChangedBy(), request.getReason());
        return ResponseEntity.ok(Map.of("qimUserId", qimUserId, "status", request.getNewStatus()));
    }

    /**
     * 사용자 탈퇴 (PII 즉시 삭제)
     * DELETE /api/v1/internal/users/{qimUserId}
     */
    @DeleteMapping("/{qimUserId}")
    public ResponseEntity<Void> withdraw(
            @PathVariable String qimUserId,
            @RequestParam(defaultValue = "USER_REQUEST") String reason) {

        userRegistrationService.withdraw(qimUserId, reason);
        return ResponseEntity.noContent().build();
    }

    /**
     * 기관별 DI 조회/생성
     * GET /api/v1/internal/users/{qimUserId}/di?agencyCode=
     */
    @GetMapping("/{qimUserId}/di")
    @Transactional
    public ResponseEntity<DiResponse> getDi(
            @PathVariable String qimUserId,
            @RequestParam String agencyCode) {

        return userRepository.findById(qimUserId)
                .map(u -> {
                    UserProfileJpaEntity profile = u.getProfile();
                    String currentDiMap = profile != null ? profile.getDiMap() : null;
                    boolean alreadyExists = diGenerationService.parseDiMap(currentDiMap).containsKey(agencyCode);

                    String di = diGenerationService.getOrCreateDi(qimUserId, agencyCode, currentDiMap);

                    if (!alreadyExists && profile != null) {
                        String updatedDiMap = diGenerationService.addDiToMap(currentDiMap, agencyCode, di);
                        profile.setDiMap(updatedDiMap);
                    }

                    return ResponseEntity.ok(DiResponse.builder()
                            .qimUserId(qimUserId)
                            .agencyCode(agencyCode)
                            .di(di)
                            .isNew(!alreadyExists)
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 사용자 상태 조회 (IdO 캐시 미스 시 직접 조회)
     * GET /api/v1/internal/users/{qimUserId}/status-check
     */
    @GetMapping("/{qimUserId}/status-check")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, String>> getUserStatus(@PathVariable String qimUserId) {
        return userRepository.findById(qimUserId)
                .map(u -> ResponseEntity.ok(Map.of("status", u.getStatus())))
                .orElse(ResponseEntity.ok(Map.of("status", "UNKNOWN")));
    }

    // ── 소셜 계정 SSO API ─────────────────────────────────────────────────────

    /**
     * 소셜 계정 조회 (Keycloak SSO 콜백 전용)
     * POST /api/v1/internal/users/find-by-social-sub
     *
     * <p>IdO {@link io.github.hipstermin.idem.hub.infrastructure.QimClientImpl#findBySocialSub}가 호출.
     * Keycloak 소셜 로그인 후 이전에 등록된 소셜 계정인지 확인한다.
     *
     * <p>조회 키: identifierHash(= SHA-256(sub)) + providerCode 복합 조건
     * <ul>
     *   <li>매칭 → 200 + qimUserId 반환</li>
     *   <li>없음 → 404 (IdO가 register-social 호출로 이어감)</li>
     * </ul>
     *
     * <p>sub 원문은 보관하지 않는다 (PII 비보관 원칙).
     * IdO가 SHA-256(sub)를 identifierHash로 이미 계산하여 전달한다.
     *
     * @param body {@code { "sub": "...", "providerCode": "KAKAO_OIDC" }}
     *             sub: Keycloak JWT sub 클레임 원문 (Q-IM이 SHA-256 해시하여 조회)
     */
    @PostMapping("/find-by-social-sub")
    @Transactional(readOnly = true)
    public ResponseEntity<UserResponse> findBySocialSub(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",  required = false) String correlationId,
            @RequestBody Map<String, String> body) {

        String sub          = body.get("sub");
        String providerCode = body.get("providerCode");

        if (sub == null || sub.isBlank() || providerCode == null || providerCode.isBlank()) {
            log.warn("[UserCtrl][findBySocialSub] 필수 파라미터 누락: providerCode={} correlationId={}",
                    providerCode, correlationId);
            return ResponseEntity.badRequest().build();
        }

        // sub 원문 → SHA-256 해시 (PII 비보관)
        String identifierHash = computeSha256Hex(sub);

        log.debug("[UserCtrl][findBySocialSub] 소셜 계정 조회: providerCode={} correlationId={}",
                providerCode, correlationId);

        return userRepository.findByIdentifierHashAndProviderCode(identifierHash, providerCode)
                .map(u -> {
                    log.info("[UserCtrl][findBySocialSub] 기존 소셜 사용자 반환: qimUserId={} providerCode={} correlationId={}",
                            u.getQimUserId(), providerCode, correlationId);
                    return ResponseEntity.ok(UserResponse.builder()
                            .qimUserId(u.getQimUserId())
                            .status(u.getStatus())
                            .isNew(false)
                            .createdAt(u.getCreatedAt())
                            .updatedAt(u.getUpdatedAt())
                            .build());
                })
                .orElseGet(() -> {
                    log.debug("[UserCtrl][findBySocialSub] 소셜 미등록 사용자: providerCode={} correlationId={}",
                            providerCode, correlationId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * 소셜 신규 사용자 등록 (Keycloak SSO 콜백 전용)
     * POST /api/v1/internal/users/register-social
     *
     * <p>IdO {@link io.github.hipstermin.idem.hub.infrastructure.QimClientImpl#registerSocialUser}가 호출.
     * {@code find-by-social-sub} 에서 404 반환 후 신규 등록 단계에서 사용한다.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>identifierHash + providerCode 복합 키로 재조회 (동시 요청 경합 방어)</li>
     *   <li>이미 있으면 기존 qimUserId 반환 (isNew=false)</li>
     *   <li>없으면 qimUserId 신규 발급 → qim_user + auth_mean_mapping 생성</li>
     *   <li>Kafka Outbox SOCIAL_USER_REGISTERED 이벤트 발행</li>
     * </ol>
     *
     * <p>설계 원칙:
     * <ul>
     *   <li>identifierHash + providerCode 복합 키: find-by-social-sub와 동일 기준으로 upsert</li>
     *   <li>CI / PII 없음: 소셜 전용 경로. 추후 본인인증 연동 시 CI 추가 가능</li>
     *   <li>identifierHash는 IdO가 계산하여 전달. 없으면 Q-IM이 sub로 재계산 (방어)</li>
     * </ul>
     *
     * @param body {@code { "sub": "...", "providerCode": "KAKAO_OIDC", "identifierHash": "..." }}
     */
    @PostMapping("/register-social")
    @Transactional
    public ResponseEntity<UserResponse> registerSocialUser(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",  required = false) String correlationId,
            @RequestBody Map<String, String> body) {

        String sub            = body.get("sub");
        String providerCode   = body.get("providerCode");
        String identifierHash = body.get("identifierHash");

        if (sub == null || sub.isBlank() || providerCode == null || providerCode.isBlank()) {
            log.warn("[UserCtrl][registerSocial] 필수 파라미터 누락: providerCode={} correlationId={}",
                    providerCode, correlationId);
            return ResponseEntity.badRequest().build();
        }

        // identifierHash가 없으면 Q-IM이 직접 SHA-256(sub) 계산 (방어 로직)
        String resolvedHash = (identifierHash != null && !identifierHash.isBlank())
                ? identifierHash
                : computeSha256Hex(sub);

        log.info("[UserCtrl][registerSocial] 소셜 신규 사용자 등록 요청: providerCode={} correlationId={}",
                providerCode, correlationId);

        // 1. 경합 방어: identifierHash + providerCode 복합 키로 재조회
        //    find-by-social-sub → register-social 사이에 동시 요청이 들어올 수 있음
        var existing = userRepository.findByIdentifierHashAndProviderCode(resolvedHash, providerCode);
        if (existing.isPresent()) {
            log.info("[UserCtrl][registerSocial] 경합 감지 — 기존 사용자 반환: qimUserId={} correlationId={}",
                    existing.get().getQimUserId(), correlationId);
            return ResponseEntity.ok(UserResponse.builder()
                    .qimUserId(existing.get().getQimUserId())
                    .status(existing.get().getStatus())
                    .isNew(false)
                    .createdAt(existing.get().getCreatedAt())
                    .updatedAt(existing.get().getUpdatedAt())
                    .build());
        }

        // 2. 신규 사용자 생성
        String  qimUserId = UuidV7.generate();
        Instant now       = Instant.now();

        // qim_user 엔티티 (CI/PII 없음 — 소셜 전용)
        QimUserJpaEntity userEntity = QimUserJpaEntity.builder()
                .qimUserId(qimUserId)
                .status("ACTIVE")
                .eventVersion(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // auth_mean_mapping: identifierHash + providerCode 복합 저장
        AuthMeanMappingJpaEntity mappingEntity = AuthMeanMappingJpaEntity.builder()
                .mappingId(UuidV7.generate())
                .user(userEntity)
                .providerCode(providerCode)
                .identifierHash(resolvedHash)
                .status("ACTIVE")
                .linkedAt(now)
                .build();

        userEntity.getAuthMeanMappings().add(mappingEntity);
        userRepository.save(userEntity);

        // 3. Kafka Outbox 이벤트 발행
        outboxService.publishInTx(new UserEvent(
                UserEvent.TYPE_UPDATED, "q-im",
                null, qimUserId, 1L,
                "ACTIVE", "SOCIAL_USER_REGISTERED", true));

        log.info("[UserCtrl][registerSocial] 소셜 신규 사용자 등록 완료: qimUserId={} providerCode={} correlationId={}",
                qimUserId, providerCode, correlationId);

        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.builder()
                .qimUserId(qimUserId)
                .status("ACTIVE")
                .isNew(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * SHA-256(input) → lowercase hex 문자열
     *
     * <p>소셜 로그인 sub 원문을 identifierHash로 변환할 때 사용.
     * PII 비보관 원칙: sub 원문은 이 메서드 호출 이후 참조 불가.
     */
    private static String computeSha256Hex(String input) {
        try {
            MessageDigest md   = MessageDigest.getInstance("SHA-256");
            byte[]        hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
