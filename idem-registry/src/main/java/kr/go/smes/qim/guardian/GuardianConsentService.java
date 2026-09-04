package kr.go.smes.qim.guardian;

/**
 * 14세 미만 회원 보호자 동의 서비스
 *
 * <p>개인정보보호법 제39조의3(아동의 개인정보보호) 준수.
 * 설계서 §P3-05 참조.
 */
public interface GuardianConsentService {

    /**
     * 미성년자 회원에 대한 보호자 동의를 요청합니다.
     *
     * <p>보호자(guardianQimUserId)가 자신의 qim_user_id로 인증된 뒤
     * 미성년자(minorQimUserId)에 대한 동의를 수락하는 흐름입니다.
     *
     * @param minorQimUserId    미성년자 회원 qim_user_id
     * @param guardianQimUserId 보호자 회원 qim_user_id (성인 검증 필요)
     * @param correlationId     트레이싱 ID
     * @throws kr.go.smes.common.exception.PlatformException E-IM-212 보호자 동의 필요
     * @throws kr.go.smes.common.exception.PlatformException E-IM-213 보호자 정보 없음
     * @throws kr.go.smes.common.exception.PlatformException E-IM-214 이미 완료됨
     */
    void grantConsent(String minorQimUserId, String guardianQimUserId, String correlationId);

    /**
     * 미성년자 여부 및 보호자 동의 상태를 조회합니다.
     *
     * @param qimUserId     조회할 회원 qim_user_id
     * @param correlationId 트레이싱 ID (없으면 "N/A" 전달)
     * @return 보호자 동의 상태
     */
    GuardianConsentStatus getStatus(String qimUserId, String correlationId);
}
