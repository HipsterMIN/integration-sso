package kr.go.smes.qim.api;

import kr.go.smes.qim.api.dto.*;
import kr.go.smes.qim.identity.DiGenerationService;
import kr.go.smes.qim.infrastructure.jpa.entity.UserProfileJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.QimUserJpaRepository;
import kr.go.smes.qim.user.UserRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Q-IM 사용자 내부 API 컨트롤러
 * — IdO 가 직접 호출하는 내부 전용 엔드포인트
 *
 * <p>보안: X-Internal-Api-Key 헤더 검증 (InternalApiKeyInterceptor)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRegistrationService userRegistrationService;
    private final QimUserJpaRepository    userRepository;
    private final DiGenerationService     diGenerationService;

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

                    // 신규 DI인 경우 di_map 업데이트
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
     * GET /api/v1/internal/users/{qimUserId}/status ← IdO QimClient 호출 경로 보조
     * NOTE: QimClientImpl 은 /api/v1/users/{id} 를 호출 → 별도 매핑 필요
     */
    @GetMapping("/{qimUserId}/status-check")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, String>> getUserStatus(@PathVariable String qimUserId) {
        return userRepository.findById(qimUserId)
                .map(u -> ResponseEntity.ok(Map.of("status", u.getStatus())))
                .orElse(ResponseEntity.ok(Map.of("status", "UNKNOWN")));
    }
}
