package io.github.hipstermin.idem.registry.consent;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.ConsentRecordJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.ConsentVersionJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.ConsentRecordJpaRepository;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.ConsentVersionJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인정보 동의 서비스 구현체 (P2 §12.3)
 *
 * <p><b>동의 이력 보존 원칙</b>:
 * 동의를 새로 할 때마다 새 레코드를 INSERT (UPDATE 없음).
 * 이전 기록이 유지되어 언제 어떤 버전에 동의했는지 감사 추적 가능.
 *
 * <p><b>약관 변경 시 재동의 흐름</b>:
 * 관리자가 새 버전을 ACTIVE로 배포 →
 * 사용자 로그인 시 IdO가 getConsentStatus() 로 재동의 필요 여부 판단 →
 * 재동의 화면으로 유도.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentServiceImpl implements ConsentService {

    private final ConsentVersionJpaRepository versionRepository;
    private final ConsentRecordJpaRepository  recordRepository;

    @Override
    @Transactional
    public ConsentResult agree(String qimUserId, ConsentRequest request) {
        log.info("[Consent] 동의 요청: qimUserId={} type={} versionId={}",
                qimUserId, request.getConsentType(), request.getVersionId());

        // 버전 조회 (명시된 versionId 또는 최신 ACTIVE)
        ConsentVersionJpaEntity version = resolveVersion(request);

        // 신규 동의 레코드 INSERT (이력 보존 — UPDATE 없음)
        ConsentRecordJpaEntity record = ConsentRecordJpaEntity.builder()
                .recordId(UuidV7.generate())
                .qimUserId(qimUserId)
                .versionId(version.getVersionId())
                .consentType(version.getConsentType())
                .consentStatus("AGREED")
                .agreedVia(request.getAgreedVia())
                .clientIp(request.getClientIp())
                .agreedAt(Instant.now())
                .build();

        recordRepository.save(record);

        log.info("[Consent] 동의 완료: qimUserId={} type={} versionTag={}",
                qimUserId, version.getConsentType(), version.getVersionTag());

        return toResult(record, version);
    }

    @Override
    @Transactional
    public ConsentResult withdraw(String qimUserId, String consentType,
                                   String reason, String correlationId) {
        log.info("[Consent] 동의 철회 요청: qimUserId={} type={}", qimUserId, consentType);

        // 최신 AGREED 기록 조회
        ConsentRecordJpaEntity record = recordRepository
                .findLatestByUserAndType(qimUserId, consentType)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IM_CONSENT_NOT_FOUND, correlationId));

        // 이미 철회된 경우
        if ("WITHDRAWN".equals(record.getConsentStatus())) {
            throw new PlatformException(PlatformErrorCode.IM_CONSENT_NOT_FOUND, correlationId);
        }

        // 필수 동의 철회 불가
        ConsentVersionJpaEntity version = versionRepository.findById(record.getVersionId())
                .orElse(null);
        if (version != null && version.isRequired()) {
            throw new PlatformException(PlatformErrorCode.IM_WITHDRAWAL_NOT_ALLOWED, correlationId);
        }

        Instant now = Instant.now();
        recordRepository.markWithdrawn(record.getRecordId(), now, reason);
        record.setConsentStatus("WITHDRAWN");
        record.setWithdrawnAt(now);
        record.setWithdrawalReason(reason);

        log.info("[Consent] 동의 철회 완료: qimUserId={} type={}", qimUserId, consentType);
        return toResult(record, version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentResult> getConsentStatus(String qimUserId) {
        return recordRepository.findActiveConsentsByUser(qimUserId).stream()
                .map(r -> {
                    ConsentVersionJpaEntity v =
                            versionRepository.findById(r.getVersionId()).orElse(null);
                    return toResult(r, v);
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentVersionInfo> getActiveVersions() {
        return versionRepository.findAllActive(Instant.now()).stream()
                .map(v -> ConsentVersionInfo.builder()
                        .versionId(v.getVersionId())
                        .consentType(v.getConsentType())
                        .versionTag(v.getVersionTag())
                        .title(v.getTitle())
                        .contentUrl(v.getContentUrl())
                        .required(v.isRequired())
                        .effectiveAt(v.getEffectiveAt())
                        .build())
                .collect(Collectors.toList());
    }

    // ── private ───────────────────────────────────────────────────────────────

    private ConsentVersionJpaEntity resolveVersion(ConsentRequest request) {
        if (request.getVersionId() != null && !request.getVersionId().isBlank()) {
            return versionRepository.findById(request.getVersionId())
                    .orElseThrow(() -> new PlatformException(
                            PlatformErrorCode.IM_CONSENT_VERSION_INVALID,
                            request.getCorrelationId()));
        }
        // 최신 ACTIVE 버전 자동 선택
        return versionRepository.findLatestActive(request.getConsentType(), Instant.now())
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IM_CONSENT_VERSION_INVALID,
                        request.getCorrelationId()));
    }

    private ConsentResult toResult(ConsentRecordJpaEntity r, ConsentVersionJpaEntity v) {
        return ConsentResult.builder()
                .recordId(r.getRecordId())
                .qimUserId(r.getQimUserId())
                .consentType(r.getConsentType())
                .versionId(r.getVersionId())
                .versionTag(v != null ? v.getVersionTag() : null)
                .consentStatus(r.getConsentStatus())
                .required(v != null && v.isRequired())
                .agreedAt(r.getAgreedAt())
                .withdrawnAt(r.getWithdrawnAt())
                .build();
    }
}
