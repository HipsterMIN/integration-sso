package kr.go.smes.ido.fe.session;

import java.util.Optional;

/**
 * FE 세션 관리 서비스 인터페이스 (IdO 담당)
 * 설계서 §12.3 / §12.4 / §12.6 참조
 *
 * <p>onepass-fe Spring Boot BFF 제거에 따라
 * FE 세션 책임이 IdO(정책 오케스트레이터)로 이관.
 */
public interface FeSessionService {

    /**
     * FE 세션 생성 (Q-Sign 인증 완료 후 호출)
     *
     * @param qimUserId    Q-IM 사용자 ID
     * @param authResultId 인증 결과 ID
     * @param authLevel    인증 수준 (L1/L2/L3)
     * @param returnUrl    화이트리스트 검증된 복귀 URL
     * @return 생성된 FE 세션 (feSessionId 포함)
     */
    FeSession create(String qimUserId, String authResultId,
                     String authLevel, String returnUrl);

    /**
     * feSessionId 로 세션 조회
     *
     * @param feSessionId 쿠키에서 읽은 세션 ID
     * @return 유효한 세션 (절대 만료된 경우 empty)
     */
    Optional<FeSession> findById(String feSessionId);

    /**
     * Sliding TTL 갱신
     *
     * @param feSessionId 갱신할 세션 ID
     * @return 갱신된 세션 (lastActivityAt 업데이트)
     */
    FeSession refresh(String feSessionId);

    /**
     * 세션 만료 (외부 채널 로그아웃 요청)
     *
     * @param feSessionId 만료할 세션 ID
     */
    void expire(String feSessionId);

    /**
     * returnUrl 화이트리스트 검증 (설계서 §12.6)
     * agency_meta.callback_whitelist 와 ido.fe.allowed-return-urls 에서 확인.
     *
     * @param returnUrl 검증할 URL
     * @return 허용된 URL 이면 true
     */
    boolean isValidReturnUrl(String returnUrl);

    /**
     * qimUserId 기준 FE 세션 일괄 무효화 (§12.5 MANDATORY 처리)
     * SessionAdvisoryEvent.TYPE_MANDATORY_SECURITY_TERMINATE 수신 시 호출.
     *
     * @param qimUserId 무효화할 사용자 ID
     * @param reason    무효화 사유
     */
    void invalidateByQimUserId(String qimUserId, String reason);

    /**
     * qimUserId 기준 Advisory 플래그 설정 (§12.5 Advisory 처리)
     * 다음 요청 시 로그아웃 안내 표시용.
     *
     * @param qimUserId 플래그 설정할 사용자 ID
     * @param reason    Advisory 사유
     */
    void markAdvisoryFlag(String qimUserId, String reason);
}
