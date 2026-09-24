package io.github.hipstermin.idem.registry.kr.biz;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.registry.crypto.PiiMaskingService;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기업회원 전환 서비스 구현체
 *
 * <h3>사업자등록번호 형식 검증 규칙</h3>
 * <ul>
 *   <li>숫자만 10자리 (예: "1234567890")</li>
 *   <li>하이픈 포함 형식 "123-45-67890"도 허용 → 정규화 후 저장</li>
 *   <li>체크섬 검증은 선택적으로 수행 (PoC 단계에서는 형식만 검증)</li>
 * </ul>
 *
 * <p>설계서 §P3-06 참조
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizMemberConversionServiceImpl implements BizMemberConversionService {

    /** 사업자등록번호 — 숫자만 10자리 또는 하이픈 포함 12자리 */
    private static final Pattern BIZ_REG_NO_PATTERN =
            Pattern.compile("^(\\d{10}|\\d{3}-\\d{2}-\\d{5})$");

    private final BizMemberJpaRepository bizMemberRepository;
    private final QimUserJpaRepository   userRepository;
    private final PiiMaskingService      piiMaskingService;

    @Override
    @Transactional
    public BizMemberResult convert(BizMemberConversionRequest request, String correlationId) {
        log.info("[BizConvert] 기업회원 전환 요청: qimUserId={} bizRegNo={} correlationId={}",
                request.getQimUserId(), maskBizRegNo(request.getBizRegNo()), correlationId);

        // 1. 사업자등록번호 형식 검증
        String normalizedBizRegNo = normalizeBizRegNo(request.getBizRegNo());
        if (normalizedBizRegNo == null) {
            log.warn("[BizConvert] 사업자등록번호 형식 오류: bizRegNo={}", request.getBizRegNo());
            throw new PlatformException(PlatformErrorCode.IM_BIZ_REG_INVALID, correlationId);
        }

        // 2. 동일 qimUserId로 이미 기업회원 등록된 경우 중복 전환 방지
        if (bizMemberRepository.existsById(request.getQimUserId())) {
            log.warn("[BizConvert] 이미 기업회원으로 전환된 사용자: qimUserId={}", request.getQimUserId());
            throw new PlatformException(PlatformErrorCode.IM_BIZ_REG_DUPLICATE, correlationId);
        }

        // 3. 사업자등록번호 중복 확인 (다른 qimUserId가 이미 등록한 경우)
        if (bizMemberRepository.existsByBizRegNo(normalizedBizRegNo)) {
            log.warn("[BizConvert] 사업자등록번호 중복: bizRegNo={}", maskBizRegNo(normalizedBizRegNo));
            throw new PlatformException(PlatformErrorCode.IM_BIZ_REG_DUPLICATE, correlationId);
        }

        // 4. 대상 사용자 존재 확인
        QimUserJpaEntity userEntity = userRepository.findById(request.getQimUserId())
                .orElseThrow(() -> {
                    log.warn("[BizConvert] 사용자 없음: qimUserId={}", request.getQimUserId());
                    return new PlatformException(PlatformErrorCode.IM_USER_NOT_FOUND, correlationId);
                });

        // 5. 대표자명 마스킹 처리
        String repNameMasked = request.getRepName() != null
                ? piiMaskingService.maskName(request.getRepName())
                : null;

        // 6. 기업회원 엔티티 생성
        BizMemberJpaEntity bizMember = BizMemberJpaEntity.builder()
                .qimUserId(request.getQimUserId())
                .user(userEntity)
                .bizRegNo(normalizedBizRegNo)
                .companyName(request.getCompanyName())
                .repNameMasked(repNameMasked)
                .bizType(request.getBizType())
                .bizStatus("ACTIVE")
                .build();

        bizMemberRepository.save(bizMember);

        log.info("[BizConvert] 기업회원 전환 완료: qimUserId={} bizRegNo={}",
                request.getQimUserId(), maskBizRegNo(normalizedBizRegNo));

        return toResult(bizMember);
    }

    @Override
    @Transactional(readOnly = true)
    public BizMemberResult findByQimUserId(String qimUserId, String correlationId) {
        return bizMemberRepository.findById(qimUserId)
                .map(this::toResult)
                .orElseThrow(() -> {
                    log.warn("[BizConvert] 기업회원 정보 없음: qimUserId={}", qimUserId);
                    return new PlatformException(PlatformErrorCode.IM_BIZ_MEMBER_NOT_FOUND, correlationId);
                });
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * 사업자등록번호를 숫자 10자리로 정규화.
     *
     * @param bizRegNo 입력값 (예: "123-45-67890" 또는 "1234567890")
     * @return 정규화된 10자리 숫자, 형식 오류 시 null
     */
    private String normalizeBizRegNo(String bizRegNo) {
        if (bizRegNo == null || bizRegNo.isBlank()) return null;
        String trimmed = bizRegNo.trim();
        if (!BIZ_REG_NO_PATTERN.matcher(trimmed).matches()) return null;
        return trimmed.replace("-", "");
    }

    /** 로그용 사업자등록번호 마스킹 (앞 3자리만 표시) */
    private String maskBizRegNo(String bizRegNo) {
        if (bizRegNo == null || bizRegNo.length() < 4) return "***";
        return bizRegNo.substring(0, 3) + "*".repeat(bizRegNo.length() - 3);
    }

    private BizMemberResult toResult(BizMemberJpaEntity entity) {
        return BizMemberResult.builder()
                .qimUserId(entity.getQimUserId())
                .bizRegNo(entity.getBizRegNo())
                .companyName(entity.getCompanyName())
                .repNameMasked(entity.getRepNameMasked())
                .bizType(entity.getBizType())
                .bizStatus(entity.getBizStatus())
                .verifiedAt(entity.getVerifiedAt())
                .convertedAt(entity.getConvertedAt())
                .build();
    }
}
