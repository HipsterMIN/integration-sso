package io.github.hipstermin.idem.registry.kr.biz;

/**
 * 기업회원 전환 서비스
 *
 * <p>사업자등록번호를 기반으로 개인 회원을 기업회원으로 전환합니다.
 * 설계서 §P3-06 참조.
 */
public interface BizMemberConversionService {

    /**
     * 개인 회원을 기업회원으로 전환합니다.
     *
     * @param request       전환 요청 정보
     * @param correlationId 트레이싱 ID
     * @return 전환 결과
     * @throws io.github.hipstermin.idem.common.exception.PlatformException E-IM-215 사업자등록번호 형식 오류
     * @throws io.github.hipstermin.idem.common.exception.PlatformException E-IM-216 중복 사업자등록번호
     * @throws io.github.hipstermin.idem.common.exception.PlatformException E-IM-201 사용자 없음
     */
    BizMemberResult convert(BizMemberConversionRequest request, String correlationId);

    /**
     * 기업회원 정보를 조회합니다.
     *
     * @param qimUserId     회원 qim_user_id
     * @param correlationId 트레이싱 ID
     * @return 기업회원 정보
     * @throws io.github.hipstermin.idem.common.exception.PlatformException E-IM-217 기업회원 정보 없음
     */
    BizMemberResult findByQimUserId(String qimUserId, String correlationId);
}
