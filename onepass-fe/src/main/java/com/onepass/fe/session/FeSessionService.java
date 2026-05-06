package com.onepass.fe.session;

import java.util.Optional;

/**
 * FE 세션 관리 서비스 인터페이스
 * 설계서 12.3 / 12.4 / 12.6절 참조
 */
public interface FeSessionService {

    /** 세션 생성 (Q-Sign 인증 완료 후) */
    FeSession create(String qimUserId, String authResultId, String authLevel, String returnUrl);

    /** 세션 조회 */
    Optional<FeSession> findById(String feSessionId);

    /** 세션 갱신 (sliding TTL) */
    FeSession refresh(String feSessionId);

    /** 세션 만료 (외부 채널 로그아웃) */
    void expire(String feSessionId);

    /**
     * returnUrl 화이트리스트 검증 (설계서 12.6절)
     * 화이트리스트 외 URL은 거부
     */
    boolean isValidReturnUrl(String returnUrl);

    /**
     * qimUserId 기준 FE 세션 일괄 무효화 (§12.5 MANDATORY 처리)
     * SessionAdvisoryConsumer 에서 MANDATORY_SECURITY_TERMINATE 이벤트 수신 시 호출
     */
    void invalidateByQimUserId(String qimUserId, String reason);

    /**
     * qimUserId 기준 Advisory 플래그 설정 (§12.5 Advisory 처리)
     * 다음 요청 시 로그아웃 안내용 플래그
     */
    void markAdvisoryFlag(String qimUserId, String reason);
}
