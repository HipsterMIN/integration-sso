package kr.go.smes.qim.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.qim.api.dto.UserRegisterRequest;
import kr.go.smes.qim.api.dto.UserResponse;
import kr.go.smes.qim.crypto.CiCryptoService;
import kr.go.smes.qim.crypto.PiiMaskingService;
import kr.go.smes.qim.domain.MinorGuardianPolicy;
import kr.go.smes.qim.identity.DiGenerationService;
import kr.go.smes.qim.infrastructure.jpa.entity.AuthMeanMappingJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.entity.QimUserJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.entity.UserProfileJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.QimUserJpaRepository;
import kr.go.smes.qim.outbox.OutboxService;
import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.event.UserEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import kr.go.smes.common.util.UuidV7;

/**
 * 사용자 등록 서비스 구현체
 *
 * <p>흐름:
 * <ol>
 *   <li>identifierHash 기반 기존 사용자 조회 → 있으면 반환</li>
 *   <li>없으면 신규 사용자 생성: PII 마스킹, CI 암호화, auth_mean_mapping 생성</li>
 *   <li>UserEvent Kafka Outbox 등록</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRegistrationServiceImpl implements UserRegistrationService {

    private static final String STATUS_ACTIVE    = "ACTIVE";
    private static final String STATUS_SUSPENDED = "SUSPENDED";
    private static final String STATUS_WITHDRAWN = "WITHDRAWN";

    private final QimUserJpaRepository  userRepository;
    private final CiCryptoService       ciCryptoService;
    private final PiiMaskingService     piiMaskingService;
    private final DiGenerationService   diGenerationService;
    private final OutboxService         outboxService;
    private final JdbcTemplate          jdbcTemplate;
    private final ObjectMapper          objectMapper;

    @Override
    @Transactional
    public UserResponse registerOrGet(UserRegisterRequest req) {
        log.info("[UserReg] 사용자 등록/조회: identifierHash={} provider={} correlationId={}",
                truncate(req.getIdentifierHash()), req.getProviderCode(), req.getCorrelationId());

        // 1. 기존 사용자 조회 (identifierHash 기반)
        var existing = userRepository.findByIdentifierHash(req.getIdentifierHash());
        if (existing.isPresent()) {
            log.info("[UserReg] 기존 사용자 반환: qimUserId={}", existing.get().getQimUserId());
            return toResponse(existing.get(), false);
        }

        // 2. 신규 사용자 생성
        return createNewUser(req);
    }

    @Override
    @Transactional
    public void updateStatus(String qimUserId, String newStatus, String changedBy, String reason) {
        log.info("[UserReg] 상태 변경: qimUserId={} newStatus={} changedBy={}", qimUserId, newStatus, changedBy);

        QimUserJpaEntity user = userRepository.findById(qimUserId)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음: " + qimUserId));

        String oldStatus = user.getStatus();
        user.setStatus(newStatus);
        userRepository.save(user);

        // 상태 이력 기록
        insertStatusHistory(qimUserId, oldStatus, newStatus, changedBy, reason);

        // UserEvent 발행
        outboxService.publishInTx(new UserEvent(
                UserEvent.TYPE_SUSPENDED, "q-im",
                null, qimUserId, user.getEventVersion() + 1,
                newStatus, reason, true));
        log.info("[UserReg] 상태 변경 완료: qimUserId={} {} → {}", qimUserId, oldStatus, newStatus);
    }

    @Override
    @Transactional
    public void withdraw(String qimUserId, String reason) {
        log.info("[UserReg] 탈퇴 처리: qimUserId={}", qimUserId);

        QimUserJpaEntity user = userRepository.findById(qimUserId)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음: " + qimUserId));

        String oldStatus = user.getStatus();
        user.setStatus(STATUS_WITHDRAWN);
        user.setWithdrawnAt(Instant.now());
        user.setWithdrawalReason(reason);
        userRepository.save(user);

        // PII 즉시 삭제 (Right to be Forgotten — GDPR §17)
        deletePii(qimUserId);

        // 상태 이력 기록
        insertStatusHistory(qimUserId, oldStatus, STATUS_WITHDRAWN, "SYSTEM", reason);

        // UserEvent 발행
        outboxService.publishInTx(new UserEvent(
                UserEvent.TYPE_WITHDRAWN, "q-im",
                null, qimUserId, user.getEventVersion() + 1,
                STATUS_WITHDRAWN, reason, true));
        log.info("[UserReg] 탈퇴 처리 완료 (PII 삭제됨): qimUserId={}", qimUserId);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private UserResponse createNewUser(UserRegisterRequest req) {
        String qimUserId = UuidV7.generate();
        Instant now = Instant.now();

        // CI 암호화 (rawCi가 있는 경우만)
        String encryptedCi = null;
        if (req.getRawCi() != null && !req.getRawCi().isBlank()) {
            encryptedCi = ciCryptoService.encrypt(req.getRawCi());
        }

        // PII 마스킹
        String nameMasked   = piiMaskingService.maskName(req.getRawName());
        String mobileMasked = piiMaskingService.maskMobile(req.getRawMobile());

        // 사용자 엔티티 생성
        QimUserJpaEntity userEntity = QimUserJpaEntity.builder()
                .qimUserId(qimUserId)
                .status(STATUS_ACTIVE)
                .eventVersion(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // 14세 미만 여부 자동 판정 (P3-05)
        boolean isMinor = MinorGuardianPolicy.isMinor(req.getBirthYear());
        if (isMinor) {
            log.info("[UserReg] 14세 미만 미성년자 감지: qimUserId={} birthYear={} — 보호자 동의 필요",
                    qimUserId, req.getBirthYear());
        }

        // 프로필 생성
        UserProfileJpaEntity profileEntity = UserProfileJpaEntity.builder()
                .qimUserId(qimUserId)
                .user(userEntity)
                .nameMasked(nameMasked)
                .mobileMasked(mobileMasked)
                .nationalityType(req.getNationalityType() != null ? req.getNationalityType() : "DOMESTIC")
                .ci(encryptedCi)
                .birthYear(req.getBirthYear())
                .gender(req.getGender() != null ? req.getGender() : "UNKNOWN")
                .isMinor(isMinor)
                .updatedAt(now)
                .build();

        // 인증수단 매핑 생성
        AuthMeanMappingJpaEntity mappingEntity = AuthMeanMappingJpaEntity.builder()
                .mappingId(UuidV7.generate())
                .user(userEntity)
                .providerCode(req.getProviderCode())
                .identifierHash(req.getIdentifierHash())
                .status("ACTIVE")
                .linkedAt(now)
                .build();

        userEntity.setProfile(profileEntity);
        userEntity.getAuthMeanMappings().add(mappingEntity);

        try {
            userRepository.save(userEntity);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 이미 생성된 경우 — 기존 사용자 반환
            log.warn("[UserReg] 동시 생성 충돌 — 기존 사용자 조회: identifierHash={}", truncate(req.getIdentifierHash()));
            return userRepository.findByIdentifierHash(req.getIdentifierHash())
                    .map(u -> toResponse(u, false))
                    .orElseThrow(() -> new RuntimeException("사용자 생성 및 조회 모두 실패", e));
        }

        // Kafka Outbox 등록
        outboxService.publishInTx(new UserEvent(
                UserEvent.TYPE_UPDATED, "q-im",
                null, qimUserId, 1L,
                STATUS_ACTIVE, "USER_REGISTERED", false));

        log.info("[UserReg] 신규 사용자 생성 완료: qimUserId={}", qimUserId);
        return toResponse(userEntity, true);
    }

    private void deletePii(String qimUserId) {
        // GDPR §17 Right to be Forgotten — V6 컬럼(guardian_qim_user_id, guardian_consent_at) 포함
        jdbcTemplate.update("""
                UPDATE user_profile
                SET name_masked           = NULL,
                    mobile_masked         = NULL,
                    ci                    = NULL,
                    di_map                = NULL,
                    extra_attributes      = NULL,
                    guardian_qim_user_id  = NULL,
                    guardian_consent_at   = NULL,
                    updated_at            = NOW(6)
                WHERE qim_user_id = ?
                """, qimUserId);
        log.info("[UserReg] PII 삭제 완료 (탈퇴): qimUserId={}", qimUserId);
    }

    private void insertStatusHistory(String qimUserId, String before, String after,
                                     String changedBy, String reason) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO user_status_history
                    (qim_user_id, status_before, status_after, changed_by, change_reason, occurred_at)
                    VALUES (?, ?, ?, ?, ?, NOW(6))
                    """, qimUserId, before, after, changedBy, reason);
        } catch (Exception e) {
            log.warn("[UserReg] 상태 이력 기록 실패 (비치명적): {}", e.getMessage());
        }
    }

    private UserResponse toResponse(QimUserJpaEntity entity, boolean isNew) {
        String nameMasked   = entity.getProfile() != null ? entity.getProfile().getNameMasked()   : null;
        String mobileMasked = entity.getProfile() != null ? entity.getProfile().getMobileMasked() : null;
        String nationality  = entity.getProfile() != null ? entity.getProfile().getNationalityType() : null;
        Short  birthYear    = entity.getProfile() != null ? entity.getProfile().getBirthYear()    : null;
        String gender       = entity.getProfile() != null ? entity.getProfile().getGender()       : null;

        return UserResponse.builder()
                .qimUserId(entity.getQimUserId())
                .status(entity.getStatus())
                .nameMasked(nameMasked)
                .mobileMasked(mobileMasked)
                .nationalityType(nationality)
                .birthYear(birthYear)
                .gender(gender)
                .isNew(isNew)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private String truncate(String s) {
        if (s == null || s.length() <= 8) return s;
        return s.substring(0, 8) + "...";
    }
}
