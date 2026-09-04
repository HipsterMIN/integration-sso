package kr.go.smes.qim.withdrawal;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.event.UserEvent;
import kr.go.smes.qim.infrastructure.jpa.entity.QimUserJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.QimUserJpaRepository;
import kr.go.smes.qim.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 회원 탈퇴 서비스 구현체 (P2 §12.2)
 *
 * <p><b>4종 탈퇴 유형별 처리 흐름</b>:
 * <pre>
 * IMMEDIATE        → PII 즉시 NULL → status=WITHDRAWN → UserEvent(TYPE_WITHDRAWN) 발행
 * SCHEDULED        → status=WITHDRAWAL_SCHEDULED → withdrawal_scheduled_at 설정 (기본 +30일)
 *                    → 스케줄러 processExpiredScheduledWithdrawals() 가 만료 시 IMMEDIATE와 동일 처리
 * AGENCY_REQUESTED → IMMEDIATE와 동일 처리, requestedBy(기관코드)를 reason에 포함
 * ADMIN_FORCED     → IMMEDIATE와 동일 처리, requestedBy(관리자ID)를 reason에 포함 + 감사로그 강화
 * </pre>
 *
 * <p><b>GDPR Right to be Forgotten (§17)</b>:
 * 즉시 삭제 유형은 {@code user_profile} 의 PII 컬럼을 즉시 NULL 처리.
 * SCHEDULED는 유예 만료 시 동일하게 처리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalServiceImpl implements WithdrawalService {

    private static final int  DEFAULT_SCHEDULED_DAYS = 30;
    private static final String SOURCE_SYSTEM         = "q-im";

    private final QimUserJpaRepository userRepository;
    private final OutboxService         outboxService;
    private final JdbcTemplate          jdbcTemplate;

    // ── 퍼블릭 API ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public WithdrawalResponse withdraw(String qimUserId, WithdrawalRequest request) {
        WithdrawalType type = request.getType();
        log.info("[Withdrawal] 탈퇴 요청 수신: qimUserId={} type={} correlationId={}",
                qimUserId, type, request.getCorrelationId());

        QimUserJpaEntity user = findUserOrThrow(qimUserId, request.getCorrelationId());
        validateWithdrawalAllowed(user, request.getCorrelationId());

        return switch (type) {
            case IMMEDIATE        -> processImmediate(user, request);
            case SCHEDULED        -> processScheduled(user, request);
            case AGENCY_REQUESTED -> processAgencyRequested(user, request);
            case ADMIN_FORCED     -> processAdminForced(user, request);
        };
    }

    @Override
    @Transactional
    public WithdrawalResponse cancelScheduledWithdrawal(String qimUserId, String correlationId) {
        log.info("[Withdrawal] 예약 탈퇴 취소 요청: qimUserId={}", qimUserId);

        QimUserJpaEntity user = findUserOrThrow(qimUserId, correlationId);

        if (!"WITHDRAWAL_SCHEDULED".equals(user.getStatus())) {
            throw new PlatformException(
                    PlatformErrorCode.IM_CONVERSION_INVALID_STATE, correlationId);
        }

        user.setStatus("ACTIVE");
        user.setWithdrawalScheduledAt(null);
        user.setWithdrawalType(null);
        user.setWithdrawalReason(null);
        userRepository.save(user);

        outboxService.publishInTx(new UserEvent(
                UserEvent.TYPE_UPDATED, SOURCE_SYSTEM, correlationId,
                qimUserId, user.getEventVersion() + 1,
                "ACTIVE", "WITHDRAWAL_SCHEDULE_CANCELLED", false));

        log.info("[Withdrawal] 예약 탈퇴 취소 완료: qimUserId={}", qimUserId);
        return WithdrawalResponse.builder()
                .qimUserId(qimUserId)
                .type(WithdrawalType.SCHEDULED)
                .resultStatus("ACTIVE")   // 취소 후 사용자의 새 상태 반환 (컨벤션: resultStatus = 신규 상태)
                .processedAt(Instant.now())
                .build();
    }

    /**
     * 예약 탈퇴 만료 처리 — 매 5분마다 실행
     * withdrawal_scheduled_at 이 지난 WITHDRAWAL_SCHEDULED 사용자를 일괄 탈퇴 처리.
     */
    @Override
    @Scheduled(fixedDelayString = "${qim.withdrawal.scheduled-check-ms:300000}")
    @Transactional
    public int processExpiredScheduledWithdrawals() {
        Instant now = Instant.now();
        List<QimUserJpaEntity> expired = userRepository.findExpiredScheduledWithdrawals(now);

        if (expired.isEmpty()) return 0;

        log.info("[Withdrawal] 예약 탈퇴 만료 처리 대상: {}건", expired.size());
        int count = 0;
        for (QimUserJpaEntity user : expired) {
            try {
                executeImmediateWithdrawal(
                        user, "SCHEDULED_EXPIRY", WithdrawalType.SCHEDULED, null);
                count++;
            } catch (Exception e) {
                log.error("[Withdrawal] 예약 탈퇴 만료 처리 실패: qimUserId={} err={}",
                        user.getQimUserId(), e.getMessage(), e);
            }
        }
        log.info("[Withdrawal] 예약 탈퇴 만료 처리 완료: {}건 처리됨", count);
        return count;
    }

    // ── 유형별 처리 ──────────────────────────────────────────────────────────

    private WithdrawalResponse processImmediate(QimUserJpaEntity user, WithdrawalRequest req) {
        executeImmediateWithdrawal(user, req.getReason(), WithdrawalType.IMMEDIATE,
                req.getCorrelationId());
        return WithdrawalResponse.builder()
                .qimUserId(user.getQimUserId())
                .type(WithdrawalType.IMMEDIATE)
                .resultStatus("WITHDRAWN")
                .processedAt(Instant.now())
                .build();
    }

    private WithdrawalResponse processScheduled(QimUserJpaEntity user, WithdrawalRequest req) {
        Instant scheduledAt = req.getScheduledAt() != null
                ? req.getScheduledAt()
                : Instant.now().plus(DEFAULT_SCHEDULED_DAYS, ChronoUnit.DAYS);

        user.setStatus("WITHDRAWAL_SCHEDULED");
        user.setWithdrawalScheduledAt(scheduledAt);
        user.setWithdrawalType(WithdrawalType.SCHEDULED.name());
        user.setWithdrawalReason(req.getReason());
        userRepository.save(user);

        outboxService.publishInTx(new UserEvent(
                UserEvent.TYPE_UPDATED, SOURCE_SYSTEM, req.getCorrelationId(),
                user.getQimUserId(), user.getEventVersion() + 1,
                "WITHDRAWAL_SCHEDULED", req.getReason(), true));

        log.info("[Withdrawal] 예약 탈퇴 등록 완료: qimUserId={} scheduledAt={}",
                user.getQimUserId(), scheduledAt);
        return WithdrawalResponse.builder()
                .qimUserId(user.getQimUserId())
                .type(WithdrawalType.SCHEDULED)
                .resultStatus("WITHDRAWAL_SCHEDULED")
                .scheduledAt(scheduledAt)
                .processedAt(Instant.now())
                .build();
    }

    private WithdrawalResponse processAgencyRequested(QimUserJpaEntity user, WithdrawalRequest req) {
        String reason = String.format("[AGENCY:%s] %s",
                req.getRequestedBy() != null ? req.getRequestedBy() : "UNKNOWN",
                req.getReason());
        executeImmediateWithdrawal(user, reason, WithdrawalType.AGENCY_REQUESTED,
                req.getCorrelationId());
        return WithdrawalResponse.builder()
                .qimUserId(user.getQimUserId())
                .type(WithdrawalType.AGENCY_REQUESTED)
                .resultStatus("WITHDRAWN")
                .processedAt(Instant.now())
                .build();
    }

    private WithdrawalResponse processAdminForced(QimUserJpaEntity user, WithdrawalRequest req) {
        String reason = String.format("[ADMIN_FORCED:%s] %s",
                req.getRequestedBy() != null ? req.getRequestedBy() : "SYSTEM",
                req.getReason());
        // 관리자 강제 탈퇴 — 감사 로그 강화 (status_history + 별도 로그)
        log.warn("[Withdrawal][AUDIT] 관리자 강제 탈퇴: qimUserId={} admin={} reason={}",
                user.getQimUserId(), req.getRequestedBy(), req.getReason());
        executeImmediateWithdrawal(user, reason, WithdrawalType.ADMIN_FORCED,
                req.getCorrelationId());
        return WithdrawalResponse.builder()
                .qimUserId(user.getQimUserId())
                .type(WithdrawalType.ADMIN_FORCED)
                .resultStatus("WITHDRAWN")
                .processedAt(Instant.now())
                .build();
    }

    // ── 공통 즉시 탈퇴 처리 ────────────────────────────────────────────────

    /**
     * PII 삭제 + 상태 갱신 + Outbox 이벤트 발행 (단일 트랜잭션)
     */
    private void executeImmediateWithdrawal(QimUserJpaEntity user, String reason,
                                             WithdrawalType type, String correlationId) {
        String qimUserId = user.getQimUserId();
        Instant now = Instant.now();

        // 1. PII 즉시 삭제 (GDPR §17 Right to be Forgotten)
        deletePii(qimUserId);

        // 2. 상태 갱신 (markWithdrawn — 단일 UPDATE)
        int updated = userRepository.markWithdrawn(
                qimUserId, "WITHDRAWN", now, reason, type.name(), now);
        if (updated == 0) {
            log.warn("[Withdrawal] markWithdrawn 영향 행 0 — 이미 탈퇴 처리됨? qimUserId={}", qimUserId);
        }

        // 3. 상태 이력 기록 (비치명적)
        insertStatusHistory(qimUserId, user.getStatus(), "WITHDRAWN",
                type.name(), reason);

        // 4. Outbox 이벤트 발행
        long nextVersion = user.getEventVersion() != null ? user.getEventVersion() + 1 : 1L;
        outboxService.publishInTx(new UserEvent(
                UserEvent.TYPE_WITHDRAWN, SOURCE_SYSTEM, correlationId,
                qimUserId, nextVersion, "WITHDRAWN", reason, true));

        log.info("[Withdrawal] 즉시 탈퇴 완료 (PII 삭제됨): qimUserId={} type={}", qimUserId, type);
    }

    // ── 검증 ─────────────────────────────────────────────────────────────────

    private void validateWithdrawalAllowed(QimUserJpaEntity user, String correlationId) {
        String status = user.getStatus();
        if ("WITHDRAWN".equals(status)) {
            throw new PlatformException(PlatformErrorCode.IM_WITHDRAWAL_ALREADY, correlationId);
        }
        // WITHDRAWAL_SCHEDULED 상태에서 SCHEDULED 요청은 불허
        // (이미 예약됨 — 취소 후 재요청 필요)
        if ("WITHDRAWAL_SCHEDULED".equals(status)) {
            throw new PlatformException(PlatformErrorCode.IM_WITHDRAWAL_ALREADY, correlationId);
        }
    }

    // ── DB 헬퍼 ──────────────────────────────────────────────────────────────

    private QimUserJpaEntity findUserOrThrow(String qimUserId, String correlationId) {
        return userRepository.findById(qimUserId)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IM_USER_NOT_FOUND, correlationId));
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
        log.info("[Withdrawal] PII 삭제 완료: qimUserId={}", qimUserId);
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
            log.warn("[Withdrawal] 상태 이력 기록 실패 (비치명적): {}", e.getMessage());
        }
    }
}
