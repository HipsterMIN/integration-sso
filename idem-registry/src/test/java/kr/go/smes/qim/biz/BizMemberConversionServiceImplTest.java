package kr.go.smes.qim.biz;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.qim.crypto.PiiMaskingService;
import kr.go.smes.qim.infrastructure.jpa.entity.BizMemberJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.entity.QimUserJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.BizMemberJpaRepository;
import kr.go.smes.qim.infrastructure.jpa.repository.QimUserJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.*;

/**
 * BizMemberConversionServiceImpl 단위 테스트
 *
 * <p>설계서 §P3-06 — 기업회원 전환 서비스 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BizMemberConversionServiceImpl — 기업회원 전환")
class BizMemberConversionServiceImplTest {

    private static final String QIM_USER_ID  = "user-uuid-001";
    private static final String BIZ_REG_NO   = "1234567890";          // 숫자 10자리
    private static final String BIZ_REG_HYPH = "123-45-67890";        // 하이픈 포함
    private static final String COMPANY_NAME = "주식회사 테스트";
    private static final String CID          = "corr-001";

    @Mock private BizMemberJpaRepository bizMemberRepository;
    @Mock private QimUserJpaRepository   userRepository;
    @Mock private PiiMaskingService      piiMaskingService;

    @InjectMocks
    private BizMemberConversionServiceImpl sut;

    // ── convert() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("convert()")
    class ConvertTest {

        @Test
        @DisplayName("정상 흐름 (숫자 10자리) → 기업회원 생성 성공")
        void happyPath_digits() {
            // given
            given(bizMemberRepository.existsByBizRegNo(BIZ_REG_NO)).willReturn(false);
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(userEntity()));
            given(piiMaskingService.maskName("홍길동")).willReturn("홍*동");
            given(bizMemberRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            BizMemberConversionRequest req = BizMemberConversionRequest.builder()
                    .qimUserId(QIM_USER_ID).bizRegNo(BIZ_REG_NO)
                    .companyName(COMPANY_NAME).repName("홍길동").build();

            // when
            BizMemberResult result = sut.convert(req, CID);

            // then
            assertThat(result.getBizRegNo()).isEqualTo(BIZ_REG_NO);
            assertThat(result.getCompanyName()).isEqualTo(COMPANY_NAME);
            assertThat(result.getRepNameMasked()).isEqualTo("홍*동");
            assertThat(result.getBizStatus()).isEqualTo("ACTIVE");
            then(bizMemberRepository).should().save(any(BizMemberJpaEntity.class));
        }

        @Test
        @DisplayName("정상 흐름 (하이픈 포함 형식) → 정규화 후 저장")
        void happyPath_hyphenFormat() {
            given(bizMemberRepository.existsByBizRegNo(BIZ_REG_NO)).willReturn(false); // 정규화된 값으로 확인
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(userEntity()));
            given(bizMemberRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            BizMemberConversionRequest req = BizMemberConversionRequest.builder()
                    .qimUserId(QIM_USER_ID).bizRegNo(BIZ_REG_HYPH) // 하이픈 포함
                    .companyName(COMPANY_NAME).build();

            BizMemberResult result = sut.convert(req, CID);

            assertThat(result.getBizRegNo()).isEqualTo(BIZ_REG_NO); // 정규화 결과 확인
        }

        @ParameterizedTest(name = "형식 오류 사업자등록번호: [{0}]")
        @ValueSource(strings = {"123456789", "12345678901", "abc1234567", "123-456-7890", ""})
        @DisplayName("사업자등록번호 형식 오류 → IM_BIZ_REG_INVALID")
        void invalidBizRegNo(String invalidNo) {
            BizMemberConversionRequest req = BizMemberConversionRequest.builder()
                    .qimUserId(QIM_USER_ID).bizRegNo(invalidNo)
                    .companyName(COMPANY_NAME).build();

            assertThatThrownBy(() -> sut.convert(req, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_BIZ_REG_INVALID);
        }

        @Test
        @DisplayName("사업자등록번호 null → IM_BIZ_REG_INVALID")
        void nullBizRegNo() {
            BizMemberConversionRequest req = BizMemberConversionRequest.builder()
                    .qimUserId(QIM_USER_ID).bizRegNo(null)
                    .companyName(COMPANY_NAME).build();

            assertThatThrownBy(() -> sut.convert(req, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_BIZ_REG_INVALID);
        }

        @Test
        @DisplayName("사업자등록번호 중복 → IM_BIZ_REG_DUPLICATE")
        void duplicateBizRegNo() {
            given(bizMemberRepository.existsByBizRegNo(BIZ_REG_NO)).willReturn(true);

            BizMemberConversionRequest req = BizMemberConversionRequest.builder()
                    .qimUserId(QIM_USER_ID).bizRegNo(BIZ_REG_NO)
                    .companyName(COMPANY_NAME).build();

            assertThatThrownBy(() -> sut.convert(req, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_BIZ_REG_DUPLICATE);
        }

        @Test
        @DisplayName("사용자 없음 → IM_USER_NOT_FOUND")
        void userNotFound() {
            given(bizMemberRepository.existsByBizRegNo(BIZ_REG_NO)).willReturn(false);
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.empty());

            BizMemberConversionRequest req = BizMemberConversionRequest.builder()
                    .qimUserId(QIM_USER_ID).bizRegNo(BIZ_REG_NO)
                    .companyName(COMPANY_NAME).build();

            assertThatThrownBy(() -> sut.convert(req, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_USER_NOT_FOUND);
        }
    }

    // ── findByQimUserId() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("findByQimUserId()")
    class FindByQimUserIdTest {

        @Test
        @DisplayName("기업회원 존재 → 조회 성공")
        void found() {
            BizMemberJpaEntity entity = bizMemberEntity();
            given(bizMemberRepository.findById(QIM_USER_ID)).willReturn(Optional.of(entity));

            BizMemberResult result = sut.findByQimUserId(QIM_USER_ID, CID);

            assertThat(result.getQimUserId()).isEqualTo(QIM_USER_ID);
            assertThat(result.getBizRegNo()).isEqualTo(BIZ_REG_NO);
            assertThat(result.getBizStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("기업회원 없음 → IM_BIZ_MEMBER_NOT_FOUND")
        void notFound() {
            given(bizMemberRepository.findById(QIM_USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> sut.findByQimUserId(QIM_USER_ID, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_BIZ_MEMBER_NOT_FOUND);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private QimUserJpaEntity userEntity() {
        QimUserJpaEntity e = new QimUserJpaEntity();
        e.setQimUserId(QIM_USER_ID);
        e.setStatus("ACTIVE");
        return e;
    }

    private BizMemberJpaEntity bizMemberEntity() {
        BizMemberJpaEntity e = new BizMemberJpaEntity();
        e.setQimUserId(QIM_USER_ID);
        e.setBizRegNo(BIZ_REG_NO);
        e.setCompanyName(COMPANY_NAME);
        e.setBizStatus("ACTIVE");
        e.setConvertedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }
}
