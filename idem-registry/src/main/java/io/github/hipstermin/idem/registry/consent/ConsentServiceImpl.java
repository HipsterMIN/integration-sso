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
        return versionRepository.findAllActive(Instant.now()).stream().map(ConsentServiceImpl::toInfo).collect(Collectors.toList());
    }

    // ── 1.1 동의 카탈로그 ──────────────────────────────────────────────────────

    static final java.util.regex.Pattern TYPE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{1,49}");
    static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{2,64}");

    @Override
    @Transactional(readOnly = true)
    public List<ConsentVersionInfo> catalog(String serviceCode) {
        return versionRepository.findCatalog(serviceCode == null ? "" : serviceCode, Instant.now()).stream()
                .map(ConsentServiceImpl::toInfo).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentVersionInfo> listVersions(String serviceCode, boolean includeInactive) {
        List<ConsentVersionJpaEntity> all = serviceCode == null || serviceCode.isBlank()
                ? versionRepository.findByServiceCodeIsNullOrderByConsentTypeAscEffectiveAtDesc()
                : versionRepository.findByServiceCodeOrderByConsentTypeAscEffectiveAtDesc(serviceCode);
        return all.stream().filter(v -> includeInactive || "ACTIVE".equals(v.getStatus()))
                .map(ConsentServiceImpl::toInfo).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ConsentVersionInfo publish(PublishRequest req) {
        String cid = req.correlationId();
        if (req.consentType() == null || !TYPE.matcher(req.consentType()).matches()) {
            throw new PlatformException(PlatformErrorCode.IM_CONSENT_VERSION_INVALID, cid, "consentType 은 영문 대문자·숫자·_ 2~50자");
        }
        if (req.title() == null || req.title().isBlank() || req.title().length() > 200) {
            throw new PlatformException(PlatformErrorCode.IM_CONSENT_VERSION_INVALID, cid, "title 은 1~200자");
        }
        if (req.versionTag() == null || req.versionTag().isBlank() || req.versionTag().length() > 50) {
            throw new PlatformException(PlatformErrorCode.IM_CONSENT_VERSION_INVALID, cid, "versionTag 은 1~50자");
        }
        if (req.contentUrl() != null && !req.contentUrl().isBlank()
                && (req.contentUrl().length() > 500 || !(req.contentUrl().startsWith("https://") || req.contentUrl().startsWith("http://")))) {
            throw new PlatformException(PlatformErrorCode.IM_CONSENT_VERSION_INVALID, cid, "contentUrl 은 http(s) URL, 500자 이하");
        }
        String scope = req.serviceCode() == null || req.serviceCode().isBlank() ? null : req.serviceCode().trim();
        if (scope != null && !CODE.matcher(scope).matches()) {
            throw new PlatformException(PlatformErrorCode.IM_CONSENT_VERSION_INVALID, cid, "serviceCode 형식");
        }
        Instant now = Instant.now();
        Instant effective = req.effectiveAt() != null ? req.effectiveAt() : now;
        // 같은 범위·유형의 ACTIVE 는 대체된다 — 사용자는 새 버전에 다시 동의해야 한다
        List<ConsentVersionJpaEntity> actives = scope == null
                ? versionRepository.findByServiceCodeIsNullAndConsentTypeAndStatus(req.consentType(), "ACTIVE")
                : versionRepository.findByServiceCodeAndConsentTypeAndStatus(scope, req.consentType(), "ACTIVE");
        for (ConsentVersionJpaEntity a : actives) {
            a.setStatus("SUPERSEDED");
            a.setSupersededAt(now);
            versionRepository.save(a);
        }
        ConsentVersionJpaEntity v = ConsentVersionJpaEntity.builder()
                .versionId(UuidV7.generate())
                .consentType(req.consentType())
                .serviceCode(scope)
                .versionTag(req.versionTag().trim())
                .title(req.title().trim())
                .contentUrl(req.contentUrl() == null || req.contentUrl().isBlank() ? null : req.contentUrl().trim())
                .required(req.required() == null || req.required())
                .status("ACTIVE")
                .effectiveAt(effective)
                .build();
        versionRepository.save(v);
        log.info("[Consent] 버전 발행: scope={} type={} tag={} superseded={}", scope == null ? "(platform)" : scope, req.consentType(), req.versionTag(), actives.size());
        return toInfo(v);
    }

    @Override
    @Transactional
    public ConsentVersionInfo retire(String versionId, String correlationId) {
        ConsentVersionJpaEntity v = versionRepository.findById(versionId)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.IM_CONSENT_VERSION_INVALID, correlationId, "버전 없음: " + versionId));
        if ("ACTIVE".equals(v.getStatus())) {
            v.setStatus("SUPERSEDED");
            v.setSupersededAt(Instant.now());
            versionRepository.save(v);
            log.info("[Consent] 버전 종료: scope={} type={} tag={}", v.getServiceCode() == null ? "(platform)" : v.getServiceCode(), v.getConsentType(), v.getVersionTag());
        }
        return toInfo(v);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentVersionInfo> missing(String qimUserId, String serviceCode) {
        java.util.Set<String> agreed = new java.util.HashSet<>(recordRepository.findAgreedVersionIds(qimUserId));
        return versionRepository.findCatalog(serviceCode == null ? "" : serviceCode, Instant.now()).stream()
                .filter(v -> !agreed.contains(v.getVersionId()))
                .map(ConsentServiceImpl::toInfo).collect(Collectors.toList());
    }

    static ConsentVersionInfo toInfo(ConsentVersionJpaEntity v) {
        return ConsentVersionInfo.builder()
                .versionId(v.getVersionId())
                .consentType(v.getConsentType())
                .serviceCode(v.getServiceCode())
                .status(v.getStatus())
                .versionTag(v.getVersionTag())
                .title(v.getTitle())
                .contentUrl(v.getContentUrl())
                .required(v.isRequired())
                .effectiveAt(v.getEffectiveAt())
                .build();
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
